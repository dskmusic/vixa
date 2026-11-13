package com.dskmusic.vixa.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.content.Context
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.Layer
import com.dskmusic.vixa.model.MIN_CLIP_US
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.TextSpec
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.addToTrack
import com.dskmusic.vixa.model.updateClip
import com.dskmusic.vixa.ui.editor.parseSrt
import com.dskmusic.vixa.ui.picker.StreamPreview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.R
import com.dskmusic.vixa.ai.AiTasks
import com.dskmusic.vixa.ai.Models
import com.dskmusic.vixa.ai.Speech
import com.dskmusic.vixa.engine.Exporter
import java.io.File

/** Herramientas rápidas con IA de la pantalla de inicio: actúan sobre un archivo suelto, sin abrir un proyecto. */
object AiQuick {
    const val SRT = "ai_srt"
    const val SEPARATE = "ai_sep"
    const val DENOISE = "ai_denoise"
    val all = setOf(SRT, SEPARATE, DENOISE)

    fun models(container: AppContainer, tool: String): List<String> = when (tool) {
        SRT -> listOf(Models.VAD, Models.whisper(container.settings.value.aiSubQuality))
        SEPARATE -> listOf(Models.SEPARATE)
        else -> listOf(if (container.models.unsupported(Models.DENOISE_HQ) == 0) Models.DENOISE_HQ else Models.DENOISE_FAST)
    }

    private fun outDir(container: AppContainer): File {
        val s = container.settings.value
        return (com.dskmusic.vixa.data.Storage.custom(s.exportDir) ?: com.dskmusic.vixa.data.Storage.ai()).apply { mkdirs() }
    }

    private fun unique(dir: File, base: String, ext: String): File {
        val safe = base.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        var f = File(dir, "$safe.$ext")
        var n = 1
        while (f.exists()) { f = File(dir, "${safe}_$n.$ext"); n++ }
        return f
    }

    /** Encola el trabajo (los modelos ya están descargados). Al terminar avisa con la ruta del resultado. */
    fun run(container: AppContainer, tool: String, path: String, onResult: (List<File>) -> Unit) {
        val store = container.models
        val ctx = store.context
        val base = File(path).nameWithoutExtension
        val title = ctx.getString(when (tool) { SRT -> R.string.ai_job_subtitles; SEPARATE -> R.string.ai_job_separate; else -> R.string.ai_job_denoise })
        container.aiJobs.run(title, { progress ->
            val dir = outDir(container)
            when (tool) {
                SRT -> {
                    val s = container.settings.value
                    val lines = AiTasks.transcribe(store, path, 0, 0, s.aiSubQuality, s.aiLanguage, false, progress)
                    val subs = Speech.subtitles(lines, 42, 2)
                    if (subs.isEmpty()) throw java.io.IOException(ctx.getString(R.string.ai_no_speech))
                    val f = unique(dir, base, "srt")
                    f.writeText(Speech.toSrt(subs))
                    listOf(f)
                }
                SEPARATE -> {
                    val model = Models.SEPARATE
                    val vocals = AiTasks.stemRef(store, path, model, true)
                    val music = AiTasks.stemRef(store, path, model, false)
                    AiTasks.ensure(store, path, vocals, progress)
                    val a = unique(dir, base + "_voice", "wav")
                    val b = unique(dir, base + "_music", "wav")
                    File(vocals.path).copyTo(a)
                    File(music.path).copyTo(b)
                    listOf(a, b)
                }
                else -> {
                    val ref = AiTasks.denoiseRef(store, path, models(container, DENOISE).first(), 1f)
                    AiTasks.ensure(store, path, ref, progress)
                    val f = unique(dir, base + "_clean", "wav")
                    File(ref.path).copyTo(f)
                    listOf(f)
                }
            }
        }, onResult)
    }

    /** Proyecto nuevo con el resultado: subtítulos sobre el vídeo, las dos pistas separadas o el audio limpio. */
    suspend fun buildProject(context: Context, container: AppContainer, r: AiQuickResult): Project? {
        val name = File(r.source).nameWithoutExtension
        return when (r.tool) {
            SRT -> {
                var p = projectFromMedia(context, container, listOf(r.source)) ?: return null
                for ((a, b, t) in parseSrt(r.files.first().readText())) {
                    val c = Clip(kind = ClipKind.TEXT, startUs = a, durationUs = (b - a).coerceAtLeast(MIN_CLIP_US), text = TextSpec(listOf(Layer(text = t)), subtitle = true))
                    p = p.addToTrack(c, TrackType.TEXT)
                }
                p
            }
            // Las dos pistas empiezan a la vez, así que cada una cae en su propia pista de audio.
            SEPARATE -> projectFromMedia(context, container, r.files.map { it.path }, name)
            else -> {
                // El proyecto usa el medio original con el audio limpio (de la caché) como resultado no destructivo.
                val p = projectFromMedia(context, container, listOf(r.source)) ?: return null
                val clip = p.allClips().firstOrNull { it.media != null && it.hasTimedMedia } ?: return p
                val ref = AiTasks.denoiseRef(container.models, r.source, models(container, DENOISE).first(), 1f)
                p.updateClip(clip.id) { it.copy(aiAudio = ref) }
            }
        }
    }
}

/** Paso intermedio de una herramienta rápida con IA: descarga los modelos que falten y lanza el trabajo. */
@Composable
fun AiQuickGate(container: AppContainer, tool: String, path: String, onResult: (List<File>) -> Unit, onDone: () -> Unit) {
    val done by rememberUpdatedState(onDone)
    val result by rememberUpdatedState(onResult)
    ModelGateDialog(container, AiQuick.models(container, tool), { done() }) { AiQuick.run(container, tool, path) { result(it) } }
}

/** Resultado de una herramienta rápida de IA: [files] son los archivos ya guardados en la carpeta de salida. */
class AiQuickResult(val tool: String, val source: String, val files: List<File>)

/** Muestra el resultado (se puede escuchar o leer), dónde se guardó, y pregunta si se crea un proyecto con él. */
@Composable
fun AiResultDialog(r: AiQuickResult, onBack: () -> Unit, onCreate: () -> Unit) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(stringResource(R.string.ai_result_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((i, f) in r.files.withIndex()) {
                    if (f.extension == "srt") {
                        Text(f.readText().take(1500), Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                    } else {
                        val label = when {
                            r.tool == AiQuick.SEPARATE && i == 0 -> stringResource(R.string.ai_stem_voice)
                            r.tool == AiQuick.SEPARATE -> stringResource(R.string.ai_stem_music)
                            else -> stringResource(R.string.ai_result)
                        }
                        Text(label, style = MaterialTheme.typography.labelLarge)
                        StreamPreview(f.toURI().toString(), false, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)), autoplay = false)
                    }
                    Text(f.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.ai_saved_in, r.files.first().parent ?: ""), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ai_result_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onCreate) { Text(stringResource(R.string.ai_create_project)) } },
        dismissButton = { TextButton(onBack) { Text(stringResource(R.string.back)) } },
    )
}
