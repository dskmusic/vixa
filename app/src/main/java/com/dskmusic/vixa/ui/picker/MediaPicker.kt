package com.dskmusic.vixa.ui.picker

import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.FolderOpen
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.data.online.OnlineType
import com.dskmusic.vixa.media.Importer
import com.dskmusic.vixa.media.MediaType
import com.dskmusic.vixa.model.ColorFill
import com.dskmusic.vixa.ui.BasicPalette
import com.dskmusic.vixa.ui.ColorDot
import com.dskmusic.vixa.ui.ColorPickerDialog
import com.dskmusic.vixa.ui.PreciseSlider
import com.dskmusic.vixa.ui.SectionTitle
import com.dskmusic.vixa.ui.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class DeviceMedia(val path: String, val video: Boolean, val album: String, val date: Long, val durationMs: Long, val title: String = "", val artist: String = "")

// Valores especiales del filtro de la galería (no pueden coincidir con el nombre de una carpeta).
private const val KIND_VIDEOS = "\u0000videos"
private const val KIND_IMAGES = "\u0000images"

/** Qué tipo de medios se pueden elegir. */
enum class PickFilter { VISUAL, AUDIO, IMAGES, ANY }

/** Acciones del apartado Color (solo al añadir a la pista principal). */
class ColorActions(
    val defaultDurationMs: Int,
    val onAdd: (fill: ColorFill, durationUs: Long, atStart: Boolean?) -> Unit,
    val onFade: (fromBlack: Boolean) -> Unit,
)

private fun queryGallery(context: Context, images: Boolean, videos: Boolean): List<DeviceMedia> {
    val out = ArrayList<DeviceMedia>()
    fun run(video: Boolean) {
        val uri = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val cols = if (video) arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DURATION)
        else arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED)
        try {
            context.contentResolver.query(uri, cols, null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    out.add(DeviceMedia(path, video, c.getString(1) ?: "", c.getLong(2), if (video) c.getLong(3) else 0L))
                }
            }
        } catch (e: Throwable) {
            ErrorLog.log("Gallery", e)
        }
    }
    if (images) run(false)
    if (videos) run(true)
    return out.sortedByDescending { it.date }
}

private fun queryAudio(context: Context): List<DeviceMedia> {
    val out = ArrayList<DeviceMedia>()
    try {
        val cols = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.Audio.Media.ALBUM, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.TITLE, MediaStore.Audio.Media.ARTIST)
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(0) ?: continue
                out.add(DeviceMedia(path, false, c.getString(1) ?: "", c.getLong(2), c.getLong(3), c.getString(4) ?: File(path).name, c.getString(5) ?: ""))
            }
        }
    } catch (e: Throwable) {
        ErrorLog.log("Audio", e)
    }
    return out
}

