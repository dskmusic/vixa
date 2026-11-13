package com.dskmusic.vixa.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Vista previa fluida: en lugar de reproducir la composición en vivo (que se reconstruye al pasar de vídeo a imagen y se
 * corta), el proyecto se renderiza a un MP4 pequeño en la caché y se reproduce como cualquier vídeo.
 * Es la misma composición que la exportación, así que lo que se ve es lo que se exportará, solo que a menor resolución.
 */
class ProxyRender(context: Context) {
    private val app = context.applicationContext

    /** Renderiza [project] con [shortSide] píxeles de lado menor y devuelve el archivo. Cancelable. */
    suspend fun render(project: Project, shortSide: Int, onProgress: (Float) -> Unit): File = withContext(Dispatchers.Main) {
        val dir = File(app.cacheDir, "proxy_preview").apply { mkdirs() }
        // Los renders de sesiones anteriores no sirven: se limpian.
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000L) it.delete() }
        val out = File(dir, "p_" + System.currentTimeMillis() + ".mp4")
        val minSide = minOf(project.width, project.height).coerceAtLeast(2)
        val s = minOf(1f, shortSide.toFloat() / minSide)
        val w = ((project.width * s).toInt() / 2 * 2).coerceAtLeast(2)
        val h = ((project.height * s).toInt() / 2 * 2).coerceAtLeast(2)
        val frozen = project.copy(fps = 24)
        val built = CompositionBuilder.build(app, frozen, { frozen }, BuildOptions(w, h))
        val bitrate = (w * h * 24 * 0.1).toInt().coerceIn(1_000_000, 4_000_000)
        val handler = Handler(Looper.getMainLooper())
        suspendCancellableCoroutine { cont ->
            var finished = false
            val encoder = DefaultEncoderFactory.Builder(app).setEnableFallback(true)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build()).build()
            val t = Transformer.Builder(app)
                .setVideoFrameProcessorFactory(CompositionBuilder.frameProcessorFactory())
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished = true
                        if (cont.isActive) cont.resume(out)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        finished = true
                        out.delete()
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            cont.invokeOnCancellation {
                finished = true
                handler.post { try { t.cancel() } catch (e: Throwable) { ErrorLog.log("ProxyRender", e) } }
                out.delete()
            }
            t.start(built.composition, out.absolutePath)
            handler.postDelayed(object : Runnable {
                override fun run() {
                    if (finished) return
                    onProgress((built.ctx.renderedUs.toFloat() / frozen.durationUs.coerceAtLeast(1)).coerceIn(0f, 1f))
                    handler.postDelayed(this, 300)
                }
            }, 300)
        }
    }
}
