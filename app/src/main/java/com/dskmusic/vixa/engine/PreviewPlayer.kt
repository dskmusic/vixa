package com.dskmusic.vixa.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Vista previa sobre CompositionPlayer. Usa la misma Composition que la exportación.
 *
 * Blindaje frente a Media3 1.11 (MultipleInputVideoGraph): el compositor marca cada carril como terminado
 * en cuanto llega a su fin y `flush()` NO lo resetea, así que cualquier seek posterior (o el bucle) lanza
 * `IllegalStateException` en `DefaultVideoCompositor.queueInputTexture`. Además, en pausa un seek no repinta
 * de forma fiable. Por eso aquí el reproductor es desechable: cualquier situación dudosa (fin de pista, error,
 * cambio de parámetros en pausa, atasco) crea uno nuevo en la posición actual. Todo se llama desde el hilo principal.
 */
class PreviewPlayer(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var player: CompositionPlayer? = null
    private var surface: Surface? = null
    private var surfaceSize = Size(1, 1)

    @Volatile private var live: Project = Project()
    private var wantPlay = false
    private var loop = false
    private var released = true
    private var scrubbing = false

    /** El reproductor actual ya no es de fiar (llegó al final o falló): el próximo seek/play lo reconstruye. */
    private var poisoned = false
    private var rebuildPending = false
    private var retries = 0
    private var stallSinceMs = 0L
    private var lastTickPosUs = -1L
    private var prevPosUs = -1L
    private var boundaries: List<Long> = emptyList()
    /** Hasta este instante no se enseña el indicador de carga (reconstrucción silenciosa en un corte). */
    private var quietUntilMs = 0L

    /** Lado menor del lienzo de vista previa (calidad). */
    var previewSide = 540
    var mediaPath: (String) -> String = { it }
    var reversedPath: (Clip) -> String? = { null }

    private val _positionUs = MutableStateFlow(0L)
    val positionUs: StateFlow<Long> = _positionUs
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _buffering.value = (playbackState == Player.STATE_BUFFERING || rebuildPending) && SystemClock.uptimeMillis() > quietUntilMs
            if (playbackState == Player.STATE_READY) retries = 0
            if (playbackState == Player.STATE_ENDED) {
                poisoned = true
                if (loop && wantPlay) {
                    // Bucle manual: el REPEAT_MODE del reproductor haría un seek sobre carriles terminados (crash).
                    _positionUs.value = 0
                    scheduleRebuild(0)
                } else {
                    wantPlay = false
                    _playing.value = false
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            ErrorLog.log("Preview", error)
            poisoned = true
            if (retries < 2) {
                // Reintento silencioso con reproductor nuevo en la misma posición.
                retries++
                scheduleRebuild(0)
                return
            }
            val chain = generateSequence<Throwable>(error) { it.cause }.take(4).joinToString(" ← ") {
                it.javaClass.simpleName + (it.message?.takeIf { m -> m.isNotBlank() }?.let { m -> "($m)" } ?: "")
            }
            _error.value = error.errorCodeName + ": " + chain
            wantPlay = false
            _playing.value = false
            releasePlayer()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && wantPlay && !rebuildPending) {
                val pos = p.currentPosition * 1000L
                _positionUs.value = pos.coerceIn(0, live.durationUs.coerceAtLeast(0))
                // CompositionPlayer no refresca bien al pasar de vídeo a imagen (o al revés) en reproducción:
                // al cruzar ese límite se reconstruye en la posición actual, igual que un seek en pausa.
                val prev = prevPosUs
                prevPosUs = pos
                // Se adelanta: si en los próximos ~60 ms hay un corte vídeo↔imagen, se salta a él con reproductor nuevo
                // (así el reproductor viejo nunca llega a cruzarlo y no deja restos del clip anterior).
                val b = if (prev >= 0 && pos > prev && pos - prev < 500_000L) boundaries.filter { it > prev && it <= pos + 60_000L }.minOrNull() else null
                if (b != null) {
                    _positionUs.value = b.coerceIn(0, live.durationUs.coerceAtLeast(0))
                    prevPosUs = b
                    quietUntilMs = SystemClock.uptimeMillis() + 900
                    scheduleRebuild(0, quiet = true)
                } else {
                    // Vigilante de atascos: reproduciendo pero sin avanzar durante 2,5 s → reproductor nuevo.
                    val now = SystemClock.uptimeMillis()
                    if (pos != lastTickPosUs) { lastTickPosUs = pos; stallSinceMs = now }
                    else if (now - stallSinceMs > 2500 && retries < 2) { retries++; stallSinceMs = now; scheduleRebuild(0) }
                }
            }
            if (!released) handler.postDelayed(this, 16)
        }
    }

    private val rebuild = Runnable { rebuildNow() }
    private val showBuffering = Runnable { if (rebuildPending || player?.playbackState == Player.STATE_BUFFERING) _buffering.value = true }

    /** Instantes donde cambia el tipo de medio visible (vídeo ↔ imagen/color/hueco): ahí el reproductor deja restos. */
    private fun typeBoundaries(p: Project): List<Long> {
        val vis = p.tracks.filter { !it.hidden && (it.type == TrackType.MAIN || it.type == TrackType.OVERLAY) }
            .flatMap { it.clips }.filter { it.kind == ClipKind.VIDEO || it.kind == ClipKind.IMAGE || it.kind == ClipKind.COLOR }
        val times = (vis.map { it.startUs } + vis.map { it.endUs }).filter { it > 0 }.distinct()
        return times.filter { b ->
            val before = vis.any { it.endUs == b && it.kind == ClipKind.VIDEO }
            val after = vis.any { it.startUs == b && it.kind == ClipKind.VIDEO }
            before != after
        }
    }

    private fun canvasSize(p: Project): Pair<Int, Int> {
        val minSide = minOf(p.width, p.height).coerceAtLeast(2)
        val s = minOf(1f, previewSide.toFloat() / minSide)
        val w = ((p.width * s).toInt() / 2 * 2).coerceAtLeast(2)
        val h = ((p.height * s).toInt() / 2 * 2).coerceAtLeast(2)
        return w to h
    }

    private fun options(p: Project): BuildOptions {
        val (w, h) = canvasSize(p)
        return BuildOptions(w, h, mediaPath, reversedPath)
    }

    /** Superficie de vídeo (TextureView: sigue al layout de Compose). */
    fun attach(s: Surface?, w: Int = 1, h: Int = 1) {
        surface = s
        surfaceSize = Size(w.coerceAtLeast(1), h.coerceAtLeast(1))
        val p = player
        if (p != null) {
            try { if (s != null) p.setVideoSurface(s, surfaceSize) else p.clearVideoSurface() } catch (e: Throwable) { ErrorLog.log("Preview", e) }
        }
        if (s != null && !released && !wantPlay) scheduleRebuild()
    }

    /** Suelta la superficie solo si sigue siendo la actual. */
    fun detach(s: Surface) {
        if (surface === s) attach(null)
    }

    /** Arranca (o reanuda tras volver de segundo plano). */
    fun start() {
        if (!released) return
        released = false
        handler.post(ticker)
        scheduleRebuild(0)
    }

    /** Nuevo estado del proyecto. En reproducción los parámetros se leen en vivo; en pausa se repinta reconstruyendo. */
    fun update(project: Project, immediate: Boolean = false) {
        val structural = CompositionBuilder.structureKey(project, options(project)) != CompositionBuilder.structureKey(live, options(live))
        live = project
        if (released) return
        if (structural || !wantPlay) scheduleRebuild(if (immediate) 0 else if (structural) 180 else 120)
    }

    /** Agrupa reconstrucciones: mientras se arrastra un control solo se reconstruye al parar. */
    private fun scheduleRebuild(delayMs: Long = 120, quiet: Boolean = false) {
        if (released) return
        rebuildPending = true
        // El indicador de carga solo sale si la reconstrucción tarda más de ~0,7 s (las cortas no deben parpadear).
        if (!quiet) {
            quietUntilMs = maxOf(quietUntilMs, SystemClock.uptimeMillis() + 700)
            handler.removeCallbacks(showBuffering)
            handler.postDelayed(showBuffering, 750)
        }
        handler.removeCallbacks(rebuild)
        if (delayMs <= 0) rebuildNow() else handler.postDelayed(rebuild, delayMs)
    }

    private fun rebuildNow() {
        handler.removeCallbacks(rebuild)
        rebuildPending = false
        if (released) return
        val project = live
        releasePlayer()
        try {
            val o = options(project)
            val built = CompositionBuilder.build(app, project, { live }, o)
            val p = CompositionPlayer.Builder(app)
                .setVideoGraphFactory(MultipleInputVideoGraph.Factory(CompositionBuilder.frameProcessorFactory()))
                .build()
            p.addListener(listener)
            surface?.let { p.setVideoSurface(it, surfaceSize) }
            player = p
            poisoned = false
            lastTickPosUs = -1L
            prevPosUs = -1L
            boundaries = typeBoundaries(project)
            stallSinceMs = SystemClock.uptimeMillis()
            val posMs = (_positionUs.value / 1000L).coerceIn(0, (built.durationUs / 1000L - 1).coerceAtLeast(0))
            p.setComposition(built.composition, posMs)
            p.prepare()
            if (scrubbing) p.setScrubbingModeEnabled(true)
            p.playWhenReady = wantPlay
            _error.value = null
        } catch (e: Throwable) {
            ErrorLog.log("Preview", e)
            _error.value = e.message ?: e.javaClass.simpleName
            _buffering.value = false
            releasePlayer()
        }
    }

    fun play() {
        if (live.durationUs <= 0) return
        if (poisoned || player == null || _positionUs.value >= live.durationUs - 30_000L) {
            if (_positionUs.value >= live.durationUs - 30_000L) _positionUs.value = 0
            wantPlay = true
            _playing.value = true
            scheduleRebuild(0)
            return
        }
        wantPlay = true
        _playing.value = true
        player?.play()
    }

    /** Salta a [us] y reproduce (vista previa de transiciones, etc.). */
    fun playFrom(us: Long) {
        _positionUs.value = us.coerceIn(0, live.durationUs.coerceAtLeast(0))
        wantPlay = true
        _playing.value = true
        scheduleRebuild(0)
    }

    fun pause() {
        wantPlay = false
        _playing.value = false
        player?.let {
            try { it.pause(); _positionUs.value = (it.currentPosition * 1000L).coerceIn(0, live.durationUs.coerceAtLeast(0)) } catch (e: Throwable) { ErrorLog.log("Preview", e) }
        }
    }

    fun toggle() = if (wantPlay) pause() else play()

    fun seekTo(us: Long) {
        val t = us.coerceIn(0, live.durationUs.coerceAtLeast(0))
        _positionUs.value = t
        val p = player
        if (p == null || poisoned || rebuildPending) { scheduleRebuild(if (scrubbing) 120 else 0); return }
        try { p.seekTo(t / 1000L) } catch (e: Throwable) { ErrorLog.log("Preview", e); scheduleRebuild(0) }
    }

    fun stepFrames(n: Int) {
        if (wantPlay) pause()
        seekTo(_positionUs.value + n * live.frameUs)
    }

    fun setScrubbing(on: Boolean) {
        if (scrubbing == on) return
        scrubbing = on
        if (on && wantPlay) pause()
        try { player?.setScrubbingModeEnabled(on) } catch (e: Throwable) { ErrorLog.log("Preview", e) }
        // Al soltar, un repintado limpio en la posición final (el seek en pausa no siempre pinta el último fotograma).
        if (!on) scheduleRebuild(150)
    }

    fun setLoop(on: Boolean) { loop = on }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        try {
            p.removeListener(listener)
            p.release()
        } catch (e: Throwable) {
            ErrorLog.log("Preview", e)
        }
    }

    /** Libera decodificadores y GL (al salir del editor o pasar a segundo plano). */
    fun release() {
        if (released) return
        if (wantPlay) pause()
        released = true
        rebuildPending = false
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
    }
}
