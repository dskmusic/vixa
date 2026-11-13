package com.dskmusic.vixa.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.R
import com.dskmusic.vixa.ai.AiCache
import com.dskmusic.vixa.ai.ModelDef
import com.dskmusic.vixa.ai.ModelStatus
import com.dskmusic.vixa.ai.Models
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Recoge los avisos y errores de los trabajos de IA (los de descarga y los de inferencia). */
@Composable
fun CollectAiMessages(container: AppContainer, onMessage: (String) -> Unit) {
    val cb = rememberUpdatedState(onMessage)
    LaunchedEffect(Unit) { container.aiJobs.messages.collect { cb.value(it) } }
}

/** Trabajo de IA en curso: título, progreso, tiempo restante y cancelar. No muestra nada si no hay ninguno. */
@Composable
fun AiJobBanner(container: AppContainer, modifier: Modifier = Modifier, scrollIntoView: Boolean = false) {
    val job by container.aiJobs.state.collectAsState()
    val j = job ?: return
    // Al aparecer (empieza un trabajo) el panel se desplaza hasta aquí para que se vea el progreso.
    val requester = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(Unit) { if (scrollIntoView) { kotlinx.coroutines.delay(80); requester.bringIntoView() } }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).then(Modifier.bringIntoViewRequester(requester))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(j.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                val eta = if (j.etaMs > 0) "  ·  " + stringResource(R.string.export_eta, com.dskmusic.vixa.engine.ExportService.formatEta(j.etaMs)) else ""
                Text(if (j.progress >= 0f) "%d%%".format((j.progress * 100).toInt()) + eta else stringResource(R.string.ai_working), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton({ container.aiJobs.cancel() }) { Text(stringResource(R.string.cancel)) }
        }
        if (j.progress >= 0f) LinearProgressIndicator({ j.progress }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/**
 * Pide confirmación (con el tamaño) y descarga los modelos de [ids] que falten; al terminar llama a [onReady].
 * Si todos están ya listos llama a [onReady] directamente. Muestra el motivo si el dispositivo no puede con alguno.
 */
@Composable
fun ModelGateDialog(container: AppContainer, ids: List<String>, onDismiss: () -> Unit, onReady: () -> Unit) {
    val store = container.models
    val states by store.states.collectAsState()
    val job by container.aiJobs.state.collectAsState()
    val missing = ids.filter { states[it]?.status != ModelStatus.READY }
    var started by remember { mutableStateOf(false) }
    var seenJob by remember { mutableStateOf(false) }
    val blocked = ids.map { store.unsupported(it) }.firstOrNull { it != 0 } ?: 0
    LaunchedEffect(missing.isEmpty()) { if (missing.isEmpty()) { onDismiss(); onReady() } }
    LaunchedEffect(job) {
        if (started && job != null) seenJob = true
        // El trabajo terminó sin que se descargara todo (error o cancelado): se vuelve a ofrecer el botón.
        if (started && seenJob && job == null) { started = false; seenJob = false }
    }
    if (missing.isEmpty()) return
    val total = missing.sumOf { Models.get(it)?.size ?: 0L }
    AlertDialog(
        onDismissRequest = { if (!started) onDismiss() },
        title = { Text(stringResource(R.string.ai_download_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (blocked != 0) Text(stringResource(blocked), color = MaterialTheme.colorScheme.error)
                else {
                    for (id in missing) Text("• " + (Models.get(id)?.name ?: id) + " · " + formatBytes(Models.get(id)?.size ?: 0L))
                    Text(stringResource(R.string.ai_download_body, formatBytes(total)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (started) {
                    val j = job
                    if (j != null && j.progress >= 0f) LinearProgressIndicator({ j.progress }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (blocked == 0 && !started) TextButton({
                started = true
                val list = missing
                container.aiJobs.run(container.models.context.getString(R.string.ai_job_download), { progress ->
                    for ((i, id) in list.withIndex()) store.download(id) { p -> progress((i + p) / list.size) }
                })
            }) { Text(stringResource(R.string.ai_download_btn, formatBytes(total))) }
        },
        dismissButton = {
            TextButton({ if (started) { container.aiJobs.cancel(); started = false } else onDismiss() }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** Sección "IA" de Ajustes, compacta: calidad e idioma por defecto, solo Wi-Fi y espacio usado. Los modelos se gestionan desde el panel IA. */
@Composable
fun AiSettingsGroup(container: AppContainer) {
    val s by container.settings.flow.collectAsState()
    val store = container.models
    val states by store.states.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var used by remember { mutableStateOf(0L to 0L) }
    LaunchedEffect(states, tick) { used = withContext(Dispatchers.IO) { store.totalBytes() to AiCache.size(store.context) } }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(stringResource(R.string.set_ai), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.ai_subtitle_quality), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
        ChipRow(listOf("tiny", "base", "small"), s.aiSubQuality, { q -> stringResource(R.string.ai_quality_label, q, formatBytes(Models.get(Models.whisper(q))?.size ?: 0L)) }) { q -> container.settings.update { it.copy(aiSubQuality = q) } }
        Text(stringResource(R.string.ai_language), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
        ChipRow(listOf("", "es", "en"), s.aiLanguage, { when (it) { "es" -> "Español"; "en" -> "English"; else -> stringResource(R.string.automatic) } }) { l -> container.settings.update { it.copy(aiLanguage = l) } }
        SwitchRowSimple(stringResource(R.string.ai_wifi_only), s.aiWifiOnly) { on -> container.settings.update { it.copy(aiWifiOnly = on) } }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_space_used), Modifier.weight(1f))
            Text(formatBytes(used.first), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_cache), Modifier.weight(1f))
            Text(formatBytes(used.second), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ AiCache.clear(store.context); tick++ }) { Text(stringResource(R.string.clear)) }
        }
        AiJobBanner(container)
    }
}

/** Tipo de modelo y funciones que lo usan (recursos de texto), según su id. */
private fun typeAndUse(id: String): Pair<Int, Int> = when {
    id == Models.VAD -> R.string.ai_type_vad to R.string.ai_use_vad
    id.startsWith("whisper_") -> R.string.ai_type_asr to R.string.ai_use_asr
    id.startsWith("denoise_") -> R.string.ai_type_enh to R.string.ai_use_enh
    id.startsWith("uvr_") -> R.string.ai_type_sep to R.string.ai_use_sep
    id.startsWith("selfie") -> R.string.ai_type_seg to R.string.ai_use_seg
    id == Models.FACE_MESH -> R.string.ai_type_face to R.string.ai_use_face
    id == Models.MAGIC_TOUCH -> R.string.ai_type_seg to R.string.ai_use_touch
    else -> R.string.ai_type_tts to R.string.ai_use_tts
}

/**
 * Ventana de administración de modelos de IA: todos los que existen, su tipo, a qué funciones pertenecen y cuánto
 * ocupan. Los descargados se pueden eliminar (con confirmación) para ahorrar espacio.
 */
@Composable
fun ModelManagerDialog(container: AppContainer, onDismiss: () -> Unit) {
    val store = container.models
    val states by store.states.collectAsState()
    var sizes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var confirm by remember { mutableStateOf<ModelDef?>(null) }
    var confirmAll by remember { mutableStateOf(false) }
    LaunchedEffect(states) {
        sizes = withContext(Dispatchers.IO) { Models.all.filter { store.isReady(it.id) }.associate { it.id to store.usedBytes(it.id) } }
    }
    val total = sizes.values.sum()
    val downloaded = Models.all.filter { states[it.id]?.status == ModelStatus.READY }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ai_manage_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.ai_manage_total, formatBytes(total), downloaded.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ confirmAll = true }, enabled = downloaded.isNotEmpty()) { Text(stringResource(R.string.ai_delete_all)) }
                }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    var lastType = -1
                    for (def in Models.all.sortedWith(compareBy({ typeAndUse(it.id).first }, { it.name }))) {
                        val (type, use) = typeAndUse(def.id)
                        if (type != lastType) {
                            lastType = type
                            Text(stringResource(type), Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                        val ready = states[def.id]?.status == ModelStatus.READY
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(def.name, style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(use), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    if (ready) stringResource(R.string.ai_ready) + " · " + formatBytes(sizes[def.id] ?: 0L) else stringResource(R.string.ai_not_downloaded) + " · " + formatBytes(def.size),
                                    style = MaterialTheme.typography.labelSmall, color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                )
                            }
                            if (ready) TextButton({ confirm = def }) { Text(stringResource(R.string.delete)) }
                        }
                    }
                }
            }
        }
    }
    confirm?.let { def ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.ai_delete_title)) },
            text = { Text(stringResource(R.string.ai_delete_body, def.name, formatBytes(sizes[def.id] ?: 0L))) },
            confirmButton = { TextButton({ store.delete(def.id); confirm = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmAll) AlertDialog(
        onDismissRequest = { confirmAll = false },
        title = { Text(stringResource(R.string.ai_delete_all)) },
        text = { Text(stringResource(R.string.ai_delete_all_body, downloaded.size, formatBytes(total))) },
        confirmButton = { TextButton({ downloaded.forEach { store.delete(it.id) }; confirmAll = false }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton({ confirmAll = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
