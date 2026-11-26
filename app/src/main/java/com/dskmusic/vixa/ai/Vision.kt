package com.dskmusic.vixa.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dskmusic.vixa.engine.FrameStrip
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.interactivesegmenterlegacy.InteractiveSegmenterLegacy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Visión con MediaPipe Tasks (modelos descargados a archivo, nunca desde assets). Todo bloqueante (Dispatchers.Default). */
object Vision {

    /** Muestra de la pista de cara: posiciones normalizadas (0..1 del fotograma) y anchos en fracción del ancho. */
    class FaceSample(
        val srcUs: Long, val found: Boolean, val cx: Float, val cy: Float, val w: Float, val roll: Float,
        val headX: Float, val headY: Float, val eyeX: Float, val eyeY: Float, val eyeD: Float, val noseX: Float, val noseY: Float,
        val mouthX: Float = 0f, val mouthY: Float = 0f,
    ) {
        fun toLine(): String = listOf(srcUs, if (found) 1 else 0, cx, cy, w, roll, headX, headY, eyeX, eyeY, eyeD, noseX, noseY, mouthX, mouthY).joinToString("\t")

        companion object {
            fun fromLine(s: String): FaceSample? {
                val p = s.split('\t')
                if (p.size < 15) return null
                return try {
                    FaceSample(p[0].toLong(), p[1] == "1", p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[6].toFloat(), p[7].toFloat(),
                        p[8].toFloat(), p[9].toFloat(), p[10].toFloat(), p[11].toFloat(), p[12].toFloat(), p[13].toFloat(), p[14].toFloat())
                } catch (e: NumberFormatException) {
                    null
                }
            }
        }
    }

    private fun base(store: ModelStore, id: String): BaseOptions {
        val f = store.find(id, "tflite") ?: store.find(id, "task") ?: throw IOException("model missing: $id")
        // Se mapea el archivo a memoria (el buffer debe ser directo); sigue siendo válido al cerrar el canal.
        val buf: ByteBuffer = FileInputStream(f).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        return BaseOptions.builder().setModelAssetBuffer(buf).build()
    }

    private fun floats(img: MPImage): FloatArray {
        val fb = ByteBufferExtractor.extract(img).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val n = img.width * img.height
        return FloatArray(n).also { fb.get(it, 0, minOf(n, fb.remaining())) }
    }

    /** Probabilidad de "persona" por píxel a partir de las máscaras de confianza (con o sin canal de fondo). */
    private fun personProb(masks: List<MPImage>, labels: List<String>): Pair<FloatArray, Int>? {
        if (masks.isEmpty()) return null
        val bg = labels.indexOfFirst { it.equals("background", ignoreCase = true) }
        val m: FloatArray
        val img: MPImage
        if (masks.size == 1) {
            img = masks[0]
            m = floats(img)
            // Un único canal etiquetado como fondo es la probabilidad de fondo: se invierte.
            if (bg == 0 && labels.size <= 1) for (i in m.indices) m[i] = 1f - m[i]
        } else {
            img = masks[if (bg >= 0 && bg < masks.size) bg else 0]
            m = floats(img)
            for (i in m.indices) m[i] = 1f - m[i]
        }
        return m to img.width
    }

