package com.dskmusic.vixa.ui.editor

import com.dskmusic.vixa.model.speedAt
import com.dskmusic.vixa.model.SpeedPoint
import androidx.compose.material.icons.filled.Diamond
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import com.dskmusic.vixa.engine.PreviewPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Grid4x4
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dskmusic.vixa.R
import com.dskmusic.vixa.engine.OverlayRender
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.allZones
import com.dskmusic.vixa.model.zoneKey
import com.dskmusic.vixa.model.keyframeNear
import com.dskmusic.vixa.model.withKeyframe
import com.dskmusic.vixa.model.withoutKeyframe
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.FitMode
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.ui.formatTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Caja de un elemento en la vista previa (píxeles de la vista, giro en grados). */
class Frame(val cx: Float, val cy: Float, val w: Float, val h: Float, val rot: Float) {
    /** Punto de la vista en coordenadas del marco (origen en su centro, ejes girados con el elemento). */
    fun local(p: Offset): Offset {
        val a = Math.toRadians(-rot.toDouble())
        val dx = p.x - cx
        val dy = p.y - cy
        return Offset((dx * cos(a) - dy * sin(a)).toFloat(), (dx * sin(a) + dy * cos(a)).toFloat())
    }

    fun contains(p: Offset): Boolean {
        val l = local(p)
        return abs(l.x) <= w / 2 + 12f && abs(l.y) <= h / 2 + 12f
    }

