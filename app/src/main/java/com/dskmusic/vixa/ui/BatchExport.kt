package com.dskmusic.vixa.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.engine.ExportStatus
import com.dskmusic.vixa.model.Container
import com.dskmusic.vixa.model.ExportKind
import com.dskmusic.vixa.model.ExportSettings
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.Quality
import com.dskmusic.vixa.model.VideoCodec
import java.io.File
import kotlin.math.ceil
import kotlin.math.min
import kotlinx.coroutines.flow.first

/** Máximo que admiten los estados de WhatsApp. */
const val WHATSAPP_MAX_US = 60_000_000L

/** Trocea [spanUs] en partes de [maxUs] (60 s por defecto) y un resto: 97 s → 60 + 37. (inicio, fin) de cada una, en orden. */
fun whatsappParts(spanUs: Long, maxUs: Long = WHATSAPP_MAX_US): List<Pair<Long, Long>> {
    val n = ceil(spanUs.toDouble() / maxUs).toInt().coerceAtLeast(1)
    return (0 until n).map { i -> (i * maxUs) to minOf(spanUs, (i + 1) * maxUs) }
}

/** Comparte varios vídeos, en el orden dado, con WhatsApp si está instalado (si no, con el selector del sistema). */
fun shareToWhatsApp(context: Context, files: List<File>, whatsapp: Boolean) {
    val uris = ArrayList<Uri>(files.map { fileUri(context, it) })
    val base = Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        if (whatsapp) context.startActivity(Intent(base).setPackage("com.whatsapp").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        else context.startActivity(Intent.createChooser(base, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        if (whatsapp) shareToWhatsApp(context, files, false)
    } catch (e: Throwable) {
        ErrorLog.log("ShareWA", e)
    }
}

/**
 * Exporta el proyecto troceado en partes de como mucho 60 s (para estados de WhatsApp), una tras otra y por orden:
 * "Nombre_1de3", "Nombre_2de3"… Al terminar ofrece compartirlas todas de golpe en WhatsApp.
 */
@Composable
fun BatchExportDialog(container: AppContainer, project: Project, preset: ExportSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val maxUs = preset.splitSeconds.coerceAtLeast(1) * 1_000_000L
    val parts = remember(project, maxUs) { whatsappParts(project.durationUs, maxUs) }
    val n = parts.size
    var idx by remember { mutableIntStateOf(0) }
    var outs by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var finished by remember { mutableStateOf(false) }
    val st by container.exporter.state.collectAsState()

    LaunchedEffect(project) {
        val short = min(project.width, project.height)
        // Los ajustes del perfil, sin pasarse de la resolución del vídeo; cada parte se exporta aparte y recodificada (cortes exactos).
        val base = preset.copy(
            kind = ExportKind.VIDEO, shortSide = listOf(480, 720, 1080, 1440, 2160).lastOrNull { it <= min(short, preset.shortSide) } ?: 480, fps = min(preset.fps, project.fps),
            passthrough = false, splitSeconds = 0,
            outDir = preset.outDir.ifEmpty { com.dskmusic.vixa.data.Storage.exportDir(container.settings.value.exportDir, ExportKind.VIDEO).path },
        )
        val name = project.name.ifBlank { "Vixa" }
        for ((i, r) in parts.withIndex()) {
            idx = i
            container.exporter.reset()
            container.exporter.start(project, base.copy(rangeStartUs = r.first, rangeEndUs = r.second, fileName = name + "_" + (i + 1) + "de" + n))
            val res = container.exporter.state.first { it.status == ExportStatus.DONE || it.status == ExportStatus.ERROR || it.status == ExportStatus.CANCELLED }
            if (res.status != ExportStatus.DONE) { error = res.message.ifBlank { context.getString(R.string.err_generic) }; return@LaunchedEffect }
            outs = outs + res.output
        }
        finished = true
    }

    val working = !finished && error.isEmpty()
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.split_title, preset.splitSeconds)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (working) {
                    Text(stringResource(R.string.wa_part, idx + 1, n))
                    LinearProgressIndicator(progress = { ((idx + st.progress) / n).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(stringResource(R.string.wa_done, n))
                }
                for (o in outs) Text("✓ " + File(o).name, style = MaterialTheme.typography.bodySmall)
                if (finished) {
                    // Uno debajo del otro: en una sola fila el segundo botón se estrechaba y el texto se partía letra a letra.
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ shareToWhatsApp(context, outs.map { File(it) }, true) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.wa_share)) }
                        OutlinedButton({ shareToWhatsApp(context, outs.map { File(it) }, false) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.share)) }
                    }
                    Text(stringResource(R.string.wa_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            if (working) TextButton({ container.exporter.cancel(); onDismiss() }) { Text(stringResource(R.string.cancel)) }
            else TextButton({ container.exporter.reset(); onDismiss() }) { Text(stringResource(R.string.close)) }
        },
    )
}