    private fun resize(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        if (sw == dw && sh == dh) return src
        val out = FloatArray(dw * dh)
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * sh / dh - 0.5f).coerceIn(0f, sh - 1f)
            val y0 = fy.toInt(); val y1 = minOf(y0 + 1, sh - 1); val ty = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * sw / dw - 0.5f).coerceIn(0f, sw - 1f)
                val x0 = fx.toInt(); val x1 = minOf(x0 + 1, sw - 1); val tx = fx - x0
                val a = src[y0 * sw + x0] * (1 - tx) + src[y0 * sw + x1] * tx
                val b = src[y1 * sw + x0] * (1 - tx) + src[y1 * sw + x1] * tx
                out[y * dw + x] = a * (1 - ty) + b * ty
            }
        }
        return out
    }

    /** Decodifica una imagen reduciéndola (potencias de 2) hasta que su lado mayor no pase de [maxSide]. */
    fun decode(path: String, maxSide: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / s > maxSide) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s }) ?: throw IOException("cannot decode image")
    }

    /**
     * Máscara de personas del medio entre [fromUs] y [toUs] (vídeo: 10 muestras por segundo; imagen: una). Se guarda
     * en [out] con el formato de [com.dskmusic.vixa.engine.MaskReader]: largo máximo 192 px, 8 bits, suavizada en el tiempo.
     */
    suspend fun personMask(store: ModelStore, modelId: String, media: String, image: Boolean, fromUs: Long, toUs: Long, out: File, progress: (Float) -> Unit) {
        val opts = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(base(store, modelId))
            .setRunningMode(if (image) RunningMode.IMAGE else RunningMode.VIDEO)
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build()
        val seg = ImageSegmenter.createFromOptions(store.context, opts)
        val labels = seg.labels
        val tmp = File(out.parentFile, out.name + ".part")
        val raf = RandomAccessFile(tmp, "rw")
        var mw = 0
        var mh = 0
        var count = 0
        var prev: FloatArray? = null
        val fps = if (image) 1 else 10
        val stepUs = 1_000_000L / fps
        val span = (toUs - fromUs).coerceAtLeast(1)
        fun handle(bmp: Bitmap) {
            val mp = BitmapImageBuilder(bmp).build()
            try {
                val res = if (image) seg.segment(mp) else seg.segmentForVideo(mp, count * (1000L / fps))
                val masks = res.confidenceMasks().orElse(emptyList())
                val (p, pw) = personProb(masks, labels) ?: return
                val ph = p.size / pw
                if (mw == 0) {
                    val k = 192f / max(pw, ph)
                    mw = max(2, (pw * k).toInt()); mh = max(2, (ph * k).toInt())
                    raf.setLength(0)
                    raf.writeBytes("VXMK"); raf.writeInt(mw); raf.writeInt(mh); raf.writeInt(fps); raf.writeLong(fromUs); raf.writeInt(0)
                }
                var cur = resize(p, pw, ph, mw, mh)
                val pr = prev
                // Suavizado temporal: reduce el parpadeo de los bordes entre fotogramas.
                if (pr != null && !image) for (i in cur.indices) cur[i] = pr[i] * 0.35f + cur[i] * 0.65f
                prev = cur
                val bytes = ByteArray(cur.size) { (cur[it] * 255f + 0.5f).toInt().coerceIn(0, 255).toByte() }
                raf.seek(HEADER + count.toLong() * mw * mh)
                raf.write(bytes)
                count++
                progress(if (image) 1f else (count * stepUs.toFloat() / span).coerceIn(0f, 1f))
            } finally {
                mp.close()
            }
        }
        try {
            if (image) handle(decode(media, 1024))
            else if (!FrameStrip.extract(media, fromUs, toUs, stepUs, 100_000, 256) { handle(it) }) throw IOException("unsupported video format")
            if (count == 0) throw IOException("no frames")
            raf.seek(24)
            raf.writeInt(count)
            raf.close()
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw IOException("rename")
        } finally {
            try { raf.close() } catch (e: Throwable) { }
            try { seg.close() } catch (e: Throwable) { }
            tmp.delete()
        }
    }

    private const val HEADER = 28L

    /** Los 468 puntos (x,y en 0..1 de la foto) de la cara mas grande de una imagen, o null si no hay ninguna. */
    fun faceLandmarks(store: ModelStore, path: String): FloatArray? {
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base(store, Models.FACE_MESH))
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(4)
            .setMinFaceDetectionConfidence(0.4f)
            .build()
        val fl = FaceLandmarker.createFromOptions(store.context, opts)
        try {
            val res = fl.detect(BitmapImageBuilder(decode(path, 1600)).build())
            var best: FloatArray? = null
            var bestArea = 0f
            for (lm in res.faceLandmarks()) {
                if (lm.size < 468) continue
                var x0 = 1f; var x1 = 0f; var y0 = 1f; var y1 = 0f
                for (i in 0 until 468) { val p = lm[i]; if (p.x() < x0) x0 = p.x(); if (p.x() > x1) x1 = p.x(); if (p.y() < y0) y0 = p.y(); if (p.y() > y1) y1 = p.y() }
                val area = (x1 - x0) * (y1 - y0)
                if (area > bestArea) { bestArea = area; best = FloatArray(468 * 2) { if (it % 2 == 0) lm[it / 2].x() else lm[it / 2].y() } }
            }
            return best
        } finally {
            try { fl.close() } catch (e: Throwable) { }
        }
    }

    /** Pista de la primera cara (10 muestras por segundo) con los puntos que usan los stickers que la siguen. */
    suspend fun faceTrack(store: ModelStore, media: String, fromUs: Long, toUs: Long, progress: (Float) -> Unit): List<FaceSample> {
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base(store, Models.FACE_MESH))
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.4f)
            .setMinTrackingConfidence(0.4f)
            .build()
        val fl = FaceLandmarker.createFromOptions(store.context, opts)
        val out = ArrayList<FaceSample>()
        val stepUs = 100_000L
        val span = (toUs - fromUs).coerceAtLeast(1)
        try {
            val ok = FrameStrip.extract(media, fromUs, toUs, stepUs, 100_000, 384) { bmp ->
                val srcUs = fromUs + out.size * stepUs
                val ar = bmp.width.toFloat() / bmp.height
                val mp = BitmapImageBuilder(bmp).build()
                try {
                    val res = fl.detectForVideo(mp, out.size * (stepUs / 1000L))
                    val lm = res.faceLandmarks().firstOrNull()
                    if (lm == null || lm.size < 468) {
                        out.add(FaceSample(srcUs, false, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
                    } else {
                        var x0 = 1f; var x1 = 0f; var y0 = 1f; var y1 = 0f
                        for (i in 0 until 468) { val p = lm[i]; if (p.x() < x0) x0 = p.x(); if (p.x() > x1) x1 = p.x(); if (p.y() < y0) y0 = p.y(); if (p.y() > y1) y1 = p.y() }
                        val w = x1 - x0
                        val hw = (y1 - y0) / ar // alto de la cara en unidades de ancho
                        // Los ojos (puntos 33 y 263) dan la inclinación; "arriba" es perpendicular a esa línea.
                        val a = lm[33]; val b = lm[263]
                        val dx = b.x() - a.x(); val dy = (b.y() - a.y()) / ar
                        val roll = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                        val r = Math.toRadians(roll.toDouble())
                        val ux = sin(r).toFloat(); val uy = -cos(r).toFloat()
                        val fh = lm[10]
                        val shift = hw * 0.35f
                        out.add(
                            FaceSample(
                                srcUs, true, (x0 + x1) / 2f, (y0 + y1) / 2f, w, roll,
                                fh.x() + ux * shift, fh.y() + uy * shift * ar,
                                (a.x() + b.x()) / 2f, (a.y() + b.y()) / 2f, kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat(),
                                lm[1].x(), lm[1].y(),
                                (lm[13].x() + lm[14].x()) / 2f, (lm[13].y() + lm[14].y()) / 2f,
                            ),
                        )
                    }
                } finally {
                    mp.close()
                }
                progress((out.size * stepUs.toFloat() / span).coerceIn(0f, 1f))
            }
            if (!ok) throw IOException("unsupported video format")
        } finally {
            try { fl.close() } catch (e: Throwable) { }
        }
        return out
    }

    /**
     * Máscara del sujeto tocado en [bmp]. Primero usa la segmentación de sujetos de Google (ML Kit): separa cada sujeto de la
     * foto y se elige el que está bajo el dedo. Si no está disponible (sin Google Play services, modelo sin descargar) o no
     * encuentra sujetos, recurre al segmentador interactivo de MediaPipe.
     */
    suspend fun subjectMask(store: ModelStore, bmp: Bitmap, nx: Float, ny: Float): Triple<FloatArray, Int, Int> {
        try {
            val r = mlkitMask(bmp, nx, ny)
            if (r != null) return r
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            com.dskmusic.vixa.data.ErrorLog.log("Cutout/mlkit", e)
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { interactiveMask(store, bmp, nx, ny) }
    }

    private suspend fun mlkitMask(bmp: Bitmap, nx: Float, ny: Float): Triple<FloatArray, Int, Int>? {
        val w = bmp.width
        val h = bmp.height
        val opts = SubjectSegmenterOptions.Builder()
            .enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build())
            .build()
        val seg = SubjectSegmentation.getClient(opts)
        try {
            val subjects = suspendCancellableCoroutine { c ->
                seg.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { c.resume(it.subjects) }
                    .addOnFailureListener { c.resumeWithException(it) }
            }
            if (subjects.isEmpty()) return null
            val tx = (nx * (w - 1)).toInt().coerceIn(0, w - 1)
            val ty = (ny * (h - 1)).toInt().coerceIn(0, h - 1)
            // Valor de cada sujeto bajo el dedo (ventana de 9×9 para tolerar bordes); gana el mayor.
            fun at(s: com.google.mlkit.vision.segmentation.subject.Subject): Float {
                val m = s.confidenceMask ?: return 0f
                var best = 0f
                for (dy in -4..4) for (dx in -4..4) {
                    val x = tx + dx - s.startX
                    val y = ty + dy - s.startY
                    if (x in 0 until s.width && y in 0 until s.height) best = max(best, m[y * s.width + x])
                }
                return best
            }
            var pick = subjects.maxByOrNull { at(it) }!!
            if (at(pick) < 0.5f) {
                // Se tocó el fondo: se elige el sujeto cuyo centro está más cerca del toque.
                pick = subjects.minByOrNull { s -> val dx = s.startX + s.width / 2f - tx; val dy = s.startY + s.height / 2f - ty; dx * dx + dy * dy }!!
            }
            val m = pick.confidenceMask ?: return null
            val out = FloatArray(w * h)
            for (y in 0 until pick.height) {
                val yy = pick.startY + y
                if (yy !in 0 until h) continue
                for (x in 0 until pick.width) {
                    val xx = pick.startX + x
                    if (xx in 0 until w) out[yy * w + xx] = m[y * pick.width + x]
                }
            }
            return Triple(out, w, h)
        } finally {
            seg.close()
        }
    }

    /**
     * Máscara del objeto tocado en [bmp] con el segmentador interactivo: probabilidad por píxel y sus dimensiones.
     * El resultado del modelo se normaliza y se comprueba (valores fuera de 0..1, máscara invertida, varias máscaras) y
     * se queda solo con la región que contiene el punto tocado, para no arrastrar trozos sueltos del fondo.
     */
    fun interactiveMask(store: ModelStore, bmp: Bitmap, nx: Float, ny: Float): Triple<FloatArray, Int, Int> {
        val opts = InteractiveSegmenterLegacy.InteractiveSegmenterLegacyOptions.builder()
            .setBaseOptions(base(store, Models.MAGIC_TOUCH))
            .setOutputConfidenceMasks(true)
            .setOutputCategoryMask(false)
            .build()
        val seg = InteractiveSegmenterLegacy.createFromOptions(store.context, opts)
        try {
            // MPImage.close() RECICLA el Bitmap con el que se creó: se le pasa una copia para no dejar inservible la foto
            // que la interfaz sigue dibujando (eso cerraba la app al tocar el sujeto).
            val mp = BitmapImageBuilder(bmp.copy(Bitmap.Config.ARGB_8888, false)).build()
            try {
                val roi = InteractiveSegmenterLegacy.RegionOfInterest.create(NormalizedKeypoint.create(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f)))
                val res = seg.segment(mp, roi)
                val masks = res.confidenceMasks().orElse(emptyList())
                if (masks.isEmpty()) throw IOException("no mask")
                val mw = masks[0].width
                val mh = masks[0].height
                val tx = (nx * (mw - 1)).toInt().coerceIn(0, mw - 1)
                val ty = (ny * (mh - 1)).toInt().coerceIn(0, mh - 1)
                // Valor medio alrededor del punto tocado (ventana de 5×5): es el criterio para elegir y orientar la máscara.
                fun atTap(f: FloatArray): Float {
                    var sum = 0f
                    var n = 0
                    for (dy in -2..2) for (dx in -2..2) { sum += f[(ty + dy).coerceIn(0, mh - 1) * mw + (tx + dx).coerceIn(0, mw - 1)]; n++ }
                    return sum / n
                }
                val all = masks.map { normalize(floats(it)) }
                var best = all.indices.maxByOrNull { atTap(all[it]) } ?: 0
                var m = all[best]
                // Si el punto tocado queda "fuera" en todas, el modelo devuelve el fondo: se invierte.
                if (atTap(m) < 0.5f) m = FloatArray(m.size) { 1f - all[best][it] }
                var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
                for (v in m) { if (v < lo) lo = v; if (v > hi) hi = v }
                com.dskmusic.vixa.data.ErrorLog.log("Cutout", "mascaras=" + masks.size + " " + mw + "x" + mh + " elegida=" + best + " rango=" + lo + ".." + hi + " enPunto=" + atTap(m))
                return Triple(keepTapped(m, mw, mh, tx, ty), mw, mh)
            } finally {
                mp.close()
            }
        } finally {
            try { seg.close() } catch (e: Throwable) { }
        }
    }

    /** Lleva la máscara a 0..1: valores de 0..255 se dividen y los que parecen logits pasan por una sigmoide. */
    private fun normalize(f: FloatArray): FloatArray {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in f) { if (v < lo) lo = v; if (v > hi) hi = v }
        return when {
            lo >= -0.001f && hi <= 1.001f -> f
            lo >= -0.001f && hi <= 255.5f && hi > 50f -> FloatArray(f.size) { f[it] / 255f }
            else -> FloatArray(f.size) { 1f / (1f + Math.exp(-f[it].toDouble()).toFloat()) }
        }
    }

    /** Deja solo la zona conectada (por encima de 0,5) que contiene el punto tocado, con un margen de 2 px para los bordes suaves. */
    private fun keepTapped(m: FloatArray, w: Int, h: Int, tx: Int, ty: Int): FloatArray {
        // Semilla: el punto tocado o, si cae justo en un borde, el píxel cercano más seguro.
        var sx = tx; var sy = ty; var bv = -1f
        for (dy in -3..3) for (dx in -3..3) {
            val x = (tx + dx).coerceIn(0, w - 1); val y = (ty + dy).coerceIn(0, h - 1)
            if (m[y * w + x] > bv) { bv = m[y * w + x]; sx = x; sy = y }
        }
        if (bv < 0.5f) return m
        val comp = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        stack[sp++] = sy * w + sx
        comp[sy * w + sx] = true
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w; val y = i / w
            if (x > 0 && !comp[i - 1] && m[i - 1] > 0.5f) { comp[i - 1] = true; stack[sp++] = i - 1 }
            if (x < w - 1 && !comp[i + 1] && m[i + 1] > 0.5f) { comp[i + 1] = true; stack[sp++] = i + 1 }
            if (y > 0 && !comp[i - w] && m[i - w] > 0.5f) { comp[i - w] = true; stack[sp++] = i - w }
            if (y < h - 1 && !comp[i + w] && m[i + w] > 0.5f) { comp[i + w] = true; stack[sp++] = i + w }
        }
        // Dilatación de 2 px de la región para conservar la transición suave del borde.
        val near = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (!comp[y * w + x]) continue
            for (dy in -2..2) for (dx in -2..2) { val xx = x + dx; val yy = y + dy; if (xx in 0 until w && yy in 0 until h) near[yy * w + xx] = true }
        }
        return FloatArray(m.size) { if (near[it]) m[it] else 0f }
    }
}