    /** Tirador más cercano a [p] dentro de [radius] px (0-3 esquinas, 4-7 mitades), o -1. */
    fun handleAt(p: Offset, radius: Float): Int {
        val l = local(p)
        var best = -1
        var bestD = radius
        for ((i, u) in HANDLES.withIndex()) {
            val d = hypot(l.x - u.x * w / 2, l.y - u.y * h / 2)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }
}

/** Posición unitaria de los tiradores: esquinas (proporcional) y mitades de cada lado (deforman). */
private val HANDLES = listOf(
    Offset(-1f, -1f), Offset(1f, -1f), Offset(-1f, 1f), Offset(1f, 1f),
    Offset(0f, -1f), Offset(0f, 1f), Offset(-1f, 0f), Offset(1f, 0f),
)

private fun frameOf(render: OverlayRender, p: Project, c: Clip, localUs: Long, vw: Float, vh: Float, tmp: RectF): Frame {
    val scale = c.prop("scale", localUs)
    val ksx = c.prop("sx", localUs)
    val ksy = c.prop("sy", localUs)
    val cx = (0.5f + c.prop("x", localUs)) * vw
    val cy = (0.5f + c.prop("y", localUs)) * vh
    val rot = c.prop("rotation", localUs)
    if (c.kind == ClipKind.TEXT || c.kind == ClipKind.STICKER) {
        render.bounds(p, c, p.width, p.height, tmp)
        val k = vw / p.width.coerceAtLeast(1)
        return Frame(cx + tmp.centerX() * k * scale, cy + tmp.centerY() * k * scale, tmp.width() * k * scale * ksx, tmp.height() * k * scale * ksy, rot)
    }
    val m = c.media
    val tr = c.transform
    var bw = 1f
    var bh = 1f
    if (m != null && c.kind != ClipKind.COLOR && m.width > 0 && m.height > 0) {
        val sa = (m.width * (1f - tr.cropL - tr.cropR).coerceAtLeast(0.01f)) / (m.height * (1f - tr.cropT - tr.cropB).coerceAtLeast(0.01f))
        val ca = vw / vh
        when (tr.fit) {
            FitMode.FIT -> if (sa > ca) bh = ca / sa else bw = sa / ca
            FitMode.FILL -> if (sa > ca) bw = sa / ca else bh = ca / sa
            FitMode.STRETCH -> {}
        }
    }
    return Frame(cx, cy, bw * vw * scale * ksx, bh * vh * scale * ksy, rot)
}

/** Vista previa con guías, selección y gestos de transformación sobre el elemento seleccionado. */
@Composable
fun Preview(vm: EditorViewModel, project: Project, modifier: Modifier = Modifier, viewOnly: Boolean = false) {
    val haptics = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val dp = LocalDensity.current.density
    val positionUs by vm.player.positionUs.collectAsState()
    val selection by vm.selection.collectAsState()
    val tool by vm.tool.collectAsState()
    val zoneSel by vm.zoneSel.collectAsState()
    val buffering by vm.player.buffering.collectAsState()
    val error by vm.player.error.collectAsState()
    val settings = vm.container.settings.flow.collectAsState().value
    val render = remember { OverlayRender() }
    val tmp = remember { RectF() }
    var guideV by remember { mutableStateOf(false) }
    var guideH by remember { mutableStateOf(false) }
    val projectState = rememberUpdatedState(project)
    val selectionState = rememberUpdatedState(selection)
    val toolState = rememberUpdatedState(tool)
    val accent = MaterialTheme.colorScheme.primary

    // Zoom / desplazamiento de la propia vista (ajustes finos). Solo con dos dedos y SIN elemento seleccionable en el lienzo.
    var viewZoom by remember { mutableStateOf(1f) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    var hand by remember { mutableStateOf(false) }
    val selClip = selection.singleOrNull()?.let { project.clip(it) }
    val noSel = !(selClip != null && selClip.kind != ClipKind.AUDIO && selClip.kind != ClipKind.ADJUST && project.trackOf(selClip.id)?.locked != true)
    val noSelState = rememberUpdatedState(noSel)
    val handState = rememberUpdatedState(hand)
    val zoomed = viewZoom > 1.01f

    // clipToBounds: el marco de selección (y sus asas) no se sale de la zona de vista previa.
    BoxWithConstraints(modifier.background(Color(settings.workspaceColor)).clipToBounds().pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val ev = awaitPointerEvent()
                val n = ev.changes.count { it.pressed }
                if ((viewOnly || noSelState.value || handState.value) && (n >= 2 || (n == 1 && handState.value && viewZoom > 1.01f))) {
                    val z = if (n >= 2) ev.calculateZoom() else 1f
                    val pan = ev.calculatePan()
                    val cen = if (n >= 2) ev.calculateCentroid(useCurrent = true) else Offset(size.width / 2f, size.height / 2f)
                    val rc = cen - Offset(size.width / 2f, size.height / 2f)
                    val z2 = (viewZoom * z).coerceIn(1f, 8f)
                    var nx = rc.x + pan.x - (z2 / viewZoom) * (rc.x - panX)
                    var ny = rc.y + pan.y - (z2 / viewZoom) * (rc.y - panY)
                    val asp = projectState.value.aspect
                    val bw = size.width.toFloat(); val bh = size.height.toFloat()
                    val vw0 = if (bw / bh > asp) bh * asp else bw
                    val vh0 = if (bw / bh > asp) bh else bw / asp
                    val mx = (z2 - 1f) * vw0 / 2f; val my = (z2 - 1f) * vh0 / 2f
                    if (z2 <= 1.01f) { nx = 0f; ny = 0f } else { nx = nx.coerceIn(-mx, mx); ny = ny.coerceIn(-my, my) }
                    viewZoom = z2; panX = nx; panY = ny
                    ev.changes.forEach { it.consume() }
                }
            } while (ev.changes.any { it.pressed })
        }
    }, contentAlignment = Alignment.Center) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val aspect = project.aspect
        val vw = if (boxW / boxH > aspect) boxH * aspect else boxW
        val vh = if (boxW / boxH > aspect) boxH else boxW / aspect
        val density = LocalDensity.current
        // Contenedor del tamaño de TODA la zona (incluida la franja gris): el lienzo de gestos lo cubre entero para poder
        // coger tiradores y elementos que sobresalen del vídeo. El zoom se aplica a todo a la vez (mismo centro).
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = viewZoom; scaleY = viewZoom; translationX = panX; translationY = panY }, contentAlignment = Alignment.Center) {
          Box(Modifier.size(with(density) { vw.toDp() }, with(density) { vh.toDp() })) {
            // Varias TextureView apiladas (una superficie por reproductor): el relevo entre reproductores trae al frente la
            // del que entra. Todas se dibujan siempre (la de detrás debe pintar su primer fotograma antes del relevo).
            val frontSlot by vm.player.frontSlot.collectAsState()
            val views = remember { arrayOfNulls<TextureView>(PreviewPlayer.SLOTS) }
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        // Captura del fotograma actual (con filtros, efectos y capas) para la miniatura del proyecto.
                        vm.previewShot = {
                            try {
                                val v = views[vm.player.frontSlot.value]
                                if (v == null || !v.isAvailable || v.width < 2 || v.height < 2) null
                                else {
                                    val k = minOf(1f, 360f / maxOf(v.width, v.height))
                                    v.getBitmap((v.width * k).toInt().coerceAtLeast(2), (v.height * k).toInt().coerceAtLeast(2))
                                }
                            } catch (e: Throwable) { null }
                        }
                        for (slot in 0 until PreviewPlayer.SLOTS) addView(TextureView(ctx).apply {
                            var s: Surface? = null
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                    s = Surface(st); vm.player.attach(s, w, h, slot)
                                }
                                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { vm.player.attach(s, w, h, slot) }
                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    s?.let { vm.player.detach(it, slot); it.release() }
                                    s = null
                                    if (slot == 0) vm.previewShot = null
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) { vm.player.onFrame(slot) }
                            }
                            views[slot] = this
                        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    }
                },
                update = { views[frontSlot]?.bringToFront() },
                modifier = Modifier.fillMaxSize(),
            )
          }
            // viewOnly (pantalla completa): solo se mira; no hay selección, marco, asas ni arrastre de elementos.
            if (!viewOnly) Canvas(
                Modifier.fillMaxSize().pointerInput(vw, vh) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Con la mano activa no se toca ningún elemento: solo se mueve el lienzo (lo hace el gesto exterior).
                        if (handState.value) return@awaitEachGesture
                        val w = vw
                        val h = vh
                        val org = Offset((size.width - vw) / 2f, (size.height - vh) / 2f)
                        var total = Offset.Zero
                        var moved = false
                        var lastSnapX = false
                        var lastSnapY = false
                        val p0 = projectState.value
                        val t0 = vm.player.positionUs.value
                        val id = selectionState.value.singleOrNull()
                        val start = id?.let { p0.clip(it) }?.takeIf { c -> c.kind != ClipKind.AUDIO && c.kind != ClipKind.ADJUST && p0.trackOf(c.id)?.locked != true }
                        val local0 = if (start != null) (t0 - start.startUs).coerceIn(0, start.durationUs) else 0L
                        val sx = start?.prop("x", local0) ?: 0f
                        val sy = start?.prop("y", local0) ?: 0f
                        var scale = start?.prop("scale", local0) ?: 1f
                        var rot = start?.prop("rotation", local0) ?: 0f
                        val frame0 = start?.let { frameOf(render, p0, it, local0, w, h, tmp) }
                        val editMask = toolState.value == "mask"
                        val editZone = toolState.value == "zone"
                        // ¿Se ha tocado un tirador del elemento seleccionado?
                        val handle = if (start != null && frame0 != null && !editMask && !editZone) frame0.handleAt(down.position - org, 24 * dp) else -1
                        val sx0 = start?.prop("sx", local0) ?: 1f
                        val sy0 = start?.prop("sy", local0) ?: 1f
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (start == null) { if (pressed.first().positionChanged()) { total += pressed.first().position - pressed.first().previousPosition; if (total.getDistance() > touchSlop) moved = true }; continue }
                            if (pressed.size >= 2) {
                                moved = true
                                val z = event.calculateZoom()
                                val r = event.calculateRotation()
                                event.changes.forEach { it.consume() }
                                if (editMask || editZone) {
                                    val pre = if (editMask) "mask" else zoneKey(vm.zoneSel.value.coerceIn(0, start.zones.size))
                                    vm.updateClip(R.string.hist_mask, start.id, live = true) { c ->
                                        c.setProp("$pre.w", local0, (c.prop("$pre.w", local0) * z).coerceIn(0.02f, 2f)).setProp("$pre.h", local0, (c.prop("$pre.h", local0) * z).coerceIn(0.02f, 2f))
                                    }
                                } else {
                                    scale = (scale * z).coerceIn(0.02f, 20f)
                                    rot += r
                                    // Imán de giro a múltiplos de 90°.
                                    val nearest = Math.round(rot / 90f) * 90f
                                    val shown = if (abs(rot - nearest) < 3f) nearest else rot
                                    vm.updateClip(R.string.hist_transform, start.id, live = true) { c -> c.setProp("scale", local0, scale).setProp("rotation", local0, shown) }
                                }
                                continue
                            }
                            val ch = pressed.first()
                            if (handle >= 0 && frame0 != null) {
                                // Tirador: esquinas escalan en proporción, mitades de lado estiran un solo eje. El lado opuesto queda fijo.
                                if (!ch.positionChanged()) continue
                                moved = true
                                ch.consume()
                                val u = HANDLES[handle]
                                val q = frame0.local(ch.position - org)
                                val w0 = frame0.w.coerceAtLeast(1f)
                                val h0 = frame0.h.coerceAtLeast(1f)
                                val kx = ((u.x * q.x + w0 / 2) / w0).coerceAtLeast(0.02f)
                                val ky = ((u.y * q.y + h0 / 2) / h0).coerceAtLeast(0.02f)
                                val uniform = u.x != 0f && u.y != 0f
                                val k = if (uniform) ((kx + ky) / 2f) else 1f
                                val fx = if (u.x == 0f) 1f else if (uniform) k else kx
                                val fy = if (u.y == 0f) 1f else if (uniform) k else ky
                                val lx = u.x * (fx - 1f) * w0 / 2
                                val ly = u.y * (fy - 1f) * h0 / 2
                                val r = Math.toRadians(frame0.rot.toDouble())
                                val ox = (lx * cos(r) - ly * sin(r)).toFloat()
                                val oy = (lx * sin(r) + ly * cos(r)).toFloat()
                                vm.updateClip(R.string.hist_transform, start.id, live = true) { c ->
                                    var n = c.setProp("x", local0, sx + ox / w).setProp("y", local0, sy + oy / h)
                                    n = if (uniform) n.setProp("scale", local0, (scale * k).coerceIn(0.02f, 20f))
                                    else n.setProp("sx", local0, (sx0 * fx).coerceIn(0.05f, 20f)).setProp("sy", local0, (sy0 * fy).coerceIn(0.05f, 20f))
                                    n
                                }
                                continue
                            }
                            if (!ch.positionChanged()) continue
                            total += ch.position - ch.previousPosition
                            if (!moved && total.getDistance() < touchSlop) continue
                            moved = true
                            ch.consume()
                            if (editMask || editZone) {
                                val pre = if (editMask) "mask" else zoneKey(vm.zoneSel.value.coerceIn(0, start.zones.size))
                                val fw = (frame0?.w ?: w).coerceAtLeast(1f)
                                val fh = (frame0?.h ?: h).coerceAtLeast(1f)
                                val ddx = (ch.position.x - ch.previousPosition.x) / fw
                                val ddy = (ch.position.y - ch.previousPosition.y) / fh
                                vm.updateClip(R.string.hist_mask, start.id, live = true) { c ->
                                    c.setProp("$pre.cx", local0, (c.prop("$pre.cx", local0) + ddx).coerceIn(0f, 1f)).setProp("$pre.cy", local0, (c.prop("$pre.cy", local0) + ddy).coerceIn(0f, 1f))
                                }
                                continue
                            }
                            var nx = sx + total.x / w
                            var ny = sy + total.y / h
                            var snapX = false
                            var snapY = false
                            if (vm.settings.snap && frame0 != null) {
                                // Imán al centro y a los bordes del lienzo.
                                val tol = 8 * dp
                                val ecx = frame0.cx + total.x
                                val ecy = frame0.cy + total.y
                                val candX = floatArrayOf(w / 2 - ecx, 0f - (ecx - frame0.w / 2), w - (ecx + frame0.w / 2))
                                val candY = floatArrayOf(h / 2 - ecy, 0f - (ecy - frame0.h / 2), h - (ecy + frame0.h / 2))
                                candX.minByOrNull { abs(it) }?.let { if (abs(it) < tol) { nx += it / w; snapX = true } }
                                candY.minByOrNull { abs(it) }?.let { if (abs(it) < tol) { ny += it / h; snapY = true } }
                            }
                            if (((snapX && !lastSnapX) || (snapY && !lastSnapY)) && vm.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            lastSnapX = snapX; lastSnapY = snapY
                            guideV = snapX; guideH = snapY
                            vm.updateClip(R.string.hist_transform, start.id, live = true) { c -> c.setProp("x", local0, nx).setProp("y", local0, ny) }
                        }
                        guideV = false; guideH = false
                        vm.commit()
                        if (!moved) {
                            // Toque: selecciona el elemento visible que esté más arriba bajo el dedo.
                            val p = projectState.value
                            val t = vm.player.positionUs.value
                            var found: Clip? = null
                            for (tr in p.tracks) {
                                if (tr.hidden || tr.locked || tr.type == TrackType.AUDIO || tr.type == TrackType.ADJUST) continue
                                val c = tr.clips.lastOrNull { t >= it.startUs && t < it.endUs } ?: continue
                                if (frameOf(render, p, c, t - c.startUs, w, h, tmp).contains(down.position - org)) { found = c; break }
                            }
                            if (found != null && found.id != id) vm.selectOnly(found.id) else if (found == null) vm.select(null)
                        }
                    }
                },
            ) {
                val w = vw
                val h = vh
                translate((size.width - vw) / 2f, (size.height - vh) / 2f) {
                if (settings.showGrid) {
                    val g = Color.White.copy(alpha = 0.35f)
                    for (i in 1..2) {
                        drawLine(g, Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), 1f)
                        drawLine(g, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), 1f)
                    }
                }
                if (settings.showSafeZones) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
                    drawRect(Color.White.copy(alpha = 0.5f), Offset(w * 0.05f, h * 0.05f), Size(w * 0.9f, h * 0.9f), style = Stroke(1.5f, pathEffect = dash))
                    drawRect(Color.Yellow.copy(alpha = 0.5f), Offset(w * 0.1f, h * 0.1f), Size(w * 0.8f, h * 0.8f), style = Stroke(1.5f, pathEffect = dash))
                }
                if (guideV) drawLine(Color.Magenta, Offset(w / 2, 0f), Offset(w / 2, h), 2f)
                if (guideH) drawLine(Color.Magenta, Offset(0f, h / 2), Offset(w, h / 2), 2f)
                val sel = selection.singleOrNull()?.let { project.clip(it) }
                if (!hand && sel != null && sel.kind != ClipKind.AUDIO && sel.kind != ClipKind.ADJUST && positionUs >= sel.startUs && positionUs <= sel.endUs) {
                    val local = positionUs - sel.startUs
                    val f = frameOf(render, project, sel, local, w, h, tmp)
                    rotate(f.rot, Offset(f.cx, f.cy)) {
                        drawRect(accent, Offset(f.cx - f.w / 2, f.cy - f.h / 2), Size(f.w, f.h), style = Stroke(2 * dp))
                        for ((i, u) in HANDLES.withIndex()) {
                            val c = Offset(f.cx + u.x * f.w / 2, f.cy + u.y * f.h / 2)
                            if (i < 4) {
                                // Esquinas: redimensionan en proporción.
                                drawCircle(Color.White, 6 * dp, c)
                                drawCircle(accent, 6 * dp, c, style = Stroke(2 * dp))
                            } else {
                                // Mitades de lado: deforman un solo eje (la barra va a lo largo del lado).
                                val bw = if (u.x == 0f) 18 * dp else 8 * dp
                                val bh = if (u.x == 0f) 8 * dp else 18 * dp
                                drawRoundRect(Color.White, Offset(c.x - bw / 2, c.y - bh / 2), Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(4 * dp))
                                drawRoundRect(accent, Offset(c.x - bw / 2, c.y - bh / 2), Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(4 * dp), style = Stroke(2 * dp))
                            }
                        }
                        // Máscara / zona: su contorno dentro del clip.
                        if (tool == "mask" || tool == "zone") {
                            val zi = zoneSel.coerceIn(0, sel.zones.size)
                            val pre = if (tool == "mask") "mask" else zoneKey(zi)
                            val mx = f.cx - f.w / 2 + sel.prop("$pre.cx", local) * f.w
                            val my = f.cy - f.h / 2 + sel.prop("$pre.cy", local) * f.h
                            val mw = sel.prop("$pre.w", local) * f.w
                            val mh = sel.prop("$pre.h", local) * f.h
                            val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
                            val round = if (tool == "mask") sel.mask.type == com.dskmusic.vixa.model.MaskType.CIRCLE else sel.allZones[zi].ellipse
                            if (round) drawOval(Color.Yellow, Offset(mx - mw / 2, my - mh / 2), Size(mw, mh), style = Stroke(2 * dp, pathEffect = dash))
                            else drawRect(Color.Yellow, Offset(mx - mw / 2, my - mh / 2), Size(mw, mh), style = Stroke(2 * dp, pathEffect = dash))
                            drawCircle(Color.Yellow, 4 * dp, Offset(mx, my))
                        }
                    }
                }
                }
            }
        }
        if (zoomed) {
            // Solo aparecen mientras la vista está ampliada: restablecer al 100 % y mano para arrastrar la vista.
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.material3.FilledTonalIconButton({ hand = !hand }, Modifier.size(36.dp),
                    colors = if (hand) androidx.compose.material3.IconButtonDefaults.filledIconButtonColors() else androidx.compose.material3.IconButtonDefaults.filledTonalIconButtonColors()) {
                    Icon(Icons.Default.PanTool, stringResource(R.string.pan_view), Modifier.size(20.dp))
                }
                androidx.compose.material3.FilledTonalIconButton({ viewZoom = 1f; panX = 0f; panY = 0f; hand = false }, Modifier.size(36.dp)) {
                    Icon(Icons.Default.FitScreen, stringResource(R.string.reset_view), Modifier.size(20.dp))
                }
            }
        }
        if (buffering) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        error?.let {
            Text(stringResource(R.string.preview_error, it), color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xAA000000)).padding(8.dp))
        }
        if (project.durationUs <= 0L) {
            Text(stringResource(R.string.empty_timeline_hint), color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).width(with(density) { vw.toDp() }).padding(horizontal = 16.dp))
        }
    }
}

