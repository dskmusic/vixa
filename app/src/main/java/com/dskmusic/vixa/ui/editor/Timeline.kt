package com.dskmusic.vixa.ui.editor

import androidx.compose.foundation.layout.padding
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dskmusic.vixa.R
import com.dskmusic.vixa.media.Thumbs
import com.dskmusic.vixa.media.Waveforms
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.Track
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.addTrack
import com.dskmusic.vixa.model.removeTrack
import com.dskmusic.vixa.model.mainIndexAt
import com.dskmusic.vixa.model.moveClip
import com.dskmusic.vixa.model.moveAcrossMain
import com.dskmusic.vixa.model.reorderMain
import com.dskmusic.vixa.model.shiftClips
import com.dskmusic.vixa.model.snap
import com.dskmusic.vixa.model.snapPoints
import com.dskmusic.vixa.model.sourceOffsetAt
import com.dskmusic.vixa.model.trimClip
import com.dskmusic.vixa.model.updateTrack
import com.dskmusic.vixa.ui.formatTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private enum class DragMode { NONE, SCRUB, TRIM_START, TRIM_END, MOVE, PINCH }

private fun rowHeightDp(t: TrackType): Float = when (t) {
    TrackType.MAIN -> 56f
    TrackType.OVERLAY -> 44f
    TrackType.AUDIO -> 40f
    else -> 32f
}

private fun kindColor(k: ClipKind): Int = when (k) {
    ClipKind.VIDEO, ClipKind.IMAGE -> 0xFF3A3F58.toInt()
    ClipKind.COLOR -> 0xFF555555.toInt()
    ClipKind.AUDIO -> 0xFF1F6F5C.toInt()
    ClipKind.TEXT -> 0xFFB5622D.toInt()
    ClipKind.STICKER -> 0xFF8D4AA8.toInt()
    ClipKind.ADJUST -> 0xFF2F6FB0.toInt()
}

/**
 * Línea de tiempo multipista. El cabezal está fijo en el centro y el contenido se desplaza bajo él:
 * arrastrar = recorrer el vídeo. Solo se dibujan los clips visibles.
 */
