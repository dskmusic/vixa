package com.dskmusic.vixa.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.model.MediaRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal fun cacheKey(path: String): String {
    val f = File(path)
    return Integer.toHexString(path.hashCode()) + "_" + java.lang.Long.toHexString(f.length() xor f.lastModified())
}

/** Miniaturas de fotogramas para la línea de tiempo: caché en memoria y en disco, extracción en segundo plano. */
object Thumbs {
    const val STEP_US = 500_000L
    private const val HEIGHT = 96

    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pending = ConcurrentHashMap.newKeySet<String>()
    // Cola LIFO: lo último pedido (lo visible ahora) se atiende primero.
    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    })
    private val _tick = MutableStateFlow(0)
    /** Cambia cada vez que llega una miniatura nueva: quien dibuja lo observa para repintar. */
    val tick: StateFlow<Int> = _tick
    @Volatile private var dir: File? = null
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverPath = ""

    fun init(context: Context) {
        dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    }

    fun dir(): File? = dir

    /** Devuelve la miniatura si ya está en memoria; si no, la encarga y devuelve null. */
    fun get(media: MediaRef, timeUs: Long): Bitmap? {
        val t = if (media.isImage || !media.hasVideo) 0L else (timeUs / STEP_US) * STEP_US
        val key = media.path + "@" + t
        memory.get(key)?.let { return it }
        if (pending.add(key)) executor.execute { load(media, t, key) }
        return null
    }

    private fun load(media: MediaRef, t: Long, key: String) {
        try {
            val d = dir ?: return
            val folder = File(d, cacheKey(media.path))
            val file = File(folder, (t / 1000L).toString() + ".jpg")
            val cached: Bitmap? = if (file.exists()) BitmapFactory.decodeFile(file.path) else null
            val bmp: Bitmap? = cached ?: run {
                val fresh = if (media.isImage) decodeImage(media.path) else extract(media.path, t)
                if (fresh != null) {
                    folder.mkdirs()
                    FileOutputStream(file).use { fresh.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                }
                fresh
            }
            if (bmp != null) {
                memory.put(key, bmp)
                _tick.value = _tick.value + 1
            }
        } catch (e: Throwable) {
            ErrorLog.log("Thumb", e)
        } finally {
            pending.remove(key)
        }
    }

    private fun decodeImage(path: String): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outHeight / s > HEIGHT * 2) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }

    private fun extract(path: String, t: Long): Bitmap? {
        val current = retriever
        val r: MediaMetadataRetriever = if (current != null && retrieverPath == path) current else {
            try { current?.release() } catch (e: Throwable) { }
            retriever = null
            val created = MediaMetadataRetriever()
            created.setDataSource(path)
            retriever = created
            retrieverPath = path
            created
        }
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
        val scale = HEIGHT.toFloat() / maxOf(1, minOf(w, h))
        val tw = (w * scale).toInt().coerceAtLeast(2)
        val th = (h * scale).toInt().coerceAtLeast(2)
        if (Build.VERSION.SDK_INT >= 27) {
            return r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th)
        }
        val full = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val s2 = HEIGHT.toFloat() / maxOf(1, minOf(full.width, full.height))
        val small = Bitmap.createScaledBitmap(full, (full.width * s2).toInt().coerceAtLeast(2), (full.height * s2).toInt().coerceAtLeast(2), true)
        if (small !== full) full.recycle()
        return small
    }

    fun clearMemory() = memory.evictAll()
}

/** Envolvente de audio (picos 0..255 a [RATE] muestras por segundo) para dibujar ondas y para el análisis. */
object Waveforms {
    const val RATE = 50

