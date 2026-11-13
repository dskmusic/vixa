@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dskmusic.vixa.ui

import android.graphics.SurfaceTexture
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import com.dskmusic.vixa.engine.ProxyRender
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.engine.AutoEdit
import com.dskmusic.vixa.engine.PreviewPlayer
import com.dskmusic.vixa.media.Importer
import com.dskmusic.vixa.media.MediaType
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.ui.editor.ASPECTS
import com.dskmusic.vixa.ui.picker.MediaPickerDialog
import com.dskmusic.vixa.ui.picker.PickFilter
import com.dskmusic.vixa.engine.SmartFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private class PickedMedia(val id: Int, val path: String)

/**
 * Asistente de vídeo en tres pasos: ajustes, orden de los medios y vista previa del montaje (que no se guarda hasta
 * abrirlo en el editor). Genera un proyecto normal con los cortes al ritmo de la música.
 */
@Composable
fun AssistantDialog(container: AppContainer, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<PickedMedia>>(emptyList()) }
    var nextId by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf("") }
    var outro by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf(0 to 0) }
    var target by remember { mutableIntStateOf(0) }
    var customOn by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    var moodId by remember { mutableStateOf("") }
    var music by remember { mutableStateOf(AutoEdit.MusicMode.AUTO) }
    var ownMusic by remember { mutableStateOf("") }
    var keepAudio by remember { mutableStateOf(false) }
    var duck by remember { mutableStateOf(true) }
    var fadeMode by remember { mutableIntStateOf(AutoEdit.Fade.BOTH) }
    var fadeColor by remember { mutableIntStateOf(0xFF000000.toInt()) }
    var picking by remember { mutableStateOf("") }
    var stage by remember { mutableIntStateOf(R.string.as_stage_media) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<AutoEdit.Result?>(null) }
    var proposals by remember { mutableStateOf<List<AutoEdit.Result>>(emptyList()) }
    var pace by remember { mutableFloatStateOf(1f) }
    var smart by remember { mutableStateOf(true) }
    var bestSeg by remember { mutableStateOf(true) }
    var subs by remember { mutableStateOf(false) }
    var three by remember { mutableStateOf(false) }
    var previewFull by remember { mutableStateOf(false) }
    val running = job != null
    // Salir del asistente con trabajo hecho (medios, textos, música propia o un montaje generado) pierde los cambios: se avisa.
    var confirmExit by remember { mutableStateOf(false) }
    val hasChanges = items.isNotEmpty() || title.isNotBlank() || outro.isNotBlank() || ownMusic.isNotEmpty() || result != null || proposals.isNotEmpty()
    fun tryExit() { if (hasChanges) confirmExit = true else onDismiss() }
    // Atrás (botón del dispositivo y flecha de la cabecera): sale de pantalla completa; desde la vista previa vuelve a la
    // lista de propuestas si las hay; si no, retrocede un paso. Solo desde los ajustes se sale del asistente.
    fun goBack() {
        when {
            previewFull -> previewFull = false
            step == 2 -> step = if (proposals.size > 1) 3 else if (items.size > 1) 1 else 0
            step == 3 -> step = if (items.size > 1) 1 else 0
            step == 1 -> step = 0
            else -> tryExit()
        }
    }
    val customSec = customText.toIntOrNull()?.coerceIn(5, 3600) ?: 0
    val effectiveTarget = if (customOn) customSec else target
    val ready = items.isNotEmpty() && (music != AutoEdit.MusicMode.OWN || ownMusic.isNotEmpty()) && (!customOn || customSec > 0)

    /** mode: 0 = desde cero, 1 = otra música (mismo estilo), 2 = otras transiciones (misma música y estilo). */
    fun generate(mode: Int = 0) {
        val es = java.util.Locale.getDefault().language == "es"
        val prev = result
        previewFull = false
        fun req(mood: String) = AutoEdit.Request(
            items.map { it.path }, title, outro, aspect, effectiveTarget, mood, music, ownMusic, keepAudio, keepAudio && duck,
            fadeMode, fadeColor, container.settings.value.defShortSide.coerceIn(480, 1440), es,
            pace = pace, smart = smart, bestSegments = bestSeg, subtitles = subs,
            reuseMusic = if (mode == 2) prev?.music else null, reuseBeats = if (mode == 2 && prev?.music != null) prev.beats else null,
        )
        val fixedMood = if (mode != 0 && prev != null) prev.mood.id else moodId
        stage = R.string.as_stage_media
        progress = 0f
        job = scope.launch {
            try {
                if (three && mode == 0) {
                    // Tres montajes a la vez: con estilo elegido, tres variantes de él; con «Sorpréndeme», tres estilos distintos.
                    val ids = if (moodId.isEmpty()) AutoEdit.moods.filter { it.id !in AutoEdit.occasionIds }.shuffled().take(3).map { it.id } else List(3) { moodId }
                    val done = FloatArray(3)
                    val rs = coroutineScope {
                        ids.mapIndexed { k, id ->
                            async { AutoEdit.build(context, container, req(id)) { st, pr -> stage = st; done[k] = pr; progress = done.average().toFloat() } }
                        }.awaitAll()
                    }
                    proposals = rs
                    step = 3
                } else {
                    val r = AutoEdit.build(context, container, req(fixedMood)) { st, pr -> stage = st; progress = pr }
                    result = r
                    // Al repetir música o transiciones, la propuesta de la lista se sustituye por la nueva.
                    proposals = if (mode == 0) emptyList() else proposals.map { if (it === prev) r else it }
                    step = 2
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ErrorLog.log("AutoEdit", e)
                error = context.getString(R.string.as_err)
            } finally {
                job = null
            }
        }
    }

    Dialog(onDismissRequest = { if (!running && !saving) goBack() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val r = result
                when {
                    running -> GeneratingView(stage, progress) { job?.cancel(); job = null }
                    step == 2 && r != null -> key(r.project.id) {
                        PreviewStep(
                            r.project,
                            info = buildString {
                                append(stringResource(r.mood.nameRes) + " · " + r.bpm + " BPM\n")
                                append(if (r.musicTitle.isNotEmpty()) "♪ " + r.musicTitle else stringResource(R.string.as_no_music))
                                for (w in r.warnings) append("\n" + stringResource(w))
                            },
                            saving = saving,
                            onBack = { goBack() },
                            onModify = { previewFull = false; step = if (items.size > 1) 1 else 0 },
                            full = previewFull,
                            onFull = { previewFull = !previewFull },
                            onAnother = { generate(0) },
                            onNewMusic = if (music == AutoEdit.MusicMode.AUTO) ({ generate(1) }) else null,
                            onNewTransitions = { generate(2) },
                            onOpen = {
                                saving = true
                                scope.launch {
                                    try {
                                        val saved = container.projects.save(r.project, updateThumb = true)
                                        onOpen(saved.id)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Throwable) {
                                        ErrorLog.log("AutoEdit/save", e)
                                        error = context.getString(R.string.as_err)
                                        saving = false
                                    }
                                }
                            },
                        )
                    }
                    step == 3 && proposals.isNotEmpty() -> ProposalsStep(
                        proposals,
                        onPick = { result = it; step = 2 },
                        onBack = { step = if (items.size > 1) 1 else 0 },
                    )
                    step == 1 -> OrderStep(
                        items,
                        onMove = { from, to ->
                            val l = items.toMutableList()
                            l.add(to, l.removeAt(from))
                            items = l
                        },
                        onRemove = { i ->
                            items = items.filterIndexed { k, _ -> k != i }
                            if (items.isEmpty()) step = 0
                        },
                        onShuffle = { items = items.shuffled() },
                        onSortDate = { scope.launch { items = withContext(Dispatchers.IO) { items.sortedBy { SmartFrame.dateOf(it.path) } } } },
                        onBack = { step = 0 },
                        onCreate = { generate(0) },
                    )
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ tryExit() }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.as_title), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.as_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Button({ picking = "media" }) { Text(stringResource(R.string.as_pick)) }
                                if (items.isNotEmpty()) {
                                    Text(stringResource(R.string.as_picked, items.size), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
                                    Text(stringResource(R.string.as_picked_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                                }
                            }

                            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text(stringResource(R.string.as_name)) })
                            OutlinedTextField(outro, { outro = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.as_outro)) })

                            SectionTitle(stringResource(R.string.as_mood))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                MoodCard(Icons.Default.AutoAwesome, MaterialTheme.colorScheme.primary, stringResource(R.string.as_mood_surprise), moodId.isEmpty()) { moodId = "" }
                                for (m in AutoEdit.moods) MoodCard(m.icon, Color(m.color), stringResource(m.nameRes), moodId == m.id) { moodId = m.id }
                            }
                            val sel = AutoEdit.mood(moodId)
                            Text(
                                stringResource(sel?.descRes ?: R.string.as_mood_surprise_d), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                            )

                            SectionTitle(stringResource(R.string.as_music))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(music == AutoEdit.MusicMode.AUTO, { music = AutoEdit.MusicMode.AUTO }, { Text(stringResource(R.string.as_music_auto)) })
                                FilterChip(music == AutoEdit.MusicMode.OWN, { music = AutoEdit.MusicMode.OWN }, { Text(stringResource(R.string.as_music_own)) })
                                FilterChip(music == AutoEdit.MusicMode.NONE, { music = AutoEdit.MusicMode.NONE }, { Text(stringResource(R.string.as_music_none)) })
                            }
                            when (music) {
                                AutoEdit.MusicMode.AUTO -> Text(stringResource(R.string.as_music_auto_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                AutoEdit.MusicMode.OWN -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton({ picking = "music" }) { Text(stringResource(R.string.as_music_pick)) }
                                    if (ownMusic.isNotEmpty()) Text(File(ownMusic).name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                }
                                AutoEdit.MusicMode.NONE -> {}
                            }
                            if (music != AutoEdit.MusicMode.NONE) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(keepAudio, { keepAudio = it })
                                    Text(stringResource(R.string.as_keep_audio), style = MaterialTheme.typography.bodyMedium)
                                }
                                if (keepAudio) Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(duck, { duck = it })
                                    Column {
                                        Text(stringResource(R.string.as_duck), style = MaterialTheme.typography.bodyMedium)
                                        Text(stringResource(R.string.as_duck_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }

                            SectionTitle(stringResource(R.string.as_fade))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for ((mode, label) in listOf(
                                    AutoEdit.Fade.NONE to R.string.as_fade_none, AutoEdit.Fade.START to R.string.as_fade_start,
                                    AutoEdit.Fade.END to R.string.as_fade_end, AutoEdit.Fade.BOTH to R.string.as_fade_both,
                                )) FilterChip(fadeMode == mode, { fadeMode = mode }, { Text(stringResource(label)) })
                            }
                            if (fadeMode != AutoEdit.Fade.NONE) {
                                Text(stringResource(R.string.as_fade_color), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                ColorRow(fadeColor, { fadeColor = it })
                            }

                            SectionTitle(stringResource(R.string.as_recipes))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for ((label, a, sec) in listOf(
                                    Triple(R.string.as_recipe_short, 9 to 16, 30), Triple(R.string.as_recipe_story, 9 to 16, 15),
                                    Triple(R.string.as_recipe_insta, 4 to 5, 30), Triple(R.string.as_recipe_youtube, 16 to 9, 0),
                                )) FilterChip(aspect == a && !customOn && target == sec, { aspect = a; customOn = false; target = sec }, { Text(stringResource(label)) })
                            }

                            SectionTitle(stringResource(R.string.as_format))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AspectChip(0, 0, aspect.first == 0, stringResource(R.string.as_format_auto)) { aspect = 0 to 0 }
                                for (a in ASPECTS) AspectChip(a.first, a.second, aspect == a) { aspect = a }
                            }

                            SectionTitle(stringResource(R.string.as_pace) + " · ×" + String.format(java.util.Locale.US, "%.2f", pace))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.as_pace_calm), style = MaterialTheme.typography.labelSmall)
                                Slider(pace, { pace = it }, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = 0.5f..2f, steps = 5)
                                Text(stringResource(R.string.as_pace_fast), style = MaterialTheme.typography.labelSmall)
                            }

                            SectionTitle(stringResource(R.string.as_duration))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(!customOn && target == 0, { customOn = false; target = 0 }, { Text(stringResource(R.string.as_dur_auto)) })
                                for (s in listOf(10, 15, 20, 30, 45, 60, 90, 120, 180)) FilterChip(!customOn && target == s, { customOn = false; target = s }, { Text(stringResource(R.string.as_dur_n, s)) })
                                FilterChip(customOn, { customOn = true }, { Text(stringResource(R.string.as_dur_custom)) })
                            }
                            if (customOn) OutlinedTextField(
                                customText, { v -> customText = v.filter { it.isDigit() }.take(4) }, Modifier.fillMaxWidth(), singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), label = { Text(stringResource(R.string.as_dur_custom_label)) },
                            )
                            Text(
                                stringResource(if (customOn) R.string.as_dur_custom_hint else R.string.as_duration_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(smart, { smart = it })
                                Column {
                                    Text(stringResource(R.string.as_smart), style = MaterialTheme.typography.bodyMedium)
                                    Text(stringResource(R.string.as_smart_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(bestSeg, { bestSeg = it })
                                Column {
                                    Text(stringResource(R.string.as_best), style = MaterialTheme.typography.bodyMedium)
                                    Text(stringResource(R.string.as_best_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(subs, { subs = it })
                                Column {
                                    Text(stringResource(R.string.as_subs), style = MaterialTheme.typography.bodyMedium)
                                    Text(stringResource(R.string.as_subs_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(three, { three = it })
                                Column {
                                    Text(stringResource(R.string.as_three), style = MaterialTheme.typography.bodyMedium)
                                    Text(stringResource(R.string.as_three_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        }
                        Row(Modifier.fillMaxWidth().padding(12.dp)) {
                            Button({ if (items.size > 1) step = 1 else generate(0) }, Modifier.fillMaxWidth(), enabled = ready) {
                                Text(stringResource(if (items.size > 1) R.string.as_continue else R.string.as_create))
                            }
                        }
                    }
                }
            }
        }
    }

    if (picking == "media") MediaPickerDialog(container, PickFilter.VISUAL, multi = true, onDismiss = { picking = "" }) { paths ->
        picking = ""
        items = paths.map { PickedMedia(nextId++, it) }
    }
    if (picking == "music") MediaPickerDialog(container, PickFilter.AUDIO, multi = false, onDismiss = { picking = "" }) { paths -> picking = ""; paths.firstOrNull()?.let { ownMusic = it } }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text(stringResource(R.string.as_exit_title)) },
        text = { Text(stringResource(R.string.as_exit_text)) },
        confirmButton = { TextButton({ confirmExit = false; onDismiss() }) { Text(stringResource(R.string.as_exit_confirm)) } },
        dismissButton = { TextButton({ confirmExit = false }) { Text(stringResource(R.string.as_exit_stay)) } },
    )
    error?.let { AlertDialog(onDismissRequest = { error = null }, confirmButton = { TextButton({ error = null }) { Text(stringResource(R.string.ok)) } }, text = { Text(it) }) }
}

/** Tarjeta de estilo: círculo de color plano con el icono blanco y el nombre debajo. */
@Composable
private fun MoodCard(icon: ImageVector, color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(78.dp).clip(shape)
            .background(if (selected) color.copy(alpha = 0.16f) else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) color else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = Color.White)
        }
        Text(label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GeneratingView(stage: Int, progress: Float, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.AutoAwesome, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(stage), Modifier.padding(top = 16.dp, bottom = 12.dp), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        LinearProgressIndicator({ progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
        OutlinedButton(onCancel, Modifier.padding(top = 24.dp)) { Text(stringResource(R.string.cancel)) }
    }
}

/** Paso 2: lista de medios con miniatura y un tirador de tres líneas para reordenarlos arrastrando con el dedo. */
@Composable
private fun ColumnScope.OrderStep(
    items: List<PickedMedia>, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit, onShuffle: () -> Unit, onSortDate: () -> Unit, onBack: () -> Unit, onCreate: () -> Unit,
) {
    var dragId by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 64.dp.toPx() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.as_order_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.as_order_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onSortDate) { Text(stringResource(R.string.as_sort_date)) }
        IconButton(onShuffle) { Icon(Icons.Default.Shuffle, stringResource(R.string.as_shuffle)) }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
            val idx by rememberUpdatedState(index)
            val count by rememberUpdatedState(items.size)
            val dragging = dragId == item.id
            Surface(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).height(58.dp).zIndex(if (dragging) 1f else 0f)
                    .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                    .then(if (dragging) Modifier else Modifier.animateItem()),
                shape = RoundedCornerShape(10.dp),
                color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
                shadowElevation = if (dragging) 8.dp else 0.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((index + 1).toString(), Modifier.width(32.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AsyncImage(File(item.path), null, Modifier.size(50.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                    Row(Modifier.weight(1f).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (Importer.typeOf(item.path) == MediaType.VIDEO) Icons.Default.Videocam else Icons.Default.Image, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(File(item.path).name, Modifier.padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton({ onRemove(index) }) { Icon(Icons.Default.Close, stringResource(R.string.delete), Modifier.size(18.dp)) }
                    Box(
                        Modifier.size(48.dp).pointerInput(item.id) {
                            detectDragGestures(
                                onDragStart = { dragId = item.id; dragOffset = 0f },
                                onDragEnd = { dragId = -1; dragOffset = 0f },
                                onDragCancel = { dragId = -1; dragOffset = 0f },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                    // Al pasar de la mitad de una fila se intercambia con la vecina y el dedo sigue sobre el elemento.
                                    if (dragOffset > rowPx / 2f && idx < count - 1) { onMove(idx, idx + 1); dragOffset -= rowPx }
                                    else if (dragOffset < -rowPx / 2f && idx > 0) { onMove(idx, idx - 1); dragOffset += rowPx }
                                },
                            )
                        },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onBack, Modifier.weight(1f)) { Text(stringResource(R.string.back)) }
        Button(onCreate, Modifier.weight(1f), enabled = items.isNotEmpty()) { Text(stringResource(R.string.as_create)) }
    }
}

/** Elección entre las tres propuestas generadas a la vez. */
@Composable
private fun ColumnScope.ProposalsStep(options: List<AutoEdit.Result>, onPick: (AutoEdit.Result) -> Unit, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.as_proposals_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.as_proposals_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((i, r) in options.withIndex()) Card(Modifier.fillMaxWidth().clickable { onPick(r) }) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Color(r.mood.color)), contentAlignment = Alignment.Center) {
                    Icon(r.mood.icon, null, Modifier.size(26.dp), tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text((i + 1).toString() + ". " + stringResource(r.mood.nameRes), style = MaterialTheme.typography.titleMedium)
                    Text(clock(r.project.durationUs) + " · " + r.bpm + " BPM · " + r.project.mainTrack.clips.size + " " + stringResource(R.string.as_shots), style = MaterialTheme.typography.bodySmall)
                    Text(if (r.musicTitle.isNotEmpty()) "♪ " + r.musicTitle else stringResource(R.string.as_no_music), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button({ onPick(r) }) { Text(stringResource(R.string.as_view)) }
            }
        }
    }
}

/**
 * Paso 3: vista previa del montaje. Primero se renderiza a un vídeo pequeño (es lo que da fluidez: la reproducción en vivo
 * se corta al pasar de vídeo a imagen) y se reproduce con play y barra de búsqueda. Si el render falla, o el usuario no quiere
 * esperar, se usa la vista en vivo del editor. El proyecto no se guarda hasta pulsar abrir.
 */
@Composable
private fun ColumnScope.PreviewStep(
    project: Project, info: String, saving: Boolean, onBack: () -> Unit, onModify: () -> Unit, full: Boolean, onFull: () -> Unit, onAnother: () -> Unit,
    onNewMusic: (() -> Unit)?, onNewTransitions: () -> Unit, onOpen: () -> Unit,
) {
    val context = LocalContext.current
    // 0 = preparando el render, 1 = reproduciendo el render, 2 = vista en vivo.
    var mode by remember { mutableIntStateOf(0) }
    var proxy by remember { mutableStateOf<File?>(null) }
    var prog by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(project.id, mode) {
        if (mode != 0) return@LaunchedEffect
        try {
            proxy = ProxyRender(context).render(project, 480) { prog = it }
            mode = 1
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ErrorLog.log("AutoEdit/proxy", e)
            mode = 2
        }
    }
    DisposableEffect(proxy) {
        val f = proxy
        onDispose { f?.delete() }
    }
    if (!full) Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        Text(stringResource(R.string.as_preview_title), style = MaterialTheme.typography.titleLarge)
    }
    when (mode) {
        0 -> Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.as_preview_preparing), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            LinearProgressIndicator({ prog }, Modifier.fillMaxWidth().padding(top = 12.dp))
            TextButton({ mode = 2 }, Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.as_preview_quick)) }
        }
        1 -> proxy?.let { ProxyPlayer(it, project, full, onFull) }
        else -> LivePlayer(project, full, onFull)
    }
    if (!full) InfoText(info)
    if (!full) Text(stringResource(R.string.as_preview_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!full) Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onModify, Modifier.weight(1f), enabled = !saving) { Text(stringResource(R.string.as_modify)) }
            OutlinedButton(onAnother, Modifier.weight(1f), enabled = !saving) { Text(stringResource(R.string.as_another)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onNewMusic != null) OutlinedButton(onNewMusic, Modifier.weight(1f), enabled = !saving) { Text(stringResource(R.string.as_new_music)) }
            OutlinedButton(onNewTransitions, Modifier.weight(1f), enabled = !saving) { Text(stringResource(R.string.as_new_transitions)) }
        }
        Button(onOpen, Modifier.fillMaxWidth(), enabled = !saving) { Text(stringResource(R.string.as_open)) }
    }
}

@Composable
private fun InfoText(info: String) {
    Text(info, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Reproductor del render: un ExoPlayer normal, sin reconstrucciones, con búsqueda inmediata. */
@Composable
private fun ColumnScope.ProxyPlayer(file: File, project: Project, full: Boolean, onFull: () -> Unit) {
    val context = LocalContext.current
    val exo = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exo) {
        val unguard = com.dskmusic.vixa.engine.PlaybackGuard.register { exo.pause() }
        onDispose { unguard(); exo.release() }
    }
    var pos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    LaunchedEffect(exo) {
        while (true) {
            pos = exo.currentPosition * 1000L
            playing = exo.isPlaying
            delay(80)
        }
    }
    val toggle = {
        if (exo.isPlaying) exo.pause()
        else {
            if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
            exo.play()
        }
    }
    PlayerLayout(
        project.aspect, playing, false, pos, project.durationUs.coerceAtLeast(1L), full, onFull, { toggle() },
        onSeek = { exo.seekTo(it / 1000L) },
        onScrub = { on -> exo.setSeekParameters(if (on) SeekParameters.CLOSEST_SYNC else SeekParameters.EXACT) },
    ) {
        AndroidView({ ctx -> TextureView(ctx).also { exo.setVideoTextureView(it) } }, Modifier.fillMaxSize())
    }
}

/** Vista en vivo (la del editor): se usa si el render falla o si se prefiere no esperar. */
@Composable
private fun ColumnScope.LivePlayer(project: Project, full: Boolean, onFull: () -> Unit) {
    val context = LocalContext.current
    val player = remember { PreviewPlayer(context) }
    DisposableEffect(player) {
        val unguard = com.dskmusic.vixa.engine.PlaybackGuard.register { player.pause() }
        player.update(project, immediate = true)
        player.start()
        player.play()
        onDispose { unguard(); player.release() }
    }
    val pos by player.positionUs.collectAsState()
    val playing by player.playing.collectAsState()
    val buffering by player.buffering.collectAsState()
    PlayerLayout(
        project.aspect, playing, buffering, pos, project.durationUs.coerceAtLeast(1L), full, onFull, { player.toggle() },
        onSeek = { player.seekTo(it) },
        onScrub = { player.setScrubbing(it) },
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    var s: Surface? = null
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) { s = Surface(st); player.attach(s, width, height) }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) { player.attach(s, width, height) }
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            s?.let { player.detach(it); it.release() }
                            s = null
                            return true
                        }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Marco del vídeo con su proporción, botón de play y barra de búsqueda; la superficie la pone cada reproductor. */
@Composable
private fun ColumnScope.PlayerLayout(
    aspect: Float, playing: Boolean, buffering: Boolean, pos: Long, dur: Long, full: Boolean, onFull: () -> Unit,
    onToggle: () -> Unit, onSeek: (Long) -> Unit, onScrub: (Boolean) -> Unit, surface: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().then(if (full) Modifier.background(Color.Black) else Modifier.padding(horizontal = 12.dp)), contentAlignment = Alignment.Center) {
        val w = if (maxWidth / maxHeight > aspect) maxHeight * aspect else maxWidth
        val h = if (maxWidth / maxHeight > aspect) maxHeight else maxWidth / aspect
        Box(Modifier.size(w, h).clip(RoundedCornerShape(if (full) 0.dp else 8.dp)).background(Color.Black).clickable { onToggle() }) {
            surface()
            if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Color.White)
            else if (!playing) Box(Modifier.align(Alignment.Center).size(56.dp).clip(CircleShape).background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(34.dp), tint = Color.White)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ onToggle() }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
        Text(clock(pos), style = MaterialTheme.typography.labelMedium)
        Slider(
            (pos.toFloat() / dur).coerceIn(0f, 1f),
            { onScrub(true); onSeek((it * dur).toLong()) },
            Modifier.weight(1f).padding(horizontal = 8.dp),
            onValueChangeFinished = { onScrub(false) },
        )
        Text(clock(dur), style = MaterialTheme.typography.labelMedium)
        IconButton({ onFull() }) { Icon(if (full) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, stringResource(R.string.fullscreen)) }
    }
}

private fun clock(us: Long): String {
    val s = (us / 1_000_000L).toInt()
    return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
}