/** Panel "Añadir medios": Dispositivo, Online y Color. Se muestra a pantalla completa. */
@Composable
fun MediaPickerDialog(
    container: AppContainer,
    filter: PickFilter,
    multi: Boolean,
    stickers: Boolean = false,
    colorActions: ColorActions? = null,
    onDismiss: () -> Unit,
    onPicked: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var section by remember { mutableIntStateOf(if (filter == PickFilter.AUDIO) 1 else 0) }
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var previewing by remember { mutableStateOf<DeviceMedia?>(null) }
    var camera by remember { mutableStateOf(false) }
    var gallery by remember { mutableStateOf<List<DeviceMedia>>(emptyList()) }
    var audios by remember { mutableStateOf<List<DeviceMedia>>(emptyList()) }
    var album by remember { mutableStateOf("") }
    val settings by container.settings.flow.collectAsState()
    val visual = filter != PickFilter.AUDIO
    val wantAudio = filter == PickFilter.AUDIO || filter == PickFilter.ANY
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (visual) gallery = queryGallery(context, true, filter != PickFilter.IMAGES)
            if (wantAudio) audios = queryAudio(context)
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Selector del sistema (otras apps / explorador de archivos); copia los archivos a la app y los devuelve.
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val paths = uris.take(if (multi) uris.size else 1).mapNotNull { Importer.copyUri(context, it) }
            if (paths.isNotEmpty()) { onDismiss(); onPicked(paths) }
        }
    }
    fun browse() {
        systemPicker.launch(if (filter == PickFilter.IMAGES) arrayOf("image/*") else if (filter == PickFilter.AUDIO) arrayOf("audio/*") else arrayOf("image/*", "video/*"))
    }
    fun toggle(path: String) {
        if (!multi) { onDismiss(); onPicked(listOf(path)) } else selected = if (path in selected) selected - path else selected + path
    }
    val tabs = buildList {
        add(R.string.tab_device); add(R.string.tab_online)
        if (colorActions != null) add(R.string.tab_color)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.add_media), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (multi && selected.isNotEmpty()) Button({ onDismiss(); onPicked(selected) }) { Text(stringResource(R.string.add_n, selected.size)) }
                    IconButton(onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                }
                // Cada pestaña lleva un icono: dispositivo (móvil), online (globo) y color (paleta).
                TabRow(tab) {
                    for ((i, r) in tabs.withIndex()) {
                        val ic = when (r) { R.string.tab_device -> Icons.Default.PhoneAndroid; R.string.tab_online -> Icons.Default.Public; else -> Icons.Default.Palette }
                        Tab(tab == i, { tab = i }, text = { Text(stringResource(r)) }, icon = { Icon(ic, null, Modifier.size(22.dp)) })
                    }
                }
                when (tab) {
                    0 -> {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (visual) FilterChip(section == 0, { section = 0 }, { Text(stringResource(R.string.gallery)) })
                            if (wantAudio) FilterChip(section == 1, { section = 1 }, { Text(stringResource(R.string.audio)) })
                            FilterChip(section == 2, { section = 2 }, { Text(stringResource(R.string.files)) })
                            FilterChip(section == 3, { section = 3 }, { Text(stringResource(R.string.recent)) })
                            if (visual) AssistChip({ camera = true }, { Text(stringResource(R.string.camera)) }, leadingIcon = { Icon(Icons.Default.PhotoCamera, null, Modifier.size(16.dp)) })
                        }
                        when (section) {
                            0 -> {
                                val albums = remember(gallery) { gallery.map { it.album }.distinct().sorted() }
                                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(album.isEmpty(), { album = "" }, { Text(stringResource(R.string.all)) })
                                    // Dos categorías con el listado completo por tipo, antes de las carpetas.
                                    if (filter != PickFilter.IMAGES) FilterChip(album == KIND_VIDEOS, { album = KIND_VIDEOS }, { Text(stringResource(R.string.gallery_videos)) })
                                    FilterChip(album == KIND_IMAGES, { album = KIND_IMAGES }, { Text(stringResource(R.string.gallery_images)) })
                                    for (a in albums) FilterChip(album == a, { album = a }, { Text(a.ifEmpty { "/" }, maxLines = 1) })
                                }
                                val list = when (album) {
                                    "" -> gallery
                                    KIND_VIDEOS -> gallery.filter { it.video }
                                    KIND_IMAGES -> gallery.filter { !it.video }
                                    else -> gallery.filter { it.album == album }
                                }
                                if (list.isEmpty()) Text(stringResource(R.string.no_media_found), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                MediaGrid(list, selected, { toggle(it.path) }, { previewing = it }, ::browse)
                            }
                            1 -> AudioList(audios, selected) { toggle(it.path) }
                            2 -> FileBrowser(Modifier.fillMaxSize(), multi = multi, mediaOnly = true) { onDismiss(); onPicked(it) }
                            else -> {
                                val recents = remember(settings.recentMedia) {
                                    settings.recentMedia.filter { File(it).exists() }.map { DeviceMedia(it, Importer.typeOf(it) == MediaType.VIDEO, "", 0, 0, File(it).name) }
                                        .filter { m -> val t = Importer.typeOf(m.path); if (filter == PickFilter.AUDIO) t == MediaType.AUDIO else if (filter == PickFilter.ANY) true else t != MediaType.AUDIO }
                                }
                                if (recents.isEmpty()) Text(stringResource(R.string.no_recent), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (filter == PickFilter.AUDIO) AudioList(recents, selected) { toggle(it.path) } else MediaGrid(recents, selected, { toggle(it.path) }, { previewing = it }, ::browse)
                            }
                        }
                    }
                    1 -> {
                        val types = when {
                            stickers -> listOf(OnlineType.GIF, OnlineType.IMAGE, OnlineType.ILLUSTRATION)
                            filter == PickFilter.AUDIO -> listOf(OnlineType.MUSIC, OnlineType.SFX)
                            filter == PickFilter.IMAGES -> listOf(OnlineType.IMAGE, OnlineType.ILLUSTRATION, OnlineType.GIF)
                            filter == PickFilter.VISUAL -> listOf(OnlineType.IMAGE, OnlineType.ILLUSTRATION, OnlineType.VIDEO, OnlineType.GIF)
                            else -> OnlineType.entries.toList()
                        }
                        OnlineBrowser(container, types, Modifier.fillMaxSize().padding(top = 4.dp), stickersFirst = stickers) { path -> toggle(path) }
                    }
                    else -> if (colorActions != null) ColorTab(colorActions, onDismiss)
                }
            }
        }
    }
    previewing?.let { m ->
        Dialog(onDismissRequest = { previewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { previewing = null }, contentAlignment = Alignment.Center) {
                if (m.video) {
                    AndroidView(factory = { ctx ->
                        FrameLayout(ctx).apply {
                            val v = VideoView(ctx)
                            addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
                            v.setVideoPath(m.path)
                            v.setOnPreparedListener { it.isLooping = true; v.start() }
                            v.setOnErrorListener { _, _, _ -> true }
                        }
                    }, modifier = Modifier.fillMaxSize())
                } else AsyncImage(File(m.path), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                Button({ toggle(m.path); previewing = null }, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp)) { Text(stringResource(R.string.add)) }
            }
        }
    }
    if (camera) CameraDialog(onDismiss = { camera = false }) { path -> camera = false; onDismiss(); onPicked(listOf(path)) }
}

