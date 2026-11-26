package com.dskmusic.vixa.ui

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.animateColor
import android.content.Context
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.data.ProjectInfo
import com.dskmusic.vixa.engine.Exporter
import com.dskmusic.vixa.media.Importer
import com.dskmusic.vixa.model.ExportKind
import com.dskmusic.vixa.model.ExportSettings
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.Quality
import com.dskmusic.vixa.model.VideoCodec
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.addToTrack
import com.dskmusic.vixa.model.insertMain
import com.dskmusic.vixa.model.newId
import com.dskmusic.vixa.ui.editor.ASPECTS
import com.dskmusic.vixa.ui.editor.sizeFor
import com.dskmusic.vixa.ui.picker.FileBrowserDialog
import com.dskmusic.vixa.ui.picker.MediaPickerDialog
import com.dskmusic.vixa.ui.picker.PickFilter
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Crea (sin guardar) un proyecto con los medios dados en la pista principal, con el lienzo del primero. */
suspend fun projectFromMedia(context: Context, container: AppContainer, paths: List<String>, name: String = ""): Project? {
    val refs = paths.mapNotNull { Importer.probe(context, it) }
    if (refs.isEmpty()) return null
    val s = container.settings.value
    val first = refs.firstOrNull { it.hasVideo || it.isImage }
    val w = ((first?.width ?: 1080) / 2 * 2).coerceAtLeast(2)
    val h = ((first?.height ?: 1920) / 2 * 2).coerceAtLeast(2)
    val fps = (first?.takeIf { !it.isImage }?.fps ?: s.defFps.toFloat()).let { Math.round(it) }.coerceIn(12, 60)
    val now = System.currentTimeMillis()
    val clips = refs.map { Importer.clipFor(it, s.imageDurationMs * 1000L) }
    val visual = clips.filter { it.isVisual }
    var p = Project(id = newId(), name = name.ifBlank { File(paths.first()).nameWithoutExtension }, createdAt = now, modifiedAt = now, width = w, height = h, fps = fps)
    p = p.insertMain(visual)
    for (a in clips.filter { !it.isVisual }) p = p.addToTrack(a, TrackType.AUDIO)
    return p
}

private class QuickTool(val id: String, val icon: ImageVector, val label: Int, val desc: Int)

