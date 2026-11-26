package com.dskmusic.vixa.ui.editor

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.data.SnapshotInfo
import com.dskmusic.vixa.engine.PreviewPlayer
import com.dskmusic.vixa.engine.Templates
import com.dskmusic.vixa.media.Analysis
import com.dskmusic.vixa.media.ImageCache
import com.dskmusic.vixa.media.Importer
import com.dskmusic.vixa.media.MediaType
import com.dskmusic.vixa.model.Attr
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.ColorFill
import com.dskmusic.vixa.model.History
import com.dskmusic.vixa.model.Layer
import com.dskmusic.vixa.model.MIN_CLIP_US
import com.dskmusic.vixa.model.MediaRef
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.StickerSpec
import com.dskmusic.vixa.model.StickerType
import com.dskmusic.vixa.model.TextSpec
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.Transform
import com.dskmusic.vixa.model.US
import com.dskmusic.vixa.model.addMarkers
import com.dskmusic.vixa.model.addToTrack
import com.dskmusic.vixa.model.cutToSourceRanges
import com.dskmusic.vixa.model.insertFreeze
import com.dskmusic.vixa.model.insertMain
import com.dskmusic.vixa.model.mainIndexAt
import com.dskmusic.vixa.model.mainIndexAfter
import com.dskmusic.vixa.model.newId
import com.dskmusic.vixa.model.normalized
import com.dskmusic.vixa.model.pasteAttributes
import com.dskmusic.vixa.model.keyframeDurationChanges
import com.dskmusic.vixa.model.removeClips
import com.dskmusic.vixa.model.rescaleClipKeyframes
import com.dskmusic.vixa.model.rescaleKeyframes
import com.dskmusic.vixa.model.rippleGap
import com.dskmusic.vixa.model.replaceMedia
import com.dskmusic.vixa.model.sourceOffsetAt
import com.dskmusic.vixa.model.splitClip
import com.dskmusic.vixa.model.splitClipAtTimes
import com.dskmusic.vixa.model.updateClip
import com.dskmusic.vixa.model.withGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Dónde colocar los medios que se añaden. */
enum class AddTarget { MAIN, OVERLAY, AUDIO, STICKER, REPLACE }

class Busy(val message: String, val progress: Float = -1f)

class EditorViewModel(private val context: Context, val container: AppContainer, private val projectId: String) : ViewModel() {
    private val app = context.applicationContext
    val player = PreviewPlayer(app)
    private val playbackGuard = com.dskmusic.vixa.engine.PlaybackGuard.register { player.pause(); ai.stopPreview() }
    val settings get() = container.settings.value

    private var history: History? = null
    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection
    /** Herramienta/panel abierto ("" = ninguno). Se conserva al girar el dispositivo. */
    val tool = MutableStateFlow("")
    /** Zoom de la línea de tiempo en dp por segundo. */
    val zoom = MutableStateFlow(60f)
    /** Ancho del lienzo de la línea de tiempo (dp), para encajar el proyecto entero. */
    var timelineWidthDp = 0f
    /** "Ver todo": zoom y posición previos + zoom de encaje. null = no activo. */
    val fitSaved = MutableStateFlow<Triple<Float, Long, Float>?>(null)

    /** Encaja el proyecto entero en la línea de tiempo; si ya está encajado, vuelve al zoom y posición de antes. */
    fun toggleFitTimeline() {
        val p = _project.value ?: return
        val s = fitSaved.value
        if (s != null && kotlin.math.abs(zoom.value - s.third) < 0.01f) {
            zoom.value = s.first; player.seekTo(s.second); fitSaved.value = null; return
        }
        if (p.durationUs <= 0 || timelineWidthDp <= 0f) return
        val fit = (timelineWidthDp * 0.9f / (p.durationUs / 1_000_000f)).coerceIn(0.01f, 600f)
        fitSaved.value = Triple(zoom.value, player.positionUs.value, fit)
        zoom.value = fit
        player.seekTo(p.durationUs / 2)
    }
    private val _busy = MutableStateFlow<Busy?>(null)
    val busy: StateFlow<Busy?> = _busy
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages
    private val _historyTick = MutableStateFlow(0)
    val historyTick: StateFlow<Int> = _historyTick
    private val _missing = MutableStateFlow<List<String>>(emptyList())
    val missing: StateFlow<List<String>> = _missing
    val multiSelect = MutableStateFlow(false)
    val loop = MutableStateFlow(false)
    val fullscreen = MutableStateFlow(false)
    /** Página abierta en el panel de IA ("" = lista de funciones). Los accesos directos la fijan antes de abrir el panel. */
    val aiPage = MutableStateFlow("")
    /** Zona borrosa seleccionada en el panel (la que se mueve al arrastrar en la vista previa). */
    val zoneSel = MutableStateFlow(0)
    val ai = AiActions(this)

    /** Atributos copiados de un clip. */
    var clipboard: Clip? = null
        private set

    private var saveJob: Job? = null
    private var busyJob: Job? = null
    private var gestureLabel: String? = null
    private var started = false

    fun launch(block: suspend () -> Unit) { viewModelScope.launch { block() } }
    fun str(id: Int, vararg args: Any): String = app.getString(id, *args)
    fun toast(text: String) { _messages.tryEmit(text) }

