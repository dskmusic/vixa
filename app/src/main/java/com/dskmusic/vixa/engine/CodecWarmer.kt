package com.dskmusic.vixa.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock

/**
 * Calentamiento del decodificador (idea deducida del analisis de CapCut, escrita desde cero).
 *
 * Pre-inicializa un MediaCodec con una configuracion 1x1 y unos maximos reales
 * (configure -> start -> stop -> release) en un hilo de fondo con prioridad de interfaz.
 * Asi, cuando el reproductor crea su decodificador para el siguiente clip, el codec/driver
 * ya esta caliente y el primer arranque es mas corto.
 *
 * Es deliberadamente "best-effort": no lanza excepciones y NO toca el reproductor,
 * la Surface ni el contexto EGL, asi que no puede provocar EGL_BAD_ALLOC ni errores GL.
 *
 * A diferencia de una unica precarga inicial, aqui se puede volver a calentar (con debounce):
 * CapCut precarga el codec del SIGUIENTE clip justo antes del corte, no solo una vez al inicio.
 */
object CodecWarmer {
    @Volatile
    private var thread: HandlerThread? = null

    @Volatile
    private var lastWarmMs = 0L

    private fun ensureThread() {
        if (thread == null) {
            synchronized(this) {
                if (thread == null) {
                    val t = HandlerThread("Animax_Codec", Process.THREAD_PRIORITY_DISPLAY)
                    t.start()
                    thread = t
                }
            }
        }
    }

    /** Calienta un decodificador por cada MIME indicado. Reintentable: se vuelve a calentar tras [minIntervalMs]. */
    fun warm(mimes: List<String>) = warm(mimes, 1200L)

    /** [minIntervalMs] evita saturar el hilo si se llama en cada tick cerca de un corte. */
    fun warm(mimes: List<String>, minIntervalMs: Long) {
        val now = SystemClock.uptimeMillis()
        if (now - lastWarmMs < minIntervalMs) return
        lastWarmMs = now
        ensureThread()
        val looper = thread?.looper ?: return
        Handler(looper).post {
            for (mime in mimes) {
                var codec: MediaCodec? = null
                try {
                    codec = MediaCodec.createDecoderByType(mime)
                    val format = MediaFormat.createVideoFormat(mime, 1, 1)
                    format.setInteger(MediaFormat.KEY_MAX_WIDTH, 1920)
                    format.setInteger(MediaFormat.KEY_MAX_HEIGHT, 1080)
                    codec.configure(format, null, null, 0)
                    codec.start()
                    codec.stop()
                } catch (_: Throwable) {
                    // Best-effort: si el dispositivo no tiene este decodificador, se ignora.
                } finally {
                    try { codec?.release() } catch (_: Throwable) {}
                }
            }
        }
    }
}