    private val memory = ConcurrentHashMap<String, ByteArray>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newFixedThreadPool(2)
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick
    @Volatile private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.cacheDir, "waves").apply { mkdirs() }
    }

    fun dir(): File? = dir

    fun get(path: String): ByteArray? {
        memory[path]?.let { return it }
        if (pending.add(path)) executor.execute {
            try {
                if (loadBlocking(path) != null) _tick.value = _tick.value + 1
            } finally {
                pending.remove(path)
            }
        }
        return null
    }

    /** Decodifica (o lee de caché) la envolvente. Bloqueante. */
    fun loadBlocking(path: String): ByteArray? {
        memory[path]?.let { return it }
        return try {
            val file = dir?.let { File(it, cacheKey(path) + ".bin") }
            val data = if (file != null && file.exists() && file.length() > 0) file.readBytes() else decode(path).also { d -> if (d != null && file != null) file.writeBytes(d) }
            if (data != null) memory[path] = data
            data
        } catch (e: Throwable) {
            ErrorLog.log("Waveform", e)
            null
        }
    }

    private fun decode(path: String): ByteArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(path)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return ByteArray(0)
            ex.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return ByteArray(0)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val out = java.io.ByteArrayOutputStream(maxOf(1024, (durUs * RATE / 1_000_000L).toInt() + 64))
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var peak = 0
            var count = 0
            var perBucket = sampleRate / RATE
            var guard = 0
            while (true) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)
                        val size = if (buf != null) ex.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    perBucket = sampleRate / RATE
                } else if (o >= 0) {
                    guard = 0
                    val buf = codec.getOutputBuffer(o)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val n = sb.remaining()
                        var k = 0
                        val ch = channels.coerceAtLeast(1)
                        while (k < n) {
                            val v = abs(sb.get(k).toInt())
                            if (v > peak) peak = v
                            k += ch
                            count++
                            if (count >= perBucket) {
                                out.write((peak * 255 / 32768).coerceIn(0, 255))
                                peak = 0
                                count = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (inputDone && ++guard > 400) break
            }
            return out.toByteArray()
        } finally {
            try { codec?.stop() } catch (e: Throwable) { }
            try { codec?.release() } catch (e: Throwable) { }
            ex.release()
        }
    }
}

/**
 * Proxies de baja resolución para medios pesados (4K, HDR). Se generan en cola con Transformer y solo
 * los usa la vista previa; la exportación trabaja siempre con los originales.
 */
class Proxies(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    val dir: File = File(app.filesDir, "proxies").apply { mkdirs() }
    private val queue = ArrayDeque<MediaRef>()
    private var running: Transformer? = null
    private val failed = HashSet<String>()
    private val _tick = MutableStateFlow(0)
    /** Cambia cuando termina un proxy: la vista previa debe reconstruirse para usarlo. */
    val tick: StateFlow<Int> = _tick
    @Volatile var enabled = true

    private fun fileFor(path: String) = File(dir, cacheKey(path) + ".mp4")

    fun needsProxy(m: MediaRef): Boolean = !m.isImage && m.hasVideo && (minOf(m.width, m.height) > 1080 || m.isHdr)

    /** Ruta a usar en la vista previa: el proxy si existe, si no el original. */
    fun pathFor(path: String): String {
        if (!enabled) return path
        val f = fileFor(path)
        return if (f.exists() && f.length() > 0) f.path else path
    }

    /** Encola la generación del proxy si hace falta. Llamar desde el hilo principal. */
    fun request(m: MediaRef) {
        if (!enabled || !needsProxy(m) || failed.contains(m.path)) return
        val f = fileFor(m.path)
        if (f.exists() && f.length() > 0) return
        if (queue.any { it.path == m.path }) return
        queue.addLast(m)
        pump()
    }

    private fun pump() {
        if (running != null) return
        val m = queue.firstOrNull() ?: return
        val out = fileFor(m.path)
        val tmp = File(dir, out.name + ".tmp")
        try {
            val landscape = m.width >= m.height
            val h = if (landscape) 540 else 960
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(File(m.path))))
                .setRemoveAudio(true)
                .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(h))))
                .build()
            val comp = Composition.Builder(listOf(EditedMediaItemSequence.withVideoFrom(listOf(item))))
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
            val t = Transformer.Builder(app)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(app).setEnableFallback(true)
                        .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(2_500_000).setiFrameIntervalSeconds(0.5f).build())
                        .build(),
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        running = null
                        queue.removeFirstOrNull()
                        if (tmp.renameTo(out)) _tick.value = _tick.value + 1 else tmp.delete()
                        main.post { pump() }
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        ErrorLog.log("Proxy", exportException)
                        running = null
                        queue.removeFirstOrNull()
                        failed.add(m.path)
                        tmp.delete()
                        main.post { pump() }
                    }
                })
                .build()
            running = t
            t.start(comp, tmp.absolutePath)
        } catch (e: Throwable) {
            ErrorLog.log("Proxy", e)
            running = null
            queue.removeFirstOrNull()
            failed.add(m.path)
            tmp.delete()
            main.post { pump() }
        }
    }

    /** Detiene la generación (al salir del editor o pasar a segundo plano). */
    fun cancelAll() {
        queue.clear()
        try { running?.cancel() } catch (e: Throwable) { }
        running = null
        dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }
}