@Composable
private fun MediaGrid(list: List<DeviceMedia>, selected: List<String>, onClick: (DeviceMedia) -> Unit, onLongClick: (DeviceMedia) -> Unit, onBrowse: (() -> Unit)? = null) {
    LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (onBrowse != null) item(key = "browse") {
            Column(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onBrowse), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.FolderOpen, null, Modifier.size(28.dp))
                Text(stringResource(R.string.browse_files), fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        items(list, key = { it.path }) { m ->
            val idx = selected.indexOf(m.path)
            Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .combinedClickable(onClick = { onClick(m) }, onLongClick = { onLongClick(m) })) {
                AsyncImage(File(m.path), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (m.video) Text(if (m.durationMs > 0) formatTime(m.durationMs * 1000L, false) else "▶", Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                    color = Color.White, fontSize = 10.sp, lineHeight = 11.sp)
                if (idx >= 0) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)).border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)))
                    Text((idx + 1).toString(), Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape).padding(top = 2.dp),
                        color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun AudioList(list: List<DeviceMedia>, selected: List<String>, onClick: (DeviceMedia) -> Unit) {
    var playing by remember { mutableStateOf("") }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        val unguard = com.dskmusic.vixa.engine.PlaybackGuard.register { try { player.stop() } catch (e: Throwable) { }; playing = "" }
        onDispose { unguard(); try { player.release() } catch (e: Throwable) { } }
    }
    // Buscador por título, artista o nombre de archivo.
    var query by remember { mutableStateOf("") }
    val shown = remember(list, query) {
        val q = query.trim()
        if (q.isEmpty()) list else list.filter { it.title.contains(q, true) || it.artist.contains(q, true) || File(it.path).name.contains(q, true) }
    }
    androidx.compose.material3.OutlinedTextField(
        query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), singleLine = true,
        placeholder = { Text(stringResource(R.string.search)) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, null) } },
    )
    if (shown.isEmpty()) Text(stringResource(R.string.no_media_found), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(Modifier.fillMaxSize()) {
        items(shown, key = { it.path }) { m ->
            val sel = m.path in selected
            Row(Modifier.fillMaxWidth().clickable { onClick(m) }.background(if (sel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({
                    try {
                        if (playing == m.path) { player.stop(); playing = "" } else {
                            player.reset(); player.setDataSource(m.path); player.prepare(); player.start(); playing = m.path
                        }
                    } catch (e: Throwable) { playing = "" }
                }) { Icon(if (playing == m.path) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(R.string.play_pause)) }
                Column(Modifier.weight(1f)) {
                    Text(m.title.ifEmpty { File(m.path).name }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(listOf(m.artist.takeIf { it.isNotEmpty() && it != "<unknown>" } ?: "", if (m.durationMs > 0) formatTime(m.durationMs * 1000L, false) else "").filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (sel) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) else Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private class GradPreset(val colors: List<Int>, val angle: Float, val radial: Boolean = false)

private fun argb(v: Long): Int = v.toInt()

/** Degradados de partida: colores, ángulo y tipo. Los últimos terminan o empiezan transparentes (para fundidos y viñetas). */
private val GRADIENT_PRESETS = listOf(
    GradPreset(listOf(argb(0xFFFF5E62), argb(0xFFFF9966)), 90f),
    GradPreset(listOf(argb(0xFF00C6FF), argb(0xFF0072FF)), 90f),
    GradPreset(listOf(argb(0xFF11998E), argb(0xFF38EF7D)), 135f),
    GradPreset(listOf(argb(0xFF7F00FF), argb(0xFFE100FF)), 135f),
    GradPreset(listOf(argb(0xFFFC466B), argb(0xFF3F5EFB)), 45f),
    GradPreset(listOf(argb(0xFF0F2027), argb(0xFF203A43), argb(0xFF2C5364)), 90f),
    GradPreset(listOf(argb(0xFFF7971E), argb(0xFFFFD200)), 90f),
    GradPreset(listOf(argb(0xFFFF0844), argb(0xFFFFB199)), 135f),
    GradPreset(listOf(argb(0xFF1A2980), argb(0xFF26D0CE)), 135f),
    GradPreset(listOf(argb(0xFFFF512F), argb(0xFFDD2476)), 135f),
    GradPreset(listOf(argb(0xFFFF9A9E), argb(0xFFFAD0C4), argb(0xFFFBC2EB)), 45f),
    GradPreset(listOf(argb(0xFFFF0000), argb(0xFFFFFF00), argb(0xFF00FF00), argb(0xFF00FFFF)), 0f),
    GradPreset(listOf(argb(0xFF000000), argb(0x00000000)), 90f),
    GradPreset(listOf(argb(0x00000000), argb(0xFF000000)), 90f),
    GradPreset(listOf(argb(0xFFFFFFFF), argb(0x00FFFFFF)), 90f),
    GradPreset(listOf(argb(0xFFFFFFFF), argb(0x00FFFFFF)), 0f, radial = true),
    GradPreset(listOf(argb(0x00000000), argb(0xCC000000)), 0f, radial = true),
)

/** Vista previa del relleno sobre un fondo de cuadros (para ver la transparencia), con las mismas fórmulas que el shader. */
@Composable
private fun GradientPreview(colors: List<Int>, angle: Float, radial: Boolean, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val sq = 10.dp.toPx()
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = 0f
            var col = row
            while (x < size.width) {
                drawRect(if (col % 2 == 0) Color(0xFF55555F) else Color(0xFF3A3A44), androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Size(sq, sq))
                x += sq; col++
            }
            y += sq; row++
        }
        if (colors.size == 1) { drawRect(Color(colors[0])); return@Canvas }
        val w = size.width
        val h = size.height
        val centre = androidx.compose.ui.geometry.Offset(w / 2f, h / 2f)
        val list = colors.map { Color(it) }
        val brush = if (radial) Brush.radialGradient(list, centre, kotlin.math.hypot(w, h) / 2f)
        else {
            val a = Math.toRadians(angle.toDouble())
            val dx = Math.cos(a).toFloat()
            val dy = Math.sin(a).toFloat()
            val half = (kotlin.math.abs(dx) * w + kotlin.math.abs(dy) * h) / 2f
            Brush.linearGradient(list, androidx.compose.ui.geometry.Offset(centre.x - dx * half, centre.y - dy * half), androidx.compose.ui.geometry.Offset(centre.x + dx * half, centre.y + dy * half))
        }
        drawRect(brush)
    }
}

/** Apartado Color: color sólido o degradado (lineal o radial, de 2 a 4 colores, con transparencia) y accesos rápidos de negro y fundidos. */
@Composable
private fun ColorTab(actions: ColorActions, onDismiss: () -> Unit) {
    // 0 = sólido, 1 = degradado lineal, 2 = degradado radial.
    var mode by remember { mutableIntStateOf(0) }
    var stops by remember { mutableStateOf(listOf(argb(0xFF000000), argb(0xFF7C5CFF))) }
    var angle by remember { mutableFloatStateOf(90f) }
    var secs by remember { mutableFloatStateOf(actions.defaultDurationMs / 1000f) }
    var editing by remember { mutableIntStateOf(-1) }
    val fill = if (mode == 0) ColorFill(listOf(stops[0])) else ColorFill(stops, angle, radial = mode == 2)
    val durUs = (secs * 1_000_000f).toLong()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        SectionTitle(stringResource(R.string.quick_actions))
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ actions.onAdd(ColorFill(), durUs, true); onDismiss() }) { Text(stringResource(R.string.black_at_start)) }
            Button({ actions.onAdd(ColorFill(), durUs, false); onDismiss() }) { Text(stringResource(R.string.black_at_end)) }
            AssistChip({ actions.onFade(true); onDismiss() }, { Text(stringResource(R.string.fade_from_black)) })
            AssistChip({ actions.onFade(false); onDismiss() }, { Text(stringResource(R.string.fade_to_black)) })
        }
        SectionTitle(stringResource(R.string.color))
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == 0, { mode = 0 }, { Text(stringResource(R.string.color_solid)) })
            FilterChip(mode == 1, { mode = 1 }, { Text(stringResource(R.string.gradient_linear)) })
            FilterChip(mode == 2, { mode = 2 }, { Text(stringResource(R.string.gradient_radial)) })
        }
        GradientPreview(fill.colors, fill.angle, fill.radial, Modifier.padding(16.dp).fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)))
        if (mode == 0) {
            FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({ stops = listOf(argb(0xFF000000)) + stops.drop(1) }, { Text(stringResource(R.string.black)) })
                AssistChip({ stops = listOf(argb(0xFFFFFFFF)) + stops.drop(1) }, { Text(stringResource(R.string.white)) })
                AssistChip({ editing = 0 }, { Text(stringResource(R.string.custom)) })
                for (c in BasicPalette) ColorDot(c, c == stops[0], 32.dp) { stops = listOf(c) + stops.drop(1) }
            }
        } else {
            SectionTitle(stringResource(R.string.gradient_presets))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (pr in GRADIENT_PRESETS) {
                    GradientPreview(
                        pr.colors, pr.angle, pr.radial,
                        Modifier.size(56.dp, 40.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .clickable { stops = pr.colors; angle = pr.angle; mode = if (pr.radial) 2 else 1 },
                    )
                }
            }
            SectionTitle(stringResource(R.string.gradient_stops))
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((i, c) in stops.withIndex()) ColorDot(c, false, 36.dp) { editing = i }
                if (stops.size < 4) AssistChip({ stops = stops + stops.last() }, { Text("+") })
                if (stops.size > 2) AssistChip({ stops = stops.dropLast(1) }, { Text("−") })
            }
            Text(stringResource(R.string.gradient_stops_hint), Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({ stops = stops.reversed() }, { Text(stringResource(R.string.gradient_reverse)) })
                AssistChip({ stops = listOf(stops.first() and 0x00FFFFFF) + stops.drop(1) }, { Text(stringResource(R.string.gradient_fade_start)) })
                AssistChip({ stops = stops.dropLast(1) + (stops.last() and 0x00FFFFFF) }, { Text(stringResource(R.string.gradient_fade_end)) })
            }
            if (mode == 1) PreciseSlider(stringResource(R.string.angle), angle, { angle = it }, range = 0f..360f, default = 90f, format = { String.format(Locale.US, "%.0f°", it) }, inputScale = 1f)
        }
        PreciseSlider(stringResource(R.string.duration), secs, { secs = it }, range = 0.2f..30f, default = actions.defaultDurationMs / 1000f, format = { String.format(Locale.US, "%.1f s", it) }, inputScale = 1f)
        Button({ actions.onAdd(fill, durUs, null); onDismiss() }, Modifier.padding(16.dp)) { Text(stringResource(R.string.add_color_clip)) }
    }
    // El diálogo permite elegir también la opacidad: con ella un lado del degradado (o el color sólido) queda transparente.
    if (editing >= 0 && editing < stops.size) ColorPickerDialog(stops[editing], true, { editing = -1 }) { c -> stops = stops.mapIndexed { i, s -> if (i == editing) c else s }; editing = -1 }
}

