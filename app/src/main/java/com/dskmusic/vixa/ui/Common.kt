package com.dskmusic.vixa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.AppSettings
import java.util.Locale

val LocalSettings = compositionLocalOf { AppSettings() }

/**
 * Vibración breve que no pasa por la respuesta táctil del sistema: se nota también en silencio, con la vibración
 * al tocar desactivada y con ahorro de batería. Solo para momentos clave (coger un clip), no para ticks repetidos.
 */
fun android.content.Context.vibrateClick() {
    val v = if (android.os.Build.VERSION.SDK_INT >= 31) getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(android.os.Vibrator::class.java)
    if (v == null || !v.hasVibrator()) return
    val effect = if (android.os.Build.VERSION.SDK_INT >= 29) android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK)
        else android.os.VibrationEffect.createOneShot(20, 80)
    // ponytail: uso "alarma" para saltarse silencio/ahorro; si Android lo restringe, probar USAGE_ACCESSIBILITY.
    if (android.os.Build.VERSION.SDK_INT >= 33) v.vibrate(effect, android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
    else @Suppress("DEPRECATION") v.vibrate(effect, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
}

fun HapticFeedback.tick(enabled: Boolean) {
    if (enabled) performHapticFeedback(HapticFeedbackType.TextHandleMove)
}

fun formatTime(us: Long, tenths: Boolean = true): String {
    val t = us.coerceAtLeast(0)
    val totalSec = t / 1_000_000L
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    val d = (t % 1_000_000L) / 100_000L
    val base = if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    return if (tenths) "$base.$d" else base
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
    else -> String.format(Locale.US, "%d KB", b / 1024)
}

/**
 * Slider preciso: doble toque sobre la cabecera restablece el valor por defecto y un toque en el número
 * abre la entrada numérica. [format] convierte el valor a texto (por defecto, porcentaje con signo).
 */
@Composable
fun PreciseSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = -1f..1f,
    default: Float = 0f,
    onFinished: () -> Unit = {},
    format: (Float) -> String = { String.format(Locale.US, "%.0f", it * 100f) },
    /** Factor entre el valor mostrado en la entrada numérica y el valor real. */
    inputScale: Float = 100f,
    trailing: @Composable (() -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    // Una sola fila: nombre (ancho fijo, con puntos suspensivos), deslizador y valor. Doble toque: valor por defecto.
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp).combinedClickable(onClick = {}, onDoubleClick = { onChange(default); onFinished() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(92.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            onValueChangeFinished = onFinished,
            modifier = Modifier.weight(1f).height(36.dp),
        )
        if (trailing != null) trailing()
        Text(
            format(value),
            style = MaterialTheme.typography.labelLarge,
            color = if (value != default) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(56.dp).clip(RoundedCornerShape(6.dp)).clickable { editing = true }.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
    if (editing) {
        NumberDialog(label, value * inputScale, onDismiss = { editing = false }) {
            onChange((it / inputScale).coerceIn(range.start, range.endInclusive))
            onFinished()
            editing = false
        }
    }
}

@Composable
fun NumberDialog(title: String, initial: Float, onDismiss: () -> Unit, onOk: (Float) -> Unit) {
    var text by remember { mutableStateOf(String.format(Locale.US, "%.2f", initial).trimEnd('0').trimEnd('.')) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        },
        confirmButton = { TextButton({ text.replace(',', '.').toFloatOrNull()?.let(onOk) ?: onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, onDismiss: () -> Unit, singleLine: Boolean = true, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = singleLine, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton({ onOk(text) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton({ onConfirm(); onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Fila de chips de selección única con desplazamiento horizontal. */
@Composable
fun <T> ChipRow(items: List<T>, selected: T?, label: @Composable (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val ss = rememberScrollState()
    ScrollHintEffect(ss, "chips:" + items.size)
    Row(modifier.horizontalScroll(ss).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (it in items) FilterChip(selected = it == selected, onClick = { onSelect(it) }, label = { Text(label(it), maxLines = 1) })
    }
}

/** Botón de herramienta: icono con etiqueta debajo. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ToolButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true, twoLines: Boolean = false, accent: Color = Color.Unspecified, onClick: () -> Unit) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        selected -> MaterialTheme.colorScheme.primary
        // matiz por familia de herramientas (solo en modo Normal): se mezcla con el color normal
        accent != Color.Unspecified -> androidx.compose.ui.graphics.lerp(LocalContentColor.current, accent, 0.5f)
        else -> LocalContentColor.current
    }
    var tip by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (tip) {
        // Ayuda rápida al mantener pulsado: centrada en la pantalla para que el dedo no la tape.
        androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(1800); tip = false }
        androidx.compose.ui.window.Popup(popupPositionProvider = object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(anchorBounds: androidx.compose.ui.unit.IntRect, windowSize: androidx.compose.ui.unit.IntSize, layoutDirection: androidx.compose.ui.unit.LayoutDirection, popupContentSize: androidx.compose.ui.unit.IntSize) =
                androidx.compose.ui.unit.IntOffset((windowSize.width - popupContentSize.width) / 2, windowSize.height / 3)
        }) {
            androidx.compose.material3.Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 6.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.inverseOnSurface)
                    Spacer(Modifier.width(10.dp))
                    Text(label.trim(), color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(enabled = enabled, onClick = onClick, onLongClick = { tip = true }).padding(horizontal = 6.dp, vertical = 6.dp).width(60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(if (twoLines) label.trim().replace(' ', '\n') else label, fontSize = 10.sp, lineHeight = 11.sp, color = color, minLines = if (twoLines) 2 else 1, maxLines = if (twoLines || label.contains(' ')) 2 else 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun ColorDot(color: Int, selected: Boolean, size: Dp = 34.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(color))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Default.Check, null, tint = if (ColorUtils.calculateLuminance(color) > 0.5) Color.Black else Color.White, modifier = Modifier.size(size / 2))
    }
}

val BasicPalette: List<Int> = listOf(
    0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF8E8E93.toInt(), 0xFFFF3B30.toInt(), 0xFFFF9F0A.toInt(), 0xFFFFD60A.toInt(),
    0xFF30D158.toInt(), 0xFF00C7BE.toInt(), 0xFF0A84FF.toInt(), 0xFF5E5CE6.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF4F9A.toInt(),
    0xFF7A2E00.toInt(), 0xFF1C3A5E.toInt(), 0xFF1B4332.toInt(), 0xFFF5E6CA.toInt(),
)

/** Selector de color libre (HSL + alfa opcional + hexadecimal) con paleta. */
@Composable
fun ColorPickerDialog(initial: Int, alpha: Boolean = false, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsl0 = remember { FloatArray(3).also { ColorUtils.colorToHSL(initial or 0xFF000000.toInt(), it) } }
    var h by remember { mutableFloatStateOf(hsl0[0]) }
    var s by remember { mutableFloatStateOf(hsl0[1]) }
    var l by remember { mutableFloatStateOf(hsl0[2]) }
    var a by remember { mutableFloatStateOf(if (alpha) (initial ushr 24) / 255f else 1f) }
    val color = (ColorUtils.HSLToColor(floatArrayOf(h, s, l)) and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color)) },
        text = {
            Column {
                Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(Color(color)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)))
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(Brush.horizontalGradient(List(7) { Color(ColorUtils.HSLToColor(floatArrayOf(it * 60f, 1f, 0.5f))) })),
                )
                Slider(h, { h = it }, valueRange = 0f..360f, modifier = Modifier.height(32.dp))
                Text(stringResource(R.string.saturation), style = MaterialTheme.typography.labelSmall)
                Slider(s, { s = it }, modifier = Modifier.height(32.dp))
                Text(stringResource(R.string.lightness), style = MaterialTheme.typography.labelSmall)
                Slider(l, { l = it }, modifier = Modifier.height(32.dp))
                if (alpha) {
                    Text(stringResource(R.string.opacity), style = MaterialTheme.typography.labelSmall)
                    Slider(a, { a = it }, modifier = Modifier.height(32.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in BasicPalette) ColorDot(c, false, 28.dp) {
                        val t = FloatArray(3)
                        ColorUtils.colorToHSL(c, t)
                        h = t[0]; s = t[1]; l = t[2]
                    }
                }
                Text("#" + String.format(Locale.US, "%08X", color), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton({ onPick(color); onDismiss() }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Fila de colores con acceso al selector libre. */
@Composable
fun ColorRow(selected: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier, alpha: Boolean = false, allowNone: Boolean = false) {
    var picker by remember { mutableStateOf(false) }
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(PaddingValues(horizontal = 12.dp, vertical = 4.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
                .clickable { picker = true },
        )
        if (allowNone) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { onPick(0) },
                contentAlignment = Alignment.Center,
            ) { Text("∅") }
        }
        if (selected != 0 && selected !in BasicPalette) ColorDot(selected, true) { picker = true }
        for (c in BasicPalette) ColorDot(c, c == selected) { onPick(c) }
    }
    if (picker) ColorPickerDialog(if (selected == 0) 0xFFFFFFFF.toInt() else selected, alpha, { picker = false }, onPick)
}

/** Estado global del aviso de desplazamiento: lo rellena el editor (contenedor y proyecto abierto). */
object ScrollHints {
    var container: com.dskmusic.vixa.AppContainer? = null
    var projectId: String = ""
}

/**
 * Rebote único (por proyecto y zona) que indica que hay más contenido fuera de vista: empuja un poco hacia delante y vuelve.
 * Sirve para barras horizontales y listas verticales con el mismo [ScrollState]. Se desactiva en Ajustes.
 */
@Composable
fun ScrollHintEffect(state: androidx.compose.foundation.ScrollState, tag: String) {
    val c = ScrollHints.container ?: return
    val key = ScrollHints.projectId + "|" + tag
    androidx.compose.runtime.LaunchedEffect(key, state.maxValue > 0) {
        val s = c.settings.flow.value
        if (state.maxValue <= 0 || !s.scrollHint || key in s.hintsSeen) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        if (state.maxValue <= 0) return@LaunchedEffect
        c.settings.update { it.copy(hintsSeen = (it.hintsSeen + key).let { set -> if (set.size > 400) set.drop(100).toSet() else set }) }
        val d = minOf(state.maxValue, 140)
        state.animateScrollTo(d, androidx.compose.animation.core.tween(380))
        state.animateScrollTo(0, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 300f))
    }
}

/**
 * Dibujo de un formato: rectángulo con la proporción [aw]:[ah] dentro de un cuadrado, para elegir a simple vista.
 * Con [aw] = 0 (original o automático) se dibuja un cuadrado discontinuo.
 */
@Composable
fun AspectShape(aw: Int, ah: Int, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(22.dp)) {
        val box = size.minDimension
        val r = if (aw <= 0 || ah <= 0) 1f else aw.toFloat() / ah
        val w = if (r >= 1f) box else box * r
        val h = if (r >= 1f) box / r else box
        val tl = androidx.compose.ui.geometry.Offset((size.width - w) / 2f, (size.height - h) / 2f)
        val corner = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        val line = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 2.dp.toPx(),
            pathEffect = if (aw <= 0) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null,
        )
        drawRoundRect(color.copy(alpha = 0.18f), tl, androidx.compose.ui.geometry.Size(w, h), corner)
        drawRoundRect(color, tl, androidx.compose.ui.geometry.Size(w, h), corner, style = line)
    }
}

/** Chip de relación de aspecto con el dibujo del formato a la izquierda. */
@Composable
fun AspectChip(aw: Int, ah: Int, selected: Boolean, label: String = "$aw:$ah", enabled: Boolean = true, onClick: () -> Unit) {
    FilterChip(selected, onClick, { Text(label) }, enabled = enabled, leadingIcon = { AspectShape(aw, ah, LocalContentColor.current) })
}

/** Llama a [onPause] cuando la app deja de estar en primer plano (minimizada o pantalla bloqueada) y a [onResume] al volver. */
@Composable
fun OnAppPause(onPause: () -> Unit, onResume: () -> Unit = {}) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val pause = androidx.compose.runtime.rememberUpdatedState(onPause)
    val resume = androidx.compose.runtime.rememberUpdatedState(onResume)
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) pause.value() else if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resume.value()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}
