package com.dskmusic.vixa.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlin.math.roundToInt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.engine.FrameStrip
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Piezas de las pantallas rápidas (GIF y Recortar): tira de fotogramas con tiradores, ajuste fino y cuadro de encuadre. */

fun fmtTime(us: Long): String {
    val ms = (us / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d.%03d", ms / 60000, (ms / 1000) % 60, ms % 1000)
}

/** Fotogramas de una ventana [from, to] del vídeo para la tira, y la ventana que cubren (puede ser distinta de la que se ve ahora). */
class Filmstrip {
    var frames by mutableStateOf<List<ImageBitmap>>(emptyList())
    var from by mutableLongStateOf(0L)
    var to by mutableLongStateOf(1L)
}

/**
 * Fotogramas de la ventana [fromUs, toUs] del vídeo. Al ampliar o mover la ventana se vuelven a decodificar (con un pequeño retardo);
 * mientras tanto la tira enseña los que ya hay, estirados a la nueva ventana. Si el decodificador por lotes falla, se piden de uno en uno.
 */
@Composable
fun rememberFilmstrip(path: String, offsetUs: Long, fromUs: Long, toUs: Long, count: Int = 12, enabled: Boolean = true): Filmstrip {
    val fs = remember(path) { Filmstrip() }
    LaunchedEffect(path, fromUs, toUs, enabled) {
        if (!enabled) { fs.frames = emptyList(); return@LaunchedEffect }
        val first = fs.frames.isEmpty()
        if (!first) delay(150)
        val tmp = ArrayList<ImageBitmap>()
        try {
            val ok = withContext(Dispatchers.IO) {
                FrameStrip.extract(path, offsetUs + fromUs, offsetUs + toUs, ((toUs - fromUs) / count).coerceAtLeast(1), count, 120) { b ->
                    tmp.add(b.asImageBitmap())
                    if (first) { fs.from = fromUs; fs.to = toUs; fs.frames = tmp.toList() }
                }
            }
            if (!ok || tmp.isEmpty()) {
                // Plan B: un fotograma exacto por hueco con MediaMetadataRetriever (más lento pero sirve para cualquier formato).
                tmp.clear()
                withContext(Dispatchers.IO) {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(path)
                        for (i in 0 until count) {
                            val t = fromUs + (toUs - fromUs) * (2 * i + 1) / (2 * count)
                            val b = r.getFrameAtTime(offsetUs + t, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                            tmp.add(Bitmap.createScaledBitmap(b, 120, (120L * b.height / b.width.coerceAtLeast(1)).toInt().coerceAtLeast(1), true).asImageBitmap())
                        }
                    } finally {
                        r.release()
                    }
                }
            }
            if (tmp.isNotEmpty()) { fs.from = fromUs; fs.to = toUs; fs.frames = tmp.toList() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ErrorLog.log("filmstrip", e)
        }
    }
    return fs
}

/**
 * Tira de fotogramas con dos tiradores (inicio y fin) y el cabezal. Un dedo: arrastrar un tirador mueve ese extremo; arrastrar en
 * cualquier otro sitio mueve toda la selección; tocar mueve el cabezal. Dos dedos: pellizcar amplía o reduce la zona visible de la
 * tira (y arrastrar con dos dedos la desplaza) para elegir con precisión; tocar el indicador ×N vuelve al 100 %. [onSeek] avisa de cada movimiento.
 */
@Composable
fun TrimBar(
    base: Filmstrip, detail: Filmstrip, winStart: Long, winEnd: Long, onWin: (Long, Long) -> Unit,
    durUs: Long, startUs: Long, endUs: Long, posUs: Long, minGapUs: Long,
    onRange: (Long, Long) -> Unit, onSeek: (Long) -> Unit, modifier: Modifier = Modifier,
) {
    val st by rememberUpdatedState(startUs)
    val en by rememberUpdatedState(endUs)
    val onR by rememberUpdatedState(onRange)
    val onS by rememberUpdatedState(onSeek)
    // La ventana visible (zoom) y los fotogramas viven en quien llama: así no se pierden al cambiar de pestaña y volver.
    val wsS by rememberUpdatedState(winStart)
    val weS by rememberUpdatedState(winEnd)
    val onW by rememberUpdatedState(onWin)
    val zoomed = winEnd - winStart < durUs
    Box(modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(durUs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    // La ventana se lleva en variables locales: varios eventos pueden llegar antes de que se recomponga.
                    var ws = wsS
                    var we = weS
                    fun usAt(x: Float): Long = ws + (x / w * (we - ws)).toLong()
                    fun xAt(us: Long): Float = (us - ws).toFloat() / (we - ws) * w
                    val d = 30.dp.toPx()
                    val xs = xAt(st)
                    val xe = xAt(en)
                    var mode = if (abs(down.position.x - xs) < d && abs(down.position.x - xs) <= abs(down.position.x - xe)) 1 else if (abs(down.position.x - xe) < d) 2 else 3
                    val baseStart = st
                    val baseLen = en - st
                    var accX = 0f
                    var moved = false
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } >= 2) {
                            // Pellizco: zoom de la ventana alrededor de los dedos (y desplazamiento con los dos dedos).
                            mode = 4
                            val z = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val c = ev.calculateCentroid()
                            val span = we - ws
                            val newSpan = (span / z).toLong().coerceIn(min(durUs, 1_000_000L), durUs)
                            val anchor = ws + (c.x / w * span).toLong()
                            val ns = (anchor - (c.x / w * newSpan).toLong() - (pan.x / w * newSpan).toLong()).coerceIn(0L, durUs - newSpan)
                            ws = ns
                            we = ns + newSpan
                            onW(ws, we)
                            ev.changes.forEach { it.consume() }
                        } else if (mode != 4) {
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!moved && (ch.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                            if (moved) {
                                // El desplazamiento se calcula ANTES de consumir el evento: tras consume(), positionChange() devuelve cero.
                                val dx = ch.position.x - ch.previousPosition.x
                                ch.consume()
                                val u = usAt(ch.position.x).coerceIn(0L, durUs)
                                when (mode) {
                                    1 -> { val n = u.coerceAtMost(en - minGapUs).coerceAtLeast(0L); onR(n, en); onS(n) }
                                    2 -> { val n = u.coerceAtLeast(st + minGapUs).coerceAtMost(durUs); onR(st, n); onS(n) }
                                    // Con zoom, un dedo desplaza la tira hacia los lados; sin zoom, mueve toda la selección (misma duración).
                                    else -> if (we - ws < durUs) {
                                        val span = we - ws
                                        val ns = (ws - (dx / w * span).toLong()).coerceIn(0L, durUs - span)
                                        ws = ns
                                        we = ns + span
                                        onW(ws, we)
                                    } else {
                                        accX += dx
                                        val ns = (baseStart + (accX / w * (we - ws)).toLong()).coerceIn(0L, (durUs - baseLen).coerceAtLeast(0L))
                                        onR(ns, ns + baseLen); onS(ns)
                                    }
                                }
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (!moved && mode != 4) onS(usAt(down.position.x).coerceIn(0L, durUs))
                }
            },
        ) {
            val span = (winEnd - winStart).toFloat()
            fun x(us: Long): Float = (us - winStart).toFloat() / span * size.width
            // Fotogramas: cada uno ocupa su hueco de la ventana que cubren (si la ventana ya cambió, se ven estirados hasta que llegan los nuevos).
            fun strip(film: Filmstrip) {
                val imgs = film.frames
                val n = imgs.size
                if (n == 0) return
                val fspan = (film.to - film.from).toFloat()
                for (i in 0 until n) {
                    val x0 = x(film.from + (fspan * i / n).toLong())
                    val x1 = x(film.from + (fspan * (i + 1) / n).toLong())
                    if (x1 < 0f || x0 > size.width) continue
                    val img = imgs[i]
                    val dw = (x1 - x0).coerceAtLeast(1f)
                    val da = dw / size.height
                    val ia = img.width.toFloat() / img.height
                    // Recorte centrado del fotograma a la proporción del hueco.
                    val sw = if (ia > da) (img.height * da).toInt().coerceIn(1, img.width) else img.width
                    val sh = if (ia > da) img.height else (img.width / da).toInt().coerceIn(1, img.height)
                    drawImage(
                        img, IntOffset((img.width - sw) / 2, (img.height - sh) / 2), IntSize(sw, sh),
                        IntOffset(x0.roundToInt(), 0), IntSize(dw.roundToInt().coerceAtLeast(1), size.height.roundToInt()),
                    )
                }
            }
            strip(base)
            if (zoomed) strip(detail)
            val xs = x(startUs)
            val xe = x(endUs)
            val l = xs.coerceIn(0f, size.width)
            val r = xe.coerceIn(0f, size.width)
            val dim = Color.Black.copy(alpha = 0.6f)
            drawRect(dim, Offset.Zero, Size(l, size.height))
            drawRect(dim, Offset(r, 0f), Size(size.width - r, size.height))
            drawRect(Color.White, Offset(l, 0f), Size(r - l, size.height), style = Stroke(3.dp.toPx()))
            val hw = 14.dp.toPx()
            val g = 3.dp.toPx()
            for (hx in listOf(xs, xe)) if (hx > -hw && hx < size.width + hw) {
                drawRect(Color.White, Offset(hx - hw / 2, 0f), Size(hw, size.height))
                drawRect(Color.Gray, Offset(hx - g / 2, 18.dp.toPx()), Size(g, size.height - 36.dp.toPx()))
            }
            val xp = x(posUs)
            if (xp in 0f..size.width) drawLine(Color(0xFFFF7A59), Offset(xp, 0f), Offset(xp, size.height), 2.dp.toPx())
        }
        // Indicador de zoom: solo visible si está ampliada; al tocarlo vuelve al 100 % y desaparece.
        if (winEnd - winStart < durUs) {
            Text(
                "×" + String.format(Locale.US, "%.1f", durUs.toFloat() / (winEnd - winStart)),
                Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.7f))
                    .clickable { onWin(0L, durUs) }.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleMedium, color = Color.White,
            )
        }
    }
}