@Composable
fun HomeScreen(
    container: AppContainer,
    incoming: List<String>,
    onIncomingConsumed: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onTutorial: () -> Unit = {},
    onHelp: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.flow.collectAsState()
    var projects by remember { mutableStateOf<List<ProjectInfo>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var quick by remember { mutableStateOf<QuickTool?>(null) }
    var quickProject by remember { mutableStateOf<Project?>(null) }
    var quickTool by remember { mutableStateOf<QuickTool?>(null) }
    var aiTool by remember { mutableStateOf<Pair<String, String>?>(null) }
    var aiResult by remember { mutableStateOf<AiQuickResult?>(null) }
    CollectAiMessages(container) { error = it }
    var pickOriginal by remember { mutableStateOf<String?>(null) }
    var assistant by remember { mutableStateOf(false) }
    var assistantInitial by remember { mutableStateOf<List<String>>(emptyList()) }
    var shared by remember { mutableStateOf<List<String>?>(null) }
    var trashed by remember { mutableStateOf<List<Pair<ProjectInfo, Int>>>(emptyList()) }
    var trashView by remember { mutableStateOf(false) }
    var purgeAsk by remember { mutableStateOf<ProjectInfo?>(null) }
    var emptyAsk by remember { mutableStateOf(false) }
    // Renombrar desde las tarjetas de "recientes": el diálogo vive aquí y no dentro de la fila perezosa, que desmonta la
    // tarjeta (y con ella el diálogo y lo escrito) cuando el teclado encoge la pantalla y la fila queda fuera de la vista.
    var renaming by remember { mutableStateOf<ProjectInfo?>(null) }
    val storeChanges by container.projects.changes.collectAsState()
    LaunchedEffect(reload, storeChanges) { projects = container.projects.list(); trashed = container.projects.trashed() }

    // Proyecto demo (menú de ayuda): se monta en segundo plano con una ventanita de "Preparando…" y se abre al terminar.
    var demoBusy by remember { mutableStateOf(false) }
    fun startDemo() {
        if (demoBusy) return
        demoBusy = true
        scope.launch {
            try {
                val es = context.resources.configuration.locales[0].language == "es"
                val r = com.dskmusic.vixa.engine.DemoBuilder.build(context, container, es)
                val saved = container.projects.save(r.project, updateThumb = true)
                demoBusy = false
                if (r.musicTitle.isBlank()) android.widget.Toast.makeText(context, R.string.demo_no_music, android.widget.Toast.LENGTH_LONG).show()
                onOpen(saved.id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                demoBusy = false
                throw e
            } catch (e: Throwable) {
                ErrorLog.log("Demo", e)
                demoBusy = false
                error = context.getString(R.string.demo_error)
            }
        }
    }

    // El proyecto demo del primer arranque lo prepara DemoSeeder (en segundo plano): al terminar, se recarga la lista.
    val seed by com.dskmusic.vixa.engine.DemoSeeder.state.collectAsState()
    LaunchedEffect(seed) { if (seed == com.dskmusic.vixa.engine.DemoSeeder.State.DONE) reload++ }

    fun createAndOpen(p: Project) {
        scope.launch { val saved = container.projects.save(p, updateThumb = true); onOpen(saved.id) }
    }
    fun fromMedia(paths: List<String>, name: String) {
        busy = true
        scope.launch {
            val p = projectFromMedia(context, container, paths, name)
            busy = false
            if (p == null) error = context.getString(R.string.err_unsupported_media, paths.joinToString(", ") { File(it).name }.take(100)) else createAndOpen(p)
        }
    }
    /** Importa un .vixa y lo abre. El indicador de carga se quita siempre, también si falla o se cancela. */
    fun importProject(path: String) {
        if (busy) return
        busy = true
        scope.launch {
            var id: String? = null
            try {
                id = container.projects.importVixa(android.net.Uri.fromFile(File(path)))?.id
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                com.dskmusic.vixa.data.ErrorLog.log("ImportVixa", e)
            } finally {
                busy = false
            }
            reload++
            if (id != null) onOpen(id) else error = context.getString(R.string.err_project_load)
        }
    }

    // Medios recibidos con "Compartir con / Abrir con": proyecto nuevo con ellos (o importación de .vixa).
    LaunchedEffect(incoming) {
        if (incoming.isEmpty()) return@LaunchedEffect
        val list = incoming
        onIncomingConsumed()
        val vixa = list.firstOrNull { it.lowercase().endsWith(".vixa") }
        // Se importa en el ámbito de la pantalla, no en el de este efecto: onIncomingConsumed() cambia la clave y
        // cancelaba la importación a medias (el proyecto se guardaba pero la pantalla se quedaba "cargando").
        if (vixa != null) importProject(vixa) else shared = list
    }
    shared?.let { list ->
        AlertDialog(
            onDismissRequest = { shared = null },
            title = { Text(stringResource(R.string.share_ask_title)) },
            text = { Text(stringResource(R.string.share_ask_text)) },
            confirmButton = { TextButton({ shared = null; assistantInitial = list; assistant = true }) { Text(stringResource(R.string.share_ask_assistant)) } },
            dismissButton = { TextButton({ shared = null; fromMedia(list, "") }) { Text(stringResource(R.string.share_ask_project)) } },
        )
    }

    val tools = remember {
        listOf(
            QuickTool("compress", Icons.Default.Compress, R.string.qt_compress, R.string.qd_compress), QuickTool("convert", Icons.Default.SwapHoriz, R.string.qt_convert, R.string.qd_convert),
            QuickTool("trim", Icons.Default.ContentCut, R.string.qt_trim, R.string.qd_trim), QuickTool("audio", Icons.Default.MusicNote, R.string.qt_extract_audio, R.string.qd_audio),
            QuickTool("gif", Icons.Default.Gif, R.string.qt_gif, R.string.qd_gif), QuickTool("join", Icons.Default.Merge, R.string.qt_join, R.string.qd_join),
        )
    }
    val shown = (projects ?: emptyList()).filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }.let { l ->
        when (sort) { 1 -> l.sortedBy { it.name.lowercase() }; 2 -> l.sortedByDescending { it.durationUs }; else -> l.sortedByDescending { it.modifiedAt } }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AnimatedLogo(Modifier.weight(1f).padding(start = 4.dp))
                            HelpMenuButton(onTutorial, onHelp, onDemo = { startDemo() })
                            IconButton({ opening = true }) { Icon(Icons.Default.FolderOpen, stringResource(R.string.open_vixa)) }
                            IconButton(onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                        }
                        // Asistente de vídeo: montaje automático al ritmo de la música.
                        Surface(
                            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp).clip(RoundedCornerShape(14.dp)).clickable { assistant = true },
                            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(Icons.Default.AutoAwesome, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.as_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(stringResource(R.string.as_home_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                        SectionTitle(stringResource(R.string.quick_tools), Modifier.padding(start = 0.dp))
                        AiJobBanner(container)
                        // Dos por fila: icono, nombre completo y una línea que explica para qué sirve cada herramienta.
                        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 2) {
                            for (t in tools) Surface(
                                Modifier.weight(1f).height(68.dp).clip(RoundedCornerShape(12.dp)).clickable { quickProject = null; quick = t },
                                shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(t.icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(t.label), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        Text(stringResource(t.desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.projects_title), Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        // Siempre visible: desde el gestor se llega a la papelera aunque no quede ningún proyecto.
                        TextButton({ showAll = true }) { Text(stringResource(R.string.view_all)) }
                    }
                }
                if (projects == null) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                else if (projects.isNullOrEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Movie, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        if (seed == com.dskmusic.vixa.engine.DemoSeeder.State.RUNNING) Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.demo_preparing_project), style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(stringResource(R.string.no_projects_title), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text(stringResource(R.string.no_projects_hint), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                else item(span = { GridItemSpan(maxLineSpan) }) {
                    // Los tres más recientes con desplazamiento horizontal y, al final, la tarjeta que abre todos los proyectos.
                    val recent = (projects ?: emptyList()).sortedByDescending { it.modifiedAt }.take(3)
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recent, key = { it.id }) { info ->
                            Box(Modifier.width(170.dp)) { ProjectCard(container, info, onOpen, onChanged = { reload++ }, onError = { error = it }, thumbAspect = 1.3f, onRename = { renaming = it }) }
                        }
                        item {
                            // Misma altura que las tarjetas: miniatura (170 / 1,3) + fila del nombre (48). El hueco con el botón
                            // flotante lo da el margen inferior de la rejilla (96 dp).
                            Column(
                                Modifier.width(130.dp).height(179.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable { showAll = true },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.view_all), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                Text(stringResource(R.string.projects_count, projects?.size ?: 0), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            ExtendedFloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp), icon = { Icon(Icons.Default.Add, null) }, text = { Text(stringResource(R.string.new_project)) })
            if (busy) Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }

    // Gestor de proyectos: ventana completa con búsqueda, orden y todos los proyectos.
    if (showAll) androidx.compose.ui.window.Dialog(onDismissRequest = { showAll = false; trashView = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (trashView) trashView = false else showAll = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(if (trashView) R.string.trash else R.string.projects_manager), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (trashView && trashed.isNotEmpty()) TextButton({ emptyAsk = true }) { Text(stringResource(R.string.trash_empty)) }
                    IconButton({ trashView = !trashView }) {
                        Icon(Icons.Default.Delete, stringResource(R.string.trash), tint = if (trashView) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (trashView) {
                    // Papelera: los proyectos eliminados se pueden restaurar o borrar del todo; caducan solos.
                    Text(stringResource(R.string.trash_note, com.dskmusic.vixa.data.ProjectStore.TRASH_DAYS), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (trashed.isEmpty()) Text(stringResource(R.string.trash_none), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(trashed.size, key = { trashed[it].first.id }) { k ->
                            val (info, days) = trashed[k]
                            val thumb = container.projects.trashThumbFile(info.id)
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(84.dp, 56.dp).background(Color(0xFF15151C))) {
                                    if (thumb.exists()) AsyncImage(ImageRequest.Builder(context).data(thumb).memoryCacheKey(thumb.path + thumb.lastModified()).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(info.name.ifBlank { stringResource(R.string.untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(R.string.trash_days_left, days), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton({ scope.launch { container.projects.restore(info.id); reload++ } }) { Text(stringResource(R.string.restore)) }
                                IconButton({ purgeAsk = info }) { Icon(Icons.Default.Delete, stringResource(R.string.trash_delete_forever)) }
                            }
                        }
                    }
                    purgeAsk?.let { info ->
                        ConfirmDialog(stringResource(R.string.trash_delete_forever), stringResource(R.string.trash_delete_confirm, info.name), { purgeAsk = null }) { purgeAsk = null; scope.launch { container.projects.purge(info.id); reload++ } }
                    }
                    if (emptyAsk) ConfirmDialog(stringResource(R.string.trash_empty), stringResource(R.string.trash_empty_confirm), { emptyAsk = false }) { emptyAsk = false; scope.launch { container.projects.purge(); reload++ } }
                } else {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true, placeholder = { Text(stringResource(R.string.search_projects)) }, leadingIcon = { Icon(Icons.Default.Search, null) })
                Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((i, r) in listOf(R.string.sort_date, R.string.sort_name, R.string.sort_duration).withIndex()) FilterChip(sort == i, { sort = i }, { Text(stringResource(r)) })
                }
                if (shown.isEmpty()) Text(stringResource(R.string.no_projects), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown, key = { it.id }) { info -> ProjectCard(container, info, { showAll = false; onOpen(it) }, onChanged = { reload++ }, onError = { error = it }) }
                }
                }
            }
        }
    }

    if (creating) NewProjectDialog(settings.defAspectW, settings.defAspectH, settings.defShortSide, settings.defFps, { creating = false }) { name, aw, ah, short, fps ->
        creating = false
        if (aw == 0) pickOriginal = name else {
            val (w, h) = sizeFor(aw, ah, short)
            val now = System.currentTimeMillis()
            createAndOpen(Project(id = newId(), name = name, createdAt = now, modifiedAt = now, width = w, height = h, fps = fps))
        }
    }
    if (assistant) AssistantDialog(container, { assistant = false; assistantInitial = emptyList() }, assistantInitial) { id -> assistant = false; onOpen(id) }
    pickOriginal?.let { name ->
        MediaPickerDialog(container, PickFilter.VISUAL, multi = true, onDismiss = { pickOriginal = null }) { paths -> pickOriginal = null; fromMedia(paths, name) }
    }
    renaming?.let { target ->
        TextInputDialog(stringResource(R.string.rename), target.name, { renaming = null }) { n ->
            renaming = null
            val name = n.trim()
            projects = projects?.map { if (it.id == target.id) it.copy(name = name) else it }
            container.appScope.launch {
                val ok = container.projects.rename(target.id, name)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (!ok) error = context.getString(R.string.err_generic)
                    reload++
                }
            }
        }
    }
    if (opening) FileBrowserDialog(stringResource(R.string.open_vixa), exts = setOf("vixa"), onDismiss = { opening = false }) { files ->
        files.firstOrNull()?.let { f -> importProject(f) }
    }
    // Paso 1: elegir el archivo. El selector llama a onDismiss y después a onPicked, así que la herramienta se
    // captura aquí (t) y el paso 2 se abre desde quickProject, no desde quick.
    quick?.let { t ->
        if (quickProject == null) {
            MediaPickerDialog(container, if (t.id == "audio" || t.id in AiQuick.all) PickFilter.ANY else PickFilter.VISUAL, multi = t.id == "join", onDismiss = { if (!busy) quick = null }) { paths ->
                if (t.id in AiQuick.all) { aiTool = t.id to paths.first(); return@MediaPickerDialog }
                busy = true
                scope.launch {
                    val p = projectFromMedia(context, container, paths)
                    busy = false
                    if (p == null || p.durationUs <= 0) { error = context.getString(R.string.err_unsupported_media, ""); quick = null } else { quickTool = t; quickProject = p }
                }
            }
        }
    }
    aiTool?.let { (tool, path) -> AiQuickGate(container, tool, path, { files -> aiResult = AiQuickResult(tool, path, files) }) { aiTool = null; quick = null } }
    // Resultado de una herramienta con IA: se previsualiza, dice dónde se guardó y se puede abrir en un proyecto nuevo.
    aiResult?.let { r ->
        AiResultDialog(r, onBack = { aiResult = null }) {
            aiResult = null
            busy = true
            scope.launch {
                val p = try { AiQuick.buildProject(context, container, r) } catch (e: Throwable) { com.dskmusic.vixa.data.ErrorLog.log("AiQuick", e); null }
                busy = false
                if (p == null) error = context.getString(R.string.err_unsupported_media, "") else createAndOpen(p)
            }
        }
    }
    val qp = quickProject
    val qt = quickTool
    if (qp != null && qt != null) {
        val short = minOf(qp.width, qp.height)
        val nearest = listOf(480, 720, 1080, 1440, 2160).lastOrNull { it <= short } ?: 480
        val base = ExportSettings(shortSide = nearest, fps = qp.fps)
        val (initial, kinds) = when (qt.id) {
            "compress" -> base.copy(shortSide = minOf(nearest, 720), quality = Quality.MEDIUM, codec = if (Exporter.isCodecSupported(VideoCodec.H265)) VideoCodec.H265 else VideoCodec.H264, passthrough = false) to listOf(ExportKind.VIDEO)
            "convert" -> base.copy(passthrough = false) to listOf(ExportKind.VIDEO, ExportKind.AUDIO, ExportKind.GIF)
            "audio" -> base.copy(kind = ExportKind.AUDIO) to listOf(ExportKind.AUDIO)
            "gif" -> base.copy(kind = ExportKind.GIF) to listOf(ExportKind.GIF)
            else -> base to listOf(ExportKind.VIDEO)
        }
        // Recortar y GIF abren con el rango activado: es lo primero que hay que ajustar.
        ExportDialog(container, qp, 0L, onDismiss = { quickProject = null; quickTool = null; quick = null }, initial = initial, allowedKinds = kinds, initialRange = qt.id == "trim" || qt.id == "gif")
    }
    // Ventana cuadrada de bordes redondeados con un spinner mientras se prepara el proyecto demo (no se puede cerrar).
    if (demoBusy) androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(Modifier.size(170.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(48.dp))
                Text(stringResource(R.string.demo_preparing), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
    error?.let { AlertDialog(onDismissRequest = { error = null }, confirmButton = { TextButton({ error = null }) { Text(stringResource(R.string.ok)) } }, text = { Text(it) }) }
}

@Composable
private fun ProjectCard(container: AppContainer, info: ProjectInfo, onOpen: (String) -> Unit, onChanged: () -> Unit, onError: (String) -> Unit, thumbAspect: Float = 1.9f, onRename: ((ProjectInfo) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") }
    // Nombre que se ve en la tarjeta: cambia al instante al renombrar, sin esperar a que se relea la lista.
    var shownName by remember(info.id, info.name) { mutableStateOf(info.name) }
    val thumb = container.projects.thumbFile(info.id)
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable {
        if (info.isTemplate) scope.launch { container.projects.duplicate(info.id, "")?.let { copy -> container.projects.save(copy.copy(isTemplate = false, name = info.name)); onOpen(copy.id) } } else onOpen(info.id)
    }) {
        Box(Modifier.fillMaxWidth().aspectRatio(thumbAspect).background(Color(0xFF15151C))) {
            if (thumb.exists()) AsyncImage(ImageRequest.Builder(context).data(thumb).memoryCacheKey(thumb.path + thumb.lastModified()).build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.Movie, null, Modifier.align(Alignment.Center).size(36.dp), tint = Color(0x66FFFFFF))
            Text(formatTime(info.durationUs, false), Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
            if (info.isTemplate) Text(stringResource(R.string.template), Modifier.align(Alignment.TopStart).padding(6.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(shownName.ifBlank { stringResource(R.string.untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(info.modifiedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.rename)) }, { menu = false; if (onRename != null) onRename(info.copy(name = shownName)) else dialog = "rename" })
                    DropdownMenuItem({ Text(stringResource(R.string.duplicate)) }, { menu = false; scope.launch { container.projects.duplicate(info.id, context.getString(R.string.copy_suffix)); onChanged() } })
                    DropdownMenuItem({ Text(stringResource(R.string.share_project)) }, { menu = false; dialog = "share" })
                    DropdownMenuItem({ Text(stringResource(R.string.export_project)) }, { menu = false; dialog = "export" })
                    DropdownMenuItem({ Text(stringResource(R.string.delete)) }, { menu = false; dialog = "delete" })
                }
            }
        }
    }
    when (dialog) {
        "rename" -> TextInputDialog(stringResource(R.string.rename), shownName, { dialog = "" }) { n ->
            dialog = ""
            val old = shownName
            shownName = n.trim()
            // En el ámbito de la app (no en el de la tarjeta, que puede desaparecer al reordenarse la lista).
            container.appScope.launch {
                val ok = container.projects.rename(info.id, n)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (!ok) { shownName = old; onError(context.getString(R.string.err_generic)) }
                    onChanged()
                }
            }
        }
        "delete" -> ConfirmDialog(stringResource(R.string.delete), stringResource(if (info.durationUs <= 0L) R.string.delete_empty_confirm else R.string.delete_project_confirm, info.name), { dialog = "" }) { scope.launch { container.projects.delete(info.id); onChanged() } }
        "share", "export" -> {
            val share = dialog == "share"
            var working by remember { mutableStateOf(false) }
            fun run(includeMedia: Boolean) {
                working = true
                scope.launch {
                    val p = container.projects.load(info.id)
                    val safe = info.name.ifBlank { "Vixa" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val dest = if (share) File(File(context.cacheDir, "share").apply { mkdirs() }, "$safe.vixa")
                    else File(com.dskmusic.vixa.data.Storage.projects().apply { mkdirs() }, "$safe.vixa")
                    val ok = p != null && container.projects.exportVixa(p, dest, includeMedia)
                    working = false
                    dialog = ""
                    if (!ok) onError(context.getString(R.string.err_generic)) else if (share) shareFile(context, dest, "application/octet-stream") else onError(context.getString(R.string.saved_to, dest.path))
                }
            }
            AlertDialog(
                onDismissRequest = { if (!working) dialog = "" },
                title = { Text(stringResource(if (share) R.string.share_project else R.string.export_project)) },
                text = { if (working) CircularProgressIndicator() else Text(stringResource(R.string.vixa_share_hint)) },
                // Con medios es la opción recomendada: el proyecto no depende de lo que haya en el móvil de quien lo recibe.
                confirmButton = { androidx.compose.material3.Button({ run(true) }, enabled = !working) { Text(stringResource(R.string.with_media)) } },
                dismissButton = { TextButton({ run(false) }, enabled = !working) { Text(stringResource(R.string.project_only)) } },
            )
        }
    }
}

@Composable
private fun NewProjectDialog(defW: Int, defH: Int, defShort: Int, defFps: Int, onDismiss: () -> Unit, onCreate: (name: String, aw: Int, ah: Int, short: Int, fps: Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf(defW to defH) }
    var short by remember { mutableIntStateOf(defShort) }
    var fps by remember { mutableIntStateOf(defFps) }
    val defaultName = stringResource(R.string.new_project)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.new_project), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.name)) })
                Text(stringResource(R.string.aspect_ratio), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (a in ASPECTS) AspectChip(a.first, a.second, aspect == a) { aspect = a }
                    AspectChip(0, 0, aspect.first == 0, stringResource(R.string.original)) { aspect = 0 to 0 }
                }
                if (aspect.first == 0) Text(stringResource(R.string.original_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    Text(stringResource(R.string.resolution), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (s in listOf(480, 720, 1080, 1440, 2160)) FilterChip(short == s, { short = s }, { Text(if (s == 2160) "4K" else s.toString() + "p") })
                    }
                    Text(stringResource(R.string.frame_rate), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (f in listOf(24, 25, 30, 50, 60)) FilterChip(fps == f, { fps = f }, { Text(f.toString()) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
                    TextButton({ onCreate(name.ifBlank { defaultName }, aspect.first, aspect.second, short, fps) }) { Text(stringResource(R.string.create)) }
                }
            }
        }
    }
}

/** Cabecera "Vixa by DSK": nombre grande en negrita, "by DSK" más pequeño, color que cicla entre tres tonos del tema. */
@Composable
private fun AnimatedLogo(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    val color by androidx.compose.animation.core.rememberInfiniteTransition(label = "logo").animateColor(
        initialValue = primary, targetValue = primary,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.keyframes { durationMillis = 7000; primary at 0; tertiary at 2300; secondary at 4600; primary at 7000 },
            androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "logoColor",
    )
    Row(verticalAlignment = Alignment.Bottom, modifier = modifier) {
        Text("Vixa", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = color)
        Spacer(Modifier.width(6.dp))
        Text("by DSK", Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = color)
    }
}
