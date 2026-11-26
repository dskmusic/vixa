package com.dskmusic.vixa.ui.editor

import androidx.compose.ui.draw.clip
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.combinedClickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.R
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.ui.ScrollHintEffect
import com.dskmusic.vixa.ui.ScrollHints
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.duplicateClip
import com.dskmusic.vixa.model.fadeBlack
import com.dskmusic.vixa.model.toggleMainOverlay
import com.dskmusic.vixa.ui.ExportDialog
import com.dskmusic.vixa.ui.ToolButton
import com.dskmusic.vixa.ui.picker.ColorActions
import com.dskmusic.vixa.ui.picker.FileBrowserDialog
import com.dskmusic.vixa.ui.picker.MediaPickerDialog
import com.dskmusic.vixa.ui.picker.PickFilter
import kotlinx.coroutines.delay

private class ToolDef(val id: String, val icon: ImageVector, val label: Int, val action: (() -> Unit)? = null)

/** Herramientas visibles en modo Fácil (sin clip y con clip seleccionado). El resto es solo del modo Pro. */
private val EASY_TOOLS = setOf(
    "add", "text", "stickers", "audio", "ai", "morph", "morphedit", "templates", "effects", "filter", "adjust", "transform", "speed", "volume", "animation",
    "replace", "split", "delete", "dup", "crop", "transition",
)

private val SIMPLE_TOOLS = setOf("text", "templates", "stickers", "speed", "volume", "transform", "crop", "animation", "filter", "adjust", "effects", "transition", "canvas", "audiofx")

