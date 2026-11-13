package com.dskmusic.vixa.engine

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.media.ExifInterface
import android.media.FaceDetector
import android.media.MediaMetadataRetriever
import com.dskmusic.vixa.ai.Vision
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.media.Importer
import com.dskmusic.vixa.media.MediaType
import com.dskmusic.vixa.model.Easing
import com.dskmusic.vixa.model.FitMode
import com.dskmusic.vixa.model.Keyframe
import com.dskmusic.vixa.model.Transform
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Encuadre inteligente de las fotos del asistente, sin modelos que descargar: busca caras con el detector del sistema
 * (android.media.FaceDetector) y, si no hay, un punto de interés por contraste. Con eso:
 *  - al cambiar de formato recorta hacia las caras en vez de dejar bandas, siempre que quepan en el recorte;
 *  - el zoom lento (Ken Burns) apunta a la cara o al punto de interés en vez de al centro.
 */
object SmartFrame {

    /** Punto de interés (u, v: 0..1 en la foto) y, si hay caras, el rectángulo que las contiene (0..1). */
    class Focus(val u: Float, val v: Float, val faces: RectF?)

    class Plan(val transform: Transform, val keyframes: Map<String, List<Keyframe>>)

    /** Analiza una foto (blocking). Devuelve null si no se puede leer o no hay nada que destacar. */
    fun analyze(path: String): Focus? {
        var bmp: Bitmap? = null
        try {
            bmp = Vision.decode(path, 480)
            val w = bmp.width and 1.inv()
            val h = bmp.height
            if (w < 16 || h < 16) return null
            val rgb = Bitmap.createBitmap(bmp, 0, 0, w, h).copy(Bitmap.Config.RGB_565, false)
            val faces = arrayOfNulls<FaceDetector.Face>(6)
            val n = FaceDetector(w, h, faces.size).findFaces(rgb, faces)
            rgb.recycle()
            var box: RectF? = null
            for (i in 0 until n) {
                val f = faces[i] ?: continue
                if (f.confidence() < 0.4f) continue
                val mid = PointF()
                f.getMidPoint(mid)
                val e = f.eyesDistance()
                // Cara aproximada a partir de la distancia entre los ojos.
                val r = RectF((mid.x - e * 1.3f) / w, (mid.y - e * 1.2f) / h, (mid.x + e * 1.3f) / w, (mid.y + e * 1.9f) / h)
                r.intersect(0f, 0f, 1f, 1f)
                if (box == null) box = r else box.union(r)
            }
            if (box != null) return Focus(box.centerX(), box.centerY(), box)
            return saliency(bmp)
        } catch (e: Throwable) {
            ErrorLog.log("SmartFrame", e)
            return null
        } finally {
            try { bmp?.recycle() } catch (e: Throwable) { }
        }
    }

    /** Centro de masa del contraste local (bordes), mezclado con el centro para no irse a un rincón. */
    private fun saliency(bmp: Bitmap): Focus? {
        val w = 48
        val h = (48f * bmp.height / bmp.width).toInt().coerceIn(16, 96)
        val s = Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        s.recycle()
        val g = FloatArray(w * h) { val p = px[it]; (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10f }
        var sx = 0f; var sy = 0f; var sw = 0f
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val dx = g[y * w + x + 1] - g[y * w + x - 1]
            val dy = g[(y + 1) * w + x] - g[(y - 1) * w + x]
            val m = sqrt(dx * dx + dy * dy)
            // Preferencia suave por el centro.
            val cx = (x - w / 2f) / (w / 2f); val cy = (y - h / 2f) / (h / 2f)
            val wt = m * (1.2f - 0.5f * (cx * cx + cy * cy))
            sx += wt * x; sy += wt * y; sw += wt
        }
        if (sw < 1f) return null
        val u = 0.5f + (sx / sw / w - 0.5f) * 0.6f
        val v = 0.5f + (sy / sw / h - 0.5f) * 0.6f
        return Focus(u, v, null)
    }

