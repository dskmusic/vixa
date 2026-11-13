package com.dskmusic.vixa.engine

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Extrae de un vídeo una tira de fotogramas pequeños en UNA sola pasada de decodificación (sin salto por fotograma,
 * mucho más rápido que MediaMetadataRetriever). Sirve para las vistas previas en bucle y de ida y vuelta.
 */
object FrameStrip {

    /**
     * Entrega por [onFrame] un fotograma cada [stepUs] entre [startUs] y [endUs] (tiempo del medio), con
     * [displayW] píxeles de ancho una vez girado. Devuelve false si el formato no es compatible (p. ej. HDR de 10 bits).
     */
    suspend fun extract(path: String, startUs: Long, endUs: Long, stepUs: Long, maxFrames: Int, displayW: Int, onFrame: (Bitmap) -> Unit): Boolean {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(path)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) { track = i; fmt = f; break }
            }
            val format = fmt ?: return false
            ex.selectTrack(track)
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var next = startUs
            var count = 0
            while (!outDone && count < maxFrames) {
                coroutineContext.ensureActive()
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(5_000)
                    if (ii >= 0) {
                        val n = ex.readSampleData(c.getInputBuffer(ii)!!, 0)
                        if (n < 0 || ex.sampleTime > endUs + 200_000L) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 5_000)
                if (oi < 0) continue
                val pts = info.presentationTimeUs
                if (info.size > 0 && pts >= next && pts <= endUs) {
                    val img = c.getOutputImage(oi)
                    if (img == null || img.format != ImageFormat.YUV_420_888) { img?.close(); c.releaseOutputBuffer(oi, false); return false }
                    try { onFrame(toBitmap(img, displayW, rotation)) } finally { img.close() }
                    count++
                    while (next <= pts) next += stepUs
                }
                c.releaseOutputBuffer(oi, false)
                if (pts > endUs || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
            return count > 0
        } finally {
            try { codec?.stop() } catch (e: Throwable) { }
            try { codec?.release() } catch (e: Throwable) { }
            ex.release()
        }
    }

    /** YUV 4:2:0 → bitmap reducido por muestreo directo (BT.601) y girado según los metadatos del vídeo. */
    private fun toBitmap(img: Image, displayW: Int, rotation: Int): Bitmap {
        val crop = img.cropRect
        val iw = crop.width()
        val ih = crop.height()
        val turned = rotation == 90 || rotation == 270
        val dispAspect = if (turned) ih.toFloat() / iw else iw.toFloat() / ih
        val dw = displayW.coerceAtLeast(2)
        val dh = (dw / dispAspect).toInt().coerceAtLeast(2)
        val ow = if (turned) dh else dw
        val oh = if (turned) dw else dh
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val px = IntArray(ow * oh)
        for (ty in 0 until oh) {
            val sy = crop.top + ty * ih / oh
            for (tx in 0 until ow) {
                val sx = crop.left + tx * iw / ow
                val y = (yb.get(sy * yP.rowStride + sx * yP.pixelStride).toInt() and 0xFF) - 16
                val u = (ub.get((sy shr 1) * uP.rowStride + (sx shr 1) * uP.pixelStride).toInt() and 0xFF) - 128
                val v = (vb.get((sy shr 1) * vP.rowStride + (sx shr 1) * vP.pixelStride).toInt() and 0xFF) - 128
                val c = 298 * y
                val r = ((c + 409 * v + 128) shr 8).coerceIn(0, 255)
                val g = ((c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                val b = ((c + 516 * u + 128) shr 8).coerceIn(0, 255)
                px[ty * ow + tx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bmp = Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, ow, oh, m, true).also { if (it !== bmp) bmp.recycle() }
    }
}
