package com.dskmusic.vixa.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dskmusic.vixa.R
import com.dskmusic.vixa.engine.Fonts
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.Watermark
import com.dskmusic.vixa.ui.PreciseSlider
import com.dskmusic.vixa.ui.SectionTitle

private val WM_COLORS = listOf(0xFFFFFFFF, 0xFF000000, 0xFFFFD93D, 0xFFFF5A5A, 0xFFFF4FA3, 0xFF3DA5FF, 0xFF4CD9A0, 0xFFB066FF).map { it.toInt() }

/**
 * Marca de agua del proyecto (texto o imagen), simple o en mosaico, con tipografía, color, contorno, sombra, inclinación, tamaño y
 * opacidad. Se ve en directo en la vista previa del editor mientras se ajusta, y sale igual al exportar.
 */
@Composable
fun WatermarkPanel(vm: EditorViewModel, project: Project, pick: (PickRequest) -> Unit) {
    val context = LocalContext.current
    val wm = project.watermark
    val h = R.string.hist_watermark
    fun set(live: Boolean = false, f: (Watermark) -> Watermark) = vm.edit(h, live = live) { it.copy(watermark = f(it.watermark)) }
    // Al abrir la herramienta por primera vez se pone una marca de ejemplo para ver el efecto enseguida.
    LaunchedEffect(Unit) {
        if (!wm.enabled && wm.text.isEmpty() && wm.imagePath.isEmpty()) set { it.copy(enabled = true, text = "Vixa") }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.watermark_enable), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(wm.enabled, { on -> set { it.copy(enabled = on) } })
    }
    if (!wm.enabled) return

    // --- Contenido: texto o imagen
    val isImage = wm.imagePath.isNotEmpty()
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!isImage, { set { it.copy(imagePath = "", text = it.text.ifEmpty { "Vixa" }) } }, { Text(stringResource(R.string.wm_text)) })
        FilterChip(isImage, { pick(PickRequest(PickKind.FILE, exts = setOf("png", "jpg", "jpeg", "webp")) { f -> f.firstOrNull()?.let { p -> set { it.copy(imagePath = p) } } }) }, { Text(stringResource(R.string.wm_image)) })
    }
    if (!isImage) {
        OutlinedTextField(
            wm.text, { t -> set(live = true) { it.copy(text = t) } }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), singleLine = true,
            label = { Text(stringResource(R.string.wm_text_label)) },
        )
        SectionTitle(stringResource(R.string.wm_font))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in Fonts.all(context).take(24)) FilterChip(wm.fontId == f.id, { set { it.copy(fontId = f.id) } }, { Text(f.name) })
        }
        SectionTitle(stringResource(R.string.wm_color))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (c in WM_COLORS) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                        .border(if (wm.color == c) 3.dp else 1.dp, if (wm.color == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { set { it.copy(color = c) } },
                )
            }
        }
        PreciseSlider(stringResource(R.string.wm_outline), wm.strokeWidth, { v -> set(live = true) { it.copy(strokeWidth = v) } }, range = 0f..0.2f, default = 0f, onFinished = { vm.commit() })
        Row(Modifier.fillMaxWidth().clickable { set { it.copy(shadow = !it.shadow) } }.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wm_shadow), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(wm.shadow, { on -> set { it.copy(shadow = on) } })
        }
    } else {
        AssistChip({ set { it.copy(imagePath = "") } }, { Text(stringResource(R.string.remove_logo)) }, modifier = Modifier.padding(horizontal = 12.dp))
    }

    // --- Disposición: simple o mosaico
    SectionTitle(stringResource(R.string.wm_layout))
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(wm.mode == 0, { set { it.copy(mode = 0) } }, { Text(stringResource(R.string.wm_simple)) })
        FilterChip(wm.mode == 1, { set { it.copy(mode = 1) } }, { Text(stringResource(R.string.wm_tile)) })
    }
    if (wm.mode == 0) {
        // Cuadrícula de 3×3 para colocarla en una esquina, un borde o el centro.
        val xs = listOf(0.14f, 0.5f, 0.86f)
        val ys = listOf(0.1f, 0.5f, 0.92f)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (yy in ys) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (xx in xs) {
                    val on = kotlin.math.abs(wm.x - xx) < 0.02f && kotlin.math.abs(wm.y - yy) < 0.02f
                    Box(
                        Modifier.size(width = 44.dp, height = 30.dp).clip(MaterialTheme.shapes.small)
                            .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { set { it.copy(x = xx, y = yy) } },
                    )
                }
            }
        }
        PreciseSlider(stringResource(R.string.position_x), wm.x, { v -> set(live = true) { it.copy(x = v) } }, range = 0f..1f, default = 0.85f, onFinished = { vm.commit() })
        PreciseSlider(stringResource(R.string.position_y), wm.y, { v -> set(live = true) { it.copy(y = v) } }, range = 0f..1f, default = 0.92f, onFinished = { vm.commit() })
    } else {
        PreciseSlider(stringResource(R.string.wm_spacing_x), wm.spacingX, { v -> set(live = true) { it.copy(spacingX = v) } }, range = 0f..3f, default = 0.3f, onFinished = { vm.commit() })
        PreciseSlider(stringResource(R.string.wm_spacing_y), wm.spacingY, { v -> set(live = true) { it.copy(spacingY = v) } }, range = 0f..3f, default = 0.6f, onFinished = { vm.commit() })
        Row(Modifier.fillMaxWidth().clickable { set { it.copy(stagger = !it.stagger) } }.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.wm_stagger), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(wm.stagger, { on -> set { it.copy(stagger = on) } })
        }
    }

    // --- Tamaño, inclinación y opacidad
    PreciseSlider(stringResource(R.string.size), wm.scale, { v -> set(live = true) { it.copy(scale = v) } }, range = 0.03f..0.6f, default = 0.15f, onFinished = { vm.commit() })
    PreciseSlider(stringResource(R.string.wm_angle), wm.angle, { v -> set(live = true) { it.copy(angle = v) } }, range = -90f..90f, default = 0f, format = { it.toInt().toString() + "°" }, inputScale = 1f, onFinished = { vm.commit() })
    PreciseSlider(stringResource(R.string.opacity), wm.opacity, { v -> set(live = true) { it.copy(opacity = v) } }, range = 0f..1f, default = 0.7f, onFinished = { vm.commit() })
    Text(stringResource(R.string.wm_hint), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