    /**
     * Encuadre y movimiento de una foto en un lienzo de proporción [canvasAspect]. [zoomIn] alterna entre acercarse y
     * alejarse del punto de interés. Devuelve null si no hay punto de interés (se usa el movimiento normal del estilo).
     */
    fun plan(f: Focus?, mediaAspect: Float, canvasAspect: Float, durUs: Long, zoomIn: Boolean): Plan? {
        if (f == null) return null
        val wide = mediaAspect > canvasAspect
        // Tamaño de la foto rellenando el lienzo, en fracción del lienzo, y parte de la foto que queda visible.
        val qw = if (wide) mediaAspect / canvasAspect else 1f
        val qh = if (wide) 1f else canvasAspect / mediaAspect
        val visW = if (wide) canvasAspect / mediaAspect else 1f
        val visH = if (wide) 1f else mediaAspect / canvasAspect
        val mismatch = abs(ln(mediaAspect / canvasAspect)) >= 0.3f
        val face = f.faces
        var fit = FitMode.FILL
        if (mismatch) {
            // Solo se recorta si las caras caben con margen en la parte visible; si no, entra entera sobre el fondo desenfocado.
            val ok = face != null && face.width() <= 0.85f * visW && face.height() <= 0.85f * visH
            if (!ok) return null
        }
        fun clampOff(v: Float, size: Float, scale: Float): Float {
            val lim = (size * scale / 2f - 0.5f).coerceAtLeast(0f)
            return v.coerceIn(-lim, lim)
        }
        // Desplazamiento base: centra el punto de interés en el recorte (solo hay margen si la foto sobresale del lienzo).
        val fx = (f.u - 0.5f) * qw
        val fy = (f.v - 0.5f) * qh
        val bx = clampOff(-fx, qw, 1f)
        val by = clampOff(-fy, qh, 1f)
        // Zoom lento hacia el punto de interés: en su punto final lo deja casi centrado.
        val s1 = 1.18f
        val ex = clampOff(-fx * s1 * 0.7f, qw, s1)
        val ey = clampOff(-fy * s1 * 0.7f, qh, s1)
        val a = if (zoomIn) 0 else 1
        val b = 1 - a
        val sc = listOf(1f, s1)
        val xs = listOf(bx, ex)
        val ys = listOf(by, ey)
        val kf = mapOf(
            "scale" to listOf(Keyframe(0L, sc[a], Easing.EASE_IN_OUT), Keyframe(durUs, sc[b], Easing.EASE_IN_OUT)),
            "x" to listOf(Keyframe(0L, xs[a], Easing.EASE_IN_OUT), Keyframe(durUs, xs[b], Easing.EASE_IN_OUT)),
            "y" to listOf(Keyframe(0L, ys[a], Easing.EASE_IN_OUT), Keyframe(durUs, ys[b], Easing.EASE_IN_OUT)),
        )
        return Plan(Transform(x = xs[a], y = ys[a], scale = sc[a], fit = fit), kf)
    }

    /** Fecha de un medio: EXIF o metadatos del vídeo si existen y, si no, la de modificación del archivo. */
    fun dateOf(path: String): Long {
        val file = File(path)
        try {
            when (Importer.typeOf(path)) {
                MediaType.IMAGE -> {
                    val ex = ExifInterface(path)
                    val s = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: ex.getAttribute(ExifInterface.TAG_DATETIME)
                    if (s != null) SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(s)?.time?.let { if (it > 631152000000L) return it }
                }
                MediaType.VIDEO -> {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(path)
                        val s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                        if (s != null && s.length >= 15) {
                            val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                            fmt.parse(s.substring(0, 15))?.time?.let { if (it > 631152000000L) return it }
                        }
                    } finally {
                        r.release()
                    }
                }
                else -> {}
            }
        } catch (e: Throwable) {
            // Sin metadatos legibles: vale la fecha del archivo.
        }
        return file.lastModified()
    }
}
