package com.dskmusic.vixa.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dskmusic.vixa.R
import com.dskmusic.vixa.ai.AiTasks
import com.dskmusic.vixa.ai.AiGroup
import com.dskmusic.vixa.ai.Models
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.ui.AiJobBanner
import com.dskmusic.vixa.ui.ChipRow
import com.dskmusic.vixa.ui.ColorRow
import com.dskmusic.vixa.ui.ModelGateDialog
import com.dskmusic.vixa.ui.PreciseSlider
import com.dskmusic.vixa.ui.SectionTitle
import com.dskmusic.vixa.ui.SwitchRowSimple
import com.dskmusic.vixa.ui.formatBytes
import java.util.Locale

/** Tarjeta de una función: nombre, descripción de una línea, estado del modelo y, si no se puede usar, el motivo. */
@Composable
private fun FeatureCard(
    icon: ImageVector, title: String, desc: String, status: String, enabled: Boolean, hint: String = "", experimental: Boolean = false, onClick: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (experimental) Text(stringResource(R.string.ai_experimental), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (!enabled && hint.isNotEmpty()) hint else status, style = MaterialTheme.typography.labelSmall,
                    color = if (!enabled && hint.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Panel "Funciones con IA": lista agrupada y, al tocar una función, sus opciones. Todo se ejecuta en el dispositivo. */
@Composable
fun AiPanel(vm: EditorViewModel, project: Project, clip: Clip?, pick: (PickRequest) -> Unit) {
    val container = vm.container
    val store = container.models
    val page by vm.aiPage.collectAsState()
    val settings by container.settings.flow.collectAsState()
    val states by store.states.collectAsState()
    var gate by remember { mutableStateOf<Pair<List<String>, () -> Unit>?>(null) }
    var manage by remember { mutableStateOf(false) }
    var cutout by remember { mutableStateOf(false) }
    val actions = vm.ai

    fun needs(ids: List<String>, then: () -> Unit) { if (ids.all { store.isReady(it) }) then() else gate = ids to then }
    val clipOk = actions.hasAudio(clip)
    val anyAudio = project.allClips().any { actions.hasAudio(it) }

    // En texto a voz el progreso va debajo de los botones (ver TtsPage); en las demás páginas, arriba y con desplazamiento automático.
    if (page != "tts") AiJobBanner(container, scrollIntoView = true)
    if (page.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.aiPage.value = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(
                stringResource(when (page) { "subtitles" -> R.string.ai_f_subtitles; "translate" -> R.string.ai_f_translate; "denoise" -> R.string.ai_f_denoise; "separate" -> R.string.ai_f_separate; "person" -> R.string.ai_f_person; "face" -> R.string.ai_f_face; else -> R.string.ai_f_tts }),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }

    when (page) {
        "subtitles", "translate" -> SubtitlesPage(vm, project, clip, page == "translate", ::needs)
        "denoise" -> DenoisePage(vm, clip, ::needs)
        "separate" -> SeparatePage(vm, clip, ::needs)
        "tts" -> TtsPage(vm, project, ::needs)
        "person" -> PersonPage(vm, clip, ::needs)
        "face" -> FacePage(vm, clip, pick, ::needs)
        else -> {
            fun status(ids: List<String>): String {
                val bad = ids.firstOrNull { store.unsupported(it) != 0 }
                if (bad != null) return ""
                val missing = ids.filter { states[it]?.status != com.dskmusic.vixa.ai.ModelStatus.READY }
                return if (missing.isEmpty()) vm.str(R.string.ai_ready) else vm.str(R.string.ai_download_size, formatBytes(missing.sumOf { Models.get(it)?.size ?: 0L }))
            }
            fun blocked(ids: List<String>): String = ids.map { store.unsupported(it) }.firstOrNull { it != 0 }?.let { vm.str(it) } ?: ""

            for (group in AiGroup.entries) {
                SectionTitle(stringResource(when (group) { AiGroup.SPEECH -> R.string.ai_group_speech; AiGroup.AUDIO -> R.string.ai_group_audio; AiGroup.VIDEO -> R.string.ai_group_video }))
                if (group == AiGroup.SPEECH) {
                    val sub = listOf(Models.VAD, Models.whisper(settings.aiSubQuality))
                    FeatureCard(Icons.Default.Subtitles, stringResource(R.string.ai_f_subtitles), stringResource(R.string.ai_d_subtitles), status(sub), anyAudio && blocked(sub).isEmpty(),
                        blocked(sub).ifEmpty { stringResource(R.string.ai_hint_audio) }) { needs(sub) { vm.aiPage.value = "subtitles" } }
                    FeatureCard(Icons.Default.Translate, stringResource(R.string.ai_f_translate), stringResource(R.string.ai_d_translate), status(sub), anyAudio && blocked(sub).isEmpty(),
                        blocked(sub).ifEmpty { stringResource(R.string.ai_hint_audio) }, experimental = true) { needs(sub) { vm.aiPage.value = "translate" } }
                    val vad = listOf(Models.VAD)
                    FeatureCard(Icons.Default.ContentCut, stringResource(R.string.ai_f_silence), stringResource(R.string.ai_d_silence), status(vad), clipOk && blocked(vad).isEmpty(),
                        blocked(vad).ifEmpty { stringResource(R.string.ai_hint_clip) }) { needs(vad) { clip?.let { actions.removeSilences(it) } } }
                    FeatureCard(Icons.Default.VerticalSplit, stringResource(R.string.ai_f_phrases), stringResource(R.string.ai_d_phrases), status(vad), clipOk && blocked(vad).isEmpty(),
                        blocked(vad).ifEmpty { stringResource(R.string.ai_hint_clip) }) { needs(vad) { clip?.let { actions.cutPhrases(it) } } }
                    FeatureCard(Icons.Default.Hearing, stringResource(R.string.ai_f_duck), stringResource(R.string.ai_d_duck), status(vad), clipOk && blocked(vad).isEmpty(),
                        blocked(vad).ifEmpty { stringResource(R.string.ai_hint_clip) }) { needs(vad) { clip?.let { actions.duckByVoice(it) } } }
                    val voices = Models.voices.count { states[it.id]?.status == com.dskmusic.vixa.ai.ModelStatus.READY }
                    FeatureCard(Icons.Default.RecordVoiceOver, stringResource(R.string.ai_f_tts), stringResource(R.string.ai_d_tts), stringResource(R.string.ai_voices_ready, voices, Models.voices.size), true) { vm.aiPage.value = "tts" }
                } else if (group == AiGroup.AUDIO) {
                    val dn = listOf(Models.DENOISE_FAST)
                    FeatureCard(Icons.Default.GraphicEq, stringResource(R.string.ai_f_denoise), stringResource(R.string.ai_d_denoise), status(dn), clipOk, stringResource(R.string.ai_hint_clip)) { vm.aiPage.value = "denoise" }
                    val sp = listOf(Models.SEPARATE)
                    FeatureCard(Icons.Default.CallSplit, stringResource(R.string.ai_f_separate), stringResource(R.string.ai_d_separate), status(sp), clipOk && blocked(sp).isEmpty(),
                        blocked(sp).ifEmpty { stringResource(R.string.ai_hint_clip) }) { vm.aiPage.value = "separate" }
                } else {
                    val seg = listOf(Models.SELFIE)
                    FeatureCard(Icons.Default.Person, stringResource(R.string.ai_f_person), stringResource(R.string.ai_d_person), status(seg), actions.canSegment(clip) && blocked(seg).isEmpty(),
                        blocked(seg).ifEmpty { stringResource(R.string.ai_hint_visual) }) { needs(seg) { vm.aiPage.value = "person" } }
                    val fm = listOf(Models.FACE_MESH)
                    FeatureCard(Icons.Default.Face, stringResource(R.string.ai_f_face), stringResource(R.string.ai_d_face), status(fm), clip?.kind == ClipKind.VIDEO && clip.media != null && blocked(fm).isEmpty(),
                        blocked(fm).ifEmpty { stringResource(R.string.ai_hint_video) }) { needs(fm) { vm.aiPage.value = "face" } }
                    val mt = listOf(Models.MAGIC_TOUCH)
                    FeatureCard(Icons.Default.Crop, stringResource(R.string.ai_f_cutout), stringResource(R.string.ai_d_cutout), status(mt), clip?.kind == ClipKind.IMAGE && clip.media != null && blocked(mt).isEmpty(),
                        blocked(mt).ifEmpty { stringResource(R.string.ai_hint_photo) }) { needs(mt) { cutout = true } }
                }
            }
            SectionTitle(stringResource(R.string.ai_manage))
            FeatureCard(Icons.Default.Storage, stringResource(R.string.ai_manage_title), stringResource(R.string.ai_manage_desc), stringResource(R.string.ai_manage_open), true) { manage = true }
            Text(stringResource(R.string.ai_privacy), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    gate?.let { (ids, then) -> ModelGateDialog(container, ids, { gate = null }, then) }
    if (manage) com.dskmusic.vixa.ui.ModelManagerDialog(container) { manage = false }
    if (cutout && clip != null) CutoutDialog(vm, clip) { cutout = false }
}

@Composable
private fun SubtitlesPage(vm: EditorViewModel, project: Project, clip: Clip?, translate: Boolean, needs: (List<String>, () -> Unit) -> Unit) {
    val settings by vm.container.settings.flow.collectAsState()
    val clipOk = vm.ai.hasAudio(clip)
    var whole by remember { mutableStateOf(!clipOk) }
    var quality by remember { mutableStateOf(settings.aiSubQuality) }
    var lang by remember { mutableStateOf(settings.aiLanguage) }
    var maxChars by remember { mutableFloatStateOf(32f) }
    var lines by remember { mutableIntStateOf(2) }
    var karaoke by remember { mutableStateOf(project.karaokeColor != 0) }
    Text(stringResource(if (translate) R.string.ai_translate_note else R.string.ai_subtitles_note), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SectionTitle(stringResource(R.string.ai_scope))
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!whole, { whole = false }, { Text(stringResource(R.string.ai_scope_clip)) }, enabled = clipOk)
        FilterChip(whole, { whole = true }, { Text(stringResource(R.string.ai_scope_project)) })
    }
    SectionTitle(stringResource(R.string.ai_subtitle_quality))
    ChipRow(listOf("tiny", "base", "small"), quality, { q -> stringResource(R.string.ai_quality_label, q, formatBytes(Models.get(Models.whisper(q))?.size ?: 0L)) }) { quality = it }
    SectionTitle(stringResource(R.string.ai_language))
    ChipRow(listOf("", "es", "en"), lang, { when (it) { "es" -> "Español"; "en" -> "English"; else -> stringResource(R.string.automatic) } }) { lang = it }
    PreciseSlider(stringResource(R.string.ai_max_chars), maxChars, { maxChars = Math.round(it).toFloat() }, range = 16f..48f, default = 32f, format = { it.toInt().toString() }, inputScale = 1f)
    SectionTitle(stringResource(R.string.ai_max_lines))
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (n in 1..3) FilterChip(lines == n, { lines = n }, { Text(n.toString()) })
    }
    SwitchRowSimple(stringResource(R.string.ai_karaoke), karaoke) { karaoke = it }
    if (karaoke) ColorRow(if (project.karaokeColor != 0) project.karaokeColor else 0xFFFFD60A.toInt(), { c -> vm.edit(R.string.hist_subtitles) { it.copy(karaokeColor = c) } })
    Button(
        { needs(listOf(Models.VAD, Models.whisper(quality))) { vm.ai.subtitles(whole, quality, lang, translate, maxChars.toInt(), lines, karaoke); vm.aiPage.value = "" } },
        Modifier.fillMaxWidth().padding(16.dp),
    ) { Text(stringResource(R.string.ai_start)) }
}

@Composable
private fun DenoisePage(vm: EditorViewModel, clip: Clip?, needs: (List<String>, () -> Unit) -> Unit) {
    val store = vm.container.models
    var model by remember { mutableStateOf(if (store.unsupported(Models.DENOISE_HQ) == 0) Models.DENOISE_HQ else Models.DENOISE_FAST) }
    var amount by remember { mutableFloatStateOf(1f) }
    if (clip == null || !vm.ai.hasAudio(clip)) { Text(stringResource(R.string.ai_hint_clip), Modifier.padding(16.dp)); return }
    Text(stringResource(R.string.ai_denoise_note), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(model == Models.DENOISE_HQ, { model = Models.DENOISE_HQ }, { Text(stringResource(R.string.ai_denoise_hq)) }, enabled = store.unsupported(Models.DENOISE_HQ) == 0)
        FilterChip(model == Models.DENOISE_FAST, { model = Models.DENOISE_FAST }, { Text(stringResource(R.string.ai_denoise_fast)) })
    }
    PreciseSlider(stringResource(R.string.ai_amount), amount, { amount = it }, range = 0f..1f, default = 1f)
    Button({ needs(listOf(model)) { vm.ai.denoise(clip, model, amount); vm.aiPage.value = "" } }, Modifier.fillMaxWidth().padding(16.dp)) { Text(stringResource(R.string.ai_start)) }
    AiResultControls(vm, clip)
}

/** Comparación antes/después y quitar el resultado: el audio original no se modifica nunca. */
@Composable
private fun AiResultControls(vm: EditorViewModel, clip: Clip) {
    val ref = clip.aiAudio ?: return
    if (ref.kind == AiTasks.TTS) return
    SectionTitle(stringResource(R.string.ai_result))
    SwitchRowSimple(stringResource(R.string.ai_use_processed), ref.enabled) { vm.ai.setAiAudio(clip, it) }
    OutlinedButton({ vm.ai.setAiAudio(clip, null) }, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.ai_remove_result)) }
}

@Composable
private fun SeparatePage(vm: EditorViewModel, clip: Clip?, needs: (List<String>, () -> Unit) -> Unit) {
    val store = vm.container.models
    var model by remember { mutableStateOf(Models.SEPARATE) }
    if (clip == null || !vm.ai.hasAudio(clip)) { Text(stringResource(R.string.ai_hint_clip), Modifier.padding(16.dp)); return }
    Text(stringResource(R.string.ai_separate_note), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(model == Models.SEPARATE, { model = Models.SEPARATE }, { Text(stringResource(R.string.ai_sep_fast)) })
        FilterChip(model == Models.SEPARATE_HQ, { model = Models.SEPARATE_HQ }, { Text(stringResource(R.string.ai_sep_hq)) }, enabled = store.unsupported(Models.SEPARATE_HQ) == 0)
    }
    for ((mode, label) in listOf("remove_voice" to R.string.ai_remove_voice, "remove_music" to R.string.ai_remove_music, "split" to R.string.ai_split_stems)) {
        OutlinedButton({ needs(listOf(model)) { vm.ai.separate(clip, model, mode); vm.aiPage.value = "" } }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { Text(stringResource(label)) }
    }
    AiResultControls(vm, clip)
}

@Composable
private fun TtsPage(vm: EditorViewModel, project: Project, needs: (List<String>, () -> Unit) -> Unit) {
    val states by vm.container.models.states.collectAsState()
    val es = java.util.Locale.getDefault().language == "es"
    var voice by remember { mutableStateOf(if (es) "tts_es_ES-davefx-medium" else "tts_en_US-amy-medium") }
    var speaker by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var speed by remember { mutableFloatStateOf(1f) }
    val def = Models.get(voice)
    fun ready(id: String) = states[id]?.status == com.dskmusic.vixa.ai.ModelStatus.READY
    // Frase de muestra en el idioma de la voz; si la voz aún no está descargada, pide confirmación y la descarga.
    fun sample(id: String, sid: Int) {
        val d = Models.get(id) ?: return
        needs(listOf(id)) { vm.ai.previewTts(Models.sample(d.lang), id, 1f, sid) }
    }
    fun label(d: com.dskmusic.vixa.ai.ModelDef): String =
        d.name.removePrefix("Piper · ") + " · " + d.quality.replace('_', ' ')
    Text(stringResource(R.string.ai_tts_note), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), label = { Text(stringResource(R.string.ai_tts_text)) }, minLines = 3, maxLines = 6)
    SectionTitle(stringResource(R.string.ai_voice))
    // Selector: español primero, luego inglés y después el resto por idioma. Cada fila trae un botón para oír una muestra.
    androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 12.dp)) {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) {
            Text(def?.let { label(it) + if (ready(it.id)) " ✓" else "" } ?: "", Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }, Modifier.heightIn(max = 420.dp)) {
            var lastLang = ""
            for (d in Models.voices) {
                if (d.lang != lastLang) {
                    lastLang = d.lang
                    Text(
                        java.util.Locale(d.lang).getDisplayLanguage(java.util.Locale.getDefault()).replaceFirstChar { it.uppercase() },
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    )
                }
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text(label(d) + " (" + java.util.Locale("", d.region).getDisplayCountry(java.util.Locale.getDefault()) + ")" + if (d.id == voice) "  ●" else "")
                            Text(if (ready(d.id)) stringResource(R.string.ai_ready) else formatBytes(d.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = {
                        voice = d.id; speaker = 0; open = false
                        // Al elegir una voz ya descargada se oye enseguida una muestra.
                        if (ready(d.id)) sample(d.id, 0)
                    },
                    trailingIcon = { IconButton({ sample(d.id, 0) }) { Icon(Icons.Default.PlayArrow, stringResource(R.string.ai_voice_sample)) } },
                )
            }
        }
    }
    if ((def?.speakers ?: 1) > 1) {
        FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until (def?.speakers ?: 1)) FilterChip(speaker == i, { speaker = i; if (ready(voice)) sample(voice, i) }, { Text(stringResource(R.string.ai_speaker, i + 1)) })
        }
    }
    PreciseSlider(stringResource(R.string.speed), speed, { speed = it }, range = 0.5f..2f, default = 1f, format = { String.format(Locale.US, "×%.2f", it) }, inputScale = 1f)
    FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ needs(listOf(voice)) { vm.ai.previewTts(text, voice, speed, speaker) } }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ai_listen)) }
        Button({ needs(listOf(voice)) { vm.ai.addTts(text, voice, speed, vm.player.positionUs.value, speaker) } }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ai_add_voice)) }
        AssistChip({ needs(listOf(voice)) { vm.ai.narrateSubtitles(voice, speed, speaker) } }, { Text(stringResource(R.string.ai_narrate_subs)) }, enabled = subtitleClips(project).isNotEmpty())
    }
    AiJobBanner(vm.container, scrollIntoView = true)
}
