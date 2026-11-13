package com.dskmusic.vixa.engine

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import com.dskmusic.vixa.media.ImageCache
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.Layer
import com.dskmusic.vixa.model.LayerKind
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.StickerType
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.kf
import java.text.BreakIterator
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Dibuja texto, plantillas, stickers, subtítulos y marca de agua sobre un Canvas en función del tiempo.
 * Es el mismo código para la vista previa, la exportación y las miniaturas animadas de las plantillas.
 * No es seguro entre hilos: cada overlay usa su propia instancia.
 */
class OverlayRender {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val state = AnimState()
    private val layerState = AnimState()
    private val rect = RectF()
    private val path = Path()
    private val breaker: BreakIterator = BreakIterator.getCharacterInstance()

    /** Firma de lo que hay que dibujar en [tUs]: si no cambia entre fotogramas no hace falta redibujar. */
    fun signature(p: Project, tUs: Long): Long {
        var h = 17L
        for (t in p.tracks) {
            if (t.hidden || (t.type != TrackType.TEXT && t.type != TrackType.STICKER)) continue
            for (c in t.clips) {
                if (tUs < c.startUs || tUs >= c.endUs) continue
                h = h * 31 + System.identityHashCode(c)
                val wu = c.text?.wordsUs
                if (p.karaokeColor != 0 && !wu.isNullOrEmpty()) h = h * 31 + wordAt(wu, tUs - c.startUs)
                if (isAnimated(c, tUs - c.startUs)) h = h * 31 + tUs
            }
        }
        val wm = p.watermark
        if (wm.enabled) h = h * 31 + wm.hashCode()
        h = h * 31 + System.identityHashCode(p.subtitleStyle)
        return h
    }

    /** Índice de la palabra que se está diciendo en [localUs] (-1 antes de la primera). */
    private fun wordAt(wordsUs: List<Long>, localUs: Long): Int {
        var i = -1
        for (t in wordsUs) { if (t <= localUs) i++ else break }
        return i
    }

    private var karaokeColor = 0
    private var karaokeWord = -1

    private fun isAnimated(c: Clip, localUs: Long): Boolean {
        if (c.keyframes.isNotEmpty()) return true
        if (Anims.isAnimating(c.animIn, c.animOut, c.animLoop, localUs, c.durationUs)) return true
        val st = c.sticker
        if (st != null && st.type == StickerType.ANIMATED) return true
        val tx = c.text ?: return false
        for (l in tx.layers) {
            if (l.dynamic.isNotEmpty()) return true
            if (Anims.isAnimating(l.animIn, l.animOut, l.animLoop, localUs - l.delayUs, c.durationUs - l.delayUs)) return true
            if (localUs < l.delayUs + 50_000L) return true
        }
        return false
    }

    fun drawAll(canvas: Canvas, p: Project, tUs: Long, w: Int, h: Int) {
        // Las pistas de más abajo en la lista se dibujan primero.
        for (i in p.tracks.indices.reversed()) {
            val t = p.tracks[i]
            if (t.hidden || (t.type != TrackType.TEXT && t.type != TrackType.STICKER)) continue
            for (c in t.clips) {
                if (tUs < c.startUs || tUs >= c.endUs) continue
                drawClip(canvas, p, c, tUs - c.startUs, w, h)
            }
        }
        drawWatermark(canvas, p, w, h)
    }