    init {
        player.previewSide = settings.previewSide
        container.proxies.enabled = settings.useProxies
        player.mediaPath = { container.proxies.pathFor(it) }
        viewModelScope.launch {
            val p = container.projects.load(projectId)
            if (p == null) { _loadFailed.value = true; return@launch }
            history = History(p, str(R.string.hist_open))
            // Historial de la sesión anterior: solo si termina exactamente en el estado guardado del proyecto.
            container.projects.loadHistory(projectId)?.let { (list, idx) ->
                val last = list.getOrNull(idx)?.project
                if (last != null && last.copy(modifiedAt = 0, formatVersion = 0) == p.copy(modifiedAt = 0, formatVersion = 0)) history?.restore(list.mapIndexed { i, e -> if (i == idx) e.copy(project = p) else e }, idx)
            }
            _project.value = p
            _missing.value = withContext(Dispatchers.IO) { container.projects.missing(p) }
            if (_missing.value.isNotEmpty()) {
                // Faltan medios: se avisa y se abre el panel de proyecto, donde se pueden reenlazar.
                toast(str(R.string.missing_media) + ": " + _missing.value.size)
                tool.value = "project"
            }
            val resumeUs = if (settings.openProjectId == projectId) settings.openPositionUs.coerceIn(0, p.durationUs) else 0L
            container.settings.update { it.copy(openProjectId = projectId, openPositionUs = resumeUs) }
            player.seekTo(resumeUs)
            prepareMedia(p)
            ai.regenerate()
            if (started) player.update(p, immediate = true)
        }
        viewModelScope.launch { container.aiJobs.messages.collect { toast(it) } }
        viewModelScope.launch { container.proxies.tick.drop(1).collectLatest { _project.value?.let { p -> player.update(p) } } }
        viewModelScope.launch {
            container.settings.flow.drop(1).collectLatest { s ->
                container.proxies.enabled = s.useProxies
                if (player.previewSide != s.previewSide) { player.previewSide = s.previewSide }
                _project.value?.let { p -> player.update(p) }
            }
        }
    }

    private fun prepareMedia(p: Project) {
        for (c in p.allClips()) {
            c.media?.let { container.proxies.request(it) }
            val st = c.sticker
            if (st != null && st.type == StickerType.ANIMATED) loadAnimated(st.value)
            if (c.stabilize && c.media != null) viewModelScope.launch { Analysis.stabilize(app, c.media); refreshPreview() }
        }
    }

    private fun loadAnimated(path: String) {
        if (ImageCache.animatedIfLoaded(path) != null) return
        viewModelScope.launch(Dispatchers.IO) {
            ImageCache.load(path)
            withContext(Dispatchers.Main) { refreshPreview() }
        }
    }

    private fun refreshPreview() { _project.value?.let { player.update(it) } }

    // --------------------------------------------------------------------------------------
    // Ciclo de vida
    // --------------------------------------------------------------------------------------

    fun onStart() {
        started = true
        _project.value?.let { player.update(it) }
        player.start()
    }

    fun onStop() {
        started = false
        container.settings.update { it.copy(openPositionUs = player.positionUs.value) }
        player.release()
        container.proxies.cancelAll()
        saveNow(updateThumb = true)
    }

    /** Salir del editor: ya no hay sesión que restaurar. */
    fun close() {
        saveNow(updateThumb = true)
        container.settings.update { it.copy(openProjectId = "", openPositionUs = 0) }
    }

    /** false cuando el editor ya se cerró: los trabajos de IA que terminen después no deben tocar el proyecto. */
    var alive = true
        private set

    override fun onCleared() {
        alive = false
        playbackGuard()
        ai.stopPreview()
        player.release()
        super.onCleared()
    }

    /** Lo pone la vista previa: devuelve el fotograma que se ve ahora (solo desde el hilo principal) o null. */
    var previewShot: (() -> android.graphics.Bitmap?)? = null

    /** Captura real de la vista previa en este instante (con filtros y efectos), o null si ya no hay superficie. */
    private fun liveShot(): android.graphics.Bitmap? = try { previewShot?.invoke() } catch (e: Throwable) { null }

    private fun saveNow(updateThumb: Boolean = false) {
        val p = _project.value ?: return
        saveJob?.cancel()
        val hl = history?.list()
        val hp = history?.position ?: 0
        // Miniatura del proyecto: el fotograma real de la vista previa (con efectos y filtros); si es negro, la guarda con el primer medio.
        val shot = if (updateThumb) liveShot() else null
        val pos = player.positionUs.value
        container.appScope.launch {
            try { container.projects.save(p, updateThumb, shot, pos) } catch (e: Throwable) { ErrorLog.log("Save", e) }
            if (hl != null) container.projects.saveHistory(p.id, hl, hp)
        }
    }

