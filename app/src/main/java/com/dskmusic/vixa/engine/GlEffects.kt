package com.dskmusic.vixa.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.dskmusic.vixa.model.BgType
import com.dskmusic.vixa.model.BlendMode
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.ColorAdjust
import com.dskmusic.vixa.model.FitMode
import com.dskmusic.vixa.model.MaskType
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.ZoneType
import com.dskmusic.vixa.model.kf
import com.dskmusic.vixa.model.sourceOffsetAt
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Contexto de render compartido por todos los efectos de una composición. Los efectos leen el proyecto
 * en vivo a través de [project]: mover un slider no obliga a reconstruir la composición.
 */
class RenderCtx(
    val appContext: Context,
    val canvasW: Int,
    val canvasH: Int,
    val project: () -> Project,
) {
    /** Tiempo del último fotograma compuesto (lo escribe la pasada MODE_FRAME); da el progreso real de la exportación. */
    @Volatile var renderedUs = 0L
}

/** Desplazamientos de estabilización por medio: pares (dx, dy) en fracción del encuadre, a 10 muestras/s. */
object StabStore {
    const val RATE = 10
    val data = ConcurrentHashMap<String, FloatArray>()

    fun offset(path: String, srcUs: Long, out: FloatArray) {
        val a = data[path]
        if (a == null || a.size < 2) { out[0] = 0f; out[1] = 0f; return }
        val i = ((srcUs * RATE / 1_000_000L).toInt()).coerceIn(0, a.size / 2 - 1)
        out[0] = a[i * 2]; out[1] = a[i * 2 + 1]
    }
}

/** Carga LUT .cube (de assets o de archivo) como mapa de bits de n·n × n. */
object LutStore {
    class Lut(val bitmap: Bitmap, val size: Int)

    private val cache = ConcurrentHashMap<String, Lut>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun get(context: Context, path: String): Lut? {
        cache[path]?.let { return it }
        if (failed.contains(path)) return null
        val lut = try {
            val stream = if (path.startsWith("asset:")) context.assets.open(path.removePrefix("asset:")) else File(path).inputStream()
            stream.use { parse(BufferedReader(InputStreamReader(it))) }
        } catch (e: Throwable) {
            null
        }
        if (lut == null) failed.add(path) else cache[path] = lut
        return lut
    }

