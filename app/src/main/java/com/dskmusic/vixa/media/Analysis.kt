package com.dskmusic.vixa.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.engine.StabStore
import com.dskmusic.vixa.model.MediaRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Análisis automático de medios. Todo es bloqueante y se ejecuta en Dispatchers.IO / Default. */
object Analysis {

    /** Golpes de la música: instantes (µs del medio) con un aumento brusco de energía. */
    suspend fun beats(path: String): List<Long> = withContext(Dispatchers.Default) {
        val env = Waveforms.loadBlocking(path) ?: return@withContext emptyList()
        if (env.size < 20) return@withContext emptyList()
        val n = env.size
        val flux = FloatArray(n)
        for (i in 1 until n) flux[i] = max(0, (env[i].toInt() and 0xFF) - (env[i - 1].toInt() and 0xFF)).toFloat()
        val out = ArrayList<Long>()
        val win = Waveforms.RATE // 1 s de contexto a cada lado
        val minGap = Waveforms.RATE / 4 // 250 ms
        var last = -minGap
        for (i in 1 until n - 1) {
            var sum = 0f
            val a = max(0, i - win)
            val b = min(n - 1, i + win)
            for (k in a..b) sum += flux[k]
            val mean = sum / (b - a + 1)
            if (flux[i] > mean * 2.2f + 6f && flux[i] >= flux[i - 1] && flux[i] >= flux[i + 1] && i - last >= minGap) {
                out.add(i * 1_000_000L / Waveforms.RATE)
                last = i
            }
        }
        out
    }

    /** Tramos con sonido (inicio/fin en µs del medio) dentro de [fromUs, toUs]; lo demás es silencio. */
    suspend fun nonSilent(path: String, fromUs: Long, toUs: Long, sensitivity: Float = 0.5f): List<Pair<Long, Long>> = withContext(Dispatchers.Default) {
        val env = Waveforms.loadBlocking(path) ?: return@withContext emptyList()
        if (env.isEmpty()) return@withContext emptyList()
        val rate = Waveforms.RATE
        val a = (fromUs * rate / 1_000_000L).toInt().coerceIn(0, env.size - 1)
        val b = (toUs * rate / 1_000_000L).toInt().coerceIn(a, env.size)
        var peak = 1
        for (i in a until b) peak = max(peak, env[i].toInt() and 0xFF)
        val threshold = max(3f, peak * (0.03f + 0.12f * sensitivity))
        val minSilence = (rate * 0.45f).toInt()
        val pad = (rate * 0.1f).toInt()
        val ranges = ArrayList<Pair<Long, Long>>()
        var start = -1
        var quiet = 0
        for (i in a until b) {
            val loud = (env[i].toInt() and 0xFF) >= threshold
            if (loud) {
                if (start < 0) start = i
                quiet = 0
            } else if (start >= 0) {
                quiet++
                if (quiet >= minSilence) {
                    ranges.add(max(a, start - pad) * 1_000_000L / rate to min(b, i - quiet + 1 + pad) * 1_000_000L / rate)
                    start = -1
                    quiet = 0
                }
            }
        }
        if (start >= 0) ranges.add(max(a, start - pad) * 1_000_000L / rate to toUs)
        ranges.filter { it.second - it.first >= 150_000L }.map { max(fromUs, it.first) to min(toUs, it.second) }
    }

    /** Pico (0..1) del tramo: sirve para normalizar el volumen. */
    suspend fun peak(path: String, fromUs: Long, toUs: Long): Float = withContext(Dispatchers.Default) {
        val env = Waveforms.loadBlocking(path) ?: return@withContext 0f
        if (env.isEmpty()) return@withContext 0f
        val a = (fromUs * Waveforms.RATE / 1_000_000L).toInt().coerceIn(0, env.size - 1)
        val b = (toUs * Waveforms.RATE / 1_000_000L).toInt().coerceIn(a, env.size)
        var p = 0
        for (i in a until b) p = max(p, env[i].toInt() and 0xFF)
        p / 255f
    }