/** Botón redondo que repite mientras se mantiene pulsado (ajuste fino rápido). */
@Composable
private fun RepeatButton(text: String, onStep: () -> Unit) {
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    Surface(
        Modifier.size(44.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                val job = scope.launch { step(); delay(400); while (true) { step(); delay(70) } }
                tryAwaitRelease()
                job.cancel()
            })
        },
        shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
    ) { Box(contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.titleLarge) } }
}

/** Etiqueta, "−", tiempo (tócalo para escribirlo al milisegundo) y "+". Cada paso es [stepUs] (un fotograma). */
@Composable
fun FineAdjust(label: String, valueUs: Long, stepUs: Long, onChange: (Long) -> Unit, modifier: Modifier = Modifier) {
    var dialog by remember { mutableStateOf(false) }
    val v by rememberUpdatedState(valueUs)
    val oc by rememberUpdatedState(onChange)
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
        RepeatButton("−") { oc(v - stepUs) }
        TextButton({ dialog = true }, Modifier.weight(1f)) { Text(fmtTime(valueUs), style = MaterialTheme.typography.titleMedium) }
        RepeatButton("+") { oc(v + stepUs) }
    }
    if (dialog) TimeDialog(valueUs, { dialog = false }) { dialog = false; onChange(it) }
}

