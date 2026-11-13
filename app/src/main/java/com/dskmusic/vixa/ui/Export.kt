package com.dskmusic.vixa.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.StatFs
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import android.net.Uri
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asImageBitmap
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.ui.platform.LocalConfiguration
import com.dskmusic.vixa.model.ClipKind
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.dskmusic.vixa.ui.picker.StreamPreview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.engine.ExportService
import com.dskmusic.vixa.engine.ExportStatus
import com.dskmusic.vixa.engine.Exporter
import com.dskmusic.vixa.model.Container
import com.dskmusic.vixa.model.ExportKind
import com.dskmusic.vixa.model.ExportSettings
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.Quality
import com.dskmusic.vixa.model.VideoCodec
import com.dskmusic.vixa.ui.picker.FileBrowserDialog
import java.io.File
import java.util.Locale

fun fileUri(context: Context, file: File) = FileProvider.getUriForFile(context, context.packageName + ".files", file)

fun mimeOf(file: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

fun shareFile(context: Context, file: File, mime: String = mimeOf(file)) {
    try {
        val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, fileUri(context, file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Throwable) {
        ErrorLog.log("Share", e)
    }
}

fun openFile(context: Context, file: File) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(context, file), mimeOf(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        shareFile(context, file)
    } catch (e: Throwable) {
        ErrorLog.log("Open", e)
    }
}

/**
 * Diálogo de exportación: ajustes, estimación de tamaño, progreso (el trabajo corre en un servicio en
 * primer plano) y acciones al terminar. [allowedKinds] limita los tipos (herramientas rápidas).
 */
@Composable
fun ExportDialog(
    container: AppContainer,
    project: Project,
    positionUs: Long,
    onDismiss: () -> Unit,
    initial: ExportSettings? = null,
    allowedKinds: List<ExportKind> = ExportKind.entries,
    onSetCover: ((Long) -> Unit)? = null,
    /** Abre con el rango de exportación activado (herramientas rápidas Recortar y GIF). */
    initialRange: Boolean = false,
) {
    val context = LocalContext.current
    val startRange = initialRange && project.durationUs > 1_000_000L
    // Un GIF largo pesa muchísimo: por defecto se propone el primer tramo de 5 s.
    val startEnd = if ((initial ?: container.settings.value.export).kind == ExportKind.GIF) minOf(project.durationUs, 5_000_000L) else project.durationUs
    val app by container.settings.flow.collectAsState()
    val state by container.exporter.state.collectAsState()
    var s by remember { mutableStateOf((initial ?: app.export).copy(frameUs = positionUs, rangeStartUs = if (startRange) 0 else -1, rangeEndUs = if (startRange) startEnd else -1, fileName = "", aspectW = initial?.aspectW ?: 0, aspectH = initial?.aspectH ?: 0, fps = initial?.fps ?: project.fps)) }
    var folder by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var rangeOn by remember { mutableStateOf(startRange) }
    // El bucle de vista previa solo se reconstruye al soltar un deslizador: lo que se ve es lo que tendrá el archivo.
    var committed by remember { mutableStateOf(s.rangeStartUs to s.rangeEndUs) }
    val durUs = project.durationUs
    val outDir = s.outDir.ifEmpty { com.dskmusic.vixa.data.Storage.exportDir(app.exportDir, s.kind).path }

    // Rango de exportación: interruptor, deslizadores y, si el proyecto es un solo vídeo, bucle de vista previa.
    @Composable
    fun rangeBlock() {
        if (durUs <= 1_000_000L) return
        SwitchRowSimple(stringResource(R.string.export_range), rangeOn) { on ->
            rangeOn = on
            s = if (on) s.copy(rangeStartUs = 0, rangeEndUs = if (s.kind == ExportKind.GIF) minOf(durUs, 5_000_000L) else durUs) else s.copy(rangeStartUs = -1, rangeEndUs = -1)
            committed = s.rangeStartUs to s.rangeEndUs
        }
        if (!rangeOn) return
        val max = durUs / 1_000_000f
        val src = previewSource(project)
        if (src != null && committed.second > committed.first) {
            val clip = project.allClips().first()
            val aspect = if (clip.media != null && clip.media.height > 0) clip.media.width.toFloat() / clip.media.height else 16f / 9f
            FramePreview(src.first, src.second, committed.first, committed.second, if (s.kind == ExportKind.GIF) s.gifSpeed else 1f, aspect, s.kind == ExportKind.GIF && s.boomerang, if (s.kind == ExportKind.GIF) s.gifFps else 15)
            Text(stringResource(R.string.range_loop_hint, (s.rangeEndUs - s.rangeStartUs) / 1_000_000f), Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        PreciseSlider(stringResource(R.string.range_start), s.rangeStartUs / 1_000_000f, { v -> s = s.copy(rangeStartUs = (v * 1_000_000f).toLong().coerceAtMost(s.rangeEndUs - 200_000L)) }, range = 0f..max, default = 0f, onFinished = { committed = s.rangeStartUs to s.rangeEndUs }, format = { formatTime((it * 1_000_000f).toLong()) }, inputScale = 1f)
        PreciseSlider(stringResource(R.string.range_end), s.rangeEndUs / 1_000_000f, { v -> s = s.copy(rangeEndUs = (v * 1_000_000f).toLong().coerceAtLeast(s.rangeStartUs + 200_000L)) }, range = 0f..max, default = max, onFinished = { committed = s.rangeStartUs to s.rangeEndUs }, format = { formatTime((it * 1_000_000f).toLong()) }, inputScale = 1f)
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip({ s = s.copy(rangeStartUs = positionUs.coerceAtMost(s.rangeEndUs - 200_000L).coerceAtLeast(0)); committed = s.rangeStartUs to s.rangeEndUs }, { Text(stringResource(R.string.start_at_playhead)) })
            AssistChip({ s = s.copy(rangeEndUs = positionUs.coerceAtLeast(s.rangeStartUs + 200_000L)); committed = s.rangeStartUs to s.rangeEndUs }, { Text(stringResource(R.string.end_at_playhead)) })
        }
    }

    Dialog(onDismissRequest = { if (state.status != ExportStatus.RUNNING) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.export), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton({ if (state.status != ExportStatus.RUNNING) { container.exporter.reset(); onDismiss() } }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                }
                if (state.status != ExportStatus.IDLE) {
                    // Progreso / resultado.
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(state.title, style = MaterialTheme.typography.titleMedium)
                        when (state.status) {
                            ExportStatus.RUNNING -> {
                                LinearProgressIndicator({ state.progress }, Modifier.fillMaxWidth())
                                Text(String.format(Locale.US, "%.0f%%", state.progress * 100f) + if (state.etaMs > 0) "  ·  " + stringResource(R.string.export_eta, ExportService.formatEta(state.etaMs)) else "")
                                if (state.warning.isNotEmpty()) Text(state.warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.export_background_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton({ container.exporter.cancel() }) { Text(stringResource(R.string.cancel)) }
                            }
                            ExportStatus.DONE -> {
                                val f = File(state.output)
                                val ext = f.extension.lowercase()
                                val isVideo = ext in setOf("mp4", "mov", "webm", "mkv", "3gp")
                                val isAudio = ext in setOf("m4a", "aac", "mp3", "wav", "ogg", "opus")
                                val ratio = if (project.width > 0 && project.height > 0) project.width.toFloat() / project.height else 1f
                                // Vista previa de lo exportado (vídeo/audio con el reproductor; imagen y GIF con Coil).
                                // Vídeo/audio: el reproductor dibuja sus controles fuera del fotograma; imagen/GIF: caja negra con Coil.
                                if (isVideo || isAudio) StreamPreview(f.toURI().toString(), isVideo, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), ratio)
                                else Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                                    // Mismo tope de alto que el vídeo: en formato vertical los botones de abajo no se pierden.
                                    AsyncImage(f, null, Modifier.heightIn(max = 280.dp).aspectRatio(ratio.coerceIn(0.4f, 2.5f)), contentScale = ContentScale.Fit)
                                }
                                Text(f.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatBytes(f.length()) + "  ·  " + (f.parent ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (state.warning.isNotEmpty()) Text(state.warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button({ openFile(context, f) }) { Text(stringResource(R.string.open)) }
                                    OutlinedButton({ shareFile(context, f) }) { Text(stringResource(R.string.share)) }
                                    TextButton({ container.exporter.reset() }) { Text(stringResource(R.string.export_again)) }
                                }
                            }
                            ExportStatus.ERROR -> {
                                Text(stringResource(R.string.export_failed), color = MaterialTheme.colorScheme.error)
                                Text(state.message, style = MaterialTheme.typography.bodySmall)
                                Button({ container.exporter.reset() }) { Text(stringResource(R.string.back)) }
                            }
                            else -> {
                                Text(stringResource(R.string.export_cancelled))
                                Button({ container.exporter.reset() }) { Text(stringResource(R.string.back)) }
                            }
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (allowedKinds.size > 1) {
                            ChipRow(allowedKinds, s.kind, { stringResource(when (it) { ExportKind.VIDEO -> R.string.kind_video; ExportKind.AUDIO -> R.string.kind_audio; ExportKind.GIF -> R.string.kind_gif; ExportKind.FRAME_PNG -> R.string.kind_png; ExportKind.FRAME_JPG -> R.string.kind_jpg }) }) { k -> s = s.copy(kind = k) }
                        }
                        // Recortar: el tramo es lo primero que hay que ajustar, así que va arriba del todo.
                        if (initialRange && s.kind == ExportKind.VIDEO) rangeBlock()
                        if (s.kind == ExportKind.VIDEO) {
                            SectionTitle(stringResource(R.string.presets))
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (p in ExportSettings.SOCIAL_PRESETS + app.exportPresets) {
                                    AssistChip({ s = p.copy(outDir = s.outDir, frameUs = s.frameUs, codec = if (Exporter.isCodecSupported(p.codec)) p.codec else VideoCodec.H264) }, { Text(p.presetName) })
                                }
                                AssistChip({ naming = true }, { Text("+ " + stringResource(R.string.save_preset)) })
                            }
                            SectionTitle(stringResource(R.string.resolution))
                            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (r in listOf(480, 720, 1080, 1440, 2160)) FilterChip(s.shortSide == r, { s = s.copy(shortSide = r) }, { Text(if (r == 2160) "4K" else r.toString() + "p") })
                            }
                            SectionTitle(stringResource(R.string.frame_rate))
                            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (f in listOf(24, 25, 30, 50, 60)) FilterChip(s.fps == f, { s = s.copy(fps = f) }, { Text(f.toString()) })
                            }
                            SectionTitle(stringResource(R.string.codec))
                            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // Solo se muestran los códecs que el dispositivo puede codificar.
                                for (c in listOf(VideoCodec.H264, VideoCodec.H265, VideoCodec.AV1).filter { Exporter.isCodecSupported(it) }) {
                                    FilterChip(s.container == Container.MP4 && s.codec == c, { s = s.copy(codec = c, container = Container.MP4) }, { Text(when (c) { VideoCodec.H264 -> "H.264"; VideoCodec.H265 -> "H.265 / HEVC"; else -> "AV1" }) })
                                }
                                if (Exporter.isWebmSupported()) FilterChip(s.container == Container.WEBM, { s = s.copy(container = Container.WEBM, codec = VideoCodec.VP9) }, { Text("WebM (VP9)") })
                            }
                            SectionTitle(stringResource(R.string.quality))
                            ChipRow(Quality.entries, s.quality, { stringResource(when (it) { Quality.LOW -> R.string.q_low; Quality.MEDIUM -> R.string.q_medium; Quality.HIGH -> R.string.q_high; Quality.CUSTOM -> R.string.custom }) }) { q -> s = s.copy(quality = q) }
                            if (s.quality == Quality.CUSTOM) PreciseSlider(stringResource(R.string.bitrate), s.customBitrate / 1_000_000f, { v -> s = s.copy(customBitrate = (v * 1_000_000f).toInt()) }, range = 0.5f..100f, default = 8f, format = { String.format(Locale.US, "%.1f Mbps", it) }, inputScale = 1f)
                            SwitchRowSimple(stringResource(R.string.passthrough), s.passthrough) { s = s.copy(passthrough = it) }
                            SwitchRowSimple(stringResource(R.string.hw_encode), s.hardware) { s = s.copy(hardware = it) }
                            SwitchRowSimple(stringResource(R.string.save_cover), s.saveCover) { s = s.copy(saveCover = it) }
                            if (onSetCover != null) TextButton({ onSetCover(positionUs) }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.set_cover_here, formatTime(positionUs))) }
                        }
                        // El rango va primero en GIF: es lo que hay que ajustar con la vista previa delante.
                        if (s.kind == ExportKind.GIF) rangeBlock()
                        if (s.kind == ExportKind.GIF) {
                            PreciseSlider(stringResource(R.string.width), s.gifWidth.toFloat(), { v -> s = s.copy(gifWidth = (v.toInt() / 2 * 2)) }, range = 120f..720f, default = 480f, format = { it.toInt().toString() + " px" }, inputScale = 1f)
                            PreciseSlider(stringResource(R.string.frame_rate), s.gifFps.toFloat(), { v -> s = s.copy(gifFps = v.toInt()) }, range = 5f..25f, default = 12f, format = { it.toInt().toString() + " fps" }, inputScale = 1f)
                            SwitchRowSimple(stringResource(R.string.gif_boomerang), s.boomerang) { s = s.copy(boomerang = it) }
                            Text(stringResource(R.string.gif_boomerang_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SectionTitle(stringResource(R.string.gif_compression))
                            ChipRow(listOf(0, 1, 2, 3), s.gifCompression, { stringResource(when (it) { 0 -> R.string.gif_comp_none; 1 -> R.string.gif_comp_light; 2 -> R.string.gif_comp_medium; else -> R.string.gif_comp_strong }) }) { s = s.copy(gifCompression = it) }
                            Text(stringResource(R.string.gif_compression_hint), Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SectionTitle(stringResource(R.string.gif_speed))
                            ChipRow(listOf(0.5f, 1f, 1.5f, 2f), s.gifSpeed, { "×" + (if (it == it.toInt().toFloat()) it.toInt().toString() else it.toString()) }) { s = s.copy(gifSpeed = it) }
                            SwitchRowSimple(stringResource(R.string.gif_dither), s.gifDither) { s = s.copy(gifDither = it) }
                            SwitchRowSimple(stringResource(R.string.gif_loop), s.gifLoop) { s = s.copy(gifLoop = it) }
                        }
                        if (s.kind == ExportKind.FRAME_PNG || s.kind == ExportKind.FRAME_JPG) {
                            PreciseSlider(stringResource(R.string.frame_time), s.frameUs / 1_000_000f, { v -> s = s.copy(frameUs = (v * 1_000_000f).toLong()) }, range = 0f..(durUs / 1_000_000f).coerceAtLeast(0.1f), default = positionUs / 1_000_000f, format = { formatTime((it * 1_000_000f).toLong()) }, inputScale = 1f)
                        } else if (s.kind != ExportKind.GIF && !(initialRange && s.kind == ExportKind.VIDEO)) rangeBlock()
                        SectionTitle(stringResource(R.string.destination))
                        OutlinedTextField(s.fileName, { s = s.copy(fileName = it) }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true,
                            label = { Text(stringResource(R.string.file_name)) }, placeholder = { Text(project.name.ifBlank { "Vixa" }) }, suffix = { Text("." + s.extension) })
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(outDir, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton({ folder = true }) { Text(stringResource(R.string.change)) }
                        }
                    }
                    // Estimación y botón.
                    val range = if (s.kind == ExportKind.FRAME_PNG || s.kind == ExportKind.FRAME_JPG) 0L else if (rangeOn) s.rangeEndUs - s.rangeStartUs else durUs
                    val est = s.estimateBytes(project.width, project.height, range)
                    val free = remember(outDir) { try { File(outDir).mkdirs(); StatFs(outDir).availableBytes } catch (e: Throwable) { -1L } }
                    val (ow, oh) = s.outputSize(project.width, project.height)
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            if (s.kind == ExportKind.VIDEO) Text(ow.toString() + "×" + oh + " · " + s.fps + " fps", style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(R.string.estimated_size, formatBytes(est)) + if (free >= 0) "  ·  " + stringResource(R.string.free_space, formatBytes(free)) else "", style = MaterialTheme.typography.bodySmall,
                                color = if (free in 0 until est) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button({
                            container.settings.update { it.copy(export = s.copy(rangeStartUs = -1, rangeEndUs = -1, fileName = "", outDir = "", aspectW = 0, aspectH = 0, presetName = ""), exportDir = s.outDir.ifEmpty { it.exportDir }) }
                            container.exporter.start(project, s.copy(outDir = outDir))
                        }, enabled = durUs > 0) { Text(stringResource(R.string.export)) }
                    }
                }
            }
        }
    }
    if (folder) FileBrowserDialog(stringResource(R.string.destination), folderMode = true, startDir = outDir, onDismiss = { folder = false }) { it.firstOrNull()?.let { d -> s = s.copy(outDir = d) } }
    if (naming) TextInputDialog(stringResource(R.string.save_preset), s.shortSide.toString() + "p " + s.fps, { naming = false }) { name ->
        container.settings.update { it.copy(exportPresets = it.exportPresets + s.copy(presetName = name, rangeStartUs = -1, rangeEndUs = -1, fileName = "", outDir = "", frameUs = 0)) }
        naming = false
    }
}

