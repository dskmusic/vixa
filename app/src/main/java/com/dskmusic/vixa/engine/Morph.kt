package com.dskmusic.vixa.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.dskmusic.vixa.model.Easing
import com.dskmusic.vixa.model.MorphImage
import com.dskmusic.vixa.model.MorphSpec
import java.io.File
import java.io.FileOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Morphing real entre fotos: cada foto lleva una lista de puntos equivalentes (la malla de la cara de MediaPipe, o una cuadrícula
 * si no hay caras). Para cada fotograma se mezclan las posiciones de los puntos, se deforma cada foto hacia esa malla
 * intermedia (triángulos de Delaunay) y se funden las dos ya alineadas.
 *
 * Espacio "q": el fotograma de salida, 0..1, con Y hacia abajo. Espacio "uv": la foto original, 0..1, con Y hacia abajo.
 * Cada [MorphImage] define el recorte (centro, alto y giro) que lleva su foto al fotograma.
 */
object MorphMath {
    /** Puntos fijos del borde del fotograma (misma posición en todas las fotos): evitan que los bordes se muevan. */
    private val ANCHORS: FloatArray = FloatArray(32).also {
        var i = 0
        for (k in 0 until 4) { it[i++] = k / 4f; it[i++] = 0f }          // arriba
        for (k in 0 until 4) { it[i++] = 1f; it[i++] = k / 4f }          // derecha
        for (k in 0 until 4) { it[i++] = 1f - k / 4f; it[i++] = 1f }     // abajo
        for (k in 0 until 4) { it[i++] = 0f; it[i++] = 1f - k / 4f }     // izquierda
    }

    /** Cuadrícula interior de apoyo cuando las fotos no tienen puntos propios (sin caras). */
    private val GRID: FloatArray = FloatArray(18).also {
        var i = 0
        for (y in 1..3) for (x in 1..3) { it[i++] = x / 4f; it[i++] = y / 4f }
    }

    /** Puntos fijos (en q) que se añaden a los de cada foto. */
    /** Puntos automaticos de la malla: 468 con caras; 0 sin ellas (tambien lee clips guardados antes de existir [MorphSpec.base]). */
    fun baseOf(spec: MorphSpec): Int = if (spec.base > 0) spec.base else if (spec.images.firstOrNull()?.pts?.size ?: 0 >= 468 * 2) 468 else 0

    fun fixed(hasOwnPoints: Boolean): FloatArray = if (hasOwnPoints) ANCHORS else ANCHORS + GRID

    fun qToUv(m: MorphImage, ar: Float, qx: Float, qy: Float, out: FloatArray, o: Int) {
        val cropH = m.ch * m.h
        val cropW = cropH * ar
        val dx = (qx - 0.5f) * cropW
        val dy = (qy - 0.5f) * cropH
        val c = cos(m.rot); val s = sin(m.rot)
        out[o] = (m.cx * m.w + dx * c - dy * s) / m.w
        out[o + 1] = (m.cy * m.h + dx * s + dy * c) / m.h
    }

    fun uvToQ(m: MorphImage, ar: Float, u: Float, v: Float, out: FloatArray, o: Int) {
        val cropH = m.ch * m.h
        val cropW = cropH * ar
        val px = u * m.w - m.cx * m.w
        val py = v * m.h - m.cy * m.h
        val c = cos(m.rot); val s = sin(m.rot)
        out[o] = (px * c + py * s) / cropW + 0.5f
        out[o + 1] = (-px * s + py * c) / cropH + 0.5f
    }