@Composable
private fun TimeDialog(valueUs: Long, onDismiss: () -> Unit, onOk: (Long) -> Unit) {
    val ms0 = valueUs / 1000
    var m by remember { mutableStateOf((ms0 / 60000).toString()) }
    var s by remember { mutableStateOf(((ms0 / 1000) % 60).toString()) }
    var ms by remember { mutableStateOf((ms0 % 1000).toString()) }
    @Composable
    fun field(value: String, label: String, set: (String) -> Unit, mod: Modifier) {
        OutlinedTextField(value, { set(it.filter(Char::isDigit).take(3)) }, mod, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qe_type_time)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                field(m, "min", { m = it }, Modifier.weight(1f))
                field(s, "s", { s = it }, Modifier.weight(1f))
                field(ms, "ms", { ms = it }, Modifier.weight(1f))
            }
        },
        confirmButton = { TextButton({ onOk(((m.toLongOrNull() ?: 0L) * 60000 + (s.toLongOrNull() ?: 0L) * 1000 + (ms.toLongOrNull() ?: 0L)) * 1000) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---------------------------------------------------------------------------------------------
// Encuadre
// ---------------------------------------------------------------------------------------------

/** Lo que se recorta por cada lado, en fracción del fotograma (los mismos cuatro valores que Transform.crop*). */
data class CropRect(val l: Float = 0f, val t: Float = 0f, val r: Float = 0f, val b: Float = 0f) {
    val w: Float get() = 1f - l - r
    val h: Float get() = 1f - t - b
}

/** Presets de proporción (en vertical). Pulsar de nuevo uno ya elegido lo gira: 3:4 ↔ 4:3. */
val RATIO_BASE = listOf("1:1", "4:5", "3:4", "2:3", "9:16")

fun reverseRatio(id: String): String = id.split(":").takeIf { it.size == 2 }?.let { it[1] + ":" + it[0] } ?: id

/** Proporción ancho/alto de un preset ("1:1" → 1), o null si es libre/original. */
fun ratioOf(id: String): Float? = id.split(":").takeIf { it.size == 2 }?.let { it[0].toFloat() / it[1].toFloat() }

/** El mayor rectángulo centrado con proporción [ratio] (en píxeles) dentro de un vídeo de vw×vh. */
fun fitRatio(ratio: Float, vw: Int, vh: Int): CropRect {
    val src = vw.toFloat() / vh
    return if (src > ratio) { val cut = (1f - ratio / src) / 2f; CropRect(cut, 0f, cut, 0f) } else { val cut = (1f - src / ratio) / 2f; CropRect(0f, cut, 0f, cut) }
}

@Composable
fun RatioChips(selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == "free", { onSelect("free") }, { Text(stringResource(R.string.qe_ratio_free)) })
        FilterChip(selected == "orig", { onSelect("orig") }, { Text(stringResource(R.string.qe_ratio_orig)) })
        for (b in RATIO_BASE) {
            val rev = reverseRatio(b)
            val sel = selected == b || selected == rev
            FilterChip(sel, { onSelect(if (sel) (if (selected == b) rev else b) else b) }, { Text(if (selected == rev) rev else b) })
        }
    }
}

/**
 * Cuadro de encuadre sobre el vídeo (este composable ocupa exactamente el fotograma): se arrastra desde dentro para moverlo y
 * desde las esquinas o los bordes para cambiar su tamaño. Con [ratio] fijo (px ancho/alto) conserva la proporción.
 * Con [interactive] = false solo enseña lo que quedará (fuera atenuado).
 */
@Composable
fun CropBox(crop: CropRect, ratio: Float?, onCrop: (CropRect) -> Unit, interactive: Boolean, modifier: Modifier = Modifier) {
    val cr by rememberUpdatedState(crop)
    val oc by rememberUpdatedState(onCrop)
    val rt by rememberUpdatedState(ratio)
    val gestures = if (!interactive) Modifier else Modifier.pointerInput(Unit) {
        var mode = 0
        detectDragGestures(
            onDragStart = { o ->
                val w = size.width.toFloat(); val h = size.height.toFloat()
                val x0 = cr.l * w; val y0 = cr.t * h; val x1 = w - cr.r * w; val y1 = h - cr.b * h
                val d = 36.dp.toPx()
                fun near(a: Float, b: Float) = abs(a - b) < d
                mode = when {
                    near(o.x, x0) && near(o.y, y0) -> 2
                    near(o.x, x1) && near(o.y, y0) -> 3
                    near(o.x, x0) && near(o.y, y1) -> 4
                    near(o.x, x1) && near(o.y, y1) -> 5
                    near(o.x, x0) && o.y in y0..y1 -> 6
                    near(o.x, x1) && o.y in y0..y1 -> 7
                    near(o.y, y0) && o.x in x0..x1 -> 8
                    near(o.y, y1) && o.x in x0..x1 -> 9
                    o.x in x0..x1 && o.y in y0..y1 -> 1
                    else -> 0
                }
            },
            onDragEnd = { mode = 0 }, onDragCancel = { mode = 0 },
        ) { change, drag ->
            if (mode == 0) return@detectDragGestures
            change.consume()
            val w = size.width.toFloat(); val h = size.height.toFloat()
            var x0 = cr.l * w; var y0 = cr.t * h; var x1 = w - cr.r * w; var y1 = h - cr.b * h
            val minW = 0.12f * w; val minH = 0.12f * h
            val lock = rt
            if (mode == 1) {
                val dx = drag.x.coerceIn(-x0, w - x1); val dy = drag.y.coerceIn(-y0, h - y1)
                x0 += dx; x1 += dx; y0 += dy; y1 += dy
            } else if (lock == null) {
                if (mode == 2 || mode == 4 || mode == 6) x0 = (x0 + drag.x).coerceIn(0f, x1 - minW)
                if (mode == 3 || mode == 5 || mode == 7) x1 = (x1 + drag.x).coerceIn(x0 + minW, w)
                if (mode == 2 || mode == 3 || mode == 8) y0 = (y0 + drag.y).coerceIn(0f, y1 - minH)
                if (mode == 4 || mode == 5 || mode == 9) y1 = (y1 + drag.y).coerceIn(y0 + minH, h)
            } else {
                // Proporción fija: se escala desde la esquina opuesta (desde el centro si se arrastra un borde).
                val cx = (x0 + x1) / 2f; val cy = (y0 + y1) / 2f
                val fromCenter = mode >= 6
                val ax = if (fromCenter) cx else if (mode == 2 || mode == 4) x1 else x0
                val ay = if (fromCenter) cy else if (mode == 2 || mode == 3) y1 else y0
                val sx = if (mode == 2 || mode == 4 || mode == 6) -1f else 1f
                var nw = if (mode == 8 || mode == 9) ((y1 - y0) + drag.y * (if (mode == 8) -1f else 1f)) * lock else (x1 - x0) + drag.x * sx
                val maxW = if (fromCenter) min(2f * min(cx, w - cx), 2f * min(cy, h - cy) * lock)
                else min(if (mode == 2 || mode == 4) x1 else w - x0, (if (mode == 2 || mode == 3) y1 else h - y0) * lock)
                nw = nw.coerceIn(min(minW, maxW), maxW)
                val nh = nw / lock
                if (fromCenter) { x0 = cx - nw / 2; x1 = cx + nw / 2; y0 = cy - nh / 2; y1 = cy + nh / 2 }
                else {
                    if (mode == 2 || mode == 4) { x0 = ax - nw; x1 = ax } else { x0 = ax; x1 = ax + nw }
                    if (mode == 2 || mode == 3) { y0 = ay - nh; y1 = ay } else { y0 = ay; y1 = ay + nh }
                }
            }
            oc(CropRect(x0 / w, y0 / h, (w - x1) / w, (h - y1) / h))
        }
    }
    Canvas(modifier.then(gestures)) {
        val x0 = crop.l * size.width; val y0 = crop.t * size.height
        val x1 = size.width - crop.r * size.width; val y1 = size.height - crop.b * size.height
        val dim = Color.Black.copy(alpha = 0.55f)
        drawRect(dim, Offset.Zero, Size(size.width, y0))
        drawRect(dim, Offset(0f, y1), Size(size.width, size.height - y1))
        drawRect(dim, Offset(0f, y0), Size(x0, y1 - y0))
        drawRect(dim, Offset(x1, y0), Size(size.width - x1, y1 - y0))
        drawRect(Color.White, Offset(x0, y0), Size(x1 - x0, y1 - y0), style = Stroke(2.dp.toPx()))
        if (interactive) {
            val g = Color.White.copy(alpha = 0.45f)
            for (k in 1..2) {
                drawLine(g, Offset(x0 + (x1 - x0) * k / 3f, y0), Offset(x0 + (x1 - x0) * k / 3f, y1), 1.dp.toPx())
                drawLine(g, Offset(x0, y0 + (y1 - y0) * k / 3f), Offset(x1, y0 + (y1 - y0) * k / 3f), 1.dp.toPx())
            }
            val len = 22.dp.toPx(); val sw = 4.dp.toPx()
            fun corner(px: Float, py: Float, dx: Float, dy: Float) {
                drawLine(Color.White, Offset(px, py), Offset(px + dx * len, py), sw)
                drawLine(Color.White, Offset(px, py), Offset(px, py + dy * len), sw)
            }
            corner(x0, y0, 1f, 1f); corner(x1, y0, -1f, 1f); corner(x0, y1, 1f, -1f); corner(x1, y1, -1f, -1f)
        }
    }
}