/** Cámara integrada (CameraX): foto o vídeo, sin depender de otra app. */
@Composable
fun CameraDialog(onDismiss: () -> Unit, onCaptured: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> granted = r[Manifest.permission.CAMERA] == true }
    LaunchedEffect(Unit) { if (!granted) ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
    var videoMode by remember { mutableStateOf(true) }
    var front by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val videoCapture = remember { VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()) }
    val dir = remember { File(Importer.mediaDir(context), "camera").apply { mkdirs() } }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }

    LaunchedEffect(granted, front, videoMode) {
        if (!granted) return@LaunchedEffect
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val preview = androidx.camera.core.Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                provider.unbindAll()
                provider.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview, if (videoMode) videoCapture else imageCapture)
            } catch (e: Throwable) {
                ErrorLog.log("Camera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
    DisposableEffect(Unit) {
        onDispose {
            try { recording?.stop() } catch (e: Throwable) { }
            try { ProcessCameraProvider.getInstance(context).get().unbindAll() } catch (e: Throwable) { }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) AndroidView({ previewView }, Modifier.fillMaxSize())
            else Text(stringResource(R.string.err_camera_permission), Modifier.align(Alignment.Center).padding(24.dp), color = Color.White)
            IconButton(onDismiss, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) { Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White) }
            if (recording != null) Text("● " + formatTime(seconds * 1_000_000L, false), Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp), color = Color.Red)
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                IconButton({ if (recording == null) videoMode = !videoMode }) { Icon(if (videoMode) Icons.Default.PhotoCamera else Icons.Default.Videocam, stringResource(R.string.camera_mode), tint = Color.White) }
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(if (recording != null) Color.Red else Color.White).border(4.dp, Color.Gray, CircleShape).clickable(enabled = granted) {
                        val rec = recording
                        if (rec != null) { rec.stop(); return@clickable }
                        try {
                            if (videoMode) {
                                val file = File(dir, "VID_" + System.currentTimeMillis() + ".mp4")
                                var pending = videoCapture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
                                seconds = 0
                                recording = pending.start(ContextCompat.getMainExecutor(context)) { ev ->
                                    if (ev is VideoRecordEvent.Status) seconds = (ev.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
                                    if (ev is VideoRecordEvent.Finalize) {
                                        recording = null
                                        if (!ev.hasError() && file.length() > 0) onCaptured(file.path) else { file.delete(); ErrorLog.log("Camera", "finalize error " + ev.error) }
                                    }
                                }
                            } else {
                                val file = File(dir, "IMG_" + System.currentTimeMillis() + ".jpg")
                                imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) { onCaptured(file.path) }
                                    override fun onError(e: ImageCaptureException) { ErrorLog.log("Camera", e) }
                                })
                            }
                        } catch (e: Throwable) {
                            ErrorLog.log("Camera", e)
                        }
                    },
                )
                IconButton({ if (recording == null) front = !front }) { Icon(Icons.Default.Cameraswitch, stringResource(R.string.switch_camera), tint = Color.White) }
            }
        }
    }
}