    /** Recorte que deja los ojos horizontales y la cara centrada. [lm] = puntos de MediaPipe (x,y en 0..1 de la foto). */
    fun fitFace(path: String, w: Int, h: Int, lm: FloatArray, ar: Float): MorphImage {
        // Esquinas exteriores de los ojos (33 y 263): ordenadas de izquierda a derecha en la foto.
        var ax = lm[33 * 2] * w; var ay = lm[33 * 2 + 1] * h
        var bx = lm[263 * 2] * w; var by = lm[263 * 2 + 1] * h
        if (ax > bx) { val tx = ax; ax = bx; bx = tx; val ty = ay; ay = by; by = ty }
        val d = hypot(bx - ax, by - ay).coerceAtLeast(1f)
        val rot = atan2(by - ay, bx - ax)
        val eyeOutH = min(0.24f, 0.34f * ar)           // distancia entre ojos en alturas del fotograma
        val cropH = d / eyeOutH
        // Los ojos van al 40 % de la altura del fotograma.
        val mx = (ax + bx) / 2f; val my = (ay + by) / 2f
        val cx = mx - sin(rot) * 0.1f * cropH
        val cy = my + cos(rot) * 0.1f * cropH
        // Puntos de la malla (los 468 de la cara), tal cual en la foto.
        return MorphImage(path, w, h, cx / w, cy / h, cropH / h, rot, lm.copyOf(468 * 2).toList())
    }

    /** Recorte centrado que llena el fotograma (sin puntos propios). */
    fun fitCenter(path: String, w: Int, h: Int, ar: Float): MorphImage {
        val ch = if (w.toFloat() / h > ar) 1f else (w.toFloat() / ar) / h
        return MorphImage(path, w, h, 0.5f, 0.5f, ch, 0f, emptyList())
    }

    /** Triangulación de Delaunay (Bowyer-Watson). Devuelve los índices de los triángulos de 3 en 3. */
    fun delaunay(x: FloatArray, y: FloatArray): IntArray {
        val n = x.size
        val px = DoubleArray(n + 3); val py = DoubleArray(n + 3)
        for (i in 0 until n) { px[i] = x[i] + (i % 7) * 1e-6; py[i] = y[i] + (i % 5) * 1e-6 }
        px[n] = -1000.0; py[n] = -1000.0
        px[n + 1] = 1000.0; py[n + 1] = -1000.0
        px[n + 2] = 0.0; py[n + 2] = 1200.0
        var tris = ArrayList<IntArray>()
        tris.add(intArrayOf(n, n + 1, n + 2))
        fun inCircle(t: IntArray, qx: Double, qy: Double): Boolean {
            val ax = px[t[0]] - qx; val ay = py[t[0]] - qy
            val bx = px[t[1]] - qx; val by = py[t[1]] - qy
            val cx = px[t[2]] - qx; val cy = py[t[2]] - qy
            val det = (ax * ax + ay * ay) * (bx * cy - cx * by) - (bx * bx + by * by) * (ax * cy - cx * ay) + (cx * cx + cy * cy) * (ax * by - bx * ay)
            val orient = (px[t[1]] - px[t[0]]) * (py[t[2]] - py[t[0]]) - (py[t[1]] - py[t[0]]) * (px[t[2]] - px[t[0]])
            return if (orient > 0) det > 0 else det < 0
        }
        for (i in 0 until n) {
            val bad = ArrayList<IntArray>()
            val keep = ArrayList<IntArray>()
            for (t in tris) if (inCircle(t, px[i], py[i])) bad.add(t) else keep.add(t)
            // Aristas del contorno del hueco: las que aparecen una sola vez.
            val edges = HashMap<Long, IntArray>()
            val count = HashMap<Long, Int>()
            for (t in bad) for (k in 0 until 3) {
                val a = t[k]; val b = t[(k + 1) % 3]
                val key = min(a, b).toLong() * 1_000_003L + max(a, b)
                edges[key] = intArrayOf(a, b)
                count[key] = (count[key] ?: 0) + 1
            }
            for ((key, e) in edges) if (count[key] == 1) keep.add(intArrayOf(e[0], e[1], i))
            tris = keep
        }
        val out = ArrayList<Int>(tris.size * 3)
        for (t in tris) if (t[0] < n && t[1] < n && t[2] < n) { out.add(t[0]); out.add(t[1]); out.add(t[2]) }
        return out.toIntArray()
    }