/** Controles de reproducción: fotograma a fotograma, reproducir, bucle, guías y pantalla completa. */
@Composable
fun Transport(vm: EditorViewModel, project: Project, modifier: Modifier = Modifier) {
    val positionUs by vm.player.positionUs.collectAsState()
    val playing by vm.player.playing.collectAsState()
    val loop by vm.loop.collectAsState()
    val full by vm.fullscreen.collectAsState()
    val settings = vm.container.settings.flow.collectAsState().value
    Row(modifier.fillMaxWidth().height(44.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(positionUs) + " / " + formatTime(project.durationUs), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 8.dp))
        val selClip = vm.selection.collectAsState().value.singleOrNull()?.let { project.clip(it) }
        // Play, hueco con el keyframe centrado y, a la derecha, bucle/guías/pantalla completa.
        Spacer(Modifier.weight(1f))
        FilledIconButton({ vm.player.toggle() }, Modifier.size(38.dp)) {
            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(R.string.play_pause))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // Keyframe global: fija TODAS las propiedades animables del clip seleccionado en este instante (o las quita).
            val kfClip = selClip
            val kfLocal = if (kfClip != null) (positionUs - kfClip.startUs).coerceIn(0, kfClip.durationUs) else 0L
            val kfPos = if (kfClip != null) kfLocal.toFloat() / kfClip.durationUs.coerceAtLeast(1) else 0f
            val kfHere = kfClip != null && (kfClip.keyframes.keys.any { kfClip.keyframeNear(it, kfLocal) != null } || kfClip.speedCurve.any { abs(it.pos - kfPos) < 0.02f })
            if (!full) IconButton({
                if (kfClip != null) vm.updateClip(R.string.hist_keyframe, kfClip.id) { k ->
                    val props = (listOf("x", "y", "scale", "rotation", "opacity", "brightness", "contrast", "saturation", "exposure", "temperature", "tint", "vibrance", "hue") +
                        (if (k.audible || k.kind == ClipKind.AUDIO) listOf("volume") else emptyList()) + k.keyframes.keys).distinct()
                    val r = if (kfHere) props.fold(k) { acc, pr -> if (acc.keyframeNear(pr, kfLocal) != null) acc.withoutKeyframe(pr, kfLocal) else acc }
                    else props.fold(k) { acc, pr -> acc.withKeyframe(pr, kfLocal, acc.prop(pr, kfLocal)) }
                    // Velocidad (vídeo/audio): punto de curva en este instante; sin curva previa se parte de inicio/fin a la velocidad actual.
                    if (k.kind != ClipKind.VIDEO && k.kind != ClipKind.AUDIO) r
                    else if (kfHere) r.copy(speedCurve = r.speedCurve.filter { abs(it.pos - kfPos) >= 0.02f }.let { if (it.size < 2) emptyList() else it })
                    else {
                        val base = if (r.speedCurve.size >= 2) r.speedCurve else listOf(SpeedPoint(0f, r.speed), SpeedPoint(1f, r.speed))
                        r.copy(speedCurve = (base.filter { abs(it.pos - kfPos) >= 0.02f } + SpeedPoint(kfPos, r.speedAt(kfPos))).sortedBy { it.pos })
                    }
                }
            }, Modifier.size(36.dp), enabled = kfClip != null) {
                Icon(Icons.Default.Diamond, stringResource(R.string.keyframe), tint = if (kfHere) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.loop.value = !loop; vm.player.setLoop(!loop) }, Modifier.size(36.dp)) {
                Icon(Icons.Default.Repeat, stringResource(R.string.loop), tint = if (loop) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({
                // Ciclo: sin guías → cuadrícula → cuadrícula + zonas seguras → sin guías.
                vm.container.settings.update { s -> if (!s.showGrid) s.copy(showGrid = true) else if (!s.showSafeZones) s.copy(showSafeZones = true) else s.copy(showGrid = false, showSafeZones = false) }
            }, Modifier.size(36.dp)) {
                Icon(Icons.Default.Grid4x4, stringResource(R.string.guides), tint = if (settings.showGrid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton({ vm.fullscreen.value = !full }, Modifier.size(36.dp)) {
                Icon(if (full) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, stringResource(R.string.fullscreen))
            }
        }
    }
}