@Composable
fun Timeline(vm: EditorViewModel, project: Project, modifier: Modifier = Modifier, onAddKind: (String) -> Unit = {}) {
    val density = LocalDensity.current
    val appRes = androidx.compose.ui.platform.LocalContext.current.resources
    val haptics = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val longPressMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val positionUs by vm.player.positionUs.collectAsState()
    val zoomDp by vm.zoom.collectAsState()
    val selection by vm.selection.collectAsState()
    val thumbTick by Thumbs.tick.collectAsState()
    val waveTick by Waveforms.tick.collectAsState()
    var scrollY by remember { mutableFloatStateOf(0f) }
    var movingId by remember { mutableStateOf<String?>(null) }
    // Pista que se va a eliminar tras una pulsación larga en un hueco (pide confirmación).
    var deleteTrackId by remember { mutableStateOf<String?>(null) }
    // Último toque sobre un clip (id, instante): dos toques seguidos sobre el mismo clip editable abren su editor.
    var lastTap by remember { mutableStateOf<Pair<String, Long>?>(null) }

    val projectState = rememberUpdatedState(project)
    val selectionState = rememberUpdatedState(selection)
    val pxPerSec = with(density) { zoomDp.dp.toPx() }
    val pxPerSecState = rememberUpdatedState(pxPerSec)
    val headerW = 40.dp
    val rulerH = with(density) { 22.dp.toPx() }
    val handleW = with(density) { 14.dp.toPx() }
    val dp = density.density

    val colors = MaterialTheme.colorScheme
    val bg = colors.surfaceContainerLow
    val primary = colors.primary
    val onSurface = colors.onSurface
    val outline = colors.outlineVariant

    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD } }
    val bmpPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    val fillPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

    fun rowTops(p: Project): FloatArray {
        val tops = FloatArray(p.tracks.size + 1)
        var y = rulerH + 4 * dp
        for ((i, t) in p.tracks.withIndex()) { tops[i] = y; y += (rowHeightDp(t.type) + 4f) * dp }
        tops[p.tracks.size] = y
        return tops
    }

    // Pista principal: entre clips queda un hueco visual donde va el botón de transición (el tiempo no cambia).
    fun junctionUs(c: Clip): Long = c.startUs + (c.transitionIn?.durationUs ?: 0L) / 2
    fun gapUs(a: Clip, b: Clip, pps: Float): Long = min((22 * dp / pps * 1_000_000f).toLong(), min(a.durationUs, b.durationUs) * 3 / 10)
    fun edgeL(tr: Track, i: Int, pps: Float): Long {
        val c = tr.clips[i]
        return if (tr.type == TrackType.MAIN && i > 0) junctionUs(c) + gapUs(tr.clips[i - 1], c, pps) / 2 else c.startUs
    }
    fun edgeR(tr: Track, i: Int, pps: Float): Long {
        val c = tr.clips[i]
        return if (tr.type == TrackType.MAIN && i < tr.clips.size - 1) junctionUs(tr.clips[i + 1]) - gapUs(c, tr.clips[i + 1], pps) / 2 else c.endUs
    }

    Box(modifier.background(bg).clipToBounds()) {
    Row(Modifier.fillMaxSize()) {
        // Cabeceras de pista: silenciar / bloquear / ocultar.
        Box(Modifier.width(headerW).fillMaxHeight().clipToBounds()) {
            val tops = rowTops(project)
            for ((i, t) in project.tracks.withIndex()) {
                val h = rowHeightDp(t.type)
                Column(Modifier.offset { IntOffset(0, (tops[i] - scrollY).roundToInt()) }.width(headerW).height(h.dp)) {
                    val audio = t.type == TrackType.AUDIO || t.type == TrackType.MAIN || t.type == TrackType.OVERLAY
                    val small = h < 40f
                    if (t.type == TrackType.AUDIO || (audio && !small && t.type == TrackType.MAIN)) {
                        IconButton({ vm.edit(R.string.hist_track) { it.updateTrack(t.id) { x -> x.copy(muted = !x.muted) } } }, Modifier.size(if (small) h.dp else (h / 2).dp)) {
                            Icon(if (t.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(16.dp),
                                tint = if (t.muted) colors.error else colors.onSurfaceVariant)
                        }
                    }
                    if (t.type != TrackType.AUDIO) {
                        IconButton({ vm.edit(R.string.hist_track) { it.updateTrack(t.id) { x -> x.copy(hidden = !x.hidden) } } }, Modifier.size(if (small || t.type != TrackType.MAIN) minOf(h, 28f).dp else (h / 2).dp)) {
                            Icon(if (t.hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, Modifier.size(16.dp),
                                tint = if (t.hidden) colors.error else colors.onSurfaceVariant)
                        }
                    }
                    if (t.type == TrackType.OVERLAY) {
                        IconButton({ vm.edit(R.string.hist_track) { it.updateTrack(t.id) { x -> x.copy(locked = !x.locked) } } }, Modifier.size(16.dp)) {
                            Icon(if (t.locked) Icons.Default.Lock else Icons.Default.LockOpen, null, Modifier.size(12.dp), tint = if (t.locked) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val p0 = projectState.value
                    val pps = pxPerSecState.value
                    val center = size.width / 2f
                    val startPos = vm.player.positionUs.value
                    fun timeAt(x: Float, pos: Long = startPos, scale: Float = pps): Long = pos + ((x - center) / scale * 1_000_000f).toLong()
                    val tops = rowTops(p0)
                    val y = down.position.y + scrollY
                    val trackIdx = (0 until p0.tracks.size).firstOrNull { y >= tops[it] && y < tops[it + 1] - 4 * dp } ?: -1
                    val track: Track? = p0.tracks.getOrNull(trackIdx)
                    val tDown = timeAt(down.position.x)
                    val tolUs = (handleW / pps * 1_000_000f).toLong()
                    // ¿Se ha tocado un tirador del clip seleccionado?
                    val sel = selectionState.value.singleOrNull()?.let { p0.clip(it) }?.takeIf { track != null && track.clips.any { c -> c.id == it.id } && !track.locked }
                    var mode = DragMode.NONE
                    var target: Clip? = null
                    if (sel != null && track != null && down.position.y > rulerH) {
                        val si = track.clips.indexOfFirst { it.id == sel.id }
                        val vs = edgeL(track, si, pps)
                        val ve = edgeR(track, si, pps)
                        if (abs(tDown - vs) <= tolUs && tDown <= vs + min(tolUs, sel.durationUs / 3)) { mode = DragMode.TRIM_START; target = sel }
                        else if (abs(tDown - ve) <= tolUs && tDown >= ve - min(tolUs, sel.durationUs / 3)) { mode = DragMode.TRIM_END; target = sel }
                    }
                    val hit: Clip? = if (down.position.y <= rulerH || track == null) null
                        else track.clips.withIndex().lastOrNull { (i, _) -> tDown >= edgeL(track, i, pps) && tDown < edgeR(track, i, pps) }?.value
                    val snapOn = vm.settings.snap
                    val snapTol = (10 * dp / pps * 1_000_000f).toLong()
                    val points = p0.snapPoints(listOfNotNull(target?.id ?: hit?.id), startPos)
                    var lastSnap: Long? = null
                    var lastScrubSnap: Long? = null
                    fun snapped(t: Long): Long {
                        if (!snapOn) return t
                        val s = points.snap(t, snapTol)
                        if (s != null && s != lastSnap) if (vm.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        lastSnap = s
                        return s ?: t
                    }
                    val initial = target ?: hit
                    val initStart = initial?.startUs ?: 0L
                    val initDur = initial?.durationUs ?: 0L
                    var total = Offset.Zero
                    var moved = false
                    var longPressed = false
                    val downTime = down.uptimeMillis
                    // Hueco de una pista (vacía o con clips, pero sin ninguno bajo el dedo): la pulsación larga ofrece eliminarla.
                    val emptyPress = hit == null && track != null && track.type != TrackType.MAIN && !track.locked && down.position.y > rulerH && mode == DragMode.NONE
                    var lastUptime = downTime
                    val scrubScale = pps
                    if (mode != DragMode.NONE) { vm.player.setScrubbing(true); if (vm.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

                    while (true) {
                        // Pulsación larga sobre un clip: se detecta por tiempo mientras no haya movimiento.
                        val event = if (!moved && !longPressed && mode == DragMode.NONE && (hit != null || emptyPress) && track?.locked != true) {
                            withTimeoutOrNull(max(1L, longPressMs - (lastUptime - downTime))) { awaitPointerEvent(PointerEventPass.Main) }
                        } else awaitPointerEvent(PointerEventPass.Main)
                        if (event != null) lastUptime = event.changes.firstOrNull()?.uptimeMillis ?: lastUptime
                        if (event == null) {
                            longPressed = true
                            if (hit == null) {
                                deleteTrackId = track?.id
                                if (vm.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                continue
                            }
                            mode = DragMode.MOVE
                            target = hit
                            movingId = hit?.id
                            if (hit != null && hit.id !in selectionState.value) vm.select(hit.id)
                            if (vm.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            continue
                        }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            if (mode != DragMode.PINCH) { mode = DragMode.PINCH; vm.commit() }
                            val z = event.calculateZoom()
                            if (z != 1f) vm.zoom.value = (vm.zoom.value * z).coerceIn(4f, 600f)
                            event.changes.forEach { it.consume() }
                            moved = true
                            continue
                        }
                        if (mode == DragMode.PINCH) continue
                        val change = pressed.first()
                        if (!change.positionChanged()) continue
                        total += change.position - change.previousPosition
                        if (!moved && total.getDistance() < touchSlop) continue
                        if (!moved) {
                            moved = true
                            if (mode == DragMode.NONE) { mode = DragMode.SCRUB; vm.player.setScrubbing(true) }
                        }
                        change.consume()
                        val dUs = (total.x / scrubScale * 1_000_000f).toLong()
                        val cur = vm.project.value ?: break
                        when (mode) {
                            DragMode.SCRUB -> {
                                var want = startPos - dUs
                                if (vm.container.settings.flow.value.playheadSnap) {
                                    // Cortes de todos los clips (sin el propio cabezal): lo atraen dentro de ~10 dp.
                                    val cuts = cur.snapPoints(emptyList(), 0L)
                                    val hit = cuts.snap(want, (10 * dp / scrubScale * 1_000_000f).toLong())
                                    if (hit != null) {
                                        if (hit != lastScrubSnap && vm.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        want = hit
                                    }
                                    lastScrubSnap = hit
                                }
                                vm.player.seekTo(want)
                                val maxScroll = max(0f, rowTops(cur)[cur.tracks.size] + 60 * dp - size.height)
                                scrollY = (scrollY - (change.position.y - change.previousPosition.y)).coerceIn(0f, maxScroll)
                            }
                            DragMode.TRIM_START -> target?.let { t0 ->
                                val c = cur.clip(t0.id) ?: return@let
                                val isMain = cur.trackOf(c.id)?.type == TrackType.MAIN
                                val want = if (isMain) dUs else snapped(initStart + dUs) - initStart
                                val applied = initDur - c.durationUs
                                vm.edit(R.string.hist_trim, live = true) { it.trimClip(c.id, true, want - applied) }
                                vm.project.value?.clip(c.id)?.let { vm.player.seekTo(it.startUs) }
                            }
                            DragMode.TRIM_END -> target?.let { t0 ->
                                val c = cur.clip(t0.id) ?: return@let
                                val want = snapped(initStart + initDur + dUs) - (initStart + initDur)
                                val applied = c.durationUs - initDur
                                vm.edit(R.string.hist_trim, live = true) { it.trimClip(c.id, false, want - applied) }
                                vm.project.value?.clip(c.id)?.let { vm.player.seekTo(max(it.startUs, it.endUs - cur.frameUs)) }
                            }
                            DragMode.MOVE -> target?.let { t0 ->
                                val c = cur.clip(t0.id) ?: return@let
                                val from = cur.trackOf(c.id) ?: return@let
                                val ty0 = change.position.y + scrollY
                                val tp0 = rowTops(cur)
                                val over0 = (0 until cur.tracks.size).firstOrNull { ty0 >= tp0[it] && ty0 < tp0[it + 1] }?.let { cur.tracks[it] }
                                if (over0 != null && over0.id != from.id && selectionState.value.size <= 1 &&
                                    ((from.type == TrackType.MAIN && over0.type == TrackType.OVERLAY) || (from.type == TrackType.OVERLAY && over0.type == TrackType.MAIN))) {
                                    // Arrastre vertical entre principal y superposición (PiP).
                                    vm.edit(R.string.hist_move, live = true) { it.moveAcrossMain(c.id, over0.id, max(0L, initStart + dUs)) }
                                    if (vm.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                } else if (from.type == TrackType.MAIN) {
                                    val tt = timeAt(change.position.x, startPos, scrubScale)
                                    val others = from.clips.filter { it.id != c.id }
                                    var idx = others.size
                                    for ((i, o) in others.withIndex()) if (tt < o.startUs + o.durationUs / 2) { idx = i; break }
                                    if (idx != from.clips.indexOfFirst { it.id == c.id }) {
                                        vm.edit(R.string.hist_move, live = true) { it.reorderMain(c.id, idx) }
                                        if (vm.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                } else {
                                    var s = initStart + dUs
                                    val a = snapped(s)
                                    if (a != s) s = a else { val e = snapped(s + c.durationUs); if (e != s + c.durationUs) s = e - c.durationUs }
                                    val group = selectionState.value
                                    if (group.size > 1 && c.id in group) {
                                        vm.edit(R.string.hist_move, live = true) { it.shiftClips(group, max(0L, s) - c.startUs) }
                                    } else {
                                        val ty = change.position.y + scrollY
                                        val tp = rowTops(cur)
                                        val over = (0 until cur.tracks.size).firstOrNull { ty >= tp[it] && ty < tp[it + 1] }?.let { cur.tracks[it] }
                                        val dest = if (over != null && over.type == from.type && !over.locked) over else from
                                        vm.edit(R.string.hist_move, live = true) { it.moveClip(c.id, dest.id, max(0L, s)) }
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                    // Fin del gesto.
                    vm.player.setScrubbing(false)
                    vm.commit()
                    movingId = null
                    if (!moved && !longPressed && mode != DragMode.PINCH) {
                        // Toque: unión entre clips (transición), clip (selección) o vacío (deseleccionar).
                        val main = p0.mainTrack
                        val junction = if (track?.type == TrackType.MAIN) main.clips.drop(1).firstOrNull { abs(tDown - junctionUs(it)) <= (12 * dp / pps * 1_000_000f).toLong() } else null
                        if (junction != null && down.position.y > rulerH) { vm.selectOnly(junction.id); vm.tool.value = "transition" }
                        else if (hit != null) {
                            val prev = lastTap
                            val editable = hit.kind == ClipKind.TEXT || hit.kind == ClipKind.STICKER
                            if (editable && !vm.multiSelect.value && prev != null && prev.first == hit.id && down.uptimeMillis - prev.second < 350L) {
                                // Doble toque: se selecciona el clip y se abre el editor de texto (o el de stickers).
                                lastTap = null
                                vm.selectOnly(hit.id)
                                vm.tool.value = if (hit.kind == ClipKind.TEXT) "text" else "stickers"
                            } else {
                                vm.select(hit.id)
                                lastTap = hit.id to down.uptimeMillis
                            }
                        }
                        else if (down.position.y <= rulerH) vm.player.seekTo(tDown)
                        else vm.select(null)
                    }
                }
            },
        ) {
            // Lecturas que invalidan el dibujo cuando llegan miniaturas / ondas nuevas.
            @Suppress("UNUSED_VARIABLE") val ticks = thumbTick + waveTick
            val p = project
            val w = size.width
            val h = size.height
            val center = w / 2f
            val usToPx = pxPerSec / 1_000_000f
            fun xOf(t: Long): Float = center + (t - positionUs) * usToPx
            val visStart = positionUs - (center / usToPx).toLong()
            val visEnd = positionUs + (center / usToPx).toLong()
            val nc = drawContext.canvas.nativeCanvas
            val tops = rowTops(p)

            // Regla de tiempo.
            val steps = longArrayOf(100_000, 200_000, 500_000, 1_000_000, 2_000_000, 5_000_000, 10_000_000, 30_000_000, 60_000_000, 300_000_000, 600_000_000)
            val step = steps.firstOrNull { it * usToPx >= 64 * dp } ?: steps.last()
            textPaint.textSize = 10 * dp
            textPaint.color = onSurface.copy(alpha = 0.6f).toArgb()
            var t = max(0L, visStart / step * step)
            while (t <= visEnd) {
                val x = xOf(t)
                drawLine(outline, Offset(x, rulerH - 6 * dp), Offset(x, rulerH), 1 * dp)
                nc.drawText(formatTime(t, step < 1_000_000), x + 3 * dp, rulerH - 8 * dp, textPaint)
                t += step
            }
            for (m in p.markers) {
                if (m.timeUs < visStart || m.timeUs > visEnd) continue
                val x = xOf(m.timeUs)
                drawLine(Color(m.color), Offset(x, rulerH - 10 * dp), Offset(x, h), 1 * dp)
                drawCircle(Color(m.color), 4 * dp, Offset(x, rulerH - 10 * dp))
            }

            // Pistas y clips.
            val rect = RectF()
            val src = Rect()
            for ((i, tr) in p.tracks.withIndex()) {
                val top = tops[i] - scrollY
                val rowH = rowHeightDp(tr.type) * dp
                if (top + rowH < rulerH || top > h) continue
                drawRect(onSurface.copy(alpha = if (tr.type == TrackType.MAIN) 0.07f else 0.035f), Offset(0f, top), Size(w, rowH))
                val alpha = if (tr.hidden || tr.muted) 0.45f else 1f
                for ((ci, c) in tr.clips.withIndex()) {
                    if (c.endUs < visStart || c.startUs > visEnd) continue
                    val x0 = xOf(c.startUs)
                    val x1 = xOf(c.endUs)
                    val selected = c.id in selection
                    val lift = if (c.id == movingId) -3 * dp else 0f
                    rect.set(xOf(edgeL(tr, ci, pxPerSec)) + 0.5f * dp, top + lift, xOf(edgeR(tr, ci, pxPerSec)) - 0.5f * dp, top + rowH + lift)
                    val cx0 = max(rect.left, 0f)
                    val cx1 = min(rect.right, w)
                    nc.save()
                    nc.clipRect(max(rect.left, -1f), rect.top, min(rect.right, w + 1f), rect.bottom)
                    fillPaint.color = if (c.kind == ClipKind.COLOR) (c.fill?.colors?.firstOrNull { (it ushr 24) > 40 }?.let { it or (0xFF shl 24) } ?: kindColor(c.kind)) else kindColor(c.kind)
                    fillPaint.alpha = (255 * alpha).toInt()
                    nc.drawRoundRect(rect, 5 * dp, 5 * dp, fillPaint)
                    val m = c.media
                    if (m != null && (c.kind == ClipKind.VIDEO || c.kind == ClipKind.IMAGE)) {
                        val aspect = if (m.height > 0) m.width.toFloat() / m.height else 1.78f
                        val tw = max(rowH * aspect, 24 * dp)
                        var tx = x0 + ((cx0 - x0) / tw).toInt() * tw
                        bmpPaint.alpha = (255 * alpha).toInt()
                        while (tx < cx1) {
                            val local = ((tx - x0) / usToPx).toLong().coerceIn(0, c.durationUs)
                            val bmp = Thumbs.get(m, c.srcInUs + c.sourceOffsetAt(local))
                            if (bmp != null && !bmp.isRecycled) {
                                // Recorte centrado para llenar la celda.
                                val ba = bmp.width.toFloat() / bmp.height
                                val ca = tw / rowH
                                if (ba > ca) { val sw = (bmp.height * ca).toInt(); src.set((bmp.width - sw) / 2, 0, (bmp.width + sw) / 2, bmp.height) }
                                else { val sh = (bmp.width / ca).toInt(); src.set(0, (bmp.height - sh) / 2, bmp.width, (bmp.height + sh) / 2) }
                                nc.drawBitmap(bmp, src, RectF(tx, rect.top, tx + tw, rect.bottom), bmpPaint)
                            }
                            tx += tw
                        }
                    }
                    if (m != null && c.kind == ClipKind.AUDIO) {
                        val env = Waveforms.get(m.path)
                        if (env != null && env.isNotEmpty()) {
                            fillPaint.color = 0xFF9FF5DC.toInt()
                            fillPaint.alpha = (200 * alpha).toInt()
                            val mid = rect.centerY()
                            val stepPx = 2 * dp
                            var x = cx0
                            while (x < cx1) {
                                val local = ((x - x0) / usToPx).toLong().coerceIn(0, c.durationUs)
                                val srcUs = c.srcInUs + c.sourceOffsetAt(local)
                                val idx = (srcUs * Waveforms.RATE / 1_000_000L).toInt()
                                if (idx >= 0 && idx < env.size) {
                                    val a = (env[idx].toInt() and 0xFF) / 255f * (rowH / 2 - 2 * dp) * min(1.5f, c.volume)
                                    nc.drawRect(x, mid - a - 0.5f * dp, x + stepPx * 0.6f, mid + a + 0.5f * dp, fillPaint)
                                }
                                x += stepPx
                            }
                        }
                    }
                    // Etiqueta.
                    val label = when (c.kind) {
                        ClipKind.TEXT -> c.text?.layers?.firstOrNull { it.text.isNotBlank() }?.text?.replace('\n', ' ') ?: "T"
                        ClipKind.STICKER -> c.sticker?.takeIf { it.type == com.dskmusic.vixa.model.StickerType.EMOJI }?.value ?: "★"
                        // Capa de ajuste/efectos: se nombra con sus efectos (o con su filtro), para localizarla de un vistazo.
                        ClipKind.ADJUST -> {
                            val fxNames = c.effects.filter { it.enabled }.mapNotNull { e -> com.dskmusic.vixa.engine.Registry.effect(e.defId)?.let { appRes.getString(it.nameRes) } }
                            when {
                                fxNames.isNotEmpty() -> fxNames.joinToString(" + ")
                                else -> com.dskmusic.vixa.engine.Registry.filter(c.filterId)?.let { appRes.getString(it.nameRes) } ?: "FX"
                            }
                        }
                        ClipKind.AUDIO -> c.label
                        else -> if (c.speed != 1f || c.speedCurve.isNotEmpty()) (if (c.speedCurve.isNotEmpty()) "~" else String.format(java.util.Locale.US, "%.1fx", c.speed)) else ""
                    }
                    if (label.isNotEmpty() && rect.width() > 24 * dp) {
                        textPaint.textSize = 10 * dp
                        textPaint.color = 0xFFFFFFFF.toInt()
                        textPaint.setShadowLayer(2 * dp, 0f, 0f, 0xCC000000.toInt())
                        nc.drawText(label, 0, min(label.length, 40), cx0 + 5 * dp, rect.top + 12 * dp, textPaint)
                        textPaint.clearShadowLayer()
                    }
                    if (c.muted && c.kind != ClipKind.AUDIO && m?.hasAudio == true) {
                        textPaint.color = 0xFFFF6B6B.toInt()
                        nc.drawText("🔇", cx0 + 4 * dp, rect.bottom - 4 * dp, textPaint)
                    }
                    // Keyframes del clip seleccionado.
                    if (selected && c.keyframes.isNotEmpty()) {
                        fillPaint.color = 0xFFFFD60A.toInt()
                        for (list in c.keyframes.values) for (k in list) {
                            val kx = xOf(c.startUs + k.tUs)
                            nc.save(); nc.rotate(45f, kx, rect.bottom - 6 * dp)
                            nc.drawRect(kx - 3 * dp, rect.bottom - 9 * dp, kx + 3 * dp, rect.bottom - 3 * dp, fillPaint)
                            nc.restore()
                        }
                    }
                    nc.restore()
                    if (selected) {
                        fillPaint.style = Paint.Style.STROKE
                        fillPaint.strokeWidth = 2 * dp
                        fillPaint.color = primary.toArgb()
                        nc.drawRoundRect(rect, 5 * dp, 5 * dp, fillPaint)
                        fillPaint.style = Paint.Style.FILL
                        if (selection.size == 1) {
                            nc.drawRoundRect(rect.left - 2 * dp, rect.top, rect.left + 8 * dp, rect.bottom, 4 * dp, 4 * dp, fillPaint)
                            nc.drawRoundRect(rect.right - 8 * dp, rect.top, rect.right + 2 * dp, rect.bottom, 4 * dp, 4 * dp, fillPaint)
                            fillPaint.color = 0xFFFFFFFF.toInt()
                            nc.drawRect(rect.left + 2 * dp, rect.centerY() - 6 * dp, rect.left + 4 * dp, rect.centerY() + 6 * dp, fillPaint)
                            nc.drawRect(rect.right - 4 * dp, rect.centerY() - 6 * dp, rect.right - 2 * dp, rect.centerY() + 6 * dp, fillPaint)
                        }
                    }
                    if (c.groupId.isNotEmpty()) drawCircle(Color(0xFFFFD60A), 3 * dp, Offset(max(x0, 0f) + 8 * dp, top + rowH - 6 * dp))
                    // Unión con el clip anterior en la pista principal: botón de transición.
                    if (tr.type == TrackType.MAIN && ci > 0) {
                        val jx = xOf(junctionUs(c))
                        val has = c.transitionIn != null
                        val jy = top + rowH / 2
                        drawCircle(if (has) primary else Color(0xCC222222), 9 * dp, Offset(jx, jy))
                        drawCircle(Color.White, 9 * dp, Offset(jx, jy), style = androidx.compose.ui.graphics.drawscope.Stroke(1 * dp))
                        if (has) {
                            // Transición aplicada: dos triángulos enfrentados (A → B).
                            drawLine(Color.White, Offset(jx - 4 * dp, jy - 3 * dp), Offset(jx + 4 * dp, jy + 3 * dp), 1.5f * dp)
                            drawLine(Color.White, Offset(jx - 4 * dp, jy + 3 * dp), Offset(jx + 4 * dp, jy - 3 * dp), 1.5f * dp)
                        } else {
                            drawLine(Color.White, Offset(jx - 4 * dp, jy), Offset(jx + 4 * dp, jy), 1.5f * dp)
                            drawLine(Color.White, Offset(jx, jy - 4 * dp), Offset(jx, jy + 4 * dp), 1.5f * dp)
                        }
                    }
                }
            }
            // Fin del proyecto y cabezal.
            val endX = xOf(p.durationUs)
            if (endX < w) drawLine(outline, Offset(endX, rulerH), Offset(endX, h), 1 * dp)
            drawLine(Color.White, Offset(center, 0f), Offset(center, h), 2 * dp)
            drawCircle(Color.White, 4 * dp, Offset(center, 4 * dp))
        }
    }
        deleteTrackId?.let { id ->
            val t = project.track(id)
            if (t == null) deleteTrackId = null else androidx.compose.material3.AlertDialog(
                onDismissRequest = { deleteTrackId = null },
                title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.delete_track_title)) },
                text = { androidx.compose.material3.Text(if (t.clips.isEmpty()) androidx.compose.ui.res.stringResource(R.string.delete_track_empty) else androidx.compose.ui.res.stringResource(R.string.delete_track_clips, t.clips.size)) },
                confirmButton = { androidx.compose.material3.TextButton({ deleteTrackId = null; vm.edit(R.string.hist_track) { it.removeTrack(id) } }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.delete)) } },
                dismissButton = { androidx.compose.material3.TextButton({ deleteTrackId = null }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.cancel)) } },
            )
        }
        // Botón "+" bajo la última pista: elegir y añadir una pista nueva.
        var addMenu by remember { mutableStateOf(false) }
        val addTop = rowTops(project)[project.tracks.size] - scrollY + 12 * dp
        Box(Modifier.align(androidx.compose.ui.Alignment.TopCenter).offset { IntOffset(0, addTop.roundToInt()) }) {
            androidx.compose.material3.FilledTonalIconButton({ addMenu = true }, Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, androidx.compose.ui.res.stringResource(R.string.tracks), Modifier.size(20.dp))
            }
            if (addMenu) {
                // Menú hacia arriba, centrado respecto al botón y con los elementos centrados.
                val gapPx = with(density) { 40.dp.roundToPx() }
                androidx.compose.ui.window.Popup(alignment = androidx.compose.ui.Alignment.BottomCenter, offset = IntOffset(0, -gapPx), onDismissRequest = { addMenu = false }) {
                    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp) {
                        Column(Modifier.width(160.dp).padding(vertical = 6.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            // Audio, texto y stickers abren directamente su herramienta (que crea la pista al añadir el clip);
                            // "Pista vacía" crea solo la pista de superposición; "Capa de ajuste" añade una y abre los filtros.
                            for ((k, r) in listOf("media" to R.string.add_media, "empty" to R.string.empty_track, "adjust" to R.string.menu_track_adjust, "fxlayer" to R.string.track_effects, "audio" to R.string.track_audio, "text" to R.string.track_text, "stickers" to R.string.track_sticker)) {
                                androidx.compose.material3.TextButton({
                                    addMenu = false
                                    when (k) {
                                        "empty" -> vm.edit(R.string.hist_track) { it.addTrack(TrackType.OVERLAY) }
                                        "adjust" -> { vm.addAdjustLayer(); vm.tool.value = "filter" }
                                        "fxlayer" -> { vm.addEffectLayer(); vm.tool.value = "effects" }
                                        else -> onAddKind(k)
                                    }
                                }, Modifier.fillMaxWidth()) {
                                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(r), textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
