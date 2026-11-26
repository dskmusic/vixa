package com.dskmusic.vixa.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dskmusic.vixa.R
import com.dskmusic.vixa.ai.Models
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.WmRemove
import com.dskmusic.vixa.ui.AiJobBanner
import com.dskmusic.vixa.ui.CropBox
import com.dskmusic.vixa.ui.CropRect
import com.dskmusic.vixa.ui.ModelGateDialog
import com.dskmusic.vixa.ui.PreciseSlider
import com.dskmusic.vixa.ui.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Zona inicial: una esquina inferior derecha (donde suelen ir las marcas); el clonado toma el trozo de encima (o el de debajo si no cabe). */
private fun defaultRemove(mode: Int = 0): WmRemove {
    val x0 = 0.68f; val y0 = 0.86f; val x1 = 0.97f; val y1 = 0.96f
    val h = y1 - y0
    return WmRemove(x0, y0, x1, y1, mode = mode, offY = if (y0 - h * 1.2f >= 0f) -h * 1.2f else h * 1.2f)
}

/** Panel de "Quitar marca de agua" de un clip de vídeo o foto: zona, modo (relleno, clonado o IA) y ajustes. */
@Composable
fun WmRemovePanel(vm: EditorViewModel, c: Clip) {
    val container = vm.container
    val wr = c.wmRemove
    var zone by remember { mutableStateOf(false) }
    var gate by remember { mutableStateOf(false) }
    val job by container.aiJobs.state.collectAsState()
    fun set(live: Boolean = false, f: (WmRemove) -> WmRemove) = vm.updateClip(R.string.hist_wm_remove, c.id, live = live) { it.copy(wmRemove = f(it.wmRemove ?: defaultRemove())) }
    if (zone) WmZoneDialog(vm, c) { zone = false }
    if (gate) ModelGateDialog(container, listOf(Models.INPAINT), { gate = false }) { gate = false; vm.ai.removeWatermark(c) }

    if (wr == null) {
        Text(stringResource(R.string.wmr_hint), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Button({ set { it }; zone = true }, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.wmr_add)) }
        return
    }
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(wr.mode == 0, { set { it.copy(mode = 0) } }, { Text(stringResource(R.string.wmr_mode_fill)) })
        FilterChip(wr.mode == 1, { set { it.copy(mode = 1) } }, { Text(stringResource(R.string.wmr_mode_clone)) })
        FilterChip(wr.mode == 2, { set { it.copy(mode = 2) } }, { Text(stringResource(R.string.wmr_mode_ai)) })
    }
    OutlinedButton({ zone = true }, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { Text(stringResource(R.string.wmr_mark)) }
    PreciseSlider(stringResource(R.string.wmr_feather), wr.feather, { v -> set(live = true) { it.copy(feather = v) } }, range = 0f..1f, default = 0.25f, onFinished = { vm.commit() })
    if (wr.mode == 1) {
        PreciseSlider(stringResource(R.string.wmr_off_x), wr.offX, { v -> set(live = true) { it.copy(offX = v) } }, range = -0.6f..0.6f, default = 0f, onFinished = { vm.commit() })
        PreciseSlider(stringResource(R.string.wmr_off_y), wr.offY, { v -> set(live = true) { it.copy(offY = v) } }, range = -0.6f..0.6f, default = -0.12f, onFinished = { vm.commit() })
    }
    if (wr.mode == 2) {
        if (wr.patchCount == 0) Text(stringResource(R.string.wmr_ai_need), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text(stringResource(R.string.wmr_ai_ready, wr.patchCount), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Button(
            { if (container.models.isReady(Models.INPAINT)) vm.ai.removeWatermark(c) else gate = true },
            Modifier.padding(horizontal = 16.dp), enabled = job == null,
        ) { Text(stringResource(if (wr.patchCount == 0) R.string.wmr_generate else R.string.wmr_regenerate)) }
        AiJobBanner(container, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
    OutlinedButton({ vm.updateClip(R.string.hist_wm_remove, c.id) { it.copy(wmRemove = null) } }, Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { Text(stringResource(R.string.wmr_remove)) }
}

/** Marca la zona sobre un fotograma del clip: un cuadro que se mueve y se redimensiona. */
@Composable
private fun WmZoneDialog(vm: EditorViewModel, c: Clip, onDismiss: () -> Unit) {
    val m = c.media
    if (m == null || m.width <= 0 || m.height <= 0) { onDismiss(); return }
    val wr = c.wmRemove ?: defaultRemove()
    var crop by remember { mutableStateOf(CropRect(wr.x0, wr.y0, 1f - wr.x1, 1f - wr.y1)) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(m.path) { frame = withContext(Dispatchers.IO) { frameOf(m, c.srcInUs) }?.asImageBitmap() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    Text(stringResource(R.string.wmr_zone_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton({
                        // Al cambiar la zona, los parches de la IA ya no valen.
                        vm.updateClip(R.string.hist_wm_remove, c.id) {
                            it.copy(wmRemove = (it.wmRemove ?: wr).copy(x0 = crop.l, y0 = crop.t, x1 = 1f - crop.r, y1 = 1f - crop.b, patchPath = "", patchCount = 0))
                        }
                        onDismiss()
                    }) { Icon(Icons.Default.Check, stringResource(R.string.ok)) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                    Box(Modifier.aspectRatio(m.width.toFloat() / m.height).clipToBounds()) {
                        frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                        CropBox(crop, null, { crop = it }, true, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