    private fun parse(reader: BufferedReader): Lut? {
        var n = 0
        val values = ArrayList<Float>()
        reader.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            if (line.startsWith("LUT_3D_SIZE")) {
                n = line.substringAfter("LUT_3D_SIZE").trim().toIntOrNull() ?: 0
                return@forEachLine
            }
            val c = line[0]
            if (!(c.isDigit() || c == '-' || c == '.')) return@forEachLine
            val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.size >= 3) {
                values.add(parts[0].toFloatOrNull() ?: 0f)
                values.add(parts[1].toFloatOrNull() ?: 0f)
                values.add(parts[2].toFloatOrNull() ?: 0f)
            }
        }
        if (n < 2 || n > 65 || values.size < n * n * n * 3) return null
        val pixels = IntArray(n * n * n)
        var i = 0
        // Orden .cube: el rojo cambia más rápido, luego verde, luego azul.
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val rr = (values[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val gg = (values[i + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            val bb = (values[i + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            pixels[g * n * n + b * n + r] = Color.argb(255, rr, gg, bb)
            i += 3
        }
        return Lut(Bitmap.createBitmap(pixels, n * n, n, Bitmap.Config.ARGB_8888), n)
    }
}

const val MODE_TEXTURE = 0
const val MODE_GENERATOR = 1
const val MODE_FRAME = 2
const val MODE_BLANK = 3

/**
 * Pasada única por clip (o por fotograma completo con [MODE_FRAME]): transformación, recorte, color,
 * croma, máscara, zona, opacidad/fusión, fondo desenfocado y transiciones.
 */
class ClipEffect(
    private val ctx: RenderCtx,
    private val clipId: String,
    private val mode: Int,
    private val transInId: String = "",
    private val transOutId: String = "",
    private val inOnTop: Boolean = true,
    private val outOnTop: Boolean = false,
    private val isMain: Boolean = false,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ClipProgram(ctx, clipId, mode, transInId, transOutId, inOnTop, outOnTop, isMain)
}

private fun colorTo(c: Int, out: FloatArray, alpha: Float = -1f) {
    out[0] = Color.red(c) / 255f
    out[1] = Color.green(c) / 255f
    out[2] = Color.blue(c) / 255f
    out[3] = if (alpha >= 0f) alpha else Color.alpha(c) / 255f
}

private class ClipProgram(
    private val ctx: RenderCtx,
    private val clipId: String,
    private val mode: Int,
    transInId: String,
    transOutId: String,
    private val inOnTop: Boolean,
    private val outOnTop: Boolean,
    private val isMain: Boolean,
) : BaseGlShaderProgram(false, 1) {

    private val program: GlProgram
    private var inW = 1
    private var inH = 1
    private val dummyTex: Int
    private var lutTex = 0
    private var lutPath = ""
    private var lutSize = 0
    private var curveTex = 0
    private var curveKey = 0
    private val anim = AnimState()
    private val stab = FloatArray(2)
    // Máscara de personas: textura en escala de grises, actualizada solo cuando cambia el fotograma de máscara.
    private var personTex = 0
    private var personBmp: android.graphics.Bitmap? = null
    private var personPx = IntArray(0)
    private var personIdx = -1
    private var personPath = ""
    private val pv = Array(3) { FloatArray(4) }
    private var lastDbg = -1L

    private val m0 = FloatArray(3)
    private val m1 = FloatArray(3)
    private val v = Array(30) { FloatArray(4) }

    init {
        try {
            val frag = Shaders.clipFragment(
                Registry.transition(transInId)?.body ?: "",
                Registry.transition(transOutId)?.body ?: "",
            )
            program = GlProgram(Shaders.VERTEX, frag)
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            dummyTex = GlUtil.createTexture(1, 1, false)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        inW = max(1, inputWidth)
        inH = max(1, inputHeight)
        return if (mode == MODE_FRAME) Size(inputWidth, inputHeight) else Size(ctx.canvasW, ctx.canvasH)
    }

    private fun set(name: String, a: FloatArray) = program.setFloatsUniformIfPresent(name, a)

    private fun f4(i: Int, x: Float, y: Float, z: Float, w: Float): FloatArray {
        val a = v[i]
        a[0] = x; a[1] = y; a[2] = z; a[3] = w
        return a
    }

    /** Sube los uniforms de color. [c] aporta ajustes, filtro, LUT y keyframes. Devuelve true si hay algo que aplicar. */
    private fun grade(c: Clip?, localUs: Long, darkness: Float): Boolean {
        val adj = c?.color ?: ColorAdjust()
        val filter = if (c != null && c.filterId.isNotEmpty()) Registry.filter(c.filterId) else null
        val fi = c?.filterIntensity ?: 0f
        val fa = filter?.adj
        fun p(name: String, base: Float, fv: Float?): Float = (c?.kf(name, localUs, base) ?: base) + (fv ?: 0f) * fi
        set("uA", f4(0, p("exposure", adj.exposure, fa?.exposure), p("brightness", adj.brightness, fa?.brightness),
            p("contrast", adj.contrast, fa?.contrast), p("saturation", adj.saturation, fa?.saturation)))
        set("uB", f4(1, p("temperature", adj.temperature, fa?.temperature), p("tint", adj.tint, fa?.tint),
            p("highlights", adj.highlights, fa?.highlights), p("shadows", adj.shadows, fa?.shadows)))
        set("uC", f4(2, p("vibrance", adj.vibrance, fa?.vibrance), p("fade", adj.fade, fa?.fade),
            p("hue", adj.hue, fa?.hue), p("lightness", adj.lightness, fa?.lightness)))
        set("uD", f4(3, p("sharpen", adj.sharpen, fa?.sharpen).coerceIn(0f, 1f), p("vignette", adj.vignette, fa?.vignette).coerceIn(0f, 1f),
            p("grain", adj.grain, fa?.grain).coerceIn(0f, 1f), ((filter?.mono ?: 0f) * fi).coerceIn(0f, 1f)))
        val ts = v[4]
        val th = v[5]
        colorTo(filter?.tintS ?: 0, ts)
        colorTo(filter?.tintH ?: 0, th)
        ts[3] *= fi
        th[3] *= fi
        set("uTintS", ts)
        set("uTintH", th)

        // LUT: la propia del clip tiene prioridad sobre la del filtro.
        val path = if (c != null && c.lutPath.isNotEmpty()) c.lutPath else filter?.lut ?: ""
        var lutAmount = 0f
        if (path.isNotEmpty()) {
            if (path != lutPath) {
                lutPath = path
                lutSize = 0
                val lut = LutStore.get(ctx.appContext, path)
                if (lut != null) {
                    if (lutTex == 0) lutTex = GlUtil.generateTexture()
                    GlUtil.setTexture(lutTex, lut.bitmap)
                    lutSize = lut.size
                }
            }
            if (lutSize > 0) lutAmount = if (c != null && c.lutPath.isNotEmpty() && filter == null) 1f else fi.coerceIn(0f, 1f)
        }
        var hasCurve = 0f
        if (adj.hasCurves) {
            val key = adj.curve.hashCode() * 31 + adj.curveR.hashCode() * 17 + adj.curveG.hashCode() * 7 + adj.curveB.hashCode()
            if (key != curveKey || curveTex == 0) {
                curveKey = key
                if (curveTex == 0) curveTex = GlUtil.generateTexture()
                GlUtil.setTexture(curveTex, curveBitmap(adj))
            }
            hasCurve = 1f
        }
        set("uLutP", f4(6, lutAmount, max(2, lutSize).toFloat(), hasCurve, darkness))
        program.setSamplerTexIdUniform("uLut", if (lutAmount > 0f) lutTex else dummyTex, 1)
        program.setSamplerTexIdUniform("uCurve", if (hasCurve > 0f) curveTex else dummyTex, 2)
        program.setSamplerTexIdUniform("uPersonTex", dummyTex, 3)
        return true
    }

    private fun curveBitmap(adj: ColorAdjust): Bitmap {
        fun eval(pts: List<Float>, x: Float): Float {
            if (pts.size != 5) return x
            val s = (x * 4f).coerceIn(0f, 4f)
            val i = s.toInt().coerceAtMost(3)
            val t = s - i
            // Interpolación suave (Catmull-Rom) entre los 5 puntos de control.
            val p0 = pts[max(0, i - 1)]
            val p1 = pts[i]
            val p2 = pts[i + 1]
            val p3 = pts[minOf(4, i + 2)]
            val r = 0.5f * ((2f * p1) + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t + (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t)
            return r.coerceIn(0f, 1f)
        }
        val px = IntArray(256)
        for (i in 0 until 256) {
            val x = eval(adj.curve, i / 255f)
            px[i] = Color.argb(255, (eval(adj.curveR, x) * 255f).toInt(), (eval(adj.curveG, x) * 255f).toInt(), (eval(adj.curveB, x) * 255f).toInt())
        }
        return Bitmap.createBitmap(px, 256, 1, Bitmap.Config.ARGB_8888)
    }

    /** Sube los uniforms y la textura de la máscara de personas del clip (si la tiene y el archivo existe). */
    private fun applyPerson(c: Clip, local: Long) {
        val pc = c.personCut
        var mode = 0f
        if (pc != null && pc.mode > 0 && (c.kind == ClipKind.VIDEO || c.kind == ClipKind.IMAGE)) {
            val r = MaskStore.get(pc.path)
            if (r != null) {
                val srcUs = if (c.kind == ClipKind.IMAGE) 0L else c.srcInUs + c.sourceOffsetAt(local)
                val idx = r.indexAt(srcUs)
                if (idx != personIdx || pc.path != personPath || personBmp == null) {
                    if (personTex == 0) personTex = GlUtil.generateTexture()
                    if (personPx.size != r.w * r.h) personPx = IntArray(r.w * r.h)
                    var bmp = personBmp
                    if (bmp == null || bmp.width != r.w || bmp.height != r.h) { bmp = android.graphics.Bitmap.createBitmap(r.w, r.h, android.graphics.Bitmap.Config.ARGB_8888); personBmp = bmp }
                    r.fill(idx, personPx)
                    bmp.setPixels(personPx, 0, r.w, 0, 0, r.w, r.h)
                    GlUtil.setTexture(personTex, bmp)
                    personIdx = idx
                    personPath = pc.path
                }
                mode = pc.mode.toFloat()
            }
        }
        val a = pv[0]; a[0] = mode; a[1] = pc?.edge ?: 0.5f; a[2] = pc?.blur ?: 0.6f; a[3] = pc?.outline ?: 0f
        set("uPerson", a)
        val col = pv[1]; colorTo(pc?.color ?: Color.BLACK, col, 1f)
        set("uPColor", col)
        val oc = pv[2]; colorTo(pc?.outlineColor ?: Color.WHITE, oc, 1f)
        set("uPOut", oc)
        program.setSamplerTexIdUniform("uPersonTex", if (mode > 0f && personTex != 0) personTex else dummyTex, 3)
    }

    private fun draw(inputTexId: Int) {
        program.setSamplerTexIdUniform("uTex", inputTexId, 0)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            val p = ctx.project()
            val cw = ctx.canvasW.toFloat()
            val ch = ctx.canvasH.toFloat()
            if (mode == MODE_FRAME) {
                drawFrameMode(p, inputTexId, presentationTimeUs)
                return
            }
            val c = if (mode == MODE_BLANK) null else p.clip(clipId)
            val track = if (c == null) null else p.trackOf(clipId)
            val t = presentationTimeUs
            // TEMPORAL: diagnóstico de fotos que no se ven en la vista previa (filtrar logcat por "VixaImg").
            if (c != null && c.kind == ClipKind.IMAGE && t / 500_000L != lastDbg) {
                lastDbg = t / 500_000L
                android.util.Log.d("VixaImg", "t=$t start=${c.startUs} end=${c.endUs} in=${inW}x$inH hidden=${track?.hidden}")
            }
            if (c == null || track == null || track.hidden || t < c.startUs - 50_000L || t > c.endUs + 50_000L) {
                set("uMode", f4(7, MODE_BLANK.toFloat(), 0f, 0f, 0f))
                program.setSamplerTexIdUniform("uLut", dummyTex, 1)
                program.setSamplerTexIdUniform("uCurve", dummyTex, 2)
                program.setSamplerTexIdUniform("uPersonTex", dummyTex, 3)
                draw(inputTexId)
                return
            }
            val local = (t - c.startUs).coerceIn(0, c.durationUs)
            val tr = c.transform
            anim.reset()
            Anims.apply(anim, c.animIn, c.animOut, c.animLoop, local, c.durationUs)

            // Tamaño base: el medio (ya recortado) encajado en el lienzo.
            val generator = c.kind == ClipKind.COLOR
            val mw = if (generator) cw else inW * (1f - tr.cropL - tr.cropR).coerceAtLeast(0.01f)
            val mh = if (generator) ch else inH * (1f - tr.cropT - tr.cropB).coerceAtLeast(0.01f)
            val srcAspect = mw / mh
            val canvasAspect = cw / ch
            var bw = 1f
            var bh = 1f
            when (tr.fit) {
                FitMode.FIT -> if (srcAspect > canvasAspect) bh = canvasAspect / srcAspect else bw = srcAspect / canvasAspect
                FitMode.FILL -> if (srcAspect > canvasAspect) bw = srcAspect / canvasAspect else bh = canvasAspect / srcAspect
                FitMode.STRETCH -> {}
            }
            var scale = c.kf("scale", local, tr.scale) * anim.scale
            var x = c.kf("x", local, tr.x) + anim.dx
            var y = c.kf("y", local, tr.y) + anim.dy
            if (c.stabilize && c.media != null) {
                StabStore.offset(c.media.path, c.srcInUs + c.sourceOffsetAt(local), stab)
                x += stab[0] * bw * scale
                y += stab[1] * bh * scale
                scale *= 1.12f
            }
            val rot = Math.toRadians((c.kf("rotation", local, tr.rotation) + anim.rotation).toDouble())
            val opacity = (c.kf("opacity", local, tr.opacity) * anim.alpha).coerceIn(0f, 1f)
            val dw = max(1f, bw * cw * scale * c.kf("sx", local, tr.sx) * anim.sx)
            val dh = max(1f, bh * ch * scale * c.kf("sy", local, tr.sy) * anim.sy)
            val cx = (0.5f + x) * cw
            val cy = (0.5f + y) * ch
            val cs = cos(rot).toFloat()
            val sn = sin(rot).toFloat()
            m0[0] = cw * cs / dw; m0[1] = ch * sn / dw; m0[2] = (-cx * cs - cy * sn) / dw + 0.5f
            m1[0] = -cw * sn / dh; m1[1] = ch * cs / dh; m1[2] = (cx * sn - cy * cs) / dh + 0.5f
            set("uM0", m0)
            set("uM1", m1)
            set("uCrop", f4(8, tr.cropL, tr.cropT, tr.cropR, tr.cropB))
            set("uSize", f4(9, dw, dh, cw, ch))
            set("uSrc", f4(10, 1f / inW, 1f / inH, if (tr.flipH) 1f else 0f, if (tr.flipV) 1f else 0f))
            val blend = when (tr.blend) {
                BlendMode.NORMAL -> 0f; BlendMode.SCREEN -> 1f; BlendMode.ADD -> 2f
                BlendMode.MULTIPLY -> 3f; BlendMode.LIGHTEN -> 4f; BlendMode.DARKEN -> 5f
            }
            set("uMode", f4(7, if (generator) 1f else 0f, opacity, blend, (local % 1_000_000L) / 1_000_000f))

            // Fundido desde / a negro.
            var dark = 0f
            if (c.blackInUs > 0 && local < c.blackInUs) dark = 1f - local.toFloat() / c.blackInUs
            if (c.blackOutUs > 0 && local > c.durationUs - c.blackOutUs) dark = max(dark, 1f - (c.durationUs - local).toFloat() / c.blackOutUs)
            grade(c, local, dark.coerceIn(0f, 1f))
            applyPerson(c, local)

            val ck = c.chroma
            val chroma = v[11]
            colorTo(ck.color, chroma, if (ck.enabled) 1f else 0f)
            set("uChroma", chroma)
            set("uChromaP", f4(12, ck.similarity * 0.6f, ck.smoothness * 0.5f, ck.spill, 0f))

            val mk = c.mask
            val maskType = when (mk.type) { MaskType.NONE -> 0f; MaskType.LINEAR -> 1f; MaskType.CIRCLE -> 2f; MaskType.RECT -> 3f }
            set("uMask", f4(13, c.kf("mask.cx", local, mk.cx), c.kf("mask.cy", local, mk.cy), c.kf("mask.w", local, mk.w), c.kf("mask.h", local, mk.h)))
            set("uMaskP", f4(14, maskType, Math.toRadians(mk.rotation.toDouble()).toFloat(), mk.feather * 0.5f, if (mk.invert) 1f else 0f))

            val zn = c.zone
            val zoneType = when (zn.type) { ZoneType.NONE -> 0f; ZoneType.BLUR -> 1f; ZoneType.MOSAIC -> 2f }
            set("uZone", f4(15, c.kf("zone.cx", local, zn.cx), c.kf("zone.cy", local, zn.cy), c.kf("zone.w", local, zn.w), c.kf("zone.h", local, zn.h)))
            set("uZoneP", f4(16, zoneType, if (zn.ellipse) 1f else 0f, zn.strength, anim.blur))
            set("uReveal", f4(17, anim.revealL, anim.revealT, anim.revealR, anim.revealB))

            val fill = c.fill
            val g0 = v[18]
            val g1 = v[19]
            val g2 = v[27]
            val g3 = v[28]
            colorTo(fill?.colors?.getOrNull(0) ?: Color.BLACK, g0)
            colorTo(fill?.colors?.getOrNull(1) ?: Color.BLACK, g1)
            colorTo(fill?.colors?.getOrNull(2) ?: Color.BLACK, g2)
            colorTo(fill?.colors?.getOrNull(3) ?: Color.BLACK, g3)
            set("uGen0", g0)
            set("uGen1", g1)
            set("uGen2", g2)
            set("uGen3", g3)
            set("uGenQ", f4(29, (fill?.colors?.size ?: 1).coerceIn(1, 4).toFloat(), if (fill?.radial == true) 1f else 0f, 0f, 0f))
            val blurBg = isMain && !generator && p.background.type == BgType.BLUR
            set("uGenP", f4(20, Math.toRadians((fill?.angle ?: 0f).toDouble()).toFloat(), if ((fill?.colors?.size ?: 0) > 1) 1f else 0f,
                if (blurBg) 1f else 0f, p.background.blur))
            val fullAspect = inW.toFloat() / inH.toFloat()
            if (fullAspect > canvasAspect) {
                val sx = canvasAspect / fullAspect
                set("uFill", f4(21, sx, 1f, (1f - sx) / 2f, 0f))
            } else {
                val sy = fullAspect / canvasAspect
                set("uFill", f4(21, 1f, sy, 0f, (1f - sy) / 2f))
            }

            // Transiciones (solo pista principal).
            var inOn = 0f
            var inP = 0f
            var outOn = 0f
            var outP = 0f
            if (isMain) {
                val ti = c.transitionIn
                if (ti != null && ti.durationUs > 0 && t < c.startUs + ti.durationUs) {
                    inOn = 1f
                    inP = ((t - c.startUs).toFloat() / ti.durationUs).coerceIn(0f, 1f)
                }
                val clips = track.clips
                val idx = clips.indexOf(c)
                val next = if (idx >= 0 && idx < clips.size - 1) clips[idx + 1] else null
                val to = next?.transitionIn
                if (next != null && to != null && to.durationUs > 0 && t >= next.startUs) {
                    outOn = 1f
                    outP = ((t - next.startUs).toFloat() / to.durationUs).coerceIn(0f, 1f)
                }
            }
            set("uTr", f4(22, inOn, inP, outOn, outP))
            set("uTrP", f4(23, if (inOnTop) 1f else 0f, if (outOnTop) 1f else 0f, 0f, 0f))
            draw(inputTexId)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** Fotograma completo: fondo del lienzo bajo la mezcla y capa de ajuste activa. */
    private fun drawFrameMode(p: Project, inputTexId: Int, t: Long) {
        ctx.renderedUs = t
        var adjust: Clip? = null
        for (tr in p.tracks) {
            if (tr.type != TrackType.ADJUST || tr.hidden) continue
            adjust = tr.clips.firstOrNull { t >= it.startUs && t < it.endUs }
            if (adjust != null) break
        }
        set("uMode", f4(7, MODE_FRAME.toFloat(), 1f, 0f, (t % 1_000_000L) / 1_000_000f))
        set("uSize", f4(9, inW.toFloat(), inH.toFloat(), inW.toFloat(), inH.toFloat()))
        grade(adjust, if (adjust != null) t - adjust.startUs else 0L, 0f)
        val bg = p.background
        val b0 = v[24]
        val b1 = v[25]
        colorTo(bg.colors.getOrNull(0) ?: Color.BLACK, b0)
        colorTo(bg.colors.getOrNull(1) ?: Color.BLACK, b1)
        set("uBg0", b0)
        set("uBg1", b1)
        set("uBgP", f4(26, Math.toRadians(bg.angle.toDouble()).toFloat(), if (bg.type == BgType.GRADIENT && bg.colors.size > 1) 1f else 0f,
            if (bg.type == BgType.BLUR) 0f else 1f, if (adjust != null) 1f else 0f))
        draw(inputTexId)
    }

    override fun release() {
        super.release()
        try {
            program.delete()
            GlUtil.deleteTexture(dummyTex)
            if (lutTex != 0) GlUtil.deleteTexture(lutTex)
            if (curveTex != 0) GlUtil.deleteTexture(curveTex)
            if (personTex != 0) GlUtil.deleteTexture(personTex)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

/** Pasada de un efecto del registro. Si [global] es true pertenece a una capa de ajuste (efecto de composición). */
class FxEffect(
    private val ctx: RenderCtx,
    private val clipId: String,
    private val instanceId: String,
    private val defId: String,
    private val global: Boolean,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        val def = Registry.effect(defId) ?: return androidx.media3.effect.PassthroughShaderProgram()
        // Un shader que no compila en este dispositivo no debe romper la vista previa: se salta el efecto.
        return try { FxProgram(ctx, clipId, instanceId, def, global) } catch (e: Throwable) {
            com.dskmusic.vixa.data.ErrorLog.log("Fx " + defId, e)
            androidx.media3.effect.PassthroughShaderProgram()
        }
    }
}

private class FxProgram(
    private val ctx: RenderCtx,
    private val clipId: String,
    private val instanceId: String,
    private val def: FxDef,
    private val global: Boolean,
) : BaseGlShaderProgram(false, 1) {

    private val program: GlProgram
    private val res = FloatArray(2)
    private val p0 = FloatArray(4)
    private val p1 = FloatArray(4)
    private val fx = FloatArray(4)

    init {
        try {
            program = GlProgram(Shaders.VERTEX, Shaders.fxFragment(def.body))
            program.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        res[0] = inputWidth.toFloat()
        res[1] = inputHeight.toFloat()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            val p = ctx.project()
            val c = p.clip(clipId)
            val inst = c?.effects?.firstOrNull { it.id == instanceId }
            val t = presentationTimeUs
            val active = c != null && inst != null && inst.enabled &&
                (!global || (t >= c.startUs && t < c.endUs && p.trackOf(clipId)?.hidden != true))
            fx[0] = if (active) 1f else 0f
            fx[2] = 1f
            if (active && c != null && inst != null) {
                val local = (t - c.startUs).coerceAtLeast(0)
                fx[1] = local / 1_000_000f
                for ((i, pd) in def.params.withIndex()) {
                    val value = c.kf("fx:" + inst.id + ":" + pd.key, local, inst.params[pd.key] ?: pd.def)
                    if (i < 4) p0[i] = value else if (i < 8) p1[i - 4] = value
                }
            }
            program.setFloatsUniformIfPresent("uRes", res)
            program.setFloatsUniformIfPresent("uP0", p0)
            program.setFloatsUniformIfPresent("uP1", p1)
            program.setFloatsUniformIfPresent("uFx", fx)
            program.setSamplerTexIdUniform("uTex", inputTexId, 0)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

/**
 * Pasada de efectos de una pista de ajuste: UNA por pista y por "hueco" de efecto (el primer efecto de cada capa, el segundo…),
 * en vez de una por cada efecto de cada capa. En cada fotograma busca la capa activa de la pista y aplica el efecto que le toca
 * (si no hay ninguna, copia la imagen). Los shaders se compilan al usarlos por primera vez y se guardan, así que un proyecto con
 * decenas de capas de efectos cuesta lo mismo que uno con una.
 */
class FxSlotEffect(
    private val ctx: RenderCtx,
    private val trackId: String,
    private val slot: Int,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = FxSlotProgram(ctx, trackId, slot)
}

private val PASS_FX = FxDef("pass", 0, 0, emptyList(), "  return T(uv);")

private class FxSlotProgram(
    private val ctx: RenderCtx,
    private val trackId: String,
    private val slot: Int,
) : BaseGlShaderProgram(false, 1) {

    private val programs = HashMap<String, GlProgram>()
    private val failed = HashSet<String>()
    private val res = FloatArray(2)
    private val p0 = FloatArray(4)
    private val p1 = FloatArray(4)
    private val fx = FloatArray(4)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        res[0] = inputWidth.toFloat()
        res[1] = inputHeight.toFloat()
        return Size(inputWidth, inputHeight)
    }

    private fun programFor(def: FxDef): GlProgram? {
        programs[def.id]?.let { return it }
        if (def.id in failed) return null
        return try {
            GlProgram(Shaders.VERTEX, Shaders.fxFragment(def.body)).also {
                it.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                programs[def.id] = it
            }
        } catch (e: Throwable) {
            // Un efecto cuyo shader no compila en este dispositivo se salta en vez de romper la vista previa.
            com.dskmusic.vixa.data.ErrorLog.log("Fx " + def.id, e)
            failed.add(def.id)
            null
        }
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val t = presentationTimeUs
            val p = ctx.project()
            val track = p.tracks.firstOrNull { it.id == trackId }
            val clip = if (track == null || track.hidden) null else track.clips.firstOrNull { t >= it.startUs && t < it.endUs }
            val inst = clip?.effects?.getOrNull(slot)?.takeIf { it.enabled }
            val def = inst?.let { Registry.effect(it.defId) }
            var program = if (def != null) programFor(def) else null
            val active = program != null && clip != null && inst != null && def != null
            if (program == null) program = programFor(PASS_FX) ?: return
            fx[0] = if (active) 1f else 0f
            fx[2] = 1f
            if (active && clip != null && inst != null && def != null) {
                val local = (t - clip.startUs).coerceAtLeast(0)
                fx[1] = local / 1_000_000f
                p0.fill(0f)
                p1.fill(0f)
                for ((i, pd) in def.params.withIndex()) {
                    val value = clip.kf("fx:" + inst.id + ":" + pd.key, local, inst.params[pd.key] ?: pd.def)
                    if (i < 4) p0[i] = value else if (i < 8) p1[i - 4] = value
                }
            }
            program.use()
            program.setFloatsUniformIfPresent("uRes", res)
            program.setFloatsUniformIfPresent("uP0", p0)
            program.setFloatsUniformIfPresent("uP1", p1)
            program.setFloatsUniformIfPresent("uFx", fx)
            program.setSamplerTexIdUniform("uTex", inputTexId, 0)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            for (pr in programs.values) pr.delete()
            programs.clear()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

/**
 * Overlay de texto, stickers, subtítulos y marca de agua. Solo redibuja (y vuelve a subir la textura)
 * cuando cambia lo que hay que mostrar.
 */
class VixaOverlay(private val ctx: RenderCtx) : BitmapOverlay() {
    private val render = OverlayRender()
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var lastSig = Long.MIN_VALUE
    private var w = ctx.canvasW
    private var h = ctx.canvasH

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        w = max(1, videoSize.width)
        h = max(1, videoSize.height)
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        var b = bitmap
        if (b == null || b.width != w || b.height != h) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = b
            canvas = Canvas(b)
            lastSig = Long.MIN_VALUE
        }
        val p = ctx.project()
        val sig = render.signature(p, presentationTimeUs)
        if (sig != lastSig) {
            lastSig = sig
            b.eraseColor(Color.TRANSPARENT)
            try {
                render.drawAll(canvas!!, p, presentationTimeUs, w, h)
            } catch (e: Throwable) {
                // Un fallo de dibujo (fuente dañada, imagen corrupta) no debe tumbar la exportación.
            }
        }
        return b
    }

    override fun release() {
        super.release()
        bitmap = null
        canvas = null
    }
}
