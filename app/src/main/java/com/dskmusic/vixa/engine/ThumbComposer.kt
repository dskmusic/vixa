package com.dskmusic.vixa.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.media.MediaMetadataRetriever
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.FitMode
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.ui.editor.prop
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Miniatura de un proyecto dibujada SIN depender de la pantalla: el fondo (color, foto o fotograma de vídeo) y, encima, los textos
 * y pegatinas con el mismo motor que la vista previa. No incluye filtros ni efectos de vídeo (para eso se prefiere la captura real).
 *
 * Elige el fotograma de la posición donde el usuario dejó el cursor; si ahí no hay contenido (solo color de fondo) o sale negro,
 * prueba otros instantes del proyecto que sí tengan contenido, al azar.
 */
object ThumbComposer {
    private const val MAX_SIDE = 360

    private fun visibleClips(p: Project): List<Clip> = p.tracks.filter { !it.hidden }.flatMap { it.clips }

    private fun isContent(c: Clip) = c.kind == ClipKind.TEXT || c.kind == ClipKind.STICKER || c.kind == ClipKind.VIDEO || c.kind == ClipKind.IMAGE

    /** ¿Hay algo que enseñar en [t]? (se decide por el modelo, no por los píxeles: un degradado de fondo solo NO cuenta). */
    fun hasContent(p: Project, t: Long): Boolean = visibleClips(p).any { c -> isContent(c) && t >= c.startUs && t < c.endUs }

    private fun contentTimes(p: Project): List<Long> = visibleClips(p).filter { isContent(it) && it.durationUs > 0L }
        .flatMap { listOf(it.startUs + it.durationUs / 2, it.startUs + it.durationUs / 4) }.distinct()

    /** Elige el mejor fotograma: la captura real [live] si vale; si no, el compuesto en [posUs]; si no, otros al azar. Null si no hay nada. */
    fun best(p: Project, posUs: Long, live: Bitmap?): Bitmap? {
        val dur = p.durationUs
        // Justo al final no queda ningún clip activo: se retrocede un fotograma
        val last = if (dur > 0L) (dur - p.frameUs.coerceAtLeast(1L)).coerceAtLeast(0L) else 0L
        val t0 = posUs.coerceIn(0L, last)
        if (hasContent(p, t0)) {
            if (live != null && !live.isRecycled && !isBlank(live)) return live
            compose(p, t0)?.takeIf { !isBlank(it) }?.let { return it }
        }
        for (t in contentTimes(p).shuffled().take(6)) {
            val tt = t.coerceIn(0L, last)
            if (!hasContent(p, tt)) continue
            compose(p, tt)?.takeIf { !isBlank(it) }?.let { return it }
        }
        return null
    }

    /** Solo un color (o negro): poca diferencia entre el píxel más claro y el más oscuro de una muestra. */
    fun isBlank(b: Bitmap): Boolean {
        var lo = 255
        var hi = 0
        val n = 16
        for (i in 0 until n) for (j in 0 until n) {
            val c = b.getPixel(i * (b.width - 1) / (n - 1), j * (b.height - 1) / (n - 1))
            val l = (((c shr 16) and 255) * 3 + ((c shr 8) and 255) * 6 + (c and 255)) / 10
            lo = min(lo, l); hi = max(hi, l)
        }
        return hi - lo < 14
    }

    fun compose(p: Project, t: Long): Bitmap? = try {
        val ar = if (p.height > 0) p.width.toFloat() / p.height else 16f / 9f
        val w = if (ar >= 1f) MAX_SIDE else (MAX_SIDE * ar).toInt().coerceAtLeast(120)
        val h = if (ar >= 1f) (MAX_SIDE / ar).toInt().coerceAtLeast(120) else MAX_SIDE
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(Color.BLACK)
        // Pistas de vídeo/color: las de más abajo en la lista se dibujan primero
        for (i in p.tracks.indices.reversed()) {
            val tr = p.tracks[i]
            if (tr.hidden) continue
            for (c in tr.clips) if ((c.kind == ClipKind.VIDEO || c.kind == ClipKind.IMAGE || c.kind == ClipKind.COLOR) && !c.gap && t >= c.startUs && t < c.endUs) drawVisual(cv, c, t, w, h)
        }
        // Textos y pegatinas (instancia nueva: el motor guarda estado interno y no se comparte con la vista previa)
        OverlayRender().drawAll(cv, p, t, w, h)
        bmp
    } catch (e: Throwable) {
        null
    }

    private fun drawVisual(cv: Canvas, c: Clip, t: Long, w: Int, h: Int) {
        val local = t - c.startUs
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.alpha = (c.prop("opacity", local).coerceIn(0f, 1f) * 255).toInt()
        if (c.kind == ClipKind.COLOR) {
            val fill = c.fill ?: return
            val cols = fill.colors.takeIf { it.isNotEmpty() } ?: return
            if (cols.size == 1) { paint.color = cols[0]; cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint); return }
            paint.shader = if (fill.radial) RadialGradient(w / 2f, h / 2f, max(w, h) * 0.7f, cols.toIntArray(), null, Shader.TileMode.CLAMP) else {
                val rad = fill.angle * PI.toFloat() / 180f
                val dx = cos(rad) * max(w, h) * 0.5f
                val dy = sin(rad) * max(w, h) * 0.5f
                LinearGradient(w / 2f - dx, h / 2f - dy, w / 2f + dx, h / 2f + dy, cols.toIntArray(), null, Shader.TileMode.CLAMP)
            }
            cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            return
        }
        val bmp = frameOf(c, local) ?: return
        val cx = (0.5f + c.prop("x", local)) * w
        val cy = (0.5f + c.prop("y", local)) * h
        val sc = c.prop("scale", local).coerceAtLeast(0.01f)
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val dst = when (c.transform.fit) {
            FitMode.STRETCH -> RectF(-w / 2f, -h / 2f, w / 2f, h / 2f)
            FitMode.FILL -> { val k = max(w / bw, h / bh); RectF(-bw * k / 2f, -bh * k / 2f, bw * k / 2f, bh * k / 2f) }
            FitMode.FIT -> { val k = min(w / bw, h / bh); RectF(-bw * k / 2f, -bh * k / 2f, bw * k / 2f, bh * k / 2f) }
        }
        val save = cv.save()
        cv.translate(cx, cy)
        cv.rotate(c.prop("rotation", local))
        cv.scale(sc * (if (c.transform.flipH) -1f else 1f), sc * (if (c.transform.flipV) -1f else 1f))
        cv.drawBitmap(bmp, null, dst, paint)
        cv.restoreToCount(save)
    }

    private fun frameOf(c: Clip, local: Long): Bitmap? {
        val m = c.media ?: return null
        return try {
            if (m.isImage) decodeImage(m.path) else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(m.path)
                    r.getFrameAtTime(c.srcInUs + (local * c.speed).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally { r.release() }
            }
        } catch (e: Throwable) { null }
    }

    private fun decodeImage(path: String): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / s > MAX_SIDE * 2 && o.outHeight / s > MAX_SIDE * 2) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }
}