    private fun scheduleSave() {
        if (!settings.autosave) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(900)
            val p = _project.value ?: return@launch
            try { container.projects.save(p) } catch (e: Throwable) { ErrorLog.log("Autosave", e) }
        }
    }

    fun save() { saveNow(updateThumb = true); toast(str(R.string.saved)) }

    /** Renombra y guarda al momento (sin esperar al autoguardado, que podría perderse al salir enseguida). */
    fun rename(name: String) {
        edit(R.string.hist_rename) { it.copy(name = name.trim()) }
        saveNow()
    }

    // --------------------------------------------------------------------------------------
    // Edición e historial
    // --------------------------------------------------------------------------------------

    /**
     * Aplica un cambio. Con [live] = true los cambios consecutivos con la misma etiqueta (un gesto o un
     * slider) se funden en un único paso del historial hasta llamar a [commit].
     */
    fun edit(label: String, live: Boolean = false, f: (Project) -> Project) {
        val h = history ?: return
        val cur = _project.value ?: return
        val raw = try { f(cur).rippleGap(cur) } catch (e: Throwable) { ErrorLog.log("Edit/$label", e); toast(str(R.string.err_generic)); return }
        // Cambio de duración con movimientos (fotogramas clave): en modo Fácil se reajustan solos; en Pro se pregunta al terminar el gesto.
        val pro = settings.proMode
        val next = if (pro) raw else raw.rescaleKeyframes(cur)
        if (next === cur || next == cur) return
        if (pro) for ((id, old) in raw.keyframeDurationChanges(cur)) pendingRescale.putIfAbsent(id, old)
        if (live && gestureLabel == label) h.replaceCurrent(next) else h.push(label, next)
        gestureLabel = if (live) label else null
        setProject(next)
        if (pro && !live) flushRescale()
    }

    private val pendingRescale = LinkedHashMap<String, Long>()
    private val _rescaleAsk = kotlinx.coroutines.flow.MutableStateFlow<List<Pair<String, Long>>>(emptyList())
    /** Clips (id, duración anterior) cuyo movimiento puede ajustarse a la nueva duración: la pantalla pregunta al usuario. */
    val rescaleAsk: kotlinx.coroutines.flow.StateFlow<List<Pair<String, Long>>> = _rescaleAsk

    private fun flushRescale() {
        if (pendingRescale.isEmpty()) return
        val p = _project.value
        val list = pendingRescale.entries.filter { (id, old) -> p?.clip(id)?.let { it.keyframes.isNotEmpty() && it.durationUs != old } == true }.map { it.key to it.value }
        pendingRescale.clear()
        if (list.isNotEmpty()) _rescaleAsk.value = list
    }

    /** Respuesta del aviso: ajusta el movimiento a la nueva duración (sí) o lo deja como está (no). */
    fun answerRescale(yes: Boolean) {
        val list = _rescaleAsk.value
        _rescaleAsk.value = emptyList()
        if (yes && list.isNotEmpty()) edit(R.string.hist_keyframe) { p -> list.fold(p) { acc, (id, old) -> acc.rescaleClipKeyframes(id, old) } }
    }

    fun edit(labelRes: Int, live: Boolean = false, f: (Project) -> Project) = edit(str(labelRes), live, f)

    /** Cierra el gesto en curso (soltar un slider, terminar un arrastre). */
    fun commit() { gestureLabel = null; flushRescale() }

    private fun setProject(p: Project) {
        _project.value = p
        _selection.value = _selection.value.filter { p.clip(it) != null }.toSet()
        _historyTick.value++
        player.update(p)
        scheduleSave()
    }

    private fun resetRescale() { pendingRescale.clear(); _rescaleAsk.value = emptyList() }
    fun undo() { gestureLabel = null; resetRescale(); history?.undo()?.let { setProject(it) } }
    fun redo() { gestureLabel = null; resetRescale(); history?.redo()?.let { setProject(it) } }
    fun jumpTo(position: Int) { gestureLabel = null; resetRescale(); history?.jumpTo(position)?.let { setProject(it) } }
    val canUndo: Boolean get() = history?.canUndo == true
    val canRedo: Boolean get() = history?.canRedo == true
    fun historyList(): List<History.Entry> = history?.list() ?: emptyList()
    fun historyPosition(): Int = history?.position ?: 0

    fun updateClip(labelRes: Int, id: String, live: Boolean = false, f: (Clip) -> Clip) = edit(labelRes, live) { it.updateClip(id, f) }

    // --------------------------------------------------------------------------------------
    // Selección
    // --------------------------------------------------------------------------------------

    val selectedClip: Clip? get() = _selection.value.singleOrNull()?.let { _project.value?.clip(it) } ?: _selection.value.firstOrNull()?.let { _project.value?.clip(it) }

    fun select(id: String?) {
        val p = _project.value ?: return
        if (id == null) { _selection.value = emptySet(); multiSelect.value = false; return }
        _selection.value = if (multiSelect.value) {
            if (id in _selection.value) _selection.value - id else _selection.value + id
        } else p.withGroups(listOf(id))
    }

    fun selectOnly(id: String) { _selection.value = setOf(id) }

    // --------------------------------------------------------------------------------------
    // Trabajo en segundo plano
    // --------------------------------------------------------------------------------------

    private fun background(messageRes: Int, block: suspend (progress: (Float) -> Unit) -> Unit) {
        if (_busy.value != null) return
        val msg = str(messageRes)
        _busy.value = Busy(msg)
        busyJob = viewModelScope.launch {
            try {
                block { pr -> _busy.value = Busy(msg, pr) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // cancelado por el usuario
            } catch (e: Throwable) {
                ErrorLog.log(msg, e)
                toast(str(R.string.err_generic) + ": " + (e.message ?: e.javaClass.simpleName))
            } finally {
                _busy.value = null
            }
        }
    }

    fun cancelBusy() { busyJob?.cancel() }

    // --------------------------------------------------------------------------------------
    // Añadir contenido
    // --------------------------------------------------------------------------------------

    fun addMedia(paths: List<String>, target: AddTarget, forceType: MediaType? = null) {
        if (paths.isEmpty()) return
        // Posición del cabezal al pedir la inserción (la importación tarda y la reproducción podría moverlo).
        val pos = player.positionUs.value
        background(R.string.busy_importing) { progress ->
            val refs = ArrayList<MediaRef>()
            val failed = ArrayList<String>()
            for ((i, path) in paths.withIndex()) {
                val type = forceType ?: Importer.typeOf(path)
                if (target == AddTarget.STICKER) {
                    if (File(path).exists()) refs.add(MediaRef(path, isImage = true)) else failed.add(File(path).name)
                } else {
                    val m = Importer.probe(app, path, if (target == AddTarget.AUDIO) MediaType.AUDIO else type)
                    if (m == null || (target == AddTarget.AUDIO && !m.hasAudio)) failed.add(File(path).name) else refs.add(m)
                }
                progress((i + 1f) / paths.size)
            }
            if (failed.isNotEmpty()) toast(str(R.string.err_unsupported_media, failed.joinToString(", ").take(120)))
            if (refs.isEmpty()) return@background
            container.settings.update { s -> s.copy(recentMedia = (paths + s.recentMedia).distinct().take(40)) }
            val imageUs = settings.imageDurationMs * 1000L
            when (target) {
                AddTarget.STICKER -> {
                    val (anim, still) = refs.partition { ImageCache.isAnimatedFile(it.path) }
                    for (m in still) addSticker(StickerType.IMAGE, m.path)
                    // GIF / WebP animado: preguntar si se añade animado o como imagen estática.
                    if (anim.isNotEmpty()) stickerAsk.value = anim.map { it.path }
                }
                AddTarget.REPLACE -> selectedClip?.let { c -> edit(R.string.hist_replace) { it.replaceMedia(c.id, refs.first()) } }
                AddTarget.MAIN -> edit(R.string.hist_add_media) { p ->
                    val clips = refs.map { Importer.clipFor(it, imageUs) }.filter { it.kind != ClipKind.AUDIO }
                    val audio = refs.filter { !it.hasVideo && !it.isImage }
                    var r = if (clips.isEmpty()) p else p.insertMain(clips, if (p.mainTrack.clips.isEmpty()) -1 else p.mainIndexAfter(pos))
                    var at = pos
                    for (a in audio) { r = r.addToTrack(Importer.clipFor(a, imageUs, asAudio = true).copy(startUs = at), TrackType.AUDIO); at += a.durationUs }
                    r
                }
                AddTarget.OVERLAY -> edit(R.string.hist_add_overlay) { p ->
                    var r = p
                    var at = pos
                    for (m in refs.filter { it.hasVideo || it.isImage }) {
                        val c = Importer.clipFor(m, imageUs).copy(startUs = at, transform = Transform(scale = 0.5f))
                        r = r.addToTrack(c, TrackType.OVERLAY)
                        at += c.durationUs
                    }
                    r
                }
                AddTarget.AUDIO -> if (refs.size == 1 && refs[0].durationUs > 2_000_000L) audioRangeAsk.value = refs[0] else edit(R.string.hist_add_audio) { p ->
                    var r = p
                    var at = pos
                    for (m in refs) { r = r.addToTrack(Importer.clipFor(m, imageUs, asAudio = true).copy(startUs = at), TrackType.AUDIO); at += m.durationUs }
                    r
                }
            }
            _project.value?.let { prepareMedia(it) }
        }
    }

    fun addColor(fill: ColorFill, durationUs: Long, atStart: Boolean? = null) = edit(R.string.hist_add_color) { p ->
        val c = Clip(kind = ClipKind.COLOR, durationUs = durationUs, fill = fill)
        p.insertMain(listOf(c), when (atStart) { true -> 0; false -> -1; null -> if (p.mainTrack.clips.isEmpty()) -1 else p.mainIndexAfter(player.positionUs.value) })
    }

    fun addText(spec: TextSpec? = null) {
        val c = Clip(kind = ClipKind.TEXT, startUs = player.positionUs.value, durationUs = 3 * US, text = spec ?: TextSpec(listOf(Layer(text = str(R.string.default_text)))))
        edit(R.string.hist_add_text) { it.addToTrack(c) }
        selectOnly(c.id)
    }

    fun addTemplate(templateId: String) {
        val t = Templates.get(templateId) ?: return
        val es = app.resources.configuration.locales[0].language == "es"
        val c = t.toClip(es, player.positionUs.value)
        edit(R.string.hist_add_template) { it.addToTrack(c) }
        selectOnly(c.id)
    }

    fun addClipCopy(source: Clip) {
        val c = source.copy(id = newId(), startUs = player.positionUs.value, groupId = "", text = source.text?.let { t -> t.copy(layers = t.layers.map { it.copy(id = newId()) }) })
        edit(R.string.hist_add_template) { it.addToTrack(c) }
        selectOnly(c.id)
    }

    /** Audio elegido pendiente del recorte previo (null = sin diálogo). */
    val audioRangeAsk = MutableStateFlow<MediaRef?>(null)

    /** Añade el audio con el tramo [aUs]..[bUs]; el resto del medio sigue disponible al estirar los tiradores (no destructivo). */
    fun addAudioRange(m: MediaRef, aUs: Long, bUs: Long) {
        audioRangeAsk.value = null
        val c = Importer.clipFor(m, 0, asAudio = true).let { it.copy(startUs = player.positionUs.value, srcInUs = aUs, srcOutUs = bUs, durationUs = (bUs - aUs).coerceAtLeast(MIN_CLIP_US)) }
        edit(R.string.hist_add_audio) { it.addToTrack(c, TrackType.AUDIO) }
        selectOnly(c.id)
    }

    /** Rutas de GIF / WebP animados pendientes de elegir animado o estático (null = sin pregunta). */
    val stickerAsk = MutableStateFlow<List<String>?>(null)

    /** Añade los stickers elegidos: animados duran un ciclo del GIF y se repiten solos al alargarlos con los tiradores. */
    fun addAnimatedStickers(paths: List<String>, animated: Boolean) {
        stickerAsk.value = null
        for (path in paths) {
            if (!animated) { addSticker(StickerType.IMAGE, path); continue }
            viewModelScope.launch {
                val total = withContext(Dispatchers.IO) { ImageCache.load(path)?.totalUs ?: 0L }
                val dur = if (total >= 300_000L) total else 3 * US
                val c = Clip(kind = ClipKind.STICKER, startUs = player.positionUs.value, durationUs = dur, sticker = StickerSpec(StickerType.ANIMATED, path))
                edit(R.string.hist_add_sticker) { it.addToTrack(c) }
                selectOnly(c.id)
                refreshPreview()
            }
        }
    }

    fun addSticker(type: StickerType, value: String, color: Int = 0xFFFFFFFF.toInt()) {
        if (type == StickerType.ANIMATED) loadAnimated(value)
        val c = Clip(kind = ClipKind.STICKER, startUs = player.positionUs.value, durationUs = 3 * US, sticker = StickerSpec(type, value, color))
        edit(R.string.hist_add_sticker) { it.addToTrack(c) }
        selectOnly(c.id)
    }

    /**
     * Capa de efectos: una capa de ajuste vacía a la que se añaden efectos (se mueve y se estira como cualquier clip). Si hay un
     * clip seleccionado ocupa su mismo tramo; si no, todo el proyecto. Los efectos de clip siguen funcionando como siempre.
     */
    fun addEffectLayer() {
        val p = _project.value ?: return
        val sel = _selection.value.firstOrNull()?.let { p.clip(it) }?.takeIf { it.kind != ClipKind.AUDIO }
        val c = if (sel != null) Clip(kind = ClipKind.ADJUST, startUs = sel.startUs, durationUs = sel.durationUs)
        else Clip(kind = ClipKind.ADJUST, startUs = 0L, durationUs = p.durationUs.coerceAtLeast(3 * US))
        edit(R.string.hist_add_fx_layer) { it.addToTrack(c) }
        selectOnly(c.id)
    }

    /** Morph entre fotos: caras automaticas (MediaPipe); si alguna foto no tiene cara, malla de cuadricula. */
    /** Clip de morph que se esta editando a mano (puntos y encuadre); lo abre EditorScreen. */
    val morphEdit = MutableStateFlow<String?>(null)

    fun updateMorph(clipId: String, spec: com.dskmusic.vixa.model.MorphSpec) =
        edit(R.string.hist_morph_edit) { it.updateClip(clipId) { c -> c.copy(morph = spec) } }

    fun createMorph(paths: List<String>, zoom: Boolean, pingPong: Boolean) {
        if (paths.size < 2) return
        val pos = player.positionUs.value
        val ar = project.value?.aspect ?: (9f / 16f)
        background(R.string.busy_morph) { progress ->
            val files = ArrayList<String>()
            val dims = ArrayList<Pair<Int, Int>>()
            val points = ArrayList<FloatArray?>()
            for ((i, src) in paths.withIndex()) {
                val f = withContext(Dispatchers.Default) { com.dskmusic.vixa.engine.MorphMath.prepare(app, src) }
                if (f != null) {
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(f, o)
                    val lm = withContext(Dispatchers.Default) {
                        try { com.dskmusic.vixa.ai.Vision.faceLandmarks(container.models, f) } catch (e: Throwable) { ErrorLog.log("morph face", e); null }
                    }
                    files.add(f); dims.add(o.outWidth to o.outHeight); points.add(lm)
                }
                progress((i + 1f) / paths.size)
            }
            if (files.size < 2) { toast(str(R.string.err_generic)); return@background }
            val faces = points.all { it != null }
            if (!faces) toast(str(R.string.morph_nofaces))
            val images = files.indices.map { i ->
                if (faces) com.dskmusic.vixa.engine.MorphMath.fitFace(files[i], dims[i].first, dims[i].second, points[i]!!, ar)
                else com.dskmusic.vixa.engine.MorphMath.fitCenter(files[i], dims[i].first, dims[i].second, ar)
            }
            val spec = com.dskmusic.vixa.model.MorphSpec(images, pingPong = pingPong, base = if (faces) 468 else 0)
            val dur = com.dskmusic.vixa.engine.MorphMath.naturalDurationUs(images.size, spec.hold, 1_300_000L, pingPong)
            // El fotograma del morph tiene el tamaño del lienzo: el recuadro de seleccion debe usar esa proporcion, no la de la foto.
            val pr = project.value
            val m = (Importer.probe(app, files[0]) ?: return@background).let { if (pr != null) it.copy(width = pr.width, height = pr.height) else it }
            val clip = Importer.clipFor(m, dur).copy(
                durationUs = dur, morph = spec, label = str(R.string.tool_morph),
                animLoop = if (zoom) com.dskmusic.vixa.model.AnimRef("kb_zoom_in") else null,
            )
            edit(R.string.hist_morph) { p -> p.insertMain(listOf(clip), if (p.mainTrack.clips.isEmpty()) -1 else p.mainIndexAfter(pos)) }
            selectOnly(clip.id)
            // Sin caras no hay malla automatica: se abre el editor para poner los puntos a mano.
            if (!faces) morphEdit.value = clip.id
        }
    }

    fun addAdjustLayer() {
        val c = Clip(kind = ClipKind.ADJUST, startUs = player.positionUs.value, durationUs = 3 * US)
        edit(R.string.hist_add_adjust) { it.addToTrack(c) }
        selectOnly(c.id)
    }

    fun addSubtitle(text: String, startUs: Long, durationUs: Long) = edit(R.string.hist_subtitles) { p ->
        p.addToTrack(Clip(kind = ClipKind.TEXT, startUs = startUs, durationUs = durationUs.coerceAtLeast(MIN_CLIP_US), text = TextSpec(listOf(Layer(text = text)), subtitle = true)), TrackType.TEXT)
    }

    /** Voz en off grabada: se añade como clip de audio marcado como voz. */
    fun addVoiceOver(path: String, startUs: Long) {
        viewModelScope.launch {
            val m = Importer.probe(app, path, MediaType.AUDIO)
            if (m == null) { toast(str(R.string.err_generic)); return@launch }
            val c = Importer.clipFor(m, 0, asAudio = true).let { it.copy(startUs = startUs, audioFx = it.audioFx.copy(isVoice = true), label = str(R.string.voice_over)) }
            edit(R.string.hist_voice_over) { it.addToTrack(c, TrackType.AUDIO) }
        }
    }

    // --------------------------------------------------------------------------------------
    // Operaciones sobre clips
    // --------------------------------------------------------------------------------------

    /** [keep]: 0 = dividir, 1 = cortar y dejar lo anterior, 2 = cortar y dejar lo posterior. */
    fun splitAtPlayhead(keep: Int = 0) {
        val p = _project.value ?: return
        val t = player.positionUs.value
        val ids = _selection.value.ifEmpty { p.allClips().filter { t > it.startUs && t < it.endUs && p.trackOf(it.id)?.type == TrackType.MAIN }.map { it.id }.toSet() }
        if (ids.isEmpty()) return
        edit(R.string.hist_split) { var r = it; for (id in ids) r = r.splitClip(id, t, keep); r }
    }

    /** Pendiente de confirmar el borrado del hueco inicial. */
    val confirmGapDelete = MutableStateFlow(false)

    fun deleteSelected(confirmed: Boolean = false) {
        val ids = _selection.value
        if (ids.isEmpty()) return
        if (!confirmed && ids.any { _project.value?.clip(it)?.gap == true }) { confirmGapDelete.value = true; return }
        confirmGapDelete.value = false
        edit(R.string.hist_delete) { it.removeClips(ids) }
        _selection.value = emptySet()
    }

    fun freezeFrame() {
        val c = selectedClip ?: return
        val m = c.media ?: return
        if (c.kind != ClipKind.VIDEO) return
        val t = player.positionUs.value.coerceIn(c.startUs, c.endUs)
        background(R.string.busy_freeze) {
            val img = Importer.extractFrame(app, m.path, c.srcInUs + c.sourceOffsetAt(t - c.startUs))
            if (img == null) toast(str(R.string.err_generic)) else edit(R.string.hist_freeze) { it.insertFreeze(c.id, t, img, settings.imageDurationMs * 1000L) }
        }
    }

    fun reverse() {
        val c = selectedClip ?: return
        val m = c.media ?: return
        if (c.kind != ClipKind.VIDEO) return
        background(R.string.busy_reverse) { progress ->
            val r = Analysis.reverse(app, m, c.srcInUs, c.srcOutUs, progress)
            if (r == null) toast(str(R.string.err_generic))
            else edit(R.string.hist_reverse) { it.updateClip(c.id) { x -> x.copy(media = r, srcInUs = 0, srcOutUs = r.durationUs, reversed = !x.reversed, speedCurve = x.speedCurve.reversed().map { sp -> sp.copy(pos = 1f - sp.pos) }) } }
        }
    }

    fun toggleStabilize() {
        val c = selectedClip ?: return
        val m = c.media ?: return
        if (c.stabilize) { updateClip(R.string.hist_stabilize, c.id) { it.copy(stabilize = false) }; return }
        background(R.string.busy_stabilize) { progress ->
            if (Analysis.stabilize(app, m, progress)) updateClip(R.string.hist_stabilize, c.id) { it.copy(stabilize = true) } else toast(str(R.string.err_generic))
        }
    }

    fun normalizeAudio() {
        val c = selectedClip ?: return
        val m = c.media ?: return
        background(R.string.busy_analyzing) {
            val peak = Analysis.peak(m.path, c.srcInUs, c.srcOutUs)
            if (peak <= 0.01f) toast(str(R.string.err_no_audio))
            else updateClip(R.string.hist_normalize, c.id) { it.copy(audioFx = it.audioFx.copy(normalizeGain = (0.95f / peak).coerceIn(0.25f, 4f))) }
        }
    }

    /** Corte automático al ritmo: marcadores en los golpes de la canción, o cortes en el clip seleccionado. */
    fun beatSync(cut: Boolean) {
        val p = _project.value ?: return
        val music = selectedClip?.takeIf { it.kind == ClipKind.AUDIO } ?: p.allClips().filter { it.kind == ClipKind.AUDIO }.maxByOrNull { it.durationUs }
        val m = music?.media
        if (music == null || m == null) { toast(str(R.string.err_no_music)); return }
        background(R.string.busy_analyzing) {
            val beats = Analysis.beats(m.path).filter { it >= music.srcInUs && it <= music.srcOutUs }
            // Tiempos en la línea de tiempo (velocidad constante del clip de música).
            val times = beats.map { music.startUs + ((it - music.srcInUs) / music.speed).toLong() }
            if (times.isEmpty()) { toast(str(R.string.err_no_beats)); return@background }
            if (cut) edit(R.string.hist_beat_cut) { pr ->
                var r = pr
                for (c in pr.mainTrack.clips) r = r.splitClipAtTimes(c.id, times.filter { t -> t > c.startUs && t < c.endUs })
                r
            } else edit(R.string.hist_beat_markers) { it.addMarkers(times) }
            toast(str(R.string.beats_found, times.size))
        }
    }

    fun removeSilences() {
        val c = selectedClip ?: return
        val m = c.media ?: return
        if (!c.hasTimedMedia || !m.hasAudio) { toast(str(R.string.err_no_audio)); return }
        // Con el modelo de voz descargado se usa el VAD (más preciso que el método por volumen).
        if (container.models.isReady(com.dskmusic.vixa.ai.Models.VAD)) { ai.removeSilences(c); return }
        background(R.string.busy_analyzing) {
            val keep = Analysis.nonSilent(m.path, c.srcInUs, c.srcOutUs)
            if (keep.isEmpty() || (keep.size == 1 && keep[0].second - keep[0].first >= c.srcSpanUs - 300_000L)) toast(str(R.string.no_silences))
            else { edit(R.string.hist_silences) { it.cutToSourceRanges(c.id, keep) }; toast(str(R.string.silences_removed, keep.size)) }
        }
    }

    fun detectScenes() {
        val c = selectedClip ?: return
        val m = c.media ?: return
        if (c.kind != ClipKind.VIDEO) return
        background(R.string.busy_analyzing) { progress ->
            val cuts = Analysis.scenes(m.path, c.srcInUs, c.srcOutUs, progress)
            if (cuts.isEmpty()) toast(str(R.string.no_scenes))
            else {
                val times = cuts.map { c.startUs + ((it - c.srcInUs) / c.speed).toLong() }
                edit(R.string.hist_scenes) { it.splitClipAtTimes(c.id, times) }
                toast(str(R.string.scenes_found, cuts.size))
            }
        }
    }

    /** Montaje automático: reparte los medios al ritmo de la canción. */
    fun autoMontage(mediaPaths: List<String>, songPath: String) {
        background(R.string.busy_montage) { progress ->
            val song = Importer.probe(app, songPath, MediaType.AUDIO)
            val refs = mediaPaths.mapNotNull { Importer.probe(app, it) }.filter { it.hasVideo || it.isImage }
            if (song == null || refs.isEmpty()) { toast(str(R.string.err_unsupported_media, "")); return@background }
            progress(0.4f)
            val total = minOf(song.durationUs, 90 * US)
            val beats = Analysis.beats(song.path).filter { it < total }
            // Puntos de corte: golpes separados al menos 1,4 s (o cada 2,5 s si no se detectan).
            val cuts = ArrayList<Long>()
            var last = 0L
            for (b in beats) if (b - last >= 1_400_000L) { cuts.add(b); last = b }
            if (cuts.size < 3) { cuts.clear(); var t = 2_500_000L; while (t < total) { cuts.add(t); t += 2_500_000L } }
            cuts.add(total)
            val clips = ArrayList<Clip>()
            var prev = 0L
            val used = HashMap<String, Long>()
            for ((i, cut) in cuts.withIndex()) {
                val len = cut - prev
                prev = cut
                if (len < MIN_CLIP_US) continue
                val m = refs[i % refs.size]
                clips.add(
                    if (m.isImage) Clip(kind = ClipKind.IMAGE, media = m, durationUs = len, animLoop = com.dskmusic.vixa.model.AnimRef(if (i % 2 == 0) "kb_zoom_in" else "kb_zoom_out"))
                    else {
                        // Avanza por el vídeo en cada aparición; vuelve al principio si se acaba.
                        var start = used[m.path] ?: (m.durationUs / 6)
                        if (start + len > m.durationUs) start = 0
                        val end = minOf(m.durationUs, start + len)
                        used[m.path] = end
                        Clip(kind = ClipKind.VIDEO, media = m, srcInUs = start, srcOutUs = end, durationUs = end - start, muted = true)
                    },
                )
            }
            val music = Importer.clipFor(song, 0, asAudio = true).copy(srcOutUs = total, fadeOutUs = US)
            edit(R.string.hist_montage) { it.insertMain(clips).addToTrack(music, TrackType.AUDIO) }
            _project.value?.let { prepareMedia(it) }
        }
    }

    /**
     * Pantalla dividida: coloca [paths] (2, 3 o 4 medios) en celdas del lienzo a partir del cabezal.
     * [layout]: "2v" (lado a lado), "2h" (arriba/abajo), "3" (uno grande + dos), "4" (cuadrícula).
     */
    fun addSplitScreen(paths: List<String>, layout: String) {
        // Celdas como (x, y, ancho, alto) en fracciones del lienzo.
        val cells: List<FloatArray> = when (layout) {
            "2v" -> listOf(floatArrayOf(0f, 0f, 0.5f, 1f), floatArrayOf(0.5f, 0f, 0.5f, 1f))
            "2h" -> listOf(floatArrayOf(0f, 0f, 1f, 0.5f), floatArrayOf(0f, 0.5f, 1f, 0.5f))
            "3" -> listOf(floatArrayOf(0f, 0f, 1f, 0.5f), floatArrayOf(0f, 0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f))
            else -> listOf(floatArrayOf(0f, 0f, 0.5f, 0.5f), floatArrayOf(0.5f, 0f, 0.5f, 0.5f), floatArrayOf(0f, 0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f))
        }
        background(R.string.busy_importing) {
            val refs = paths.take(cells.size).mapNotNull { Importer.probe(app, it) }.filter { it.hasVideo || it.isImage }
            if (refs.size < 2) { toast(str(R.string.err_split_needs)); return@background }
            val pos = player.positionUs.value
            val dur = refs.filter { !it.isImage }.minOfOrNull { it.durationUs } ?: (settings.imageDurationMs * 1000L)
            edit(R.string.hist_split_screen) { p ->
                var r = p
                val canvasAspect = p.aspect
                for ((i, m) in refs.withIndex()) {
                    val cell = cells[i]
                    val cellAspect = cell[2] / cell[3] * canvasAspect
                    val src = if (m.height > 0) m.width.toFloat() / m.height else cellAspect
                    // Recorte centrado a la proporción de la celda y escala para llenarla exactamente.
                    val cropX = if (src > cellAspect) (1f - cellAspect / src) / 2f else 0f
                    val cropY = if (src > cellAspect) 0f else (1f - src / cellAspect) / 2f
                    val scale = if (cellAspect > canvasAspect) cell[2] else cell[3]
                    val tr = Transform(x = cell[0] + cell[2] / 2f - 0.5f, y = cell[1] + cell[3] / 2f - 0.5f, scale = scale, cropL = cropX, cropR = cropX, cropT = cropY, cropB = cropY)
                    val base = Importer.clipFor(m, dur)
                    val c = (if (m.isImage) base else base.copy(srcOutUs = dur, durationUs = dur)).copy(startUs = pos, transform = tr, muted = i > 0)
                    // Cada celda en su propia pista de superposición.
                    r = r.copy(tracks = listOf(com.dskmusic.vixa.model.Track(type = TrackType.OVERLAY, clips = listOf(c))) + r.tracks).normalized()
                }
                r
            }
            _project.value?.let { prepareMedia(it) }
        }
    }

    fun copyAttributes() { clipboard = selectedClip; if (clipboard != null) toast(str(R.string.attributes_copied)) }

    fun pasteAttributes(attrs: Set<Attr>) {
        val from = clipboard ?: return
        val ids = _selection.value
        if (ids.isEmpty()) return
        edit(R.string.hist_paste_attrs) { it.pasteAttributes(from, ids, attrs) }
    }

    // --------------------------------------------------------------------------------------
    // Proyecto
    // --------------------------------------------------------------------------------------

    fun relink(oldPath: String, newPath: String) {
        edit(R.string.hist_relink) { container.projects.relink(it, oldPath, newPath) }
        _project.value?.let { p -> viewModelScope.launch { _missing.value = withContext(Dispatchers.IO) { container.projects.missing(p) }; prepareMedia(p) } }
    }

    fun saveSnapshot(label: String) {
        val p = _project.value ?: return
        viewModelScope.launch { container.projects.saveSnapshot(p, label); toast(str(R.string.snapshot_saved)) }
    }

    suspend fun snapshots(): List<SnapshotInfo> = container.projects.snapshots(projectId)

    fun restoreSnapshot(s: SnapshotInfo) {
        viewModelScope.launch {
            val p = container.projects.loadSnapshot(s) ?: return@launch
            val id = _project.value?.id ?: return@launch
            edit(R.string.hist_restore_snapshot) { p.copy(id = id).normalized() }
        }
    }

    fun deleteSnapshot(s: SnapshotInfo) { viewModelScope.launch { container.projects.deleteSnapshot(s) } }

    fun saveAsTemplate(name: String) {
        val p = _project.value ?: return
        viewModelScope.launch {
            val t = p.copy(id = newId(), name = name, isTemplate = true, createdAt = System.currentTimeMillis(),
                tracks = p.tracks.map { tr -> tr.copy(clips = tr.clips.map { c -> if (c.media != null && c.isVisual) c.copy(slot = true) else c }) })
            container.projects.save(t, updateThumb = true)
            toast(str(R.string.template_saved))
        }
    }

    fun saveAs(name: String, onDone: (String) -> Unit) {
        val p = _project.value ?: return
        viewModelScope.launch {
            val copy = container.projects.save(p.copy(id = newId(), name = name, createdAt = System.currentTimeMillis(), isTemplate = false), updateThumb = true)
            onDone(copy.id)
        }
    }

    fun saveTextTemplate(name: String) {
        val c = selectedClip?.takeIf { it.kind == ClipKind.TEXT } ?: return
        viewModelScope.launch { container.projects.saveTextTemplate(c, name); toast(str(R.string.template_saved)) }
    }
}
