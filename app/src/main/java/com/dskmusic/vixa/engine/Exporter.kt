package com.dskmusic.vixa.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.EncoderUtil
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.Container
import com.dskmusic.vixa.model.ExportKind
import com.dskmusic.vixa.model.ExportSettings
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.VideoCodec
import com.dskmusic.vixa.model.slice
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

enum class ExportStatus { IDLE, RUNNING, DONE, ERROR, CANCELLED }

data class ExportState(
    val status: ExportStatus = ExportStatus.IDLE,
    val progress: Float = 0f,
    val etaMs: Long = -1,
    val title: String = "",
    val output: String = "",
    val message: String = "",
    val warning: String = "",
)

/**
 * Exportación con Transformer. Escribe siempre en un archivo temporal y solo lo renombra al terminar
 * bien; si el codificador falla reintenta con configuraciones cada vez más conservadoras.
 */
class Exporter(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = _state

    var reversedPath: (Clip) -> String? = { null }

    private var transformer: Transformer? = null
    private var tempFile: File? = null
    private var startMs = 0L
    @Volatile private var cancelled = false

    private class Attempt(val mime: String, val shortSide: Int, val software: Boolean, val passthrough: Boolean)

    val isRunning: Boolean get() = _state.value.status == ExportStatus.RUNNING

    fun reset() {
        if (!isRunning) _state.value = ExportState()
    }

    companion object {
        fun isCodecSupported(codec: VideoCodec): Boolean = try {
            EncoderUtil.getSupportedEncoders(codec.mime).isNotEmpty()
        } catch (e: Throwable) {
            false
        }

        fun isWebmSupported(): Boolean = Build.VERSION.SDK_INT >= 29 && isCodecSupported(VideoCodec.VP9) &&
            try { EncoderUtil.getSupportedEncoders(MimeTypes.AUDIO_OPUS).isNotEmpty() } catch (e: Throwable) { false }

        fun defaultDir(kind: ExportKind = ExportKind.VIDEO): File = com.dskmusic.vixa.data.Storage.forKind(kind)

        /** El proyecto es una simple sucesión de vídeos sin retocar: se puede copiar sin recodificar. */
        fun passthroughEligible(p: Project, s: ExportSettings): Boolean {
            if (!s.passthrough || s.kind != ExportKind.VIDEO || s.container != Container.MP4) return false
            if (s.aspectW > 0 || p.watermark.enabled) return false
            val main = p.mainTrack.clips
            if (main.isEmpty() || p.tracks.any { it.type != TrackType.MAIN && it.clips.isNotEmpty() }) return false
            val (ow, oh) = s.outputSize(p.width, p.height)
            return main.all { c ->
                val m = c.media
                c.kind == ClipKind.VIDEO && m != null && !m.isHdr && m.width == ow && m.height == oh && m.width == p.width && m.height == p.height &&
                    c == Clip(id = c.id, kind = ClipKind.VIDEO, startUs = c.startUs, durationUs = c.durationUs, media = m,
                        srcInUs = c.srcInUs, srcOutUs = c.srcOutUs, label = c.label, groupId = c.groupId)
            }
        }
    }

    fun cancel() {
        cancelled = true
        main.post {
            try { transformer?.cancel() } catch (e: Throwable) { ErrorLog.log("Export", e) }
            transformer = null
            cleanupTemp()
            if (isRunning) _state.value = ExportState(ExportStatus.CANCELLED, title = _state.value.title)
        }
    }

    private fun cleanupTemp() {
        tempFile?.let { if (it.exists()) it.delete() }
        tempFile = null
    }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun fail(message: String) {
        cleanupTemp()
        transformer = null
        _state.value = _state.value.copy(status = ExportStatus.ERROR, message = message, etaMs = -1)
    }

    fun start(source: Project, settings: ExportSettings) {
        if (isRunning) return
        cancelled = false
        var p = source
        if (settings.aspectW > 0 && settings.aspectH > 0) {
            val side = minOf(p.width, p.height)
            p = if (settings.aspectW >= settings.aspectH) p.copy(width = side * settings.aspectW / settings.aspectH / 2 * 2, height = side)
            else p.copy(width = side, height = side * settings.aspectH / settings.aspectW / 2 * 2)
        }
        when (settings.kind) {
            ExportKind.FRAME_PNG, ExportKind.FRAME_JPG -> {
                val t = settings.frameUs.coerceIn(0, (p.durationUs - 200_000L).coerceAtLeast(0))
                p = p.slice(t, minOf(p.durationUs, t + 400_000L))
            }
            else -> if (settings.rangeStartUs >= 0 && settings.rangeEndUs > settings.rangeStartUs) p = p.slice(settings.rangeStartUs, settings.rangeEndUs)
        }
        if (p.durationUs <= 0) {
            _state.value = ExportState(ExportStatus.ERROR, message = str(R.string.err_empty_project))
            return
        }
        val dir = if (settings.outDir.isNotEmpty()) File(settings.outDir) else defaultDir(settings.kind)
        if (!dir.exists() && !dir.mkdirs()) {
            _state.value = ExportState(ExportStatus.ERROR, message = str(R.string.err_cannot_write, dir.path))
            return
        }
        val need = settings.estimateBytes(p.width, p.height, p.durationUs) * 13 / 10 + 20_000_000L
        val free = try { StatFs(dir.path).availableBytes } catch (e: Throwable) { Long.MAX_VALUE }
        if (free < need) {
            _state.value = ExportState(ExportStatus.ERROR, message = str(R.string.err_no_space, need / 1_000_000, free / 1_000_000))
            return
        }
        val baseName = (settings.fileName.ifBlank { p.name.ifBlank { "Vixa" } }).replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var target = File(dir, baseName + "." + settings.extension)
        var n = 1
        while (target.exists()) { target = File(dir, baseName + "_" + n + "." + settings.extension); n++ }
        val title = target.name

        val attempts = ArrayList<Attempt>()
        val video = settings.kind != ExportKind.AUDIO
        if (video) {
            val gifOrFrame = settings.kind != ExportKind.VIDEO
            val webm = settings.kind == ExportKind.VIDEO && settings.container == Container.WEBM
            val mime = if (gifOrFrame) MimeTypes.VIDEO_H264 else if (webm) MimeTypes.VIDEO_VP9 else settings.codec.mime
            val side = if (settings.kind == ExportKind.GIF) minOf(settings.gifWidth, settings.shortSide) else settings.shortSide
            if (passthroughEligible(p, settings)) attempts.add(Attempt(mime, side, false, true))
            attempts.add(Attempt(mime, side, !settings.hardware, false))
            if (mime != MimeTypes.VIDEO_H264 && !webm) attempts.add(Attempt(MimeTypes.VIDEO_H264, side, false, false))
            if (side > 720) attempts.add(Attempt(if (webm) mime else MimeTypes.VIDEO_H264, 720, false, false))
            attempts.add(Attempt(if (webm) mime else MimeTypes.VIDEO_H264, minOf(side, 720), true, false))
        } else attempts.add(Attempt("", 0, false, false))

        startMs = SystemClock.elapsedRealtime()
        _state.value = ExportState(ExportStatus.RUNNING, title = title)
        ExportService.start(app)
        main.post { runAttempt(p, settings, target, attempts, 0, "") }
    }

    private fun runAttempt(p: Project, s: ExportSettings, target: File, attempts: List<Attempt>, index: Int, warning: String) {
        if (cancelled) return
        val a = attempts[index]
        val webm = s.kind == ExportKind.VIDEO && s.container == Container.WEBM
        val tmp = File(target.parentFile, ".vixa_tmp_" + System.currentTimeMillis() + (if (s.kind == ExportKind.AUDIO) ".m4a" else ".mp4"))
        tempFile = tmp
        try {
            val frozen = if (s.kind == ExportKind.GIF) p.copy(fps = s.gifFps) else if (s.kind == ExportKind.VIDEO) p.copy(fps = s.fps) else p
            val (w, h) = s.copy(shortSide = a.shortSide).outputSize(p.width, p.height)
            var renderCtx: RenderCtx? = null
            val composition: Composition = if (a.passthrough) {
                val items = p.mainTrack.clips.map { c ->
                    val m = c.media!!
                    EditedMediaItem.Builder(
                        MediaItem.Builder().setUri(Uri.fromFile(File(m.path))).setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder().setStartPositionUs(c.srcInUs).setEndPositionUs(c.srcOutUs).build(),
                        ).build(),
                    ).setDurationUs(m.durationUs.coerceAtLeast(c.srcOutUs)).build()
                }
                Composition.Builder(listOf(EditedMediaItemSequence.withAudioAndVideoFrom(items)))
                    .setTransmuxVideo(true).setTransmuxAudio(true).build()
            } else {
                val built = CompositionBuilder.build(app, frozen, { frozen }, BuildOptions(w, h, { it }, reversedPath, includeVideo = s.kind != ExportKind.AUDIO))
                if (s.kind != ExportKind.AUDIO) renderCtx = built.ctx
                built.composition
            }
            val tb = Transformer.Builder(app)
                .setVideoFrameProcessorFactory(CompositionBuilder.frameProcessorFactory())
                .setAudioMimeType(if (webm) MimeTypes.AUDIO_OPUS else MimeTypes.AUDIO_AAC)
            if (s.kind != ExportKind.AUDIO) tb.setVideoMimeType(a.mime)
            if (webm) tb.setMuxerFactory(InAppMp4Muxer.Factory())
            if (a.passthrough) tb.experimentalSetTrimOptimizationEnabled(true)
            val ef = DefaultEncoderFactory.Builder(app).setEnableFallback(true)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(s.copy(shortSide = a.shortSide).videoBitrate(w, h)).build())
            if (a.software) {
                ef.setVideoEncoderSelector(EncoderSelector { mime ->
                    val all = EncoderUtil.getSupportedEncoders(mime)
                    val sw = all.filter { !EncoderUtil.isHardwareAccelerated(it, mime) }
                    ImmutableList.copyOf(if (sw.isEmpty()) all else sw)
                })
            }
            tb.setEncoderFactory(ef.build())
            tb.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    // Medición de velocidad (logcat "VixaExport"): fotogramas, tiempo y codificador realmente usado.
                    val ms = SystemClock.elapsedRealtime() - startMs
                    android.util.Log.i("VixaExport", "frames=" + exportResult.videoFrameCount + " ms=" + ms + " fps=" +
                        (exportResult.videoFrameCount * 1000L / ms.coerceAtLeast(1)) + " enc=" + exportResult.videoEncoderName + " " + w + "x" + h + " intento=" + index)
                    worker.execute { finish(p, s, tmp, target, warning) }
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    transformer = null
                    ErrorLog.log("Export(" + a.mime + "," + a.shortSide + (if (a.software) ",sw" else "") + ")", exportException)
                    if (tmp.exists()) tmp.delete()
                    if (cancelled) return
                    if (index + 1 < attempts.size) {
                        val next = attempts[index + 1]
                        val w2 = str(R.string.export_retry_warning, next.mime.substringAfter('/'), next.shortSide) +
                            (if (next.software) " (software)" else "")
                        _state.value = _state.value.copy(progress = 0f, etaMs = -1, warning = w2)
                        startMs = SystemClock.elapsedRealtime()
                        main.post { runAttempt(p, s, target, attempts, index + 1, w2) }
                    } else {
                        fail(exportException.errorCodeName + ": " + (exportException.cause?.message ?: exportException.message ?: ""))
                    }
                }
            })
            val t = tb.build()
            transformer = t
            t.start(composition, tmp.absolutePath)
            // El GIF reserva la segunda mitad de la barra para su post-proceso.
            pollProgress(t, renderCtx, frozen.durationUs, if (s.kind == ExportKind.GIF) 0.5f else 0.97f)
        } catch (e: Throwable) {
            ErrorLog.log("Export", e)
            if (tmp.exists()) tmp.delete()
            if (index + 1 < attempts.size) main.post { runAttempt(p, s, target, attempts, index + 1, warning) }
            else fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun pollProgress(t: Transformer, ctx: RenderCtx?, durationUs: Long, scale: Float) {
        val holder = ProgressHolder()
        // Progreso monótono y ETA calculado con la velocidad de los últimos ~20 s (no con la media desde el inicio,
        // que engaña cuando el arranque es rápido y el resto va más lento).
        val samples = ArrayDeque<Pair<Long, Float>>()
        var shown = 0f
        main.postDelayed(object : Runnable {
            override fun run() {
                if (transformer !== t) return
                try {
                    // Con vídeo el progreso real es el tiempo del último fotograma compuesto: el del Transformer mide la
                    // carga de medios (con imágenes marca 100 % nada más empezar) y los bytes escritos dependen del contenido.
                    val pr = if (ctx != null) (ctx.renderedUs.toFloat() / durationUs).coerceIn(0f, 1f)
                    else if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress / 100f else -1f
                    if (pr >= 0f) {
                        val now = SystemClock.elapsedRealtime()
                        samples.addLast(now to pr)
                        while (samples.size > 2 && now - samples.first().first > 20_000L) samples.removeFirst()
                        val (t0, p0) = samples.first()
                        val rate = if (now > t0 && pr > p0) (pr - p0) / (now - t0).toFloat() else 0f
                        val eta = if (pr > 0.02f && rate > 0f) ((1f - pr) / rate).toLong() else -1
                        shown = maxOf(shown, pr * scale)
                        _state.value = _state.value.copy(progress = shown, etaMs = eta)
                    }
                } catch (e: Throwable) {
                    return
                }
                main.postDelayed(this, 400)
            }
        }, 400)
    }

    /** Post-proceso (en segundo plano): GIF, fotograma, remux a WebM, renombrado atómico y escaneo. */
    private fun finish(p: Project, s: ExportSettings, tmp: File, target: File, warning: String) {
        var warn = warning
        var out = target
        try {
            if (cancelled) { tmp.delete(); return }
            when (s.kind) {
                ExportKind.GIF -> {
                    val part = File(target.parentFile, ".vixa_tmp_" + target.name)
                    writeGif(tmp, part, s)
                    tmp.delete()
                    if (!part.renameTo(target)) throw IllegalStateException("rename")
                }
                ExportKind.FRAME_PNG, ExportKind.FRAME_JPG -> {
                    val r = MediaMetadataRetriever()
                    val bmp: Bitmap? = try {
                        r.setDataSource(tmp.absolutePath)
                        r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                    } finally { r.release() }
                    tmp.delete()
                    if (bmp == null) throw IllegalStateException("frame")
                    val part = File(target.parentFile, ".vixa_tmp_" + target.name)
                    FileOutputStream(part).use {
                        bmp.compress(if (s.kind == ExportKind.FRAME_PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it)
                    }
                    if (!part.renameTo(target)) throw IllegalStateException("rename")
                }
                else -> {
                    if (s.kind == ExportKind.VIDEO && s.container == Container.WEBM) {
                        val part = File(target.parentFile, ".vixa_tmp_" + target.name)
                        try {
                            remuxToWebm(tmp, part)
                            tmp.delete()
                            if (!part.renameTo(target)) throw IllegalStateException("rename")
                        } catch (e: Throwable) {
                            // El dispositivo no pudo remultiplexar: se conserva el MP4 (VP9 + Opus).
                            ErrorLog.log("Export/webm", e)
                            part.delete()
                            out = File(target.parentFile, target.nameWithoutExtension + ".mp4")
                            if (!tmp.renameTo(out)) throw IllegalStateException("rename")
                            warn = str(R.string.export_webm_fallback)
                        }
                    } else if (!tmp.renameTo(target)) throw IllegalStateException("rename")
                    if (s.kind == ExportKind.VIDEO && s.saveCover) saveCover(out, p)
                }
            }
            tempFile = null
            MediaScannerConnection.scanFile(app, arrayOf(out.absolutePath), null, null)
            _state.value = _state.value.copy(status = ExportStatus.DONE, progress = 1f, etaMs = 0, output = out.absolutePath, warning = warn)
        } catch (e: Throwable) {
            ErrorLog.log("Export/finish", e)
            tmp.delete()
            main.post { fail(e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun saveCover(video: File, p: Project) {
        try {
            val r = MediaMetadataRetriever()
            val frame: Bitmap? = try {
                r.setDataSource(video.absolutePath)
                r.getFrameAtTime(p.coverUs.coerceIn(0, p.durationUs), MediaMetadataRetriever.OPTION_CLOSEST)
            } finally { r.release() }
            val bmp = frame ?: return
            val f = File(video.parentFile, video.nameWithoutExtension + "_cover.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            MediaScannerConnection.scanFile(app, arrayOf(f.absolutePath), null, null)
        } catch (e: Throwable) {
            ErrorLog.log("Export/cover", e)
        }
    }

    private fun writeGif(video: File, out: File, s: ExportSettings) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(video.absolutePath)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: s.gifWidth
            val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: s.gifWidth
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val sw = if (rot == 90 || rot == 270) vh else vw
            val sh = if (rot == 90 || rot == 270) vw else vh
            val w = minOf(s.gifWidth, sw).coerceAtLeast(2)
            val h = (sh.toLong() * w / sw.coerceAtLeast(1)).toInt().coerceAtLeast(2)
            val fps = s.gifFps.coerceIn(2, 30)
            val total = (durMs * fps / 1000L).toInt().coerceAtLeast(1)
            // Boomerang: tras los fotogramas hacia delante se añaden los de vuelta (sin repetir el primero ni el último),
            // así el final enlaza con el principio. Los de vuelta se vuelven a leer del vídeo: no se guardan en memoria.
            val back = if (s.boomerang && total > 2) total - 2 else 0
            val steps = (total + back).coerceAtLeast(1)
            FileOutputStream(out).buffered().use { os ->
                val enc = GifEncoder(os, w, h, fps, s.gifCompression, s.gifDither, s.gifLoop, s.gifSpeed)
                for (n in 0 until total + back) {
                    if (cancelled) break
                    val i = if (n < total) n else total - 2 - (n - total)
                    val b = r.getFrameAtTime(i * 1_000_000L / fps, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                    enc.addFrame(b)
                    b.recycle()
                    _state.value = _state.value.copy(progress = 0.5f + 0.5f * n / steps)
                }
                enc.finish()
            }
        } finally {
            r.release()
        }
    }

    /** Remultiplexa MP4 (VP9 + Opus) a WebM con las API de plataforma. */
    private fun remuxToWebm(src: File, dst: File) {
        val ex = MediaExtractor()
        ex.setDataSource(src.absolutePath)
        val muxer = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
        try {
            val map = HashMap<Int, Int>()
            var maxSize = 1 shl 20
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                ex.selectTrack(i)
                map[i] = muxer.addTrack(f)
                if (f.containsKey(android.media.MediaFormat.KEY_MAX_INPUT_SIZE)) maxSize = maxOf(maxSize, f.getInteger(android.media.MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            muxer.start()
            val buf = ByteBuffer.allocate(maxSize * 2)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                info.set(0, size, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(map[ex.sampleTrackIndex] ?: 0, buf, info)
                ex.advance()
            }
            muxer.stop()
        } finally {
            try { muxer.release() } catch (e: Throwable) { }
            ex.release()
        }
    }
}
