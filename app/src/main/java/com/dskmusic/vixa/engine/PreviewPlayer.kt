package com.dskmusic.vixa.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.effect.MultipleInputVideoGraph
import androidx.media3.effect.SingleInputVideoGraph
import androidx.media3.transformer.CompositionPlayer
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.Project
import kotlin.math.abs
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
 *
 * Cambio de clip (medido en MediaTek/Android 16): con UNA entrada (SingleInputVideoGraph) el cambio natural funciona;
 * con el compositor de varias entradas el siguiente clip de vídeo no arranca (androidx/media #3533, #3453). Por eso:
 *  - Proyecto sin capas: cada tramo de la pista principal entre transiciones se reproduce con una entrada ([Seg.run] >= 0)
 *    y el compositor solo vive DURANTE cada transición con vídeo, sin cruzar nunca un corte de vídeo.
 *  - Los pasos entre reproductores son un RELEVO: el siguiente ([Pre]) se prepara ~1,5 s antes, en pausa, en OTRA superficie
 *    (hay [SLOTS] TextureView apiladas; nunca se comparte superficie, que era lo que daba EGL 0x3003). Poco antes del corte
 *    se arranca (tarda ~60-190 ms en echar a andar) y, cuando su reloj se mueve, su vista pasa al frente.
 *  - Si el tramo siguiente es corto (una transición de 0,5 s), el de DESPUÉS se prepara también por adelantado ([after]):
 *    un reproductor tarda ~0,3-0,45 s en estar listo y dentro de la transición no daría tiempo.
 *  - Si el relevo no llega a tiempo se espera un instante parado en el corte y, en último caso, se reconstruye ahí (~0,3 s).
 * Los vigilantes del ticker (buffering largo, vídeo congelado, avance lento, posición parada) recuperan los atascos restantes.
 */
class PreviewPlayer(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    /** Tramo de reproducción: [run] >= 0 → una entrada con ese tramo de la pista principal; -1 → composición completa. */
    private class Seg(val startUs: Long, val endUs: Long, val run: Int)

    /** Reproductor de relevo: preparado en pausa en [atUs] sobre la superficie [slot]. Es su propia escucha mientras espera. */
    private inner class Pre(val atUs: Long, val slot: Int) : Player.Listener {
        lateinit var p: CompositionPlayer
        var run = -1
        var lo = 0L
        var hi = Long.MAX_VALUE
        var ready = false
        var painted = false
        /** Instante en que se le dio play() (0 = aún en pausa). */
        var startedMs = 0L

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) ready = true
        }

        override fun onPlayerError(error: PlaybackException) {
            ErrorLog.log("Preview", error)
            Log.d(TAG, "relevo: el reproductor preparado para ${atUs / 1000}ms falló (${error.errorCodeName})")
            if (this === after) { dropAfter(); return }
            if (this !== next) return
            // Si el actual ya estaba esperándole (o él ya sonaba), se reconstruye en el corte.
            val needed = startedMs != 0L || waitSinceMs != 0L
            dropNext()
            if (needed && wantPlay) {
                _positionUs.value = atUs.coerceIn(0, live.durationUs.coerceAtLeast(0))
                scheduleRebuild(0, quiet = true, why = "relay-error")
            }
        }
    }

    private val surfaces = arrayOfNulls<Surface>(SLOTS)
    private val sizes = Array(SLOTS) { Size(1, 1) }
    /** Hasta este instante la superficie puede seguir conectada al reproductor que se acaba de liberar. */
    private val slotFreeAt = LongArray(SLOTS)
    private var front = 0

    private var player: CompositionPlayer? = null
    private var curRun = -1
    /** Rango en el que el reproductor actual muestra contenido válido (uno de un tramo solo vale dentro de él). */
    private var curLo = 0L
    private var curHi = Long.MAX_VALUE

    /** Relevo del próximo corte y, si el tramo que abre es corto, el del corte siguiente. */
    private var next: Pre? = null
    private var after: Pre? = null
    private var nextTryAtMs = 0L
    private var afterTryAtMs = 0L
    /** El actual está parado en el corte esperando a que [next] esté listo (0 = no). */
    private var waitSinceMs = 0L
    /** Cuánto antes del corte se arranca el de relevo: media móvil de lo que tarda en echar a andar. */
    private var leadUs = 150_000L
    private var rebuiltAtMs = 0L

    /**
     * Audio aparte: un reproductor solo de audio que suena continuo, sin relevos. Los de vídeo van sin audio. Así el relevo
     * (que salta/solapa 0-100 ms) ya no se oye. Si algo va mal en el audio, poner false: vuelve al audio dentro de cada reproductor.
     */
    private val SPLIT_AUDIO = true
    private var audio: CompositionPlayer? = null
    private var audioDirty = true

    /** Reproductor saliente de un relevo: se libera un instante después para no bloquear el hilo principal justo en el corte. */
    private var dying: CompositionPlayer? = null
    private var dyingSlot = -1

    private var segs: List<Seg> = listOf(Seg(0, Long.MAX_VALUE, -1))
    /** Cortes que un reproductor de composición completa no debe cruzar reproduciendo. */
    private var cuts: List<Long> = emptyList()

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
    private val _frontSlot = MutableStateFlow(0)
    /** Qué superficie (TextureView) debe estar al frente. */
    val frontSlot: StateFlow<Int> = _frontSlot

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            bufferingSinceMs = if (playbackState == Player.STATE_BUFFERING) (if (bufferingSinceMs == 0L) SystemClock.uptimeMillis() else bufferingSinceMs) else 0L
            _buffering.value = (playbackState == Player.STATE_BUFFERING || rebuildPending) && SystemClock.uptimeMillis() > quietUntilMs
            if (playbackState == Player.STATE_READY) retries = 0
            if (playbackState == Player.STATE_ENDED) {
                poisoned = true
                if (loop && wantPlay) {
                    // Bucle manual: el REPEAT_MODE del reproductor haría un seek sobre carriles terminados (crash).
                    _positionUs.value = 0
                    scheduleRebuild(0, why = "loop")
                } else {
                    wantPlay = false
                    _playing.value = false
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            ErrorLog.log("Preview", error)
            poisoned = true
            if (retries < 4) {
                // Reintento silencioso con reproductor nuevo en la misma posición. Con un pequeño retardo
                // y creciente para dar tiempo a que el anterior libere la superficie EGL.
                retries++
                scheduleRebuild(150L * retries, quiet = true, why = "error-retry")
                return
            }
            val chain = generateSequence<Throwable>(error) { it.cause }.take(4).joinToString(" ← ") {
                it.javaClass.simpleName + (it.message?.takeIf { m -> m.isNotBlank() }?.let { m -> "($m)" } ?: "")
            }
            _error.value = error.errorCodeName + ": " + chain
            wantPlay = false
            _playing.value = false
            try { audio?.pause() } catch (_: Throwable) {}
            releasePlayer()
        }
    }

    private val audioListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) audioDirty = true
        }

        override fun onPlayerError(error: PlaybackException) {
            ErrorLog.log("Preview", error)
            Log.d(TAG, "audio aparte: error (${error.errorCodeName}), se reconstruye")
            audioDirty = true
            handler.post {
                releaseAudio()
                if (wantPlay && !released && retries < 4) { retries++; scheduleRebuild(0, quiet = true, why = "audio-error") }
            }
        }
    }

    private fun audioPosUs(): Long? = audio?.let { try { it.currentPosition * 1000L } catch (_: Throwable) { null } }

    private fun releaseAudio() {
        val a = audio ?: return
        audio = null
        a.removeListener(audioListener)
        kill(a)
    }

    private fun buildAudio(project: Project, posUs: Long) {
        releaseAudio()
        val (w, h) = canvasSize(project)
        val built = CompositionBuilder.build(app, project, { live }, BuildOptions(w, h, mediaPath, reversedPath, includeVideo = false))
        val a = CompositionPlayer.Builder(app).build()
        try {
            a.addListener(audioListener)
            a.setComposition(built.composition, (posUs / 1000L).coerceIn(0, (built.durationUs / 1000L - 1).coerceAtLeast(0)))
            a.prepare()
            a.playWhenReady = wantPlay
        } catch (e: Throwable) {
            a.removeListener(audioListener)
            kill(a)
            throw e
        }
        audio = a
        audioDirty = false
    }

    /** Deja el audio donde toca: lo (re)crea si cambió el proyecto; si no, solo lo recoloca si se aleja de [posUs]. */
    private fun syncAudio(project: Project, posUs: Long) {
        try {
            val a = audio
            if (a == null || audioDirty) { buildAudio(project, posUs); return }
            if (abs(a.currentPosition * 1000L - posUs) > 150_000L) a.seekTo(posUs / 1000L)
            a.playWhenReady = wantPlay
        } catch (e: Throwable) {
            ErrorLog.log("Preview", e)
            releaseAudio()
            audioDirty = true
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            val bs = bufferingSinceMs
            if (p != null && wantPlay && !rebuildPending && bs > 0 && SystemClock.uptimeMillis() - bs > 1500 && retries < 4) {
                // Atascado en "cargando" con el sonido avanzando (el compositor espera un fotograma que no llega).
                retries++
                bufferingSinceMs = 0
                Log.d(TAG, "BUFFERING >1,5 s en pos=${_positionUs.value / 1000}ms: reconstruyendo")
                scheduleRebuild(0, quiet = true, why = "buffering>1.5s")
                if (!released) handler.postDelayed(this, 16)
                return
            }
            if (p != null && wantPlay && !rebuildPending) {
                val vpos = p.currentPosition * 1000L
                val apos = audioPosUs()
                // Con audio aparte, el reloj que se enseña es el del audio (continuo, sin saltos en los relevos). Para no cruzar
                // un corte vale el que vaya por delante.
                val pos = if (apos != null) maxOf(apos, vpos) else vpos
                _positionUs.value = (apos ?: vpos).coerceIn(0, live.durationUs.coerceAtLeast(0))
                val prev = prevPosUs
                prevPosUs = pos
                // Corte que este reproductor no debe cruzar, si cae en los próximos ~30 ms.
                val end = nextEnd(prev)
                val b = if (prev >= 0 && pos > prev && pos - prev < 500_000L && end <= pos + 30_000L) end else null
                val now = SystemClock.uptimeMillis()
                val n = next
                if (n != null && n.startedMs != 0L) {
                    // Relevo en curso: el siguiente ya está arrancando. En cuanto su reloj se mueve, toma el mando.
                    val moving = try { n.p.currentPosition * 1000L > n.atUs } catch (t: Throwable) { false }
                    if (moving) {
                        val lat = (now - n.startedMs) * 1000L
                        // Media móvil de lo que tarda en arrancar (medido 47-229 ms). Si el adelanto se pasa, el relevo SALTA contenido
                        // (se nota como un acelerón: medido ~100 ms); si se queda corto, la imagen se congela. La media equilibra ambos.
                        // No volver a sesgarlo hacia arriba (maxOf): causaba saltos de ~100 ms en los cortes.
                        leadUs = ((leadUs + lat) / 2).coerceIn(30_000L, 250_000L)
                        Log.d(TAG, "relevo: arranque en ${lat / 1000}ms (viejo en ${pos / 1000}ms); próximo adelanto ${leadUs / 1000}ms")
                        relay(n)
                    } else if (now - n.startedMs > 600) {
                        Log.d(TAG, "relevo: el siguiente no arranca en 600 ms: reconstruyendo")
                        cutRebuild(n.atUs, now, "relay-timeout")
                    } else if (b != null) {
                        // El viejo llegó al corte antes de que el nuevo arrancase: espera parado en su último fotograma.
                        try { p.pause() } catch (t: Throwable) { ErrorLog.log("Preview", t) }
                    }
                } else if (n != null && waitSinceMs != 0L) {
                    // Parado en el corte esperando a que el de relevo termine de prepararse.
                    if (n.ready && n.painted) {
                        try { n.p.play(); n.startedMs = now } catch (t: Throwable) { ErrorLog.log("Preview", t) }
                    } else if (now - waitSinceMs > 350) {
                        Log.d(TAG, "relevo NO listo tras esperar en ${n.atUs / 1000}ms (ready=${n.ready} pintado=${n.painted}): reconstruyendo")
                        cutRebuild(n.atUs, now, "boundary-wait")
                    }
                } else if (b != null) {
                    if (n != null && n.atUs == b) {
                        // El viejo se para en el corte. Si el de relevo está listo se arranca ya; si no, se le espera un poco
                        // (más barato y más seguro que reconstruir: una reconstrucción son ~0,3 s y a veces se atasca).
                        try { p.pause() } catch (t: Throwable) { ErrorLog.log("Preview", t) }
                        if (n.ready && n.painted) {
                            try { n.p.play(); n.startedMs = now } catch (t: Throwable) { ErrorLog.log("Preview", t) }
                        } else waitSinceMs = now
                    } else {
                        Log.d(TAG, "sin relevo en ${b / 1000}ms: reconstruyendo")
                        cutRebuild(b, now, "boundary")
                    }
                } else {
                    // Relevo: al acercarse un corte se prepara el reproductor siguiente en otra superficie. No mientras el actual
                    // aún está arrancando tras una reconstrucción (medido: crear otro en ese momento lo deja sin fotogramas).
                    val up = nextEnd(pos)
                    var nx = n
                    if (nx != null && nx.atUs != up) { dropNext(); nx = null }
                    val settled = now - rebuiltAtMs > 400 || frameCount >= 3
                    if (nx == null && up != Long.MAX_VALUE && up - pos < 1_500_000L && now >= nextTryAtMs && settled) {
                        nextTryAtMs = now + 400
                        nx = prepare(up)
                        next = nx
                    }
                    if (nx != null && nx.ready && nx.painted) {
                        // Si el tramo que abre el relevo es corto, se prepara ya también el de su final.
                        if (after == null && now >= afterTryAtMs) {
                            val e2 = endAfter(nx.run, nx.hi, nx.atUs)
                            if (e2 != Long.MAX_VALUE && e2 - nx.atUs < 1_200_000L) {
                                afterTryAtMs = now + 400
                                after = prepare(e2)
                            }
                        }
                        // Arranque adelantado: un reproductor en pausa tarda en echar a andar tras play(). Se arranca ese
                        // tiempo antes del corte (se va ajustando con lo medido) para que coja el corte ya en marcha.
                        if (up - pos <= leadUs) {
                            try { nx.p.play(); nx.startedMs = now } catch (t: Throwable) { ErrorLog.log("Preview", t) }
                        }
                    }
                    // Vigilante de atascos: reproduciendo pero sin avanzar durante 2,5 s → reproductor nuevo.
                    if (pos != lastTickPosUs) { lastTickPosUs = pos; stallSinceMs = now }
                    else if (now - stallSinceMs > 2500 && retries < 2) { retries++; stallSinceMs = now; scheduleRebuild(0, why = "stall-pos") }
                    // ponytail: vigilante "stall-frames" ELIMINADO: una foto/título estático no genera fotogramas nuevos y
                    // reconstruía el reproductor en cada uno (era el parón). No volver a ponerlo sin distinguir contenido estático.
                    // Vídeo congelado con el reloj avanzando: SOLO cuando hay un clip de VÍDEO visible (un vídeo da fotogramas
                    // continuos; una foto o un título estático no, y ahí no debe actuar). Sin fotogramas durante 0,9 s → reconstruir.
                    if (videoVisibleAt(pos)) {
                        if (videoSince == 0L) videoSince = now
                        if (freezeTries < 3 && now - maxOf(videoSince, lastFrameMs) > 900) {
                            freezeTries++
                            videoSince = 0L
                            Log.d(TAG, "vídeo congelado: sin fotogramas 0,9 s en pos=${pos / 1000}ms (intento $freezeTries)")
                            scheduleRebuild(0, quiet = true, why = "video-frozen")
                        }
                    } else videoSince = 0L
                    // Vigilante de AVANCE: si en 1 s el reloj avanza menos de 0,3 s (congelado o "arrastrándose"), reproductor nuevo.
                    if (now >= slowCheckAt) {
                        val ref = slowRefPos
                        slowRefPos = pos
                        slowCheckAt = now + 1000
                        if (ref >= 0 && pos - ref < 300_000L && retries < 3) {
                            retries++
                            Log.d(TAG, "avance lento: ${(pos - ref) / 1000}ms en 1 s en pos=${pos / 1000}ms")
                            scheduleRebuild(0, quiet = true, why = "slow-advance")
                        }
                    }
                }
            }
            if (!released) handler.postDelayed(this, 16)
        }
    }

    /** Último recurso en un corte: reproductor nuevo justo en [b], sin indicador de carga. */
    private fun cutRebuild(b: Long, now: Long, why: String) {
        // El audio sigue sonando sin interrupción: el vídeo se reconstruye donde está el audio.
        _positionUs.value = (audioPosUs() ?: b).coerceIn(0, live.durationUs.coerceAtLeast(0))
        prevPosUs = b
        quietUntilMs = now + 900
        scheduleRebuild(0, quiet = true, why = why)
    }

    private var bufferingSinceMs = 0L
    private var videoSince = 0L
    private var freezeTries = 0

    /** ¿Hay un clip de vídeo visible (pista principal u overlay) en [us]? Ahí deben llegar fotogramas continuamente. */
    private fun videoVisibleAt(us: Long): Boolean = live.tracks.any { t ->
        !t.hidden && (t.type == TrackType.MAIN || t.type == TrackType.OVERLAY) &&
            t.clips.any { it.kind == ClipKind.VIDEO && us >= it.startUs && us < it.endUs - 100_000L }
    }

    private var slowCheckAt = 0L
    private var slowRefPos = -1L

    /** Reinicia la ventana del vigilante de avance, con un margen para que un reproductor recién creado arranque. */
    private fun resetSlow(graceMs: Long) { slowRefPos = -1L; slowCheckAt = SystemClock.uptimeMillis() + graceMs }
    private var frameCount = 0
    private var lastFrameMs = 0L

    /** Pone a cero los vigilantes para un reproductor que acaba de empezar (reconstrucción o relevo). */
    private fun resetWatch() {
        val now = SystemClock.uptimeMillis()
        frameCount = 0
        videoSince = 0L
        resetSlow(2000)
        bufferingSinceMs = 0
        lastFrameMs = now
        lastTickPosUs = -1L
        stallSinceMs = now
    }

    /** Lo llama cada TextureView cuando pinta un fotograma nuevo (hilo principal). */
    fun onFrame(slot: Int = 0) {
        if (slot == front) { frameCount++; lastFrameMs = SystemClock.uptimeMillis(); if (frameCount >= 8) freezeTries = 0 }
        next?.let { if (it.slot == slot) it.painted = true }
        after?.let { if (it.slot == slot) it.painted = true }
    }

    private val rebuild = Runnable { rebuildNow() }
    private val reaper = Runnable { reap() }
    private val showBuffering = Runnable {
        if (rebuildPending || player?.playbackState == Player.STATE_BUFFERING) {
            Log.d(TAG, "spinner ON: rebuildPending=$rebuildPending estado=${player?.playbackState} pos=${_positionUs.value / 1000}ms")
            _buffering.value = true
        }
    }

    /** Instantes donde un vídeo empieza o acaba (incluido vídeo→vídeo). Se excluye el final del proyecto. */
    private fun typeBoundaries(p: Project): List<Long> {
        val vis = p.tracks.filter { !it.hidden && (it.type == TrackType.MAIN || it.type == TrackType.OVERLAY) }
            .flatMap { it.clips }.filter { it.kind == ClipKind.VIDEO || it.kind == ClipKind.IMAGE || it.kind == ClipKind.COLOR }
        val times = (vis.map { it.startUs } + vis.map { it.endUs }).filter { it > 0 }.distinct()
        return times.filter { b ->
            val before = vis.any { it.endUs == b && it.kind == ClipKind.VIDEO }
            val after = vis.any { it.startUs == b && it.kind == ClipKind.VIDEO }
            (before || after) && b < p.durationUs - 100_000L
        }
    }

    /**
     * Reparte el proyecto en tramos. Sin capas y con una sola pista principal: una entrada entre transiciones y composición
     * completa solo en cada transición. En cualquier otro caso, todo con la composición completa (y relevo en cada corte de vídeo).
     * La cuenta de tramos debe coincidir con la de CompositionBuilder (`runs`).
     */
    private fun plan(p: Project) {
        val vis = p.tracks.filter { !it.hidden }
        val overlay = vis.any { it.type == TrackType.OVERLAY && it.clips.isNotEmpty() }
        val clips = vis.filter { it.type == TrackType.MAIN }.singleOrNull()?.clips.orEmpty()
        val out = ArrayList<Seg>()
        if (!overlay && clips.isNotEmpty()) {
            var cursor = 0L
            var run = 0
            for (i in 1 until clips.size) {
                val c = clips[i]
                if (c.transitionIn == null) continue
                val end = clips[i - 1].endUs
                if (c.startUs > cursor) out.add(Seg(cursor, c.startUs, run))
                if (end > c.startUs) out.add(Seg(c.startUs, end, -1))
                run++
                cursor = maxOf(end, c.startUs)
            }
            out.add(Seg(cursor, Long.MAX_VALUE, run))
        } else out.add(Seg(0, Long.MAX_VALUE, -1))
        // El compositor SÍ cruza bien los cortes donde no empieza ni acaba un vídeo (p. ej. transiciones entre imágenes):
        // ahí no hace falta relevo. Un tramo sin cortes de vídeo dentro y con algún borde así pasa a composición completa,
        // y los tramos de composición completa contiguos se funden por esos bordes.
        val hard = typeBoundaries(p).toSet()
        val conv = out.map { s ->
            val softEdge = (s.startUs > 0 && s.startUs !in hard) || (s.endUs != Long.MAX_VALUE && s.endUs !in hard)
            if (s.run >= 0 && softEdge && hard.none { it > s.startUs && it < s.endUs }) Seg(s.startUs, s.endUs, -1) else s
        }
        val merged = ArrayList<Seg>()
        for (s in conv) {
            val l = merged.lastOrNull()
            if (l != null && l.run < 0 && s.run < 0 && s.startUs !in hard) merged[merged.lastIndex] = Seg(l.startUs, s.endUs, -1)
            else merged.add(s)
        }
        segs = merged
        cuts = hard.sorted()
    }

    /**
     * Tramo para un reproductor en [us]. En pausa, si hay varios tramos, se usa la composición completa: vale para buscar
     * por todo el proyecto; al reproducir aguanta hasta el siguiente corte y ahí releva al reproductor del tramo.
     */
    private fun segFor(us: Long, playing: Boolean): Seg =
        if (segs.size > 1 && !playing) Seg(0, Long.MAX_VALUE, -1) else segs.firstOrNull { us < it.endUs } ?: segs.last()

    /** Siguiente instante (> [afterUs]) que un reproductor de tipo [run] (válido hasta [hi]) no debe cruzar, o MAX si no hay. */
    private fun endAfter(run: Int, hi: Long, afterUs: Long): Long {
        val e = if (run >= 0) hi else cuts.firstOrNull { it > afterUs } ?: Long.MAX_VALUE
        return if (e > afterUs && e < live.durationUs - 100_000L) e else Long.MAX_VALUE
    }

    private fun nextEnd(afterUs: Long): Long = endAfter(curRun, curHi, afterUs)

    private fun canvasSize(p: Project): Pair<Int, Int> {
        val minSide = minOf(p.width, p.height).coerceAtLeast(2)
        val s = minOf(1f, previewSide.toFloat() / minSide)
        val w = ((p.width * s).toInt() / 2 * 2).coerceAtLeast(2)
        val h = ((p.height * s).toInt() / 2 * 2).coerceAtLeast(2)
        return w to h
    }

    private fun options(p: Project, run: Int = -1, withAudio: Boolean = true): BuildOptions {
        val (w, h) = canvasSize(p)
        return BuildOptions(w, h, mediaPath, reversedPath, includeAudio = withAudio, soloRun = run)
    }

    /** Superficie de vídeo [slot] (TextureView: sigue al layout de Compose). Con una sola (slot 0) no hay relevos. */
    fun attach(s: Surface?, w: Int = 1, h: Int = 1, slot: Int = 0) {
        surfaces[slot] = s
        sizes[slot] = Size(w.coerceAtLeast(1), h.coerceAtLeast(1))
        if (next?.slot == slot || after?.slot == slot) {
            if (s == null) { if (next?.slot == slot) dropNext() else dropAfter() }
            else try { (if (next?.slot == slot) next else after)?.p?.setVideoSurface(s, sizes[slot]) } catch (e: Throwable) { ErrorLog.log("Preview", e) }
        }
        if (slot != front) return
        val p = player
        if (p != null) {
            try { if (s != null) p.setVideoSurface(s, sizes[slot]) else p.clearVideoSurface() } catch (e: Throwable) { ErrorLog.log("Preview", e) }
        }
        if (s != null && !released && !wantPlay) scheduleRebuild(why = "attach")
    }

    /** Suelta la superficie solo si sigue siendo la actual de ese [slot]. */
    fun detach(s: Surface, slot: Int = 0) {
        if (surfaces[slot] === s) attach(null, slot = slot)
    }

    /** Arranca (o reanuda tras volver de segundo plano). */
    fun start() {
        if (!released) return
        released = false
        handler.post(ticker)
        scheduleRebuild(0, why = "start")
    }

    /** Nuevo estado del proyecto. En reproducción los parámetros se leen en vivo; en pausa se repinta reconstruyendo. */
    fun update(project: Project, immediate: Boolean = false) {
        val structural = CompositionBuilder.structureKey(project, options(project)) != CompositionBuilder.structureKey(live, options(live))
        live = project
        if (released) return
        if (structural) audioDirty = true
        if (structural || !wantPlay) scheduleRebuild(if (immediate) 0 else if (structural) 180 else 120, why = "update structural=$structural wantPlay=$wantPlay")
    }

    /** Agrupa reconstrucciones: mientras se arrastra un control solo se reconstruye al parar. */
    private var lastWhy = ""

    private fun scheduleRebuild(delayMs: Long = 120, quiet: Boolean = false, why: String = "?") {
        if (released) return
        lastWhy = why
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

    /** Crea un reproductor para [seg] en [posUs] sobre la superficie [slot]. Devuelve también si quedó de una sola entrada. */
    private fun create(project: Project, posUs: Long, seg: Seg, slot: Int, l: Player.Listener, play: Boolean): Pair<CompositionPlayer, Boolean> {
        val built = CompositionBuilder.build(app, project, { live }, options(project, seg.run, withAudio = !SPLIT_AUDIO))
        val f = CompositionBuilder.frameProcessorFactory()
        val p = CompositionPlayer.Builder(app)
            .setVideoGraphFactory(if (built.singleInput) SingleInputVideoGraph.Factory(f) else MultipleInputVideoGraph.Factory(f))
            .build()
        try {
            p.addListener(l)
            surfaces[slot]?.let { p.setVideoSurface(it, sizes[slot]) }
            val posMs = (posUs / 1000L).coerceIn(0, (built.durationUs / 1000L - 1).coerceAtLeast(0))
            p.setComposition(built.composition, posMs)
            p.prepare()
            p.playWhenReady = play
        } catch (e: Throwable) {
            p.removeListener(l)
            kill(p)
            throw e
        }
        return p to built.singleInput
    }

    private fun rebuildNow() {
        handler.removeCallbacks(rebuild)
        rebuildPending = false
        if (released) return
        val project = live
        resetWatch()
        releasePlayer()
        try {
            plan(project)
            var pos = _positionUs.value
            var seg = segFor(pos, wantPlay)
            if (wantPlay) {
                // Arrancar reproduciendo a un suspiro de un corte no funciona (medido: el reproductor se queda en "cargando"
                // sin dar fotogramas): se empieza ya en el corte, con el reproductor que toca al otro lado.
                val e = endAfter(seg.run, seg.endUs, pos)
                if (e != Long.MAX_VALUE && e - pos < 120_000L) {
                    pos = e
                    _positionUs.value = e
                    seg = segFor(e, true)
                }
            }
            Log.d(TAG, "rebuild pos=${pos / 1000}ms play=$wantPlay scrub=$scrubbing tramo=${seg.run} why=$lastWhy")
            if (SPLIT_AUDIO) syncAudio(project, pos)
            val (p, single) = create(project, pos, seg, front, listener, wantPlay)
            player = p
            curRun = if (single) seg.run else -1
            curLo = if (single) seg.startUs else 0L
            curHi = if (single) seg.endUs else Long.MAX_VALUE
            poisoned = false
            prevPosUs = -1L
            rebuiltAtMs = SystemClock.uptimeMillis()
            if (scrubbing) p.setScrubbingModeEnabled(true)
            _error.value = null
        } catch (e: Throwable) {
            ErrorLog.log("Preview", e)
            _error.value = e.message ?: e.javaClass.simpleName
            _buffering.value = false
            releasePlayer()
        }
    }

    /** Prepara en pausa, en una superficie libre, el reproductor que tomará el relevo en [b]. Null si no se puede ahora. */
    private fun prepare(b: Long): Pre? {
        val t0 = SystemClock.uptimeMillis()
        val slot = (0 until SLOTS).firstOrNull {
            it != front && it != dyingSlot && it != next?.slot && it != after?.slot && surfaces[it] != null && t0 >= slotFreeAt[it]
        } ?: return null
        return try {
            val seg = segFor(b, true)
            val pre = Pre(b, slot)
            val (p, single) = create(live, b, seg, slot, pre, false)
            pre.p = p
            pre.run = if (single) seg.run else -1
            pre.lo = if (single) seg.startUs else 0L
            pre.hi = if (single) seg.endUs else Long.MAX_VALUE
            Log.d(TAG, "relevo preparado para ${b / 1000}ms en superficie $slot (tramo ${pre.run}) en ${SystemClock.uptimeMillis() - t0}ms de hilo principal")
            pre
        } catch (e: Throwable) {
            ErrorLog.log("Preview", e)
            null
        }
    }

    /** Relevo: [n] (ya arrancado) pasa a ser el reproductor actual, su vista va al frente y el anterior se jubila. */
    private fun relay(n: Pre) {
        reap()
        val old = player
        if (old != null) {
            old.removeListener(listener)
            try { old.pause() } catch (e: Throwable) { ErrorLog.log("Preview", e) }
            dying = old
            dyingSlot = front
            // Se libera un instante después: release() bloquea el hilo principal y aquí retrasaría el cambio de vista.
            handler.postDelayed(reaper, 60)
        }
        n.p.removeListener(n)
        n.p.addListener(listener)
        player = n.p
        front = n.slot
        _frontSlot.value = front
        curRun = n.run
        curLo = n.lo
        curHi = n.hi
        next = after
        after = null
        waitSinceMs = 0L
        nextTryAtMs = 0L
        prevPosUs = -1L
        poisoned = false
        resetWatch()
        try { n.p.play() } catch (e: Throwable) { ErrorLog.log("Preview", e); scheduleRebuild(0, quiet = true, why = "relay-exception") }
        Log.d(TAG, "relevo en ${n.atUs / 1000}ms → superficie $front (tramo $curRun)")
    }

    fun play() {
        if (live.durationUs <= 0) return
        // Reanudar a un suspiro de un corte no da tiempo al relevo: se reconstruye (y rebuildNow empieza ya en el corte).
        val nearCut = nextEnd(_positionUs.value).let { it != Long.MAX_VALUE && it - _positionUs.value < 120_000L }
        if (poisoned || player == null || nearCut || _positionUs.value >= live.durationUs - 30_000L) {
            if (_positionUs.value >= live.durationUs - 30_000L) _positionUs.value = 0
            wantPlay = true
            _playing.value = true
            scheduleRebuild(0, why = "play poisoned=$poisoned")
            return
        }
        wantPlay = true
        _playing.value = true
        resetSlow(1500)
        player?.play()
        audio?.play()
    }

    /** Salta a [us] y reproduce (vista previa de transiciones, etc.). */
    fun playFrom(us: Long) {
        _positionUs.value = us.coerceIn(0, live.durationUs.coerceAtLeast(0))
        wantPlay = true
        _playing.value = true
        scheduleRebuild(0, why = "playFrom")
    }

    /** Un relevo a medias (el siguiente ya suena, o el actual está parado esperándole) no sobrevive a una pausa o un salto. */
    private fun cancelRelay() {
        if (waitSinceMs != 0L || (next?.startedMs ?: 0L) != 0L) dropNext()
    }

    fun pause() {
        wantPlay = false
        _playing.value = false
        cancelRelay()
        val apos = audioPosUs()
        try { audio?.pause() } catch (e: Throwable) { ErrorLog.log("Preview", e) }
        player?.let {
            try { it.pause(); _positionUs.value = (apos ?: (it.currentPosition * 1000L)).coerceIn(0, live.durationUs.coerceAtLeast(0)) } catch (e: Throwable) { ErrorLog.log("Preview", e) }
        }
    }

    fun toggle() = if (wantPlay) pause() else play()

    fun seekTo(us: Long) {
        val t = us.coerceIn(0, live.durationUs.coerceAtLeast(0))
        _positionUs.value = t
        resetSlow(1500)
        cancelRelay()
        val p = player
        // Un reproductor de un solo tramo no tiene contenido fuera de él: fuera de su rango se reconstruye.
        // Y saltar reproduciendo con el compositor de varias entradas lo deja sin fotogramas (medido: 0,9 s congelado hasta
        // que actúa el vigilante): ahí también es más corto reconstruir.
        if (p == null || poisoned || rebuildPending || t < curLo || t >= curHi || (wantPlay && curRun < 0)) {
            scheduleRebuild(if (scrubbing) 120 else 0, why = "seek player=${p != null} poisoned=$poisoned pending=$rebuildPending tramo=$curRun")
            return
        }
        try { if (!scrubbing) audio?.seekTo(t / 1000L); p.seekTo(t / 1000L); if (wantPlay) p.play() } catch (e: Throwable) { ErrorLog.log("Preview", e); scheduleRebuild(0, why = "seek-exception") }
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
        if (!on) scheduleRebuild(150, why = "scrub-end")
    }

    fun setLoop(on: Boolean) { loop = on }

    private fun kill(p: CompositionPlayer) {
        try {
            // Soltar la superficie antes de liberar: si otro reproductor la pide mientras este aún la tiene
            // conectada, EGL falla con 0x3003 (BAD_ALLOC) al crear la superficie.
            try { p.clearVideoSurface() } catch (_: Throwable) {}
            p.release()
        } catch (e: Throwable) {
            ErrorLog.log("Preview", e)
        }
    }

    /** Libera el reproductor saliente del último relevo. */
    private fun reap() {
        handler.removeCallbacks(reaper)
        val d = dying ?: return
        dying = null
        kill(d)
        if (dyingSlot >= 0) slotFreeAt[dyingSlot] = SystemClock.uptimeMillis() + 300
        dyingSlot = -1
    }

    private fun dropPre(pre: Pre) {
        pre.p.removeListener(pre)
        kill(pre.p)
        slotFreeAt[pre.slot] = SystemClock.uptimeMillis() + 300
    }

    private fun dropAfter() {
        val a = after ?: return
        after = null
        dropPre(a)
    }

    /** Descarta los reproductores de relevo (el de después depende del siguiente). */
    private fun dropNext() {
        dropAfter()
        waitSinceMs = 0L
        val n = next ?: return
        next = null
        dropPre(n)
    }

    private fun releasePlayer() {
        reap()
        dropNext()
        val p = player ?: return
        player = null
        p.removeListener(listener)
        kill(p)
    }

    /** Libera decodificadores y GL (al salir del editor o pasar a segundo plano). */
    fun release() {
        if (released) return
        if (wantPlay) pause()
        released = true
        rebuildPending = false
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        releaseAudio()
        audioDirty = true
    }

    companion object {
        private const val TAG = "VixaPreview"
        /**
         * Superficies disponibles. Pueden estar ocupadas a la vez: la del reproductor actual, la del siguiente, la del de
         * después (tramos cortos) y la del que se acaba de jubilar.
         */
        const val SLOTS = 4
    }
}