    /**
     * Posición en la secuencia: (foto de partida, avance 0..1 hacia la siguiente). Cada foto se mantiene [MorphSpec.hold]
     * y cada paso a la siguiente dura 1 (con suavizado). [s] = 0..1 sobre toda la duración del clip.
     */
    fun locate(spec: MorphSpec, s: Float): Pair<Int, Float> {
        val n = spec.images.size
        val s2 = if (spec.pingPong) (if (s < 0.5f) s * 2f else 2f - s * 2f) else s
        var x = s2.coerceIn(0f, 1f) * (n * spec.hold + (n - 1))
        for (i in 0 until n) {
            if (x < spec.hold) return if (i == n - 1) (n - 2) to 1f else i to 0f
            x -= spec.hold
            if (i == n - 1) break
            if (x < 1f) return i to Easing.EASE_IN_OUT.ease(x)
            x -= 1f
        }
        return (n - 2) to 1f
    }

    /** Duración natural del morph con [n] fotos, a [stepUs] por paso. */
    fun naturalDurationUs(n: Int, hold: Float, stepUs: Long, pingPong: Boolean): Long =
        ((n * hold + (n - 1)) * stepUs * (if (pingPong) 2 else 1)).toLong()

    /**
     * Copia la foto a un JPEG derecho (con la rotación EXIF aplicada y como mucho 1280 px). La detección de caras y el render
     * usan este archivo, así los puntos y la textura coinciden siempre. Devuelve la ruta, o null si no se pudo leer.
     */
    fun prepare(context: Context, path: String): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
            var bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val rot = when (android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                3 -> 180f; 6 -> 90f; 8 -> 270f; else -> 0f
            }
            val k = min(1f, 1280f / max(bmp.width, bmp.height))
            if (rot != 0f || k < 1f) {
                val m = Matrix().apply { postRotate(rot); postScale(k, k) }
                val b2 = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (b2 !== bmp) bmp.recycle()
                bmp = b2
            }
            val f = File(com.dskmusic.vixa.media.Importer.mediaDir(context), "morph_" + Integer.toHexString((path + File(path).length()).hashCode()) + "_" + System.nanoTime() % 100000 + ".jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
            f.path
        } catch (e: Throwable) {
            null
        }
    }
}

class MorphEffect(private val ctx: RenderCtx, private val clipId: String) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = MorphProgram(ctx, clipId)
}

/** Geometría del morph ya preparada: por foto, posición de cada vértice en q y en uv; y los triángulos. */
private class MorphGeom(val spec: MorphSpec, ar: Float) {
    val n: Int
    val q: Array<FloatArray>
    val uv: Array<FloatArray>
    val tris: IntArray

    init {
        // Puntos propios: los mismos en todas las fotos (cara de MediaPipe + los que añada el usuario, o solo los del usuario).
        val cnt = spec.images[0].pts.size / 2
        val np = if (spec.images.all { it.pts.size == cnt * 2 }) cnt else 0
        // Con cara, los puntos de la cara ya cubren el centro; sin ella se anade una cuadricula fija de apoyo.
        val fixed = MorphMath.fixed(MorphMath.baseOf(spec) > 0)
        val nf = fixed.size / 2
        n = np + nf
        q = Array(spec.images.size) { FloatArray(n * 2) }
        uv = Array(spec.images.size) { FloatArray(n * 2) }
        for ((i, m) in spec.images.withIndex()) {
            for (k in 0 until np) {
                uv[i][k * 2] = m.pts[k * 2]; uv[i][k * 2 + 1] = m.pts[k * 2 + 1]
                MorphMath.uvToQ(m, ar, uv[i][k * 2], uv[i][k * 2 + 1], q[i], k * 2)
            }
            for (k in 0 until nf) {
                q[i][(np + k) * 2] = fixed[k * 2]; q[i][(np + k) * 2 + 1] = fixed[k * 2 + 1]
                MorphMath.qToUv(m, ar, fixed[k * 2], fixed[k * 2 + 1], uv[i], (np + k) * 2)
            }
        }
        // La malla se triangula una vez sobre la posición media de cada punto en todas las fotos.
        val mx = FloatArray(n); val my = FloatArray(n)
        for (k in 0 until n) {
            for (i in q.indices) { mx[k] += q[i][k * 2]; my[k] += q[i][k * 2 + 1] }
            mx[k] /= q.size; my[k] /= q.size
        }
        tris = MorphMath.delaunay(mx, my)
    }
}