    private fun gray(r: MediaMetadataRetriever, tUs: Long, w: Int, h: Int, sync: Boolean, out: IntArray): Boolean {
        val opt = if (sync) MediaMetadataRetriever.OPTION_CLOSEST_SYNC else MediaMetadataRetriever.OPTION_CLOSEST
        val raw = (if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(tUs, opt, w, h) else r.getFrameAtTime(tUs, opt)) ?: return false
        val b = if (raw.width != w || raw.height != h) Bitmap.createScaledBitmap(raw, w, h, true) else raw
        b.getPixels(out, 0, w, 0, 0, w, h)
        if (b !== raw) b.recycle()
        raw.recycle()
        for (i in out.indices) {
            val p = out[i]
            out[i] = (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
        }
        return true
    }

    /** Cambios de escena (µs del medio) dentro de [fromUs, toUs]. */
    suspend fun scenes(path: String, fromUs: Long, toUs: Long, onProgress: (Float) -> Unit = {}): List<Long> = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        val out = ArrayList<Long>()
        try {
            r.setDataSource(path)
            val w = 32
            val h = 18
            var prev = IntArray(w * h)
            var cur = IntArray(w * h)
            var hasPrev = false
            val step = 400_000L
            var t = fromUs
            var lastCut = fromUs
            while (t < toUs) {
                coroutineContext.ensureActive()
                if (gray(r, t, w, h, false, cur)) {
                    if (hasPrev) {
                        var diff = 0L
                        for (i in cur.indices) diff += abs(cur[i] - prev[i])
                        val mean = diff.toFloat() / cur.size
                        if (mean > 34f && t - lastCut > 1_000_000L) { out.add(t); lastCut = t }
                    }
                    val tmp = prev; prev = cur; cur = tmp
                    hasPrev = true
                }
                t += step
                onProgress(((t - fromUs).toFloat() / (toUs - fromUs).coerceAtLeast(1)).coerceIn(0f, 1f))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ErrorLog.log("Scenes", e)
        } finally {
            r.release()
        }
        out
    }

    /** Nitidez (media del laplaciano) y movimiento respecto al muestreo anterior, de un fotograma en miniatura. */
    class FrameFeature(val tUs: Long, val sharp: Float, val motion: Float)

    /** Muestrea el vídeo cada [stepUs] y mide nitidez y movimiento de cada muestra: sirve para elegir el mejor tramo. */
    suspend fun frameFeatures(path: String, fromUs: Long, toUs: Long, stepUs: Long): List<FrameFeature> = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        val out = ArrayList<FrameFeature>()
        try {
            r.setDataSource(path)
            val w = 48
            val h = 27
            var prev = IntArray(w * h)
            var cur = IntArray(w * h)
            var hasPrev = false
            var t = fromUs
            while (t < toUs) {
                coroutineContext.ensureActive()
                if (gray(r, t, w, h, false, cur)) {
                    var lap = 0L
                    for (y in 1 until h - 1) for (x in 1 until w - 1) {
                        val i = y * w + x
                        lap += abs(4 * cur[i] - cur[i - 1] - cur[i + 1] - cur[i - w] - cur[i + w])
                    }
                    var diff = 0L
                    if (hasPrev) for (i in cur.indices) diff += abs(cur[i] - prev[i])
                    out.add(FrameFeature(t, lap.toFloat() / ((w - 2) * (h - 2)), if (hasPrev) diff.toFloat() / cur.size else 0f))
                    val tmp = prev; prev = cur; cur = tmp
                    hasPrev = true
                }
                t += stepUs
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ErrorLog.log("FrameFeatures", e)
        } finally {
            r.release()
        }
        out
    }

    /**
     * Estabilización básica: estima el desplazamiento global entre fotogramas y guarda en [StabStore]
     * la corrección (trayectoria suavizada − trayectoria real) a [StabStore.RATE] muestras por segundo.
     */
    suspend fun stabilize(context: Context, media: MediaRef, onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (StabStore.data.containsKey(media.path)) return@withContext true
        val cache = File(File(context.cacheDir, "stab").apply { mkdirs() }, cacheKey(media.path) + ".bin")
        if (cache.exists() && cache.length() >= 8) {
            val bb = ByteBuffer.wrap(cache.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            val arr = FloatArray(bb.remaining() / 4) { bb.getFloat() }
            StabStore.data[media.path] = arr
            return@withContext true
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(media.path)
            val w = 48
            val h = if (media.width >= media.height) 27 else 85
            val count = (media.durationUs * StabStore.RATE / 1_000_000L).toInt().coerceIn(2, 3000)
            val px = FloatArray(count)
            val py = FloatArray(count)
            var prev = IntArray(w * h)
            var cur = IntArray(w * h)
            var hasPrev = false
            val range = 4
            for (i in 0 until count) {
                coroutineContext.ensureActive()
                val ok = gray(r, i * 1_000_000L / StabStore.RATE, w, h, false, cur)
                var bx = 0
                var by = 0
                if (ok && hasPrev) {
                    var best = Long.MAX_VALUE
                    for (dy in -range..range) for (dx in -range..range) {
                        var sad = 0L
                        var y = range
                        while (y < h - range) {
                            var x = range
                            while (x < w - range) {
                                sad += abs(prev[y * w + x] - cur[(y + dy) * w + x + dx])
                                x += 2
                            }
                            y += 2
                        }
                        // Ligera preferencia por el desplazamiento nulo para no temblar con ruido.
                        sad += (abs(dx) + abs(dy)) * 6L
                        if (sad < best) { best = sad; bx = dx; by = dy }
                    }
                }
                px[i] = (if (i > 0) px[i - 1] else 0f) + bx.toFloat() / w
                py[i] = (if (i > 0) py[i - 1] else 0f) + by.toFloat() / h
                if (ok) { val tmp = prev; prev = cur; cur = tmp; hasPrev = true }
                onProgress(i.toFloat() / count)
            }
            val out = FloatArray(count * 2)
            val win = StabStore.RATE
            for (i in 0 until count) {
                var sx = 0f
                var sy = 0f
                val a = max(0, i - win)
                val b = min(count - 1, i + win)
                for (k in a..b) { sx += px[k]; sy += py[k] }
                val n = (b - a + 1).toFloat()
                out[i * 2] = (sx / n - px[i]).coerceIn(-0.055f, 0.055f)
                out[i * 2 + 1] = (sy / n - py[i]).coerceIn(-0.055f, 0.055f)
            }
            StabStore.data[media.path] = out
            val bb = ByteBuffer.allocate(out.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in out) bb.putFloat(v)
            cache.writeBytes(bb.array())
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ErrorLog.log("Stabilize", e)
            false
        } finally {
            r.release()
        }
    }

    /**
     * Prerender de reproducción inversa del tramo [fromUs, toUs]: lee los fotogramas hacia atrás y los
     * codifica (H.264, sin audio) en un archivo nuevo. Devuelve el MediaRef del resultado.
     */
    suspend fun reverse(context: Context, media: MediaRef, fromUs: Long, toUs: Long, onProgress: (Float) -> Unit = {}): MediaRef? = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "media/reversed").apply { mkdirs() }
        val out = File(dir, cacheKey(media.path) + "_" + fromUs / 1000 + "_" + toUs / 1000 + ".mp4")
        val fps = media.fps.coerceIn(10f, 30f)
        val frameUs = (1_000_000f / fps).toLong()
        val total = ((toUs - fromUs) / frameUs).toInt().coerceAtLeast(1)
        val longSide = 1280f
        val s = min(1f, longSide / max(media.width, media.height).coerceAtLeast(1))
        val w = ((media.width * s).toInt() / 2 * 2).coerceAtLeast(16)
        val h = ((media.height * s).toInt() / 2 * 2).coerceAtLeast(16)
        if (out.exists() && out.length() > 0) {
            return@withContext MediaRef(out.path, total * frameUs, w, h, hasVideo = true, hasAudio = false, fps = fps, origin = media.path, title = media.title)
        }
        val tmp = File(dir, out.name + ".tmp")
        val r = MediaMetadataRetriever()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            r.setDataSource(media.path)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (w * h * fps * 0.15f).toInt().coerceAtLeast(1_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps.toInt())
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = enc
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = enc.createInputSurface()
            enc.start()
            val mx = MediaMuxer(tmp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            val info = MediaCodec.BufferInfo()
            var track = -1
            var written = 0L
            val dst = Rect(0, 0, w, h)

            fun drain(end: Boolean) {
                var idle = 0
                while (true) {
                    val o = enc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mx.addTrack(enc.outputFormat)
                        mx.start()
                        muxerStarted = true
                    } else if (o >= 0) {
                        val buf = enc.getOutputBuffer(o)
                        if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && muxerStarted) {
                            // La superficie no permite fijar tiempos: se numeran los fotogramas en orden de salida.
                            info.presentationTimeUs = written * frameUs
                            written++
                            mx.writeSampleData(track, buf, info)
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    } else {
                        if (!end || ++idle > 300) return
                    }
                }
            }

            for (i in 0 until total) {
                coroutineContext.ensureActive()
                val t = (toUs - (i + 1) * frameUs).coerceAtLeast(fromUs)
                val bmp = (if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                else r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)) ?: continue
                val canvas = surface.lockHardwareCanvas()
                try {
                    canvas.drawBitmap(bmp, null, dst, null)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                bmp.recycle()
                drain(false)
                onProgress((i + 1).toFloat() / total)
            }
            enc.signalEndOfInputStream()
            drain(true)
            mx.stop()
            muxerStarted = false
            surface.release()
            if (written == 0L || !tmp.renameTo(out)) { tmp.delete(); return@withContext null }
            MediaRef(out.path, written * frameUs, w, h, hasVideo = true, hasAudio = false, fps = fps, origin = media.path, title = media.title)
        } catch (e: kotlinx.coroutines.CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Throwable) {
            ErrorLog.log("Reverse", e)
            tmp.delete()
            null
        } finally {
            try { codec?.stop() } catch (e: Throwable) { }
            try { codec?.release() } catch (e: Throwable) { }
            try { if (muxerStarted) muxer?.stop() } catch (e: Throwable) { }
            try { muxer?.release() } catch (e: Throwable) { }
            r.release()
        }
    }
}
