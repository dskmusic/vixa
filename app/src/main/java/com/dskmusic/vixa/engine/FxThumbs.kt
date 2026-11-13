package com.dskmusic.vixa.engine

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import com.dskmusic.vixa.data.ErrorLog
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Miniaturas de efectos: el MISMO shader del efecto, ejecutado una vez sobre una imagen de muestra en un
 * contexto GL fuera de pantalla (pbuffer). Se cachean; todo corre en un único hilo (el contexto EGL es de ese hilo).
 */
object FxThumbs {
    const val W = 192
    const val H = 120

    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "fx-thumbs") }.asCoroutineDispatcher()
    private var display: EGLDisplay? = null
    private var context: EGLContext? = null
    private var surface: EGLSurface? = null
    private var ready = false
    @Volatile private var sample: Bitmap? = null

    /** Se asigna desde la UI antes de pedir miniaturas. */
    @Volatile var appContext: android.content.Context? = null

    /** Imagen de muestra elegida por el usuario en Ajustes (copia reducida); si no existe se usa la de serie. */
    fun customFile(ctx: android.content.Context) = java.io.File(ctx.filesDir, "fx_sample_custom.jpg")

    fun customBitmap(ctx: android.content.Context): Bitmap? = try {
        customFile(ctx).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
    } catch (e: Throwable) { null }

    /** Olvida la muestra y las miniaturas ya calculadas (al cambiar o restaurar la imagen). */
    fun resetSample() {
        sample = null
        FxSamples.dropCache()
        cache.clear()
        failed.clear()
    }

    /** Guarda [uri] como imagen de muestra (reducida a 1600 px y con la rotación EXIF aplicada). */
    suspend fun setCustom(ctx: android.content.Context, uri: android.net.Uri): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var k = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / k > 1600) k *= 2
            var bmp = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = k }) }
                ?: return@withContext false
            val deg = try {
                ctx.contentResolver.openInputStream(uri)?.use { s ->
                    when (android.media.ExifInterface(s).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (e: Throwable) { 0f }
            if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(deg) }, true)
            java.io.FileOutputStream(customFile(ctx)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            resetSample()
            true
        } catch (e: Throwable) {
            ErrorLog.log("FxSample", e)
            false
        }
    }

    /** Vuelve a la imagen de serie. */
    fun restoreDefault(ctx: android.content.Context) {
        customFile(ctx).delete()
        resetSample()
    }

    /** Foto de muestra (la de Ajustes o res/drawable-nodpi/fx_sample.jpg) recortada al centro a W×H. */
    fun sampleBitmap(): Bitmap {
        sample?.let { return it }
        val ctx = checkNotNull(appContext) { "FxThumbs.appContext sin asignar" }
        val src = FxSamples.bitmap(ctx) ?: Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
        val k = maxOf(W.toFloat() / src.width, H.toFloat() / src.height)
        val sw = (W / k).toInt().coerceAtMost(src.width)
        val sh = (H / k).toInt().coerceAtMost(src.height)
        val crop = Bitmap.createBitmap(src, (src.width - sw) / 2, (src.height - sh) / 2, sw, sh)
        val b = Bitmap.createScaledBitmap(crop, W, H, true)
        sample = b
        return b
    }

    suspend fun get(def: FxDef): Bitmap? {
        cache[def.id]?.let { return it }
        if (failed.contains(def.id)) return null
        return withContext(dispatcher) {
            val bmp = try { render(def) } catch (e: Throwable) { ErrorLog.log("FxThumb ${def.id}", e); null }
            if (bmp != null) cache[def.id] = bmp else failed.add(def.id)
            bmp
        }
    }

    @Volatile private var validated = false

    /** Transiciones y efectos cuyo shader no compila en este dispositivo (se rellena en [validateRegistry]). */
    @Volatile var brokenTransitions: Set<String> = emptySet()
        private set
    @Volatile var brokenEffects: Set<String> = emptySet()
        private set

    /**
     * Comprobación única por ejecución: compila todos los shaders del registro (transiciones dentro del shader de clip y
     * efectos) y apunta en el registro de errores los que fallan. Una transición con GLSL roto estropea el clip entero,
     * y sus casillas (dibujadas en Compose) no lo delatan.
     */
    suspend fun validateRegistry() {
        if (validated) return
        validated = true
        withContext(dispatcher) {
            try {
                if (!initEgl()) return@withContext
                val bad = ArrayList<String>()
                val badT = HashSet<String>()
                val badF = HashSet<String>()
                for (t in Registry.transitions) {
                    val s = compile(GLES20.GL_FRAGMENT_SHADER, Shaders.clipFragment(t.body, t.body))
                    if (s == 0) { bad.add("transición " + t.id); badT.add(t.id) } else GLES20.glDeleteShader(s)
                }
                for (f in Registry.effects) {
                    val s = compile(GLES20.GL_FRAGMENT_SHADER, Shaders.fxFragment(f.body))
                    if (s == 0) { bad.add("efecto " + f.id); badF.add(f.id) } else GLES20.glDeleteShader(s)
                }
                brokenTransitions = badT
                brokenEffects = badF
                ErrorLog.log("ShaderCheck", if (bad.isEmpty()) "todos los shaders compilan" else "FALLAN (${bad.size}): " + bad.joinToString(", "))
            } catch (e: Throwable) {
                ErrorLog.log("ShaderCheck", e)
            }
        }
    }

    private fun initEgl(): Boolean {
        if (ready) return true
        val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(d, ver, 0, ver, 1)) return false
        val cfgAttr = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val n = IntArray(1)
        if (!EGL14.eglChooseConfig(d, cfgAttr, 0, cfgs, 0, 1, n, 0) || n[0] < 1) return false
        val ctx = EGL14.eglCreateContext(d, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surf = EGL14.eglCreatePbufferSurface(d, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, W, EGL14.EGL_HEIGHT, H, EGL14.EGL_NONE), 0)
        if (!EGL14.eglMakeCurrent(d, surf, surf, ctx)) return false
        display = d; context = ctx; surface = surf
        ready = true
        return true
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            ErrorLog.log("FxThumb", GLES20.glGetShaderInfoLog(s))
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    private fun render(def: FxDef): Bitmap? {
        if (!initEgl()) return null
        val vs = compile(GLES20.GL_VERTEX_SHADER, Shaders.VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, Shaders.fxFragment(def.body))
        if (vs == 0 || fs == 0) return null
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val tex = IntArray(1)
        try {
            GLES20.glUseProgram(prog)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, sampleBitmap(), 0)
            val quad = floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 0f, 1f, -1f, 1f, 0f, 1f, 1f, 1f, 0f, 1f)
            val buf = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
            buf.position(0)
            val pos = GLES20.glGetAttribLocation(prog, "aFramePosition")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 4, GLES20.GL_FLOAT, false, 0, buf)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uRes"), W.toFloat(), H.toFloat())
            val p0 = FloatArray(4)
            val p1 = FloatArray(4)
            for ((i, pd) in def.params.withIndex()) if (i < 4) p0[i] = pd.def else if (i < 8) p1[i - 4] = pd.def
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uP0"), 1, p0, 0)
            GLES20.glUniform4fv(GLES20.glGetUniformLocation(prog, "uP1"), 1, p1, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, "uFx"), 1f, 0.4f, 1f, 0f)
            GLES20.glViewport(0, 0, W, H)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            val px = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, W, H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return null
            val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            px.rewind()
            out.copyPixelsFromBuffer(px)
            return out
        } finally {
            GLES20.glDeleteTextures(1, tex, 0)
            GLES20.glDeleteProgram(prog)
        }
    }
}
