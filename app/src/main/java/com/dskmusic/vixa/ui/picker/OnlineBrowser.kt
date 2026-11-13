package com.dskmusic.vixa.ui.picker

import androidx.compose.foundation.layout.heightIn
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.online.OnlineItem
import com.dskmusic.vixa.data.online.OnlineType
import com.dskmusic.vixa.data.online.Providers
import com.dskmusic.vixa.data.online.Query
import com.dskmusic.vixa.ui.formatTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun typeLabel(t: OnlineType): Int = when (t) {
    OnlineType.IMAGE -> R.string.online_images
    OnlineType.ILLUSTRATION -> R.string.online_illustrations
    OnlineType.VIDEO -> R.string.online_videos
    OnlineType.GIF -> R.string.online_gifs
    OnlineType.MUSIC -> R.string.online_music
    OnlineType.SFX -> R.string.online_sfx
}

/** Reproductor de vista previa (vídeo o audio) con controles: reproducir/pausa, ±10 s y barra de progreso. */
@Composable
fun StreamPreview(url: String, video: Boolean, modifier: Modifier = Modifier, aspect: Float = 16f / 9f, thumbUrl: String = "", autoplay: Boolean = true) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            repeatMode = Player.REPEAT_MODE_ONE
            prepare()
            playWhenReady = autoplay
        }
    }
    DisposableEffect(url) { onDispose { player.release() } }
    var playing by remember(url) { mutableStateOf(autoplay) }
    DisposableEffect(url) {
        val unguard = com.dskmusic.vixa.engine.PlaybackGuard.register { player.pause(); playing = false }
        onDispose { unguard() }
    }
    var loading by remember(url) { mutableStateOf(true) }
    var progress by remember(url) { mutableFloatStateOf(0f) }
    var durationMs by remember(url) { mutableLongStateOf(0L) }
    LaunchedEffect(url) {
        while (true) {
            playing = player.isPlaying
            loading = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
            val d = player.duration
            durationMs = if (d > 0) d else 0L
            if (d > 0) progress = (player.currentPosition.toFloat() / d).coerceIn(0f, 1f)
            kotlinx.coroutines.delay(200)
        }
    }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
            if (video) {
                // Alto máx. 280dp (deja sitio a los botones de abrir/compartir en vídeos verticales) y la superficie se ajusta a la proporción del vídeo dentro de ese espacio (sin recortar en vertical).
                AndroidView(factory = { SurfaceView(it).also { v -> player.setVideoSurfaceView(v) } }, modifier = Modifier.heightIn(max = 280.dp).aspectRatio(aspect.coerceIn(0.4f, 2.5f)))
            } else {
                Box(Modifier.fillMaxWidth().height(if (thumbUrl.isNotEmpty()) 180.dp else 72.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    if (thumbUrl.isNotEmpty()) AsyncImage(thumbUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else Icon(Icons.Default.MusicNote, null, Modifier.size(36.dp))
                }
            }
            if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.size(36.dp))
        }
        // Controles fuera del fotograma, sobre fondo propio: no se confunden con el vídeo ni quedan tapados.
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            androidx.compose.material3.Slider(progress, { v -> progress = v; if (durationMs > 0) player.seekTo((v * durationMs).toLong()) }, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                IconButton({ player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0)) }) { Icon(Icons.Default.Replay10, null) }
                IconButton({ if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }; playing = !playing }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(32.dp))
                }
                IconButton({ player.seekTo(player.currentPosition + 10_000L) }) { Icon(Icons.Default.Forward10, null) }
                Text(formatTime(player.currentPosition * 1000L, false) + " / " + formatTime(durationMs * 1000L, false), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * Recursos online organizados por tipo de contenido. Mezcla los resultados de todas las fuentes y
 * etiqueta cada elemento con su origen. Al elegir uno se descarga (con progreso) y se devuelve su ruta.
 */
@Composable
fun OnlineBrowser(container: AppContainer, types: List<OnlineType>, modifier: Modifier = Modifier, stickersFirst: Boolean = false, onPicked: (String) -> Unit) {
    val repo = container.online
    val settings by container.settings.flow.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lang = if (context.resources.configuration.locales[0].language == "es") "es" else "en"
    var type by remember { mutableStateOf(types.first()) }
    /** 0 = explorar, 1 = favoritos, 2 = descargados. */
    var view by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var orientation by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("") }
    var duration by remember { mutableIntStateOf(0) }
    var stickers by remember { mutableStateOf(stickersFirst) }
    var sources by remember { mutableStateOf<Set<String>>(emptySet()) }
    var items by remember { mutableStateOf<List<OnlineItem>>(emptyList()) }
    var failed by remember { mutableStateOf<List<String>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<OnlineItem?>(null) }
    var downloading by remember { mutableStateOf<OnlineItem?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    val favorites by repo.favorites.collectAsState()
    val downloads by repo.downloads.collectAsState()

    fun load(reset: Boolean) {
        if (loading && !reset) return
        job?.cancel()
        val p = if (reset) 1 else page + 1
        loading = true
        job = scope.launch {
            try {
                val r = repo.search(Query(type, text, p, lang, settings.nsfw, orientation, color, duration, stickers), sources)
                items = if (reset) r.items else (items + r.items).distinctBy { it.key }
                failed = r.failed
                hasMore = r.hasMore && r.items.isNotEmpty()
                page = p
            } finally {
                loading = false
            }
        }
    }
    // Al entrar en un tipo se carga contenido por defecto; cualquier filtro relanza la búsqueda.
    LaunchedEffect(type, text, orientation, color, duration, stickers, sources, settings.nsfw, settings.disabledProviders, settings.apiKeys) { items = emptyList(); load(true) }

    fun choose(item: OnlineItem) {
        if (downloading != null) return
        downloading = item
        progress = 0f
        scope.launch {
            val f = repo.download(item) { progress = it }
            downloading = null
            preview = null
            if (f != null) onPicked(f.path) else failed = listOf(Providers.get(item.provider)?.name ?: item.provider)
        }
    }

    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in types) FilterChip(view == 0 && type == t, { type = t; view = 0; sources = emptySet(); color = ""; duration = 0 }, { Text(stringResource(typeLabel(t))) })
            FilterChip(view == 1, { view = 1 }, { Text(stringResource(R.string.favorites)) })
            FilterChip(view == 2, { view = 2 }, { Text(stringResource(R.string.downloaded)) })
        }
        if (view == 0) {
            OutlinedTextField(
                input, { input = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (input.isNotEmpty()) IconButton({ input = ""; text = "" }) { Icon(Icons.Default.Close, stringResource(R.string.clear)) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { text = input.trim() }),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Fuentes (filtro opcional).
                for (p in Providers.forType(type).filter { repo.isEnabled(it.id) }) {
                    FilterChip(p.id in sources, { sources = if (p.id in sources) sources - p.id else sources + p.id }, { Text(p.name) })
                }
                if (type == OnlineType.GIF) FilterChip(stickers, { stickers = !stickers }, { Text(stringResource(R.string.stickers)) })
                if (type == OnlineType.IMAGE || type == OnlineType.ILLUSTRATION || type == OnlineType.VIDEO) {
                    for ((v, r) in listOf("landscape" to R.string.landscape, "portrait" to R.string.portrait, "square" to R.string.square)) {
                        FilterChip(orientation == v, { orientation = if (orientation == v) "" else v }, { Text(stringResource(r)) })
                    }
                }
                if (type == OnlineType.IMAGE || type == OnlineType.ILLUSTRATION) {
                    for ((v, c) in listOf("red" to 0xFFE53935, "orange" to 0xFFFB8C00, "yellow" to 0xFFFDD835, "green" to 0xFF43A047, "blue" to 0xFF1E88E5, "black" to 0xFF111111, "white" to 0xFFFFFFFF)) {
                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(c)).clickable { color = if (color == v) "" else v }, contentAlignment = Alignment.Center) {
                            if (color == v) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = if (v == "white" || v == "yellow") Color.Black else Color.White)
                        }
                    }
                }
                if (type == OnlineType.MUSIC || type == OnlineType.SFX) {
                    for ((v, r) in listOf(1 to R.string.dur_short, 2 to R.string.dur_medium, 3 to R.string.dur_long)) FilterChip(duration == v, { duration = if (duration == v) 0 else v }, { Text(stringResource(r)) })
                }
            }
            // Etiquetas sugeridas.
            val tags = remember(type) { Providers.forType(type).flatMap { it.suggestions(type) }.distinct().take(14) }
            if (text.isEmpty() && tags.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in tags) AssistChip({ input = t; text = t }, { Text(t, fontSize = 12.sp) })
                }
            }
            if (failed.isNotEmpty()) {
                Text(stringResource(R.string.sources_unavailable, failed.joinToString(", ")), Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val shown = when (view) {
            1 -> favorites.filter { it.type in types }
            2 -> downloads.filter { it.type in types }
            else -> items
        }
        val shownType = shown.firstOrNull()?.type ?: type
        if (loading && shown.isEmpty()) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else if (shown.isEmpty()) Text(stringResource(R.string.no_results), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        val audio = view == 0 && (type == OnlineType.MUSIC || type == OnlineType.SFX) || (view != 0 && (shownType == OnlineType.MUSIC || shownType == OnlineType.SFX))
        if (audio) {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(shown, key = { _, it -> it.key }) { i, item ->
                    if (view == 0 && hasMore && i >= shown.size - 4) LaunchedEffect(page) { load(false) }
                    Row(Modifier.fillMaxWidth().clickable { preview = item }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                            if (item.thumbUrl.isNotEmpty()) AsyncImage(item.thumbUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(Icons.Default.MusicNote, null)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(listOf(item.author, formatTime((item.durationSec * 1_000_000).toLong(), false), Providers.get(item.provider)?.name ?: item.provider).filter { it.isNotEmpty() }.joinToString(" · "),
                                maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton({ repo.toggleFavorite(item) }) { Icon(if (favorites.any { it.key == item.key }) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(R.string.favorite)) }
                    }
                }
            }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(shown, key = { _, it -> it.key }) { i, item ->
                    if (view == 0 && hasMore && i >= shown.size - 6) LaunchedEffect(page) { load(false) }
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable { preview = item }) {
                        AsyncImage(item.thumbUrl, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        // Etiqueta de origen.
                        Text(Providers.get(item.provider)?.name ?: item.provider, Modifier.align(Alignment.BottomStart).padding(3.dp).background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                            color = Color.White, fontSize = 9.sp, lineHeight = 10.sp)
                        if (item.durationSec > 0f) Text(formatTime((item.durationSec * 1_000_000).toLong(), false), Modifier.align(Alignment.TopEnd).padding(3.dp).background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                            color = Color.White, fontSize = 9.sp, lineHeight = 10.sp)
                    }
                }
                if (loading && shown.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(8.dp)) }
            }
        }
    }

    preview?.let { item ->
        val isVideo = item.type == OnlineType.VIDEO
        val isAudio = item.type == OnlineType.MUSIC || item.type == OnlineType.SFX
        AlertDialog(
            onDismissRequest = { if (downloading == null) preview = null },
            title = { Text(item.title.ifBlank { stringResource(typeLabel(item.type)) }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    val ratio = if (item.width > 0 && item.height > 0) (item.width.toFloat() / item.height).coerceIn(0.5f, 2f) else 1.6f
                    if (isVideo || isAudio) StreamPreview(item.previewUrl.ifEmpty { item.downloadUrl }, isVideo, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)), ratio, item.thumbUrl)
                    else AsyncImage(item.previewUrl.ifEmpty { item.thumbUrl }, item.title, Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
                    Text(listOf(Providers.get(item.provider)?.name ?: item.provider, item.author, if (item.width > 0) item.width.toString() + "×" + item.height else "").filter { it.isNotEmpty() }.joinToString(" · "),
                        Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (downloading?.key == item.key) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = { Button({ choose(item) }, enabled = downloading == null) { Text(stringResource(if (repo.isDownloaded(item)) R.string.add else R.string.download_and_add)) } },
            dismissButton = {
                Row {
                    IconButton({ repo.toggleFavorite(item) }) { Icon(if (favorites.any { it.key == item.key }) Icons.Default.Favorite else Icons.Default.FavoriteBorder, stringResource(R.string.favorite)) }
                    if (view == 2) TextButton({ repo.removeDownload(item); preview = null }) { Text(stringResource(R.string.delete)) }
                    TextButton({ preview = null }, enabled = downloading == null) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}
