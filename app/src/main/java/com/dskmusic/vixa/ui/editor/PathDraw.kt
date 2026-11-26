package com.dskmusic.vixa.ui.editor

import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dskmusic.vixa.R
import com.dskmusic.vixa.engine.Anims
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.Easing
import com.dskmusic.vixa.model.Keyframe
import com.dskmusic.vixa.model.StickerType
import com.dskmusic.vixa.media.Thumbs
import kotlin.math.hypot

/** Convierte el trazo (puntos 0..1 sobre el lienzo) en fotogramas clave x / y repartidos por igual a lo largo del clip. */
private fun pathKeyframes(pts: List<Offset>, durUs: Long, n: Int = 24): Pair<List<Keyframe>, List<Keyframe>>? {
    if (pts.size < 2) return null
    val cum = FloatArray(pts.size)
    for (i in 1 until pts.size) cum[i] = cum[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
    val total = cum.last()
    if (total < 0.02f) return null
    val xs = ArrayList<Keyframe>()
    val ys = ArrayList<Keyframe>()
    var j = 0
    for (k in 0 until n) {
        val d = total * k / (n - 1)
        while (j < pts.size - 2 && cum[j + 1] < d) j++
        val seg = cum[j + 1] - cum[j]
        val f = if (seg <= 0f) 0f else ((d - cum[j]) / seg).coerceIn(0f, 1f)
        val x = pts[j].x + (pts[j + 1].x - pts[j].x) * f
        val y = pts[j].y + (pts[j + 1].y - pts[j].y) * f
        val t = durUs * k / (n - 1)
        // Las claves "x" / "y" son el desplazamiento del centro en fracción del lienzo (0 = centrado).
        xs.add(Keyframe(t, x - 0.5f, Easing.LINEAR))
        ys.add(Keyframe(t, y - 0.5f, Easing.LINEAR))
    }
    return xs to ys
}

/** Punto del trazo a la fracción [f] (0..1) de su longitud, para la previsualización del movimiento. */
private fun pointAlong(pts: List<Offset>, f: Float): Offset {
    if (pts.isEmpty()) return Offset.Zero
    if (pts.size == 1) return pts[0]
    val cum = FloatArray(pts.size)
    for (i in 1 until pts.size) cum[i] = cum[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
    val target = cum.last() * f.coerceIn(0f, 1f)
    var j = 0
    while (j < pts.size - 2 && cum[j + 1] < target) j++
    val seg = cum[j + 1] - cum[j]
    val t = if (seg <= 0f) 0f else ((target - cum[j]) / seg).coerceIn(0f, 1f)
    return Offset(pts[j].x + (pts[j + 1].x - pts[j].x) * t, pts[j].y + (pts[j + 1].y - pts[j].y) * t)
}

private fun android.graphics.Bitmap.isNearlyBlack(): Boolean {
    var sum = 0L
    val n = 8
    for (i in 0 until n) for (j in 0 until n) {
        val c = getPixel(i * (width - 1) / (n - 1), j * (height - 1) / (n - 1))
        sum += ((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)
    }
    return sum / (n * n * 3) < 12
}

/** Miniatura del medio de la pista principal en el instante [pos] (null si no hay vídeo ni foto debajo). */
private fun backdropFor(p: com.dskmusic.vixa.model.Project?, pos: Long): android.graphics.Bitmap? {
    val clips = p?.mainTrack?.clips ?: return null
    val c = clips.firstOrNull { pos >= it.startUs && pos < it.startUs + it.durationUs && it.media != null } ?: clips.firstOrNull { it.media != null } ?: return null
    val m = c.media ?: return null
    val t = c.srcInUs + ((pos - c.startUs).coerceAtLeast(0L) * c.speed).toLong()
    return Thumbs.get(m, t)
}

/** Pantalla para dibujar con el dedo por dónde se mueve un elemento (pegatina, texto, capa de medios…) durante su duración. */
@Composable
fun DrawPathDialog(vm: EditorViewModel, clip: Clip, onDismiss: () -> Unit) {
    val pts: SnapshotStateList<Offset> = remember { mutableStateListOf() }
    val project = vm.project.collectAsState().value
    val aspect = if (project != null && project.height > 0) project.width.toFloat() / project.height else 9f / 16f
    // Fondo: el fotograma real de la vista previa; si no se puede (o sale negro), la miniatura del vídeo/foto que hay en ese instante.
    val pos = remember { vm.player.positionUs.value }
    val tick = Thumbs.tick.collectAsState().value
    val shot = remember { try { vm.previewShot?.invoke()?.takeIf { !it.isRecycled && !it.isNearlyBlack() } } catch (e: Throwable) { null } }
    val backdrop = remember(tick) { (shot ?: backdropFor(project, pos))?.asImageBitmap() }
    // Previsualización: un marcador (el emoji, si es uno) recorre el trazo en bucle, a la velocidad real del elemento (entre 1,2 y 5 s).
    var previewOn by remember { mutableStateOf(true) }
    val durMs = (clip.durationUs / 1000L).coerceIn(1200L, 5000L).toInt()
    val prog by rememberInfiniteTransition(label = "pathPreview").animateFloat(0f, 1f, infiniteRepeatable(tween(durMs, easing = LinearEasing), RepeatMode.Restart), label = "pathPreviewT")
    val emoji = clip.sticker?.takeIf { it.type == com.dskmusic.vixa.model.StickerType.EMOJI }?.value
    val markerPaint = remember { android.graphics.Paint().apply { isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.stk_path_hint), style = MaterialTheme.typography.titleMedium)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    val w = if (maxWidth / maxHeight > aspect) maxHeight * aspect else maxWidth
                    val h = w / aspect
                    Canvas(
                        Modifier.size(w, h).background(Color(0xFF20222B)).pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { o -> pts.clear(); pts.add(Offset((o.x / size.width).coerceIn(0f, 1f), (o.y / size.height).coerceIn(0f, 1f))) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    pts.add(Offset((change.position.x / size.width).coerceIn(0f, 1f), (change.position.y / size.height).coerceIn(0f, 1f)))
                                },
                            )
                        },
                    ) {
                        backdrop?.let { img ->
                            // Recorte "cubrir": llena el lienzo sin deformar la imagen.
                            val target = size.width / size.height
                            val bw = img.width; val bh = img.height
                            val sw = if (bw.toFloat() / bh > target) (bh * target).toInt() else bw
                            val sh = if (bw.toFloat() / bh > target) bh else (bw / target).toInt()
                            drawImage(img, IntOffset((bw - sw) / 2, (bh - sh) / 2), IntSize(sw, sh), IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()))
                            drawRect(Color(0x33000000))
                        }
                        if (previewOn && pts.size > 1) {
                            val m = pointAlong(pts.toList(), prog)
                            val cx = m.x * size.width
                            val cy = m.y * size.height
                            if (!emoji.isNullOrEmpty()) drawIntoCanvas { c ->
                                markerPaint.textSize = 34.dp.toPx()
                                c.nativeCanvas.drawText(emoji, cx, cy + 12.dp.toPx(), markerPaint)
                            } else {
                                drawCircle(Color.White, 13.dp.toPx(), Offset(cx, cy))
                                drawCircle(Color(0xFFFF4FA3), 9.dp.toPx(), Offset(cx, cy))
                            }
                        }
                        if (pts.size > 1) {
                            val path = Path()
                            path.moveTo(pts[0].x * size.width, pts[0].y * size.height)
                            for (i in 1 until pts.size) path.lineTo(pts[i].x * size.width, pts[i].y * size.height)
                            drawPath(path, Color(0xFFFFD60A), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ previewOn = !previewOn }) { Icon(if (previewOn) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(R.string.play_pause)) }
                    OutlinedButton({ pts.clear() }) { Text(stringResource(R.string.clear)) }
                    OutlinedButton(onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button({
                        val kf = pathKeyframes(pts.toList(), clip.durationUs)
                        if (kf != null) vm.updateClip(R.string.hist_animation, clip.id) { it.copy(keyframes = it.keyframes + mapOf("x" to kf.first, "y" to kf.second)) }
                        onDismiss()
                    }) { Text(stringResource(R.string.stk_path_done)) }
                }
            }
        }
    }
}