private class MorphProgram(private val ctx: RenderCtx, private val clipId: String) : BaseGlShaderProgram(false, 1) {

    private val program: GlProgram
    private var geom: MorphGeom? = null
    private val textures = HashMap<String, Int>()
    private val missing = HashSet<String>()
    private var pos = FloatArray(0)
    private var uvA = FloatArray(0)
    private var uvB = FloatArray(0)

    init {
        try {
            program = GlProgram(VERT, FRAG)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(ctx.canvasW, ctx.canvasH)

    private fun texFor(path: String): Int {
        textures[path]?.let { return it }
        if (missing.contains(path)) return 0
        val bmp = BitmapFactory.decodeFile(path)
        if (bmp == null) { missing.add(path); return 0 }
        val id = GlUtil.generateTexture()
        GlUtil.setTexture(id, bmp)
        bmp.recycle()
        textures[path] = id
        return id
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val c = ctx.project().clip(clipId)
            val spec = c?.morph
            if (c == null || spec == null || spec.images.size < 2) return
            var g = geom
            if (g == null || g.spec !== spec) {
                g = MorphGeom(spec, ctx.canvasW.toFloat() / ctx.canvasH)
                geom = g
            }
            val s = ((presentationTimeUs - c.startUs).coerceAtLeast(0).toFloat() / c.durationUs.coerceAtLeast(1)).coerceIn(0f, 1f)
            val (i, u) = MorphMath.locate(spec, s)
            val ta = texFor(spec.images[i].path)
            val tb = texFor(spec.images[i + 1].path)
            if (ta == 0 || tb == 0) return
            val nt = g.tris.size
            if (pos.size != nt * 2) { pos = FloatArray(nt * 2); uvA = FloatArray(nt * 2); uvB = FloatArray(nt * 2) }
            val qa = g.q[i]; val qb = g.q[i + 1]
            val ua = g.uv[i]; val ub = g.uv[i + 1]
            for (k in 0 until nt) {
                val v = g.tris[k]
                // q (Y hacia abajo) → espacio de recorte (Y hacia arriba).
                pos[k * 2] = (qa[v * 2] + (qb[v * 2] - qa[v * 2]) * u) * 2f - 1f
                pos[k * 2 + 1] = 1f - (qa[v * 2 + 1] + (qb[v * 2 + 1] - qa[v * 2 + 1]) * u) * 2f
                uvA[k * 2] = ua[v * 2]; uvA[k * 2 + 1] = ua[v * 2 + 1]
                uvB[k * 2] = ub[v * 2]; uvB[k * 2 + 1] = ub[v * 2 + 1]
            }
            program.use()
            program.setBufferAttribute("aPos", pos, 2)
            program.setBufferAttribute("aUvA", uvA, 2)
            program.setBufferAttribute("aUvB", uvB, 2)
            program.setSamplerTexIdUniform("uA", ta, 0)
            program.setSamplerTexIdUniform("uB", tb, 1)
            program.setFloatUniform("uT", u)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, nt)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            for (t in textures.values) GlUtil.deleteTexture(t)
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        const val VERT = """
attribute vec2 aPos;
attribute vec2 aUvA;
attribute vec2 aUvB;
varying vec2 vA;
varying vec2 vB;
void main() {
  gl_Position = vec4(aPos, 0.0, 1.0);
  vA = aUvA;
  vB = aUvB;
}
"""
        const val FRAG = """
precision mediump float;
varying vec2 vA;
varying vec2 vB;
uniform sampler2D uA;
uniform sampler2D uB;
uniform float uT;
void main() {
  gl_FragColor = mix(texture2D(uA, vA), texture2D(uB, vB), uT);
}
"""
    }
}
