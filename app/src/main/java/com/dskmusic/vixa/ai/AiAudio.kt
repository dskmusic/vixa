package com.dskmusic.vixa.ai

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Caché de resultados de IA en files/ai/cache. La clave reúne medio (ruta, tamaño, fecha), parámetros y modelo. */
object AiCache {
    fun dir(context: Context): File = File(context.filesDir, "ai/cache").apply { mkdirs() }

    fun key(mediaPath: String, vararg parts: Any): String {
        val f = File(mediaPath)
        val raw = mediaPath + "|" + f.length() + "|" + f.lastModified() + "|" + parts.joinToString("|")
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }

    fun file(context: Context, key: String, ext: String): File = File(dir(context), "$key.$ext")
    fun size(context: Context): Long = dir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun clear(context: Context) { dir(context).listFiles()?.forEach { it.deleteRecursively() } }
}

/** Escritor WAV PCM de 16 bits por bloques; la cabecera se completa al cerrar. */
class WavWriter(private val file: File, private val sampleRate: Int, private val channels: Int) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var buf = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)

    init {
        raf.setLength(0)
        raf.write(ByteArray(44))
    }

    /** [samples] intercalado si hay más de un canal; valores en -1..1. */
    fun write(samples: FloatArray, offset: Int = 0, count: Int = samples.size - offset) {
        var i = offset
        val end = offset + count
        while (i < end) {
            buf.clear()
            val n = minOf(end - i, buf.capacity() / 2)
            for (k in 0 until n) buf.putShort((samples[i + k].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            raf.write(buf.array(), 0, n * 2)
            dataBytes += n * 2L
            i += n
        }
    }

    override fun close() {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(sampleRate)
        h.putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        h.put("data".toByteArray()).putInt(dataBytes.toInt())
        raf.seek(0)
        raf.write(h.array())
        raf.close()
    }
}

object AiAudio {

    /** Duración de la pista de audio de [path] en µs (0 si no tiene). */
    fun durationUs(path: String): Long {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) return if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            }
        } catch (e: Throwable) {
            // medio ilegible: lo tratará quien decodifique
        } finally {
            ex.release()
        }
        return 0L
    }

    /**
     * Decodifica el audio de [path] entre [fromUs] y [toUs] (toUs <= 0 = hasta el final) y lo entrega por
     * bloques a [onBlock] ya remuestreado a [rate]. Con [stereo] = false el bloque es mono; con true va
     * intercalado L/R (un medio mono se duplica). Nunca tiene el audio entero en memoria.
     * Devuelve false si el medio no tiene pista de audio.
     */
    suspend fun decode(path: String, fromUs: Long, toUs: Long, rate: Int, stereo: Boolean, onBlock: suspend (FloatArray, Int) -> Unit): Boolean {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(path)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; format = f; break }
            }
            val fmt = format ?: return false
            ex.selectTrack(track)
            if (fromUs > 0) ex.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            var srcRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val outCh = if (stereo) 2 else 1
            val rs = Resampler(outCh)
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            var frames = FloatArray(0)
            while (!outDone) {
                coroutineContext.ensureActive()
                if (!inDone) {
                    val ii = c.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val ib = c.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0 || (toUs > 0 && ex.sampleTime > toUs + 500_000L)) {
                            c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inDone = true
                        } else {
                            c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    srcRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    srcCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        val ob = c.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                        ob.position(info.offset)
                        val bytes = if (float) 4 else 2
                        val total = info.size / (bytes * srcCh)
                        // Recorte exacto al tramo pedido (el seek cae en el fotograma clave anterior).
                        val t0 = info.presentationTimeUs
                        val first = if (t0 < fromUs) ((fromUs - t0) * srcRate / 1_000_000L).toInt().coerceAtMost(total) else 0
                        var take = total - first
                        if (toUs > 0) take = minOf(take, ((toUs - t0) * srcRate / 1_000_000L).toInt() - first)
                        if (take > 0) {
                            if (frames.size < take * outCh) frames = FloatArray(take * outCh)
                            var w = 0
                            for (k in 0 until total) {
                                var l = 0f
                                var r = 0f
                                for (ch in 0 until srcCh) {
                                    val v = if (float) ob.getFloat() else ob.getShort() / 32768f
                                    if (ch == 0) { l = v; r = v } else if (ch == 1) r = v
                                }
                                if (k < first || w >= take) continue
                                if (stereo) { frames[w * 2] = l; frames[w * 2 + 1] = r } else frames[w] = (l + r) * 0.5f
                                w++
                            }
                            rs.push(frames, w, srcRate, rate, onBlock)
                        } else if (toUs > 0 && t0 > toUs) {
                            outDone = true
                        }
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            return true
        } finally {
            try { codec?.stop() } catch (e: Throwable) { }
            try { codec?.release() } catch (e: Throwable) { }
            ex.release()
        }
    }

    /** Todo el tramo en un único vector mono (solo para tramos cortos o frecuencias bajas). */
    suspend fun decodeMono(path: String, fromUs: Long, toUs: Long, rate: Int): FloatArray {
        var out = FloatArray(rate * 30)
        var n = 0
        decode(path, fromUs, toUs, rate, false) { b, count ->
            if (n + count > out.size) out = out.copyOf(maxOf(out.size * 2, n + count))
            System.arraycopy(b, 0, out, n, count)
            n += count
        }
        return out.copyOf(n)
    }

    /** Remuestreo lineal de un vector mono. */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val n = (x.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        val step = from.toDouble() / to
        for (i in 0 until n) {
            val p = i * step
            val a = p.toInt().coerceAtMost(x.size - 1)
            val b = (a + 1).coerceAtMost(x.size - 1)
            val f = (p - a).toFloat()
            out[i] = x[a] * (1f - f) + x[b] * f
        }
        return out
    }
}

/**
 * Remuestreador por bloques. Al bajar de frecuencia promedia las muestras de cada intervalo de salida
 * (filtro de caja, evita el aliasing más grueso); al subir, interpola linealmente.
 */
private class Resampler(private val channels: Int) {
    private var pos = 0.0
    private val acc = FloatArray(channels)
    private var accN = 0
    private val prev = FloatArray(channels)
    private var hasPrev = false
    private var out = FloatArray(0)

    suspend fun push(x: FloatArray, frames: Int, from: Int, to: Int, sink: suspend (FloatArray, Int) -> Unit) {
        if (frames <= 0) return
        if (from == to) { sink(x, frames * channels); return }
        val need = ((frames.toLong() * to / from) + 4).toInt() * channels
        if (out.size < need) out = FloatArray(need)
        var w = 0
        if (to < from) {
            val ratio = to.toDouble() / from
            for (i in 0 until frames) {
                for (ch in 0 until channels) acc[ch] += x[i * channels + ch]
                accN++
                pos += ratio
                if (pos >= 1.0) {
                    pos -= 1.0
                    for (ch in 0 until channels) { out[w++] = acc[ch] / accN; acc[ch] = 0f }
                    accN = 0
                }
            }
        } else {
            val step = from.toDouble() / to
            for (i in 0 until frames) {
                if (!hasPrev) { for (ch in 0 until channels) prev[ch] = x[i * channels + ch]; hasPrev = true }
                while (pos < 1.0) {
                    val f = pos.toFloat()
                    for (ch in 0 until channels) out[w++] = prev[ch] * (1f - f) + x[i * channels + ch] * f
                    pos += step
                }
                pos -= 1.0
                for (ch in 0 until channels) prev[ch] = x[i * channels + ch]
            }
        }
        if (w > 0) sink(out, w)
    }
}
