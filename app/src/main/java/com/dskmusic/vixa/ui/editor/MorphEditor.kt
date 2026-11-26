package com.dskmusic.vixa.ui.editor

import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dskmusic.vixa.R
import com.dskmusic.vixa.engine.MorphMath
import com.dskmusic.vixa.model.MorphImage
import com.dskmusic.vixa.model.MorphSpec
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Editor del morph: cada foto se ve ya encuadrada (como saldra en el vídeo). Modo "Puntos": un toque añade un punto en TODAS las
 * fotos (en el sitio equivalente), se arrastra en la foto actual hasta su rasgo (ojo con ojo...) y una pulsacion larga lo borra.
 * Modo "Encuadre": pellizcar, girar y arrastrar para recortar la foto actual. Los puntos de la cara automatica se ven pequenos y no se mueven.
 */
@Composable
fun MorphEditorDialog(vm: EditorViewModel, clipId: String, onDismiss: () -> Unit) {
    val project = vm.project.value
    val initial = project?.clip(clipId)?.morph
    if (project == null || initial == null) { onDismiss(); return }
    val ar = project.aspect
    var spec by remember { mutableStateOf(initial) }
    var cur by remember { mutableIntStateOf(0) }
    var frameMode by remember { mutableStateOf(false) }
    val base = MorphMath.baseOf(spec)
    val bitmaps = remember(initial) { initial.images.map { BitmapFactory.decodeFile(it.path) } }
    val cnt = spec.images[0].pts.size / 2
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Deshacer / rehacer: copias del morph entero. Los gestos continuos (encuadre, deslizador) se agrupan en una sola entrada.
    val undo = remember { mutableStateListOf<MorphSpec>() }
    val redo = remember { mutableStateListOf<MorphSpec>() }
    var lastMerge by remember { mutableLongStateOf(0L) }
    fun push() { undo.add(spec); if (undo.size > 60) undo.removeAt(0); redo.clear() }
    fun pushMerged() { val n = System.currentTimeMillis(); if (n - lastMerge > 500) push(); lastMerge = n }
    fun doUndo() { if (undo.isNotEmpty()) { redo.add(spec); spec = undo.removeAt(undo.lastIndex) } }
    fun doRedo() { if (redo.isNotEmpty()) { undo.add(spec); spec = redo.removeAt(redo.lastIndex) } }

    fun setImage(i: Int, f: (MorphImage) -> MorphImage) { spec = spec.copy(images = spec.images.mapIndexed { j, m -> if (j == i) f(m) else m }) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val header: @Composable () -> Unit = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
                    Text(stringResource(R.string.morph_edit), style = MaterialTheme.typography.titleMedium)
                    TextButton({ vm.updateMorph(clipId, spec); onDismiss() }) { Text(stringResource(R.string.morph_done)) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in spec.images.indices) FilterChip(cur == i, { cur = i }, { Text(stringResource(R.string.morph_photo, i + 1)) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(!frameMode, { frameMode = false }, { Text(stringResource(R.string.morph_mode_points)) })
                    FilterChip(frameMode, { frameMode = true }, { Text(stringResource(R.string.morph_mode_frame)) })
                    Spacer(Modifier.weight(1f))
                    IconButton(::doUndo, enabled = undo.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo)) }
                    IconButton(::doRedo, enabled = redo.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo)) }
                }
            }
            val canvasBlock: @Composable (Modifier) -> Unit = { mod ->
                val img = spec.images[cur]
                val bmp = bitmaps[cur]
                // La foto ocupa solo el hueco que dejan los controles (sin desbordar); los controles quedan debajo.
                Box(mod.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Canvas(
                    Modifier.aspectRatio(ar).clipToBounds()
                        .pointerInput(frameMode, cur) {
                            if (frameMode) detectTransformGestures { _, pan, zoom, rotDeg ->
                                pushMerged()
                                setImage(cur) { m ->
                                    val cropH = m.ch * m.h
                                    val dx = pan.x / size.width * cropH * ar
                                    val dy = pan.y / size.height * cropH
                                    val c = cos(m.rot); val s = sin(m.rot)
                                    // La foto sigue al dedo: el centro del recorte se mueve al reves.
                                    m.copy(
                                        cx = m.cx - (dx * c - dy * s) / m.w, cy = m.cy - (dx * s + dy * c) / m.h,
                                        ch = (m.ch / zoom).coerceIn(0.05f, 3f), rot = m.rot - Math.toRadians(rotDeg.toDouble()).toFloat(),
                                    )
                                }
                            }
                        }
                        .pointerInput(frameMode, cur) {
                            if (!frameMode) detectTapGestures(
                                onTap = { o ->
                                    // Tocar un punto que ya existe no crea otro (se arrastra para moverlo).
                                    val qx = o.x / size.width; val qy = o.y / size.height
                                    if (nearestPoint(spec, cur, ar, base, qx, qy, 40.dp.toPx() / size.width) >= 0) return@detectTapGestures
                                    // Punto nuevo en el sitio equivalente de cada foto (mismo punto del fotograma encuadrado).
                                    push()
                                    val uv = FloatArray(2)
                                    spec = spec.copy(images = spec.images.map { m -> MorphMath.qToUv(m, ar, qx, qy, uv, 0); m.copy(pts = m.pts + uv[0] + uv[1]) })
                                },
                                onLongPress = { o ->
                                    val k = nearestUser(spec, cur, ar, base, o.x / size.width, o.y / size.height, 40.dp.toPx() / size.width)
                                    if (k >= 0) { push(); spec = spec.copy(images = spec.images.map { m -> m.copy(pts = m.pts.filterIndexed { i, _ -> i / 2 != k }) }) }
                                },
                            )
                        }
                        .pointerInput(frameMode, cur) {
                            if (!frameMode) {
                                var k = -1
                                detectDragGestures(
                                    onDragStart = { o ->
                                        k = nearestPoint(spec, cur, ar, base, o.x / size.width, o.y / size.height, 40.dp.toPx() / size.width)
                                        if (k >= 0) push()
                                    },
                                    onDragEnd = { k = -1 }, onDragCancel = { k = -1 },
                                ) { change, _ ->
                                    if (k >= 0) {
                                        change.consume()
                                        val uv = FloatArray(2)
                                        MorphMath.qToUv(spec.images[cur], ar, (change.position.x / size.width).coerceIn(-0.2f, 1.2f), (change.position.y / size.height).coerceIn(-0.2f, 1.2f), uv, 0)
                                        setImage(cur) { m -> m.copy(pts = m.pts.toMutableList().also { it[k * 2] = uv[0]; it[k * 2 + 1] = uv[1] }) }
                                    }
                                }
                            }
                        },
                ) {
                    // La foto vista a traves de su encuadre: los tres puntos del fotograma (0,0), (1,0), (0,1) en pixeles de la foto.
                    val p = FloatArray(6)
                    MorphMath.qToUv(img, ar, 0f, 0f, p, 0); MorphMath.qToUv(img, ar, 1f, 0f, p, 2); MorphMath.qToUv(img, ar, 0f, 1f, p, 4)
                    for (i in 0 until 3) { p[i * 2] *= img.w; p[i * 2 + 1] *= img.h }
                    val mx = Matrix()
                    mx.setPolyToPoly(p, 0, floatArrayOf(0f, 0f, size.width, 0f, 0f, size.height), 0, 3)
                    if (bmp != null) drawContext.canvas.nativeCanvas.drawBitmap(bmp, mx, null)
                    val q = FloatArray(2)
                    for (k in 0 until cnt) {
                        MorphMath.uvToQ(img, ar, img.pts[k * 2], img.pts[k * 2 + 1], q, 0)
                        val o = Offset(q[0] * size.width, q[1] * size.height)
                        if (k < base) drawCircle(Color.White.copy(alpha = 0.55f), 1.6.dp.toPx(), o)
                        else {
                            val col = Color.hsv(((k - base) * 47f) % 360f, 0.85f, 1f)
                            drawCircle(col, 7.dp.toPx(), o)
                            drawCircle(Color.White, 7.dp.toPx(), o, style = Stroke(2.dp.toPx()))
                        }
                    }
                }
                }
            }
            val footer: @Composable () -> Unit = {
                Text(
                    stringResource(if (frameMode) R.string.morph_hint_frame else R.string.morph_hint_points, cnt - base),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.morph_hold) + ": " + (Math.round(spec.hold * 100) / 100f))
                Slider(spec.hold, { pushMerged(); spec = spec.copy(hold = it) }, valueRange = 0f..1f)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.morph_ping)); Switch(spec.pingPong, { push(); spec = spec.copy(pingPong = it) })
                }
            }
            if (landscape) {
                // Horizontal: la foto a la izquierda y los controles en una columna a la derecha.
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    canvasBlock(Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { header(); footer() }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    header()
                    canvasBlock(Modifier.weight(1f))
                    footer()
                }
            }
        }
    }
}