/** Fuente reproducible del recorte: solo si el proyecto es un único clip de vídeo a velocidad 1 (ruta, inicio en el origen). */
private fun previewSource(p: Project): Pair<String, Long>? {
    val c = p.allClips().singleOrNull() ?: return null
    val m = c.media ?: return null
    if (c.kind != ClipKind.VIDEO || c.speed != 1f || c.speedCurve.isNotEmpty() || c.reversed || !File(m.path).exists()) return null
    return m.path to c.srcInUs
}

/** Bucle mudo del tramo [startUs, endUs] del proyecto: es exactamente lo que contendrá el GIF o el recorte. */
@Composable
private fun RangePreview(path: String, offsetUs: Long, startUs: Long, endUs: Long, aspect: Float, speed: Float = 1f) {
    val context = LocalContext.current
    val player = remember(path, offsetUs, startUs, endUs, speed) {
        ExoPlayer.Builder(context).build().apply {
            setPlaybackSpeed(speed)
            setMediaItem(
                MediaItem.Builder().setUri(Uri.fromFile(File(path))).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionUs(offsetUs + startUs).setEndPositionUs(offsetUs + endUs).build(),
                ).build(),
            )
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    OnAppPause({ player.pause() }, { player.play() })
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(factory = { SurfaceView(it) }, update = { v -> player.setVideoSurfaceView(v) }, modifier = Modifier.heightIn(max = 220.dp).aspectRatio(aspect.coerceIn(0.4f, 2.5f)))
    }
}