    fun drawClip(canvas: Canvas, p: Project, c: Clip, localUs: Long, w: Int, h: Int) {
        val tr = c.transform
        state.reset()
        Anims.apply(state, c.animIn, c.animOut, c.animLoop, localUs, c.durationUs)
        val cx = (0.5f + c.kf("x", localUs, tr.x) + state.dx) * w
        val cy = (0.5f + c.kf("y", localUs, tr.y) + state.dy) * h
        val sc = c.kf("scale", localUs, tr.scale) * state.scale
        val rot = c.kf("rotation", localUs, tr.rotation) + state.rotation
        val alpha = (c.kf("opacity", localUs, tr.opacity) * state.alpha).coerceIn(0f, 1f)
        if (alpha <= 0.003f || sc <= 0.0001f) return
        val save = canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rot)
        canvas.scale(sc * state.sx * c.kf("sx", localUs, tr.sx) * (if (tr.flipH) -1f else 1f), sc * state.sy * c.kf("sy", localUs, tr.sy) * (if (tr.flipV) -1f else 1f))
        val layerSave = if (alpha < 0.997f) canvas.saveLayerAlpha(-w.toFloat(), -h.toFloat(), w.toFloat(), h.toFloat(), (alpha * 255).toInt()) else -1
        if (state.revealL > 0f || state.revealR > 0f || state.revealT > 0f || state.revealB > 0f) {
            bounds(p, c, w, h, rect)
            canvas.clipRect(
                rect.left + rect.width() * state.revealL, rect.top + rect.height() * state.revealT,
                rect.right - rect.width() * state.revealR, rect.bottom - rect.height() * state.revealB,
            )
        }
        when (c.kind) {
            ClipKind.TEXT -> {
                val spec = c.text
                if (spec != null) {
                    val karaoke = spec.subtitle && p.karaokeColor != 0 && spec.wordsUs.isNotEmpty()
                    karaokeColor = if (karaoke) p.karaokeColor else 0
                    karaokeWord = if (karaoke) wordAt(spec.wordsUs, localUs) else -1
                    for (l in spec.layers) {
                        val base = if (spec.subtitle) p.subtitleStyle.copy(id = l.id, text = l.text) else l
                        drawLayer(canvas, base, c, localUs, w, h, state)
                    }
                }
            }
            ClipKind.STICKER -> drawSticker(canvas, c, localUs, w, h)
            else -> {}
        }
        if (layerSave >= 0) canvas.restoreToCount(layerSave)
        canvas.restoreToCount(save)
    }

    /** Caja (sin animar, relativa al centro del clip, en píxeles) que ocupa el contenido de un clip de texto/sticker. */
    fun bounds(p: Project, c: Clip, w: Int, h: Int, out: RectF) {
        out.setEmpty()
        val minSide = min(w, h).toFloat()
        val st = c.sticker
        if (c.kind == ClipKind.STICKER && st != null) {
            val s = st.size * minSide
            var sw = s
            var sh = s
            if (st.type == StickerType.IMAGE || st.type == StickerType.ANIMATED) {
                val b = if (st.type == StickerType.ANIMATED) ImageCache.animatedIfLoaded(st.value)?.frameAt(0) else ImageCache.still(st.value)
                if (b != null && b.width > 0 && b.height > 0) {
                    val a = b.width.toFloat() / b.height.toFloat()
                    if (a >= 1f) sh = s / a else sw = s * a
                }
            }
            out.set(-sw / 2, -sh / 2, sw / 2, sh / 2)
            return
        }
        val spec = c.text ?: return
        val tmp = RectF()
        for (l0 in spec.layers) {
            val l = if (spec.subtitle) p.subtitleStyle.copy(id = l0.id, text = l0.text) else l0
            layerBox(l, c, 0L, w, h, tmp)
            if (out.isEmpty) out.set(tmp) else out.union(tmp)
        }
        if (out.isEmpty) out.set(-minSide * 0.1f, -minSide * 0.05f, minSide * 0.1f, minSide * 0.05f)
    }

    // -----------------------------------------------------------------------------------------
    // Texto
    // -----------------------------------------------------------------------------------------

    private val lines = ArrayList<String>()

    private fun textOf(l: Layer, c: Clip, localUs: Long): String {
        var s = l.text
        if (l.dynamic == "countdown") s = max(0, ceil((c.durationUs - localUs) / 1_000_000.0).toInt()).toString()
        return if (l.upper) s.uppercase() else s
    }

    private fun setupPaint(l: Layer, minSide: Float): Float {
        val size = max(1f, l.size * minSide)
        paint.reset()
        // Paint.reset() no devuelve la alineación a la izquierda: tras dibujar un emoji (centrado) o la marca de agua, todos los
        // textos salían desplazados medio ancho hacia la izquierda.
        paint.textAlign = Paint.Align.LEFT
        paint.isAntiAlias = true
        paint.isSubpixelText = true
        paint.typeface = Fonts.typeface(l.fontId)
        paint.textSize = size
        paint.letterSpacing = l.letterSpacing
        paint.isFakeBoldText = l.bold
        paint.textSkewX = if (l.italic) -0.22f else 0f
        return size
    }

    private fun layout(text: String, maxW: Float) {
        lines.clear()
        for (raw in text.split('\n')) {
            var rest = raw
            if (rest.isEmpty()) { lines.add(""); continue }
            while (rest.isNotEmpty()) {
                val n = paint.breakText(rest, true, maxW, null)
                if (n >= rest.length || n <= 0) { lines.add(rest); break }
                var cut = rest.lastIndexOf(' ', n)
                if (cut <= 0) cut = n
                lines.add(rest.substring(0, cut).trimEnd())
                rest = rest.substring(cut).trimStart()
            }
        }
    }

    /** Calcula la caja de una capa (relativa al centro del clip). Deja [lines] y [paint] preparados para textos. */
    private fun layerBox(l: Layer, c: Clip, localUs: Long, w: Int, h: Int, out: RectF): Float {
        val minSide = min(w, h).toFloat()
        val lx = l.x * w
        val ly = l.y * h
        if (l.kind != LayerKind.TEXT) {
            val lw = l.w * w
            val lh = if (l.kind == LayerKind.OVAL && l.h <= 0f) lw else l.h * h
            out.set(lx - lw / 2, ly - lh / 2, lx + lw / 2, ly + lh / 2)
            return 0f
        }
        val size = setupPaint(l, minSide)
        layout(textOf(l, c, localUs), l.maxWidth * w)
        var maxLine = 0f
        for (s in lines) maxLine = max(maxLine, paint.measureText(s))
        val lineH = size * l.lineSpacing
        val total = lineH * lines.size
        val pad = if (l.bgColor != 0) l.bgPadding * size else 0f
        val left = when (l.align) { 0 -> lx; 2 -> lx - maxLine; else -> lx - maxLine / 2 }
        out.set(left - pad, ly - total / 2 - pad, left + maxLine + pad, ly + total / 2 + pad)
        return size
    }

    private fun shaderFor(colors: List<Int>, angle: Float, r: RectF): Shader? {
        if (colors.size < 2) return null
        val a = Math.toRadians(angle.toDouble())
        val dx = (cos(a) * r.width() / 2).toFloat()
        val dy = (sin(a) * r.height() / 2).toFloat()
        return LinearGradient(r.centerX() - dx, r.centerY() - dy, r.centerX() + dx, r.centerY() + dy, colors.toIntArray(), null, Shader.TileMode.CLAMP)
    }

    private val box = RectF()
    private val charFx = CharFx()

    private fun drawLayer(canvas: Canvas, l: Layer, c: Clip, localUs: Long, w: Int, h: Int, clipState: AnimState) {
        val t = localUs - l.delayUs
        if (t < 0) return
        val s = layerState
        s.reset()
        Anims.apply(s, l.animIn, l.animOut, l.animLoop, t, c.durationUs - l.delayUs)
        // Las animaciones por carácter del clip se heredan en las capas.
        if (s.charMode == AnimState.CHAR_NONE && clipState.charMode != AnimState.CHAR_NONE) {
            s.charMode = clipState.charMode; s.chars = clipState.chars; s.charPhase = clipState.charPhase
        }
        val a = s.alpha.coerceIn(0f, 1f)
        if (a <= 0.003f) return
        val size = layerBox(l, c, localUs, w, h, box)
        val save = canvas.save()
        canvas.translate(s.dx * w, s.dy * h)
        canvas.rotate(l.rotation + s.rotation, box.centerX(), box.centerY())
        canvas.scale(s.scale * s.sx, s.scale * s.sy, box.centerX(), box.centerY())
        if (s.revealL > 0f || s.revealR > 0f || s.revealT > 0f || s.revealB > 0f) {
            canvas.clipRect(
                box.left + box.width() * s.revealL, box.top + box.height() * s.revealT,
                box.right - box.width() * s.revealR, box.bottom - box.height() * s.revealB,
            )
        }
        val alphaI = (a * 255).toInt()
        if (l.kind != LayerKind.TEXT) {
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = l.color
            paint.shader = shaderFor(l.gradient, l.gradientAngle, box)
            paint.alpha = (paint.alpha * a).toInt()
            val r = l.cornerRadius * min(box.width(), box.height())
            if (l.kind == LayerKind.OVAL) canvas.drawOval(box, paint) else canvas.drawRoundRect(box, r, r, paint)
            canvas.restoreToCount(save)
            return
        }
        if (l.bgColor != 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG)
            bg.color = l.bgColor
            bg.alpha = (bg.alpha * a).toInt()
            val r = l.bgRadius * size
            canvas.drawRoundRect(box, r, r, bg)
        }
        val pad = if (l.bgColor != 0) l.bgPadding * size else 0f
        val lineH = size * l.lineSpacing
        val fm = paint.fontMetrics
        val innerLeft = box.left + pad
        val innerW = box.width() - pad * 2
        var total = 0
        if (s.charMode != AnimState.CHAR_NONE) for (ln in lines) total += countGraphemes(ln)
        var index = 0
        val gradient = shaderFor(l.gradient, l.gradientAngle, box)
        var wordIndex = 0
        for ((i, ln) in lines.withIndex()) {
            val lw = paint.measureText(ln)
            val x = when (l.align) { 0 -> innerLeft; 2 -> innerLeft + innerW - lw; else -> innerLeft + (innerW - lw) / 2 }
            val baseline = box.top + pad + lineH * i + (lineH - (fm.descent - fm.ascent)) / 2 - fm.ascent
            if (s.charMode == AnimState.CHAR_NONE) {
                drawRun(canvas, ln, 0, ln.length, x, baseline, l, size, alphaI, gradient)
                if (karaokeColor != 0) {
                    // Karaoke: la palabra que se está diciendo se repinta encima con el color de resalte.
                    var a0 = 0
                    while (a0 < ln.length) {
                        while (a0 < ln.length && ln[a0] == ' ') a0++
                        if (a0 >= ln.length) break
                        var b0 = a0
                        while (b0 < ln.length && ln[b0] != ' ') b0++
                        if (wordIndex == karaokeWord) drawRun(canvas, ln, a0, b0, x + paint.measureText(ln, 0, a0), baseline, l.copy(color = karaokeColor, shadowRadius = 0f), size, alphaI, null)
                        wordIndex++
                        a0 = b0
                    }
                }
            } else {
                breaker.setText(ln)
                var start = breaker.first()
                var end = breaker.next()
                while (end != BreakIterator.DONE) {
                    val gx = x + paint.measureText(ln, 0, start)
                    drawGrapheme(canvas, ln, start, end, gx, baseline, l, size, alphaI, gradient, s, index, total)
                    index++
                    start = end
                    end = breaker.next()
                }
            }
        }
        canvas.restoreToCount(save)
    }

    private fun countGraphemes(s: String): Int {
        breaker.setText(s)
        var n = 0
        breaker.first()
        while (breaker.next() != BreakIterator.DONE) n++
        return n
    }

    private fun drawGrapheme(
        canvas: Canvas, ln: String, start: Int, end: Int, x: Float, baseline: Float, l: Layer, size: Float,
        alphaI: Int, gradient: Shader?, s: AnimState, index: Int, total: Int,
    ) {
        val f = charFx
        s.charFx(index, total, f)
        val a = f.a
        if (f.hidden || a <= 0.003f) return
        val dx = f.dx * size
        val dy = f.dy * size
        if (f.sc != 1f || f.rot != 0f) {
            val save = canvas.save()
            val gw = paint.measureText(ln, start, end)
            val px = x + dx + gw / 2
            val py = baseline + dy - size * 0.35f
            if (f.rot != 0f) canvas.rotate(f.rot, px, py)
            if (f.sc != 1f) canvas.scale(f.sc, f.sc, px, py)
            drawRun(canvas, ln, start, end, x + dx, baseline + dy, l, size, (alphaI * a).toInt(), gradient)
            canvas.restoreToCount(save)
        } else {
            drawRun(canvas, ln, start, end, x + dx, baseline + dy, l, size, (alphaI * a).toInt(), gradient)
        }
    }

    private fun drawRun(canvas: Canvas, ln: String, start: Int, end: Int, x: Float, y: Float, l: Layer, size: Float, alphaI: Int, gradient: Shader?) {
        if (start >= end) return
        if (l.strokeWidth > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = l.strokeWidth * size
            paint.shader = null
            paint.clearShadowLayer()
            paint.color = l.strokeColor
            paint.alpha = paint.alpha * alphaI / 255
            canvas.drawText(ln, start, end, x, y, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = l.color
        paint.alpha = paint.alpha * alphaI / 255
        if (l.shadowRadius > 0f) {
            val sa = ((l.shadowColor ushr 24) * alphaI / 255) shl 24 or (l.shadowColor and 0x00FFFFFF)
            paint.setShadowLayer(l.shadowRadius * size, l.shadowDx * size, l.shadowDy * size, sa)
        } else paint.clearShadowLayer()
        paint.shader = gradient
        canvas.drawText(ln, start, end, x, y, paint)
        paint.shader = null
        paint.clearShadowLayer()
    }

    // -----------------------------------------------------------------------------------------
    // Stickers y marca de agua
    // -----------------------------------------------------------------------------------------

    private fun drawSticker(canvas: Canvas, c: Clip, localUs: Long, w: Int, h: Int) {
        val st = c.sticker ?: return
        val s = st.size * min(w, h)
        when (st.type) {
            StickerType.EMOJI -> {
                paint.reset()
                paint.isAntiAlias = true
                paint.textSize = s * 0.85f
                paint.textAlign = Paint.Align.CENTER
                val fm = paint.fontMetrics
                canvas.drawText(st.value, 0f, -(fm.ascent + fm.descent) / 2, paint)
            }
            StickerType.SHAPE -> {
                val def = Shapes.get(st.value) ?: return
                path.reset()
                def.build(path)
                paint.reset()
                paint.isAntiAlias = true
                paint.color = st.color
                if (def.stroke) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 0.08f
                } else paint.style = Paint.Style.FILL
                val save = canvas.save()
                canvas.translate(-s / 2, -s / 2)
                canvas.scale(s, s)
                canvas.drawPath(path, paint)
                canvas.restoreToCount(save)
            }
            StickerType.IMAGE, StickerType.ANIMATED -> {
                val b = if (st.type == StickerType.ANIMATED) {
                    ImageCache.animatedIfLoaded(st.value)?.frameAt(localUs) ?: ImageCache.still(st.value, 480)
                } else ImageCache.still(st.value)
                if (b == null || b.isRecycled) return
                val a = b.width.toFloat() / b.height.toFloat()
                val bw = if (a >= 1f) s else s * a
                val bh = if (a >= 1f) s / a else s
                rect.set(-bw / 2, -bh / 2, bw / 2, bh / 2)
                bmpPaint.colorFilter = null
                bmpPaint.alpha = 255
                canvas.drawBitmap(b, null, rect, bmpPaint)
            }
        }
    }

    private fun drawWatermark(canvas: Canvas, p: Project, w: Int, h: Int) {
        val wm = p.watermark
        if (!wm.enabled) return
        val minSide = min(w, h).toFloat()
        val cx = wm.x * w
        val cy = wm.y * h
        val alpha = (wm.opacity.coerceIn(0f, 1f) * 255).toInt()
        if (wm.imagePath.isNotEmpty()) {
            val b = ImageCache.still(wm.imagePath) ?: return
            val bw = wm.scale * minSide
            val bh = bw * b.height / b.width.toFloat()
            rect.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
            bmpPaint.colorFilter = null
            bmpPaint.alpha = alpha
            canvas.drawBitmap(b, null, rect, bmpPaint)
            bmpPaint.alpha = 255
        } else if (wm.text.isNotEmpty()) {
            paint.reset()
            paint.isAntiAlias = true
            paint.typeface = Fonts.typeface("poppins_bold")
            paint.textSize = wm.scale * minSide * 0.35f
            paint.textAlign = Paint.Align.CENTER
            paint.color = 0xFFFFFFFF.toInt()
            paint.alpha = alpha
            paint.setShadowLayer(paint.textSize * 0.12f, 0f, paint.textSize * 0.05f, 0x99000000.toInt())
            canvas.drawText(wm.text, cx, cy + paint.textSize * 0.35f, paint)
        }
    }

    @Suppress("unused")
    private fun tint(color: Int) = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
}