/** Punto mas cercano a (qx,qy) en la foto [cur]: primero entre los del usuario (radio [r], en anchos) y, si no hay, entre los de la cara (radio mas pequeno). -1 si ninguno. */
private fun nearestPoint(spec: MorphSpec, cur: Int, ar: Float, base: Int, qx: Float, qy: Float, r: Float): Int {
    val u = nearestIn(spec, cur, ar, base, spec.images[cur].pts.size / 2, qx, qy, r)
    return if (u >= 0) u else nearestIn(spec, cur, ar, 0, base, qx, qy, r * 0.5f)
}

/** Indice (entre los puntos del usuario, no los de la cara) del mas cercano, o -1. Para borrar. */
private fun nearestUser(spec: MorphSpec, cur: Int, ar: Float, base: Int, qx: Float, qy: Float, r: Float): Int =
    nearestIn(spec, cur, ar, base, spec.images[cur].pts.size / 2, qx, qy, r)

private fun nearestIn(spec: MorphSpec, cur: Int, ar: Float, from: Int, to: Int, qx: Float, qy: Float, r: Float): Int {
    val m = spec.images[cur]
    val q = FloatArray(2)
    var best = -1
    var bestD = r
    for (k in from until to) {
        MorphMath.uvToQ(m, ar, m.pts[k * 2], m.pts[k * 2 + 1], q, 0)
        val d = hypot(q[0] - qx, (q[1] - qy) / ar)
        if (d < bestD) { bestD = d; best = k }
    }
    return best
}