/**
 * Vista previa en bucle (normal o de ida y vuelta) desde una tira de fotogramas en memoria: una sola pasada de
 * decodificación ([FrameStrip]) llena la tira, que se reproduce sincronizada con la pantalla. Así el bucle no se
 * entrecorta ni tiene el salto de rebobinado de un reproductor, y el boomerang es inmediato. Mientras se decodifica
 * se muestra cada fotograma según llega. Si el formato no es compatible (p. ej. HDR) se usa el reproductor normal.
 */
@Composable
private fun FramePreview(path: String, offsetUs: Long, startUs: Long, endUs: Long, speed: Float, aspect: Float, boomerang: Boolean, fps: Int) {
    val key = listOf(path, offsetUs, startUs, endUs, fps)
    val frames = remember(key) { androidx.compose.runtime.mutableStateListOf<androidx.compose.ui.graphics.ImageBitmap>() }
    var loading by remember(key) { mutableStateOf(true) }
    var failed by remember(key) { mutableStateOf(false) }
    val span = (endUs - startUs).coerceAtLeast(1)
    val stepUs = maxOf(1_000_000L / fps.coerceIn(2, 30), span / 150)
    androidx.compose.runtime.LaunchedEffect(key) {
        loading = true
        try {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.dskmusic.vixa.engine.FrameStrip.extract(path, offsetUs + startUs, offsetUs + endUs, stepUs, 150, 240) { frames.add(it.asImageBitmap()) }
            }
            if (!ok) failed = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ErrorLog.log("FramePreview", e)
            failed = true
        } finally {
            loading = false
        }
    }
    if (failed && frames.isEmpty()) { RangePreview(path, offsetUs, startUs, endUs, aspect, speed); return }
    var shown by remember(key) { androidx.compose.runtime.mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    OnAppPause({ paused = true }, { paused = false })
    androidx.compose.runtime.LaunchedEffect(key, speed, boomerang, loading) {
        val frameNs = (stepUs * 1000L / speed).toLong().coerceAtLeast(16_000_000L)
        var t0 = -1L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                val n = frames.size
                if (n == 0 || paused) { t0 = -1L; return@withFrameNanos }
                if (loading) { shown = n - 1; t0 = -1L; return@withFrameNanos }
                if (t0 < 0) t0 = now
                val cycle = if (boomerang && n > 2) 2 * n - 2 else n
                val pos = (((now - t0) / frameNs) % cycle).toInt()
                shown = if (pos < n) pos else 2 * n - 2 - pos
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        val f = frames.getOrNull(shown)
        if (f == null) androidx.compose.material3.CircularProgressIndicator(Modifier.padding(40.dp))
        else androidx.compose.foundation.Image(f, null, Modifier.heightIn(max = 220.dp).aspectRatio(aspect.coerceIn(0.4f, 2.5f)), contentScale = ContentScale.Fit)
    }
}

@Composable
fun SwitchRowSimple(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}