@Composable
fun EditorScreen(container: AppContainer, projectId: String, onExit: () -> Unit, onOpenProject: (String) -> Unit) {
    val context = LocalContext.current
    val vm: EditorViewModel = viewModel(key = "editor:$projectId") { EditorViewModel(context.applicationContext, container, projectId) }
    val project by vm.project.collectAsState()
    val loadFailed by vm.loadFailed.collectAsState()
    val selection by vm.selection.collectAsState()
    val tool by vm.tool.collectAsState()
    val busy by vm.busy.collectAsState()
    val fullscreen by vm.fullscreen.collectAsState()
    val position by vm.player.positionUs.collectAsState()
    // Se lee para recomponer la barra superior (deshacer/rehacer) tras cada cambio del historial.
    @Suppress("UNUSED_VARIABLE") val historyTick = vm.historyTick.collectAsState().value
    var pick by remember { mutableStateOf<PickRequest?>(null) }
    var fadeAsk by remember { mutableStateOf<Boolean?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var morphPaths by remember { mutableStateOf<List<String>?>(null) }
    var splitAsk by remember { mutableStateOf(false) }
    var trackAsk by remember { mutableStateOf(false) }
    var demoNotice by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }

    // Imagen de muestra de las vistas previas: la elegida en Ajustes o, con el modo aleatorio, una nueva cada vez que se abre el editor.
    val appCtx = LocalContext.current.applicationContext
    remember { vm.settings.let { com.dskmusic.vixa.engine.FxSamples.refresh(appCtx, it.fxSample, it.fxSampleRandom, true) }; 0 }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) vm.onStart() else if (e == Lifecycle.Event.ON_STOP) vm.onStop()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); vm.onStop() }
    }
    LaunchedEffect(Unit) { vm.messages.collect { message = it } }
    LaunchedEffect(message) { if (message != null) { delay(3500); message = null } }

    fun exit() { vm.close(); onExit() }
    BackHandler {
        when {
            fullscreen -> vm.fullscreen.value = false
            tool.isNotEmpty() -> vm.tool.value = ""
            selection.isNotEmpty() -> vm.select(null)
            else -> exit()
        }
    }

    val p = project
    if (p == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            if (loadFailed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.err_project_load))
                Button({ exit() }, Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.back)) }
            } else CircularProgressIndicator()
        }
        return
    }
    ScrollHints.container = vm.container
    // Proyecto demo: lleva muchas capas y efectos a la vez; se avisa y se ofrece renderizarlo (exportarlo) para verlo fluido.
    if (p.demo && demoNotice) androidx.compose.material3.AlertDialog(
        onDismissRequest = { demoNotice = false },
        title = { Text(stringResource(R.string.demo_notice_title)) },
        text = { Text(stringResource(R.string.demo_notice_body)) },
        confirmButton = { androidx.compose.material3.TextButton({ demoNotice = false; vm.player.pause(); exporting = true }) { Text(stringResource(R.string.demo_render)) } },
        dismissButton = { androidx.compose.material3.TextButton({ demoNotice = false }) { Text(stringResource(R.string.demo_continue)) } },
    )
    ScrollHints.projectId = p.id
    val clip: Clip? = selection.firstOrNull()?.let { p.clip(it) }
    val track = clip?.let { p.trackOf(it.id) }

    // --- Barras de herramientas -------------------------------------------------------------
    fun open(id: String): () -> Unit = { vm.tool.value = id }
    fun addMain() { pick = PickRequest(PickKind.MEDIA, AddTarget.MAIN, multi = true) { vm.addMedia(it, AddTarget.MAIN) } }
    // Botón "+" bajo la línea de tiempo: abre la herramienta del tipo elegido, como si se pulsara en la barra.
    val addKind: (String) -> Unit = { k ->
        when (k) {
            // Igual que el botón "Añadir medio" de la barra inferior.
            "media" -> addMain()
            "audio" -> pick = PickRequest(PickKind.MEDIA, AddTarget.AUDIO, multi = true) { vm.addMedia(it, AddTarget.AUDIO) }
            "text" -> { vm.addText(); vm.tool.value = "text" }
            "stickers" -> vm.tool.value = "stickers"
        }
    }
    val rootTools = listOf(
        ToolDef("add", Icons.Default.Add, R.string.tool_add) { addMain() },
        ToolDef("pip", Icons.Default.PictureInPicture, R.string.overlay) { pick = PickRequest(PickKind.MEDIA, AddTarget.OVERLAY, multi = true) { vm.addMedia(it, AddTarget.OVERLAY) } },
        ToolDef("audio", Icons.Default.MusicNote, R.string.audio) { pick = PickRequest(PickKind.MEDIA, AddTarget.AUDIO, multi = true) { vm.addMedia(it, AddTarget.AUDIO) } },
        ToolDef("text", Icons.Default.TextFields, R.string.text) { vm.addText(); vm.tool.value = "text" },
        ToolDef("templates", Icons.Default.Dashboard, R.string.templates),
        ToolDef("stickers", Icons.Default.EmojiEmotions, R.string.stickers),
        ToolDef("subtitles", Icons.Default.Subtitles, R.string.subtitles),
        ToolDef("voiceover", Icons.Default.Mic, R.string.voice_over),
        ToolDef("morph", Icons.Default.Face, R.string.tool_morph) {
            pick = PickRequest(PickKind.MEDIA, AddTarget.MAIN, multi = true) { ps ->
                val im = ps.filter { com.dskmusic.vixa.media.Importer.typeOf(it) == com.dskmusic.vixa.media.MediaType.IMAGE }
                if (im.size < 2) vm.toast(vm.str(R.string.morph_need2)) else morphPaths = im
            }
        },
        ToolDef("ai", Icons.Default.AutoAwesome, R.string.ai_short),
        ToolDef("adjlayer", Icons.Default.Layers, R.string.tool_looks) { vm.addAdjustLayer(); vm.tool.value = "filter" },
        // Sin clip seleccionado: efectos sobre todo el proyecto, como capa independiente (los de clip están al seleccionar uno).
        ToolDef("fxlayer", Icons.Default.Bolt, R.string.effects) { vm.addEffectLayer(); vm.tool.value = "effects" },
        ToolDef("canvas", Icons.Default.AspectRatio, R.string.canvas),
        ToolDef("extras", Icons.Default.AutoFixHigh, R.string.extras),
        ToolDef("project", Icons.Default.Save, R.string.project),
        ToolDef("history", Icons.Default.History, R.string.history),
    )
    val clipTools: List<ToolDef> = if (clip == null) emptyList() else buildList {
        val visual = clip.isVisual
        val isMain = track?.type == TrackType.MAIN
        add(ToolDef("split", Icons.Default.ContentCut, R.string.split) { splitAsk = true })
        add(ToolDef("delete", Icons.Default.Delete, R.string.delete) { vm.deleteSelected() })
        add(ToolDef("dup", Icons.Default.ContentCopy, R.string.duplicate) { vm.edit(R.string.hist_duplicate) { it.duplicateClip(clip.id) } })
        if (clip.kind == ClipKind.TEXT) { add(ToolDef("text", Icons.Default.TextFields, R.string.text)); add(ToolDef("templates", Icons.Default.AutoAwesome, R.string.templates)) }
        if (clip.morph != null) add(ToolDef("morphedit", Icons.Default.Face, R.string.morph_edit) { vm.morphEdit.value = clip.id })
        if (clip.kind == ClipKind.STICKER) add(ToolDef("stickers", Icons.Default.EmojiEmotions, R.string.stickers))
        if (clip.hasTimedMedia) add(ToolDef("speed", Icons.Default.Speed, R.string.speed))
        if (clip.kind == ClipKind.AUDIO || (clip.kind == ClipKind.VIDEO && clip.media?.hasAudio == true)) {
            add(ToolDef("volume", Icons.AutoMirrored.Filled.VolumeUp, R.string.volume))
            add(ToolDef("audiofx", Icons.Default.GraphicEq, R.string.audio_fx))
        }
        if (clip.kind != ClipKind.AUDIO && clip.kind != ClipKind.ADJUST) {
            add(ToolDef("transform", Icons.Default.Transform, R.string.transform))
            add(ToolDef("animation", Icons.Default.Animation, R.string.animation))
        }
        if (visual || clip.kind == ClipKind.ADJUST) {
            add(ToolDef("filter", Icons.Default.FilterVintage, R.string.filters))
            add(ToolDef("adjust", Icons.Default.Tune, R.string.adjust))
            if (clip.kind != ClipKind.COLOR) add(ToolDef("effects", Icons.Default.AutoAwesome, R.string.effects))
        }
        if (visual) {
            if (isMain) add(ToolDef("transition", Icons.Default.SwapHoriz, R.string.transition))
            if (clip.kind != ClipKind.COLOR) {
                add(ToolDef("crop", Icons.Default.Crop, R.string.crop))
                add(ToolDef("chroma", Icons.Default.Palette, R.string.chroma_key))
                add(ToolDef("zone", Icons.Default.BlurOn, R.string.zone))
            }
            add(ToolDef("mask", Icons.Default.Texture, R.string.mask))
            add(ToolDef("canvasbg", Icons.Default.Wallpaper, R.string.canvas) { vm.tool.value = "canvas" })
            add(ToolDef("fadeblack", Icons.Default.Gradient, R.string.fade_black) {
                vm.updateClip(R.string.hist_fade_black, clip.id) { if (it.blackInUs > 0 || it.blackOutUs > 0) it.copy(blackInUs = 0, blackOutUs = 0) else it.copy(blackInUs = 500_000L, blackOutUs = 500_000L) }
            })
            add(ToolDef("tomain", Icons.Default.SwapVert, if (isMain) R.string.to_overlay else R.string.to_main) { vm.edit(R.string.hist_move) { it.toggleMainOverlay(clip.id) } })
            if (clip.kind != ClipKind.COLOR) add(ToolDef("replace", Icons.Default.SwapHoriz, R.string.replace) { pick = PickRequest(PickKind.MEDIA, AddTarget.REPLACE) { vm.addMedia(it, AddTarget.REPLACE) } })
        }
        if (clip.kind == ClipKind.VIDEO) {
            add(ToolDef("freeze", Icons.Default.AcUnit, R.string.freeze_frame) { vm.freezeFrame() })
            add(ToolDef("reverse", Icons.Default.FastRewind, R.string.reverse) { vm.reverse() })
            add(ToolDef("stab", Icons.Default.Vibration, if (clip.stabilize) R.string.stabilize_off else R.string.stabilize) { vm.toggleStabilize() })
            add(ToolDef("track", Icons.Default.MyLocation, R.string.track_object) { trackAsk = true })
        }
        add(ToolDef("ai", Icons.Default.AutoAwesome, R.string.ai_short))
        add(ToolDef("keyframes", Icons.Default.Diamond, R.string.keyframes))
        add(ToolDef("extras", Icons.Default.AutoFixHigh, R.string.extras))
    }
    val appSettings by container.settings.flow.collectAsState()
    val pro = appSettings.proMode
    // Vista simplificada: con uno de estos paneles abierto se oculta la línea de tiempo (los que dependen del cursor la conservan).
    val simple = appSettings.simpleView && tool.isNotEmpty() && tool in SIMPLE_TOOLS
    val tools = (if (clip != null) clipTools else rootTools).filter { pro || it.id in EASY_TOOLS }

    // Barra rápida sobre el vídeo: dividir, duplicar y borrar el clip seleccionado (se puede quitar en Ajustes)
    val quickBar: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
        val c = clip
        if (appSettings.quickClipBar && c != null && tool.isEmpty()) Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ splitAsk = true }, Modifier.size(40.dp)) { Icon(Icons.Default.ContentCut, stringResource(R.string.split), Modifier.size(20.dp)) }
            IconButton({ vm.edit(R.string.hist_duplicate) { it.duplicateClip(c.id) } }, Modifier.size(40.dp)) { Icon(Icons.Default.ContentCopy, stringResource(R.string.duplicate), Modifier.size(20.dp)) }
            IconButton({ vm.deleteSelected() }, Modifier.size(40.dp)) { Icon(Icons.Default.Delete, stringResource(R.string.delete), Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) }
        }
    }
    val topBar: @Composable (Modifier) -> Unit = { m ->
        Row(m.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ exit() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(p.name.ifBlank { stringResource(R.string.untitled) }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            androidx.compose.material3.FilterChip(pro, { vm.container.settings.update { it.copy(proMode = !it.proMode) } }, { Text("Pro") }, Modifier.padding(end = 4.dp))
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(end = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { HistoryButton(vm, true); HistoryButton(vm, false) }
            }
            androidx.compose.material3.Button({ vm.player.pause(); exporting = true }, Modifier.padding(end = 8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)) { Text(stringResource(R.string.export)) }
        }
    }
    val toolbarRow: @Composable () -> Unit = {
        val toolsScroll = rememberScrollState()
        ScrollHintEffect(toolsScroll, "tools:" + (clip != null))
        Row(Modifier.fillMaxWidth().horizontalScroll(toolsScroll).padding(horizontal = 4.dp, vertical = 2.dp)) {
            for (t in tools) ToolButton(t.icon, stringResource(t.label), selected = tool == t.id, accent = if (pro) Color.Unspecified else toolAccent(t.id), onClick = t.action ?: open(t.id))
        }
    }
    val panel: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(toolTitle(tool)), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton({ vm.tool.value = "" }, Modifier.size(40.dp)) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            }
            val panelScroll = rememberScrollState()
            ScrollHintEffect(panelScroll, "panel:" + tool)
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(panelScroll).padding(bottom = 8.dp)) {
                ToolPanel(vm, p, tool, clip, { pick = it }, onOpenProject)
            }
        }
    }

    // --- Diseños ----------------------------------------------------------------------------
    // Pro: al cambiar la duración de algo con movimiento, se pregunta si se ajusta (en modo Fácil se ajusta solo).
    val rescaleAsk by vm.rescaleAsk.collectAsState()
    if (rescaleAsk.isNotEmpty()) androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.answerRescale(false) },
        title = { Text(stringResource(R.string.rescale_ask_title)) },
        text = { Text(stringResource(R.string.rescale_ask_body)) },
        confirmButton = { androidx.compose.material3.TextButton({ vm.answerRescale(true) }) { Text(stringResource(R.string.rescale_yes)) } },
        dismissButton = { androidx.compose.material3.TextButton({ vm.answerRescale(false) }) { Text(stringResource(R.string.rescale_no)) } },
    )
    // Reparto vista previa / línea de tiempo: dp que se le quitan a la línea de tiempo y se le dan a la vista previa (0 = normal).
    var splitDelta by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val splitDensity = androidx.compose.ui.platform.LocalDensity.current.density
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // La orientación se mide SIN el teclado: si no, al abrir el panel de emojis (más alto) el diseño
        // saltaba a horizontal, se recreaba el campo de texto y se cerraba el teclado.
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
        val landscape = maxWidth > maxHeight
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val maxH = maxHeight
            val expanded = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
            val medium = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
            if (fullscreen) {
                // Vista previa a pantalla completa con sus controles.
                Column(Modifier.fillMaxSize().background(Color.Black)) {
                    Preview(vm, p, Modifier.fillMaxWidth().weight(1f), viewOnly = true)
                    // Barra de progreso de lado a lado, justo encima de los controles (vertical y horizontal).
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        androidx.compose.material3.Slider(
                            value = if (p.durationUs > 0) (position.toFloat() / p.durationUs).coerceIn(0f, 1f) else 0f,
                            onValueChange = { v -> vm.player.setScrubbing(true); vm.player.seekTo((v * p.durationUs).toLong()) },
                            onValueChangeFinished = { vm.player.setScrubbing(false) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(28.dp),
                        )
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) { Transport(vm, p) }
                }
            } else if (landscape || expanded) {
                // Horizontal / tablet: vista previa a un lado, herramientas al otro y línea de tiempo ancha abajo.
                val sideW = if (expanded) 400.dp else if (medium) 340.dp else maxWidth * 0.4f
                val landBase = if (maxH < 420.dp) maxH * 0.36f else minOf(maxH * 0.4f, 300.dp)
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Box(Modifier.fillMaxWidth().weight(1f)) { Preview(vm, p, Modifier.fillMaxSize()); quickBar() }
                            Transport(vm, p, onResize = { dy -> splitDelta = (splitDelta + dy / splitDensity).coerceIn(landBase.value - maxH.value * 0.7f, landBase.value - 90f) }, onReset = { splitDelta = 0f })
                        }
                        Surface(Modifier.width(sideW).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Column(Modifier.fillMaxSize()) {
                                topBar(Modifier.fillMaxWidth())
                                if (tool.isEmpty()) {
                                    FlowRow(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        for (t in tools) ToolButton(t.icon, stringResource(t.label), accent = if (pro) Color.Unspecified else toolAccent(t.id), onClick = t.action ?: open(t.id))
                                    }
                                } else panel(Modifier.fillMaxSize())
                            }
                        }
                    }
                    Timeline(vm, p, Modifier.fillMaxWidth().height((landBase - splitDelta.dp).coerceIn(90.dp, maxH * 0.7f)), addKind)
                }
            } else {
                // Vertical: vista previa arriba, línea de tiempo en el centro y herramientas abajo.
                Column(Modifier.fillMaxSize()) {
                    topBar(Modifier.fillMaxWidth())
                    // La vista previa mide lo justo para el formato del vídeo (sin franjas) y lo que sobra va a la línea
                    // de tiempo. El tope es el alto que tenía antes, así que un vídeo vertical se ve igual que siempre.
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        val tlMin = if (tool.isEmpty()) minOf(maxH * 0.32f, 280.dp) else 112.dp
                        val fitH = if (p.width > 0) maxWidth * (p.height.toFloat() / p.width) else maxHeight
                        val baseH = minOf(fitH, maxHeight - 44.dp - tlMin).coerceAtLeast(0.dp)
                        val hiH = (maxHeight - 44.dp - 90.dp).coerceAtLeast(96.dp)
                        val previewH = (baseH + splitDelta.dp).coerceIn(96.dp, hiH)
                        if (simple) Column(Modifier.fillMaxSize()) {
                            Preview(vm, p, Modifier.fillMaxWidth().weight(1f))
                            Transport(vm, p)
                        } else Column(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxWidth().height(previewH)) { Preview(vm, p, Modifier.fillMaxSize()); quickBar() }
                            Transport(vm, p, onResize = { dy -> splitDelta = (splitDelta + dy / splitDensity).coerceIn(96f - baseH.value, hiH.value - baseH.value) }, onReset = { splitDelta = 0f })
                            Timeline(vm, p, Modifier.fillMaxWidth().weight(1f), addKind)
                        }
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        if (tool.isEmpty()) toolbarRow() else panel(Modifier.fillMaxWidth().heightIn(max = if (simple) minOf(maxH * 0.5f, 440.dp) else minOf(maxH * 0.4f, 340.dp)))
                    }
                }
            }
            message?.let { Snackbar(Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Text(it) } }
        }
        }
    }

    if (vm.confirmGapDelete.collectAsState().value) {
        com.dskmusic.vixa.ui.ConfirmDialog(stringResource(R.string.gap_at_start), stringResource(R.string.gap_delete_warn),
            onDismiss = { vm.confirmGapDelete.value = false }, onConfirm = { vm.deleteSelected(confirmed = true) })
    }
    busy?.let { b ->
        Dialog(onDismissRequest = {}) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(24.dp).width(260.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(b.message)
                    if (b.progress >= 0f) LinearProgressIndicator({ b.progress }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton({ vm.cancelBusy() }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
    pick?.let { r ->
        when (r.kind) {
            PickKind.MEDIA -> MediaPickerDialog(
                container,
                filter = when (r.target) { AddTarget.AUDIO -> PickFilter.AUDIO; AddTarget.STICKER -> PickFilter.IMAGES; AddTarget.MAIN -> PickFilter.ANY; else -> PickFilter.VISUAL },
                multi = r.multi,
                stickers = r.target == AddTarget.STICKER,
                colorActions = if (r.target == AddTarget.MAIN) ColorActions(vm.settings.colorDurationMs, { fill, d, atStart -> vm.addColor(fill, d, atStart) }, { from -> fadeAsk = from }) else null,
                onDismiss = { pick = null },
                onPicked = { paths -> pick = null; r.onPicked(paths) },
            )
            PickKind.FILE -> FileBrowserDialog(stringResource(R.string.choose_file), r.exts, r.multi, onDismiss = { pick = null }) { paths -> pick = null; r.onPicked(paths) }
            PickKind.FOLDER -> FileBrowserDialog(stringResource(R.string.choose_folder), folderMode = true, onDismiss = { pick = null }) { paths -> pick = null; r.onPicked(paths) }
        }
    }
    fadeAsk?.let { from -> project?.let { p -> FadeDialog(vm, p, from) { fadeAsk = null } } }
    vm.stickerAsk.collectAsState().value?.let { paths ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.stickerAsk.value = null },
            title = { Text(stringResource(R.string.sticker_animated_title)) },
            text = { Text(stringResource(R.string.sticker_animated_msg)) },
            confirmButton = { androidx.compose.material3.TextButton({ vm.addAnimatedStickers(paths, true) }) { Text(stringResource(R.string.sticker_as_animated)) } },
            dismissButton = { androidx.compose.material3.TextButton({ vm.addAnimatedStickers(paths, false) }) { Text(stringResource(R.string.sticker_as_static)) } },
        )
    }
    vm.audioRangeAsk.collectAsState().value?.let { m ->
        var range by remember(m.path) { mutableStateOf(0f..m.durationUs.toFloat()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.audioRangeAsk.value = null },
            title = { Text(stringResource(R.string.audio_range_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.audio_range_msg), style = MaterialTheme.typography.bodySmall)
                    Text(com.dskmusic.vixa.ui.formatTime(range.start.toLong()) + "  –  " + com.dskmusic.vixa.ui.formatTime(range.endInclusive.toLong()) +
                        "   (" + com.dskmusic.vixa.ui.formatTime((range.endInclusive - range.start).toLong()) + ")", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                    // Un solo control con dos tiradores: inicio y fin del tramo.
                    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                    androidx.compose.material3.RangeSlider(range, { r -> range = r }, valueRange = 0f..m.durationUs.toFloat(), modifier = Modifier.padding(top = 4.dp))
                }
            },
            confirmButton = { androidx.compose.material3.TextButton({ vm.addAudioRange(m, range.start.toLong(), range.endInclusive.toLong().coerceAtLeast(range.start.toLong() + 500_000L)) }) { Text(stringResource(R.string.audio_add_selection)) } },
            dismissButton = { androidx.compose.material3.TextButton({ vm.addAudioRange(m, 0L, m.durationUs) }) { Text(stringResource(R.string.audio_add_whole)) } },
        )
    }
    FavoritesHost()
    if (trackAsk && clip != null && clip.kind == ClipKind.VIDEO && clip.media != null) TrackDialog(vm, clip, { pick = it }) { trackAsk = false }
    if (splitAsk) androidx.compose.material3.AlertDialog(
        onDismissRequest = { splitAsk = false },
        title = { Text(stringResource(R.string.split), style = MaterialTheme.typography.titleMedium) },
        text = {
            // Tres opciones con icono y etiqueta corta, en una sola fila.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for ((k, ic, r) in listOf(Triple(0, Icons.Default.ContentCut, R.string.split_both_short), Triple(1, Icons.Default.SkipPrevious, R.string.split_keep_before_short), Triple(2, Icons.Default.SkipNext, R.string.split_keep_after_short))) {
                    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { splitAsk = false; vm.splitAtPlayhead(k) }.padding(8.dp).width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.material3.Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(48.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(ic, null) }
                        }
                        Text(stringResource(r), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        },
        confirmButton = {},
    )
    morphPaths?.let { ps -> MorphOptionsDialog(vm, ps) { morphPaths = null } }
    val morphEditId by vm.morphEdit.collectAsState()
    morphEditId?.let { id -> MorphEditorDialog(vm, id) { vm.morphEdit.value = null } }
    if (exporting) ExportDialog(container, p, position, onDismiss = { exporting = false }, onSetCover = { t -> vm.edit(R.string.hist_cover) { it.copy(coverUs = t) }; vm.toast(vm.str(R.string.cover_set)) })
}

private fun toolTitle(tool: String): Int = when (tool) {
    "speed" -> R.string.speed
    "volume" -> R.string.volume
    "transform" -> R.string.transform
    "crop" -> R.string.crop
    "animation" -> R.string.animation
    "filter" -> R.string.filters
    "adjust" -> R.string.adjust
    "effects" -> R.string.effects
    "transition" -> R.string.transition
    "chroma" -> R.string.chroma_key
    "mask" -> R.string.mask
    "zone" -> R.string.zone
    "keyframes" -> R.string.keyframes
    "text" -> R.string.text
    "templates" -> R.string.templates
    "stickers" -> R.string.stickers
    "subtitles" -> R.string.subtitles
    "audiofx" -> R.string.audio_fx
    "voiceover" -> R.string.voice_over
    "canvas" -> R.string.canvas
    "extras" -> R.string.extras
    "project" -> R.string.project
    "history" -> R.string.history
    "ai" -> R.string.ai_title
    else -> R.string.tools
}

@Composable
private fun ToolPanel(vm: EditorViewModel, p: Project, tool: String, clip: Clip?, pick: (PickRequest) -> Unit, onOpenProject: (String) -> Unit) {
    when (tool) {
        "templates" -> TemplatesPanel(vm, p)
        "stickers" -> StickersPanel(vm, clip?.takeIf { it.kind == ClipKind.STICKER }, pick)
        "subtitles" -> SubtitlesPanel(vm, p, pick)
        "voiceover" -> VoiceOverPanel(vm)
        "canvas" -> CanvasPanel(vm, p)
        "extras" -> ExtrasPanel(vm, p, pick)
        "project" -> ProjectPanel(vm, p, pick, onOpenProject)
        "history" -> HistoryPanel(vm)
        "ai" -> AiPanel(vm, p, clip, pick)
        else -> {
            if (clip == null) { Text(stringResource(R.string.select_clip_hint), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); return }
            when (tool) {
                "speed" -> SpeedPanel(vm, clip)
                "volume" -> VolumePanel(vm, clip)
                "transform" -> TransformPanel(vm, clip)
                "crop" -> CropPanel(vm, clip)
                "animation" -> AnimationPanel(vm, clip)
                "filter" -> FilterPanel(vm, clip) { pick(PickRequest(PickKind.FILE, exts = setOf("cube")) { f -> f.firstOrNull()?.let { path -> vm.updateClip(R.string.hist_filter, clip.id) { it.copy(lutPath = path, filterIntensity = 1f) } } }) }
                "adjust" -> AdjustPanel(vm, clip)
                "effects" -> EffectsPanel(vm, clip)
                "transition" -> TransitionPanel(vm, clip, p.trackOf(clip.id)?.clips?.firstOrNull()?.id == clip.id)
                "chroma" -> ChromaPanel(vm, clip)
                "mask" -> MaskPanel(vm, clip)
                "zone" -> ZonePanel(vm, clip)
                "keyframes" -> KeyframesPanel(vm, clip)
                "text" -> TextPanel(vm, p, clip, pick)
                "audiofx" -> AudioFxPanel(vm, p, clip)
            }
        }
    }
}

/** Deshacer / rehacer; con pulsación larga abre la lista de pasos para saltar a uno concreto. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HistoryButton(vm: EditorViewModel, undo: Boolean) {
    var open by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val tick = vm.historyTick.collectAsState().value // (.value lee ya; con `by` nunca se leía y no recomponía) fuerza recomposición al cambiar el historial
    val enabled = if (undo) vm.canUndo else vm.canRedo
    Box {
        Box(
            Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .combinedClickable(enabled = enabled, onClick = { if (undo) vm.undo() else vm.redo() }, onLongClick = { open = true }),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (undo) Icons.AutoMirrored.Filled.Undo else Icons.AutoMirrored.Filled.Redo, stringResource(if (undo) R.string.undo else R.string.redo),
                tint = LocalContentColor.current.copy(alpha = if (enabled) 1f else 0.38f))
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }, Modifier.heightIn(max = 360.dp)) {
            val tick by vm.historyTick.collectAsState()
            val list = remember(tick, open) { vm.historyList() }
            val pos = vm.historyPosition()
            for (i in list.indices.reversed()) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text((i + 1).toString() + ".  " + list[i].label, color = if (i == pos) MaterialTheme.colorScheme.primary else if (i > pos) LocalContentColor.current.copy(alpha = 0.45f) else LocalContentColor.current, maxLines = 1) },
                    onClick = { vm.jumpTo(i); open = false },
                )
            }
        }
    }
}
