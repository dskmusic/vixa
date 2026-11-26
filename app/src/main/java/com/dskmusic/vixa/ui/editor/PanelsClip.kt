package com.dskmusic.vixa.ui.editor

import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dskmusic.vixa.R
import com.dskmusic.vixa.engine.AnimKind
import com.dskmusic.vixa.engine.Anims
import com.dskmusic.vixa.engine.Registry
import com.dskmusic.vixa.model.AnimRef
import com.dskmusic.vixa.model.BlendMode
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.ColorAdjust
import com.dskmusic.vixa.model.Easing
import com.dskmusic.vixa.model.EffectInstance
import com.dskmusic.vixa.model.FitMode
import com.dskmusic.vixa.model.MaskType
import com.dskmusic.vixa.model.SpeedPoint
import com.dskmusic.vixa.model.speedAt
import com.dskmusic.vixa.model.Transform
import com.dskmusic.vixa.model.Transition
import com.dskmusic.vixa.model.ZoneType
import com.dskmusic.vixa.model.Zone
import com.dskmusic.vixa.model.MAX_ZONES
import com.dskmusic.vixa.model.allZones
import com.dskmusic.vixa.model.removeZone
import com.dskmusic.vixa.model.withZone
import com.dskmusic.vixa.model.zoneKey
import com.dskmusic.vixa.model.zoneKeyIndex
import com.dskmusic.vixa.model.applyTransitionToAll
import com.dskmusic.vixa.model.detachAudio
import com.dskmusic.vixa.model.keyframeNear
import com.dskmusic.vixa.model.withKeyframe
import com.dskmusic.vixa.model.withoutKeyframe
import com.dskmusic.vixa.ui.ChipRow
import com.dskmusic.vixa.ui.ColorRow
import com.dskmusic.vixa.ui.PreciseSlider
import com.dskmusic.vixa.ui.SectionTitle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

private fun pct(v: Float) = String.format(Locale.US, "%.0f", v * 100f)
private fun secs(v: Float) = String.format(Locale.US, "%.2f s", v)

@Composable
internal fun localUs(vm: EditorViewModel, c: Clip): Long {
    val pos by vm.player.positionUs.collectAsState()
    return (pos - c.startUs).coerceIn(0, c.durationUs)
}

/** Slider ligado a una propiedad animable, con botón de keyframe (rombo). */
@Composable
fun PropSlider(
    vm: EditorViewModel, c: Clip, prop: String, label: String, histRes: Int,
    range: ClosedFloatingPointRange<Float> = -1f..1f, default: Float = 0f,
    format: (Float) -> String = ::pct, inputScale: Float = 100f, keyframes: Boolean = true,
) {
    val local = localUs(vm, c)
    val value = c.prop(prop, local)
    val atKey = c.keyframeNear(prop, local) != null
    PreciseSlider(
        label, value, { v -> vm.updateClip(histRes, c.id, live = true) { it.setProp(prop, local, v) } },
        range = range, default = default, onFinished = { vm.commit() }, format = format, inputScale = inputScale,
        trailing = if (!keyframes) null else {
            {
                IconButton({
                    vm.updateClip(R.string.hist_keyframe, c.id) { if (atKey) it.withoutKeyframe(prop, local) else it.withKeyframe(prop, local, value) }
                }, Modifier.size(28.dp)) {
                    Icon(Icons.Default.Diamond, stringResource(R.string.keyframe), Modifier.size(16.dp),
                        tint = if (atKey) MaterialTheme.colorScheme.primary else if (c.keyframes[prop].isNullOrEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.tertiary)
                }
            }
        },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun SpeedPanel(vm: EditorViewModel, c: Clip) {
    val curPos = (localUs(vm, c).toFloat() / c.durationUs.coerceAtLeast(1)).coerceIn(0f, 1f)
    val keyed = c.speedCurve.size >= 2
    PreciseSlider(
        stringResource(R.string.speed) + if (keyed) "  ◆" else "", log10((if (keyed) c.speedAt(curPos) else c.speed).coerceIn(0.1f, 10f)),
        { v ->
            val sp = (Math.round(10f.pow(v) * 20f) / 20f).coerceIn(0.1f, 10f)
            vm.updateClip(R.string.hist_speed, c.id, live = true) { k ->
                // Con keyframes de velocidad, el slider cambia (o crea) el punto del instante actual, como el resto de propiedades.
                if (k.speedCurve.size >= 2) k.copy(speedCurve = (k.speedCurve.filter { abs(it.pos - curPos) >= 0.02f } + SpeedPoint(curPos, sp)).sortedBy { it.pos })
                else k.copy(speed = sp, speedCurve = emptyList())
            }
        },
        range = -1f..1f, default = 0f, onFinished = { vm.commit() },
        format = { String.format(Locale.US, "%.2fx", 10f.pow(it)) }, inputScale = 1f,
    )
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)) AssistChip({ vm.updateClip(R.string.hist_speed, c.id) { it.copy(speed = s, speedCurve = emptyList()) } }, { Text(String.format(Locale.US, "%.2gx", s)) })
    }
    SwitchRow(stringResource(R.string.keep_pitch), c.keepPitch) { on -> vm.updateClip(R.string.hist_speed, c.id) { it.copy(keepPitch = on) } }
    SectionTitle(stringResource(R.string.speed_curves))
    val current = Registry.speedPresets.firstOrNull { it.points == c.speedCurve }
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(c.speedCurve.isEmpty(), { vm.updateClip(R.string.hist_speed, c.id) { it.copy(speedCurve = emptyList()) } }, { Text(stringResource(R.string.none)) }) }
        items(Registry.speedPresets) { p ->
            FilterChip(current == p, { vm.updateClip(R.string.hist_speed, c.id) { it.copy(speedCurve = p.points) } }, { Text(stringResource(p.nameRes)) })
        }
        item {
            FilterChip(c.speedCurve.isNotEmpty() && current == null, {
                vm.updateClip(R.string.hist_speed, c.id) { it.copy(speedCurve = List(5) { i -> SpeedPoint(i / 4f, it.speed) }) }
            }, { Text(stringResource(R.string.custom)) })
        }
    }
    // Keyframes de velocidad: un punto de la curva en la posición del cursor (rombo = añadir / quitar).
    val pos = (localUs(vm, c).toFloat() / c.durationUs.coerceAtLeast(1)).coerceIn(0f, 1f)
    val near = c.speedCurve.indexOfFirst { abs(it.pos - pos) < 0.02f }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.keyframe) + "  " + String.format(Locale.US, "%.2fx", c.speedAt(pos)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton({
            vm.updateClip(R.string.hist_speed, c.id) { k ->
                val cur = if (k.speedCurve.size >= 2) k.speedCurve else listOf(SpeedPoint(0f, k.speed), SpeedPoint(1f, k.speed))
                val next = if (near >= 0 && k.speedCurve.size >= 2) cur.filterIndexed { i, _ -> i != near } else (cur + SpeedPoint(pos, k.speedAt(pos))).sortedBy { it.pos }
                k.copy(speedCurve = if (next.size < 2) emptyList() else next)
            }
        }, Modifier.size(28.dp)) {
            Icon(Icons.Default.Diamond, stringResource(R.string.keyframe), Modifier.size(16.dp),
                tint = if (near >= 0) MaterialTheme.colorScheme.primary else if (c.speedCurve.isEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.tertiary)
        }
    }
    for (i in c.speedCurve.indices) {
        PreciseSlider(
            stringResource(R.string.curve_point, i + 1) + String.format(Locale.US, " (%.0f%%)", c.speedCurve[i].pos * 100f), log10(c.speedCurve[i].speed.coerceIn(0.1f, 10f)),
            { v -> vm.updateClip(R.string.hist_speed, c.id, live = true) { it.copy(speedCurve = it.speedCurve.mapIndexed { k, sp -> if (k == i) sp.copy(speed = 10f.pow(v)) else sp }) } },
            range = -1f..1f, onFinished = { vm.commit() }, format = { String.format(Locale.US, "%.2fx", 10f.pow(it)) }, inputScale = 1f,
        )
    }
}

@Composable
fun VolumePanel(vm: EditorViewModel, c: Clip) {
    val maxFade = (c.durationUs / 2_000_000f).coerceAtLeast(0.1f)
    // Botones grandes (Volumen / Fundidos / Más), un bloque a la vez, en los dos modos.
    var sub by remember { mutableStateOf("vol") }
    EasyBar(listOf(EasyTab("vol", Icons.AutoMirrored.Filled.VolumeUp, R.string.volume), EasyTab("fade", Icons.Default.GraphicEq, R.string.easy_fades), EasyTab("more", Icons.Default.MoreHoriz, R.string.more)), sub) { sub = it }
    when (sub) {
        "vol" -> {
            PropSlider(vm, c, "volume", stringResource(R.string.volume), R.string.hist_volume, 0f..2f, 1f)
            SwitchRow(stringResource(R.string.mute), c.muted) { on -> vm.updateClip(R.string.hist_volume, c.id) { it.copy(muted = on) } }
        }
        "fade" -> {
            PreciseSlider(stringResource(R.string.fade_in), c.fadeInUs / 1_000_000f, { v -> vm.updateClip(R.string.hist_volume, c.id, live = true) { it.copy(fadeInUs = (v * 1_000_000f).toLong()) } },
                range = 0f..minOf(10f, maxFade), onFinished = { vm.commit() }, format = ::secs, inputScale = 1f)
            PreciseSlider(stringResource(R.string.fade_out), c.fadeOutUs / 1_000_000f, { v -> vm.updateClip(R.string.hist_volume, c.id, live = true) { it.copy(fadeOutUs = (v * 1_000_000f).toLong()) } },
                range = 0f..minOf(10f, maxFade), onFinished = { vm.commit() }, format = ::secs, inputScale = 1f)
        }
        else -> if (c.kind == ClipKind.VIDEO && c.media?.hasAudio == true && !c.audioDetached) {
            OutlinedButton({ vm.edit(R.string.hist_detach) { it.detachAudio(c.id) } }, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.detach_audio)) }
        } else Text("–", Modifier.padding(16.dp))
    }
}

@Composable
fun TransformPanel(vm: EditorViewModel, c: Clip) {
    val tr = c.transform
    val pro = !easyMode(vm)
    // Botones grandes (Tamaño / Girar / Mover / Camino) en los dos modos; en Pro, además, "Encaje" (ajuste y mezcla).
    var sub by remember { mutableStateOf("size") }
    var drawingPath by remember { mutableStateOf(false) }
    val hasPath = !c.keyframes["x"].isNullOrEmpty() && !c.keyframes["y"].isNullOrEmpty()
    val tabs = buildList {
        add(EasyTab("size", Icons.Default.OpenInFull, R.string.size))
        add(EasyTab("turn", Icons.Default.RotateRight, R.string.rotation))
        add(EasyTab("move", Icons.Default.OpenWith, R.string.easy_move))
        if (pro && c.isVisual) add(EasyTab("fit", Icons.Default.AspectRatio, R.string.fit))
        add(EasyTab("path", Icons.Default.Gesture, R.string.stk_act_path, hasPath))
    }
    EasyBar(tabs, sub) { k -> if (k == "path") drawingPath = true else sub = k }
    when (sub) {
        "size" -> {
            PropSlider(vm, c, "scale", stringResource(R.string.scale), R.string.hist_transform, 0.05f..5f, 1f)
            PropSlider(vm, c, "opacity", stringResource(R.string.opacity), R.string.hist_transform, 0f..1f, 1f)
        }
        "turn" -> {
            PropSlider(vm, c, "rotation", stringResource(R.string.rotation), R.string.hist_transform, -180f..180f, 0f, { String.format(Locale.US, "%.0f°", it) }, 1f)
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tr.flipH, { vm.updateClip(R.string.hist_transform, c.id) { it.copy(transform = it.transform.copy(flipH = !tr.flipH)) } }, { Text(stringResource(R.string.flip_h), maxLines = 1) })
                FilterChip(tr.flipV, { vm.updateClip(R.string.hist_transform, c.id) { it.copy(transform = it.transform.copy(flipV = !tr.flipV)) } }, { Text(stringResource(R.string.flip_v), maxLines = 1) })
                AssistChip({ vm.updateClip(R.string.hist_transform, c.id) { it.copy(transform = it.transform.copy(rotation = ((it.transform.rotation + 90f + 180f) % 360f) - 180f)) } }, { Text("+90°", maxLines = 1) })
            }
        }
        "move" -> {
            PropSlider(vm, c, "x", stringResource(R.string.position_x), R.string.hist_transform, -1f..1f, 0f)
            PropSlider(vm, c, "y", stringResource(R.string.position_y), R.string.hist_transform, -1f..1f, 0f)
        }
        "fit" -> if (c.isVisual) {
            SectionTitle(stringResource(R.string.fit))
            ChipRow(FitMode.entries, tr.fit, { stringResource(when (it) { FitMode.FIT -> R.string.fit_fit; FitMode.FILL -> R.string.fit_fill; FitMode.STRETCH -> R.string.fit_stretch }) }) { f ->
                vm.updateClip(R.string.hist_transform, c.id) { it.copy(transform = it.transform.copy(fit = f)) }
            }
            SectionTitle(stringResource(R.string.blend_mode))
            ChipRow(BlendMode.entries, tr.blend, {
                stringResource(when (it) {
                    BlendMode.NORMAL -> R.string.blend_normal; BlendMode.SCREEN -> R.string.blend_screen; BlendMode.ADD -> R.string.blend_add
                    BlendMode.MULTIPLY -> R.string.blend_multiply; BlendMode.LIGHTEN -> R.string.blend_lighten; BlendMode.DARKEN -> R.string.blend_darken
                })
            }) { b -> vm.updateClip(R.string.hist_transform, c.id) { it.copy(transform = it.transform.copy(blend = b)) } }
        }
    }
    AssistChip({ vm.updateClip(R.string.hist_transform, c.id) { it.copy(transform = Transform(fit = it.transform.fit), keyframes = it.keyframes - setOf("x", "y", "scale", "rotation", "opacity")) } }, { Text(stringResource(R.string.reset), maxLines = 1) }, Modifier.padding(horizontal = 12.dp))
    if (drawingPath) DrawPathDialog(vm, c) { drawingPath = false }
}

@Composable
fun CropPanel(vm: EditorViewModel, c: Clip) {
    val tr = c.transform
    fun set(f: (Transform, Float) -> Transform): (Float) -> Unit = { v -> vm.updateClip(R.string.hist_crop, c.id, live = true) { it.copy(transform = f(it.transform, v)) } }
    PreciseSlider(stringResource(R.string.crop_left), tr.cropL, set { t, v -> t.copy(cropL = v) }, range = 0f..0.48f, onFinished = { vm.commit() })
    PreciseSlider(stringResource(R.string.crop_right), tr.cropR, set { t, v -> t.copy(cropR = v) }, range = 0f..0.48f, onFinished = { vm.commit() })
    PreciseSlider(stringResource(R.string.crop_top), tr.cropT, set { t, v -> t.copy(cropT = v) }, range = 0f..0.48f, onFinished = { vm.commit() })
    PreciseSlider(stringResource(R.string.crop_bottom), tr.cropB, set { t, v -> t.copy(cropB = v) }, range = 0f..0.48f, onFinished = { vm.commit() })
    val m = c.media
    if (m != null && m.width > 0 && m.height > 0) {
        SectionTitle(stringResource(R.string.crop_to_ratio))
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((rw, rh) in listOf(1 to 1, 9 to 16, 16 to 9, 4 to 5, 4 to 3)) {
                AssistChip({
                    // Recorte centrado a la proporción elegida.
                    val src = m.width.toFloat() / m.height
                    val dst = rw.toFloat() / rh
                    vm.updateClip(R.string.hist_crop, c.id) {
                        if (src > dst) { val cut = (1f - dst / src) / 2f; it.copy(transform = it.transform.copy(cropL = cut, cropR = cut, cropT = 0f, cropB = 0f)) }
                        else { val cut = (1f - src / dst) / 2f; it.copy(transform = it.transform.copy(cropT = cut, cropB = cut, cropL = 0f, cropR = 0f)) }
                    }
                }, { Text("$rw:$rh") })
            }
            AssistChip({ vm.updateClip(R.string.hist_crop, c.id) { it.copy(transform = it.transform.copy(cropL = 0f, cropR = 0f, cropT = 0f, cropB = 0f)) } }, { Text(stringResource(R.string.reset)) })
        }
    }
}

@Composable
fun AnimationPanel(vm: EditorViewModel, c: Clip) {
    var tab by remember { mutableIntStateOf(0) }
    // Dibujar camino: vale para cualquier elemento que se pueda animar (texto, pegatina, capa de medios...).
    var drawingPath by remember { mutableStateOf(false) }
    val hasPath = !c.keyframes["x"].isNullOrEmpty() && !c.keyframes["y"].isNullOrEmpty()
    EasyBar(
        listOf(
            EasyTab("0", Icons.AutoMirrored.Filled.Login, R.string.anim_in, c.animIn != null), EasyTab("2", Icons.Default.Loop, R.string.anim_loop, c.animLoop != null),
            EasyTab("1", Icons.AutoMirrored.Filled.Logout, R.string.anim_out, c.animOut != null), EasyTab("path", Icons.Default.Gesture, R.string.stk_act_path, hasPath),
        ),
        tab.toString(),
    ) { k -> if (k == "path") drawingPath = true else tab = k.toInt() }
    if (hasPath) AssistChip({ vm.updateClip(R.string.hist_animation, c.id) { it.copy(keyframes = it.keyframes - setOf("x", "y")) } }, { Text(stringResource(R.string.stk_path_remove), maxLines = 1) }, Modifier.padding(horizontal = 12.dp), leadingIcon = { Icon(Icons.Default.Close, null) })
    if (drawingPath) DrawPathDialog(vm, c) { drawingPath = false }
    val kinds = listOf(AnimKind.IN, AnimKind.OUT, AnimKind.LOOP)
    val labels = listOf(R.string.anim_in, R.string.anim_out, R.string.anim_loop)
    val kind = kinds[tab]
    val cur = when (kind) { AnimKind.IN -> c.animIn; AnimKind.OUT -> c.animOut; AnimKind.LOOP -> c.animLoop }
    fun set(r: AnimRef?) = vm.updateClip(R.string.hist_animation, c.id) { when (kind) { AnimKind.IN -> it.copy(animIn = r); AnimKind.OUT -> it.copy(animOut = r); AnimKind.LOOP -> it.copy(animLoop = r) } }
    val animProg = rememberLoopProgress()
    val favs = rememberFavs()
    TileGrid(Modifier.padding(vertical = 6.dp)) { tile ->
        PreviewTile(stringResource(R.string.none), cur == null, { set(null) }, tile.height(68.dp)) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) }
        for (a in Anims.list(kind, c.kind == ClipKind.TEXT).favFirst(favs) { "an:$kind:${it.id}" }) {
            AnimTile(a, kind, stringResource(a.nameRes), cur?.id == a.id, animProg, tile.height(68.dp), favKey = "an:$kind:${a.id}") {
                set(AnimRef(a.id, cur?.durationUs ?: 500_000L))
                vm.player.playFrom(if (kind == AnimKind.OUT) c.endUs - (cur?.durationUs ?: 500_000L) else c.startUs)
            }
        }
    }
    if (cur != null && kind != AnimKind.LOOP) {
        PreciseSlider(stringResource(R.string.duration), cur.durationUs / 1_000_000f, { v -> vm.updateClip(R.string.hist_animation, c.id, live = true) { x ->
            val r = cur.copy(durationUs = (v * 1_000_000f).toLong())
            if (kind == AnimKind.IN) x.copy(animIn = r) else x.copy(animOut = r)
        } }, range = 0.1f..minOf(5f, (c.durationUs / 1_000_000f).coerceAtLeast(0.2f)), default = 0.5f, onFinished = { vm.commit() }, format = ::secs, inputScale = 1f)
    }
}

@Composable
fun FilterPanel(vm: EditorViewModel, c: Clip, onPickLut: () -> Unit) {
    val cats = remember { Registry.filters.map { it.catRes }.distinct() }
    var cat by remember { mutableIntStateOf(Registry.filter(c.filterId)?.catRes ?: cats.first()) }
    ChipRow(cats, cat, { stringResource(it) }) { cat = it }
    Spacer(Modifier.height(6.dp))
    TileGrid { t ->
        val tile = t.height(68.dp)
        FilterTile(stringResource(R.string.none), c.filterId.isEmpty(), null, tile) { vm.updateClip(R.string.hist_filter, c.id) { it.copy(filterId = "") } }
        for (f in Registry.filters.filter { it.catRes == cat }.favFirst(rememberFavs()) { "filter:${it.id}" }) {
            FilterTile(stringResource(f.nameRes), c.filterId == f.id, remember(f.id) { filterMatrix(f) }, tile, f, "filter:${f.id}") { vm.updateClip(R.string.hist_filter, c.id) { it.copy(filterId = f.id, filterIntensity = if (it.filterId.isEmpty()) 1f else it.filterIntensity) } }
        }
    }
    if (c.filterId.isNotEmpty() || c.lutPath.isNotEmpty()) {
        PreciseSlider(stringResource(R.string.intensity), c.filterIntensity, { v -> vm.updateClip(R.string.hist_filter, c.id, live = true) { it.copy(filterIntensity = v) } },
            range = 0f..1f, default = 1f, onFinished = { vm.commit() })
    }
    SectionTitle(stringResource(R.string.lut))
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { FilterChip(c.lutPath.isEmpty(), { vm.updateClip(R.string.hist_filter, c.id) { it.copy(lutPath = "") } }, { Text(stringResource(R.string.none)) }) }
        items(Registry.bundledLuts) { l ->
            FilterChip(c.lutPath == l, { vm.updateClip(R.string.hist_filter, c.id) { it.copy(lutPath = l, filterIntensity = if (it.filterId.isEmpty()) 1f else it.filterIntensity) } },
                { Text(l.substringAfterLast('/').removeSuffix(".cube").replace('_', ' ')) })
        }
        if (c.lutPath.isNotEmpty() && c.lutPath !in Registry.bundledLuts) item { FilterChip(true, {}, { Text(c.lutPath.substringAfterLast('/')) }) }
        item { AssistChip(onPickLut, { Text(stringResource(R.string.import_cube)) }) }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FilterTile(name: String, selected: Boolean, matrix: androidx.compose.ui.graphics.ColorMatrix?, modifier: Modifier = Modifier.size(84.dp, 64.dp), preset: com.dskmusic.vixa.engine.FilterPreset? = null, favKey: String? = null, onClick: () -> Unit) {
    val isFav = favKey != null && favKey in rememberFavs()
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .border(if (selected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = if (favKey != null) ({ Favs.pending.value = favKey }) else null),
        contentAlignment = Alignment.BottomCenter,
    ) {
        FilterSample(matrix, Modifier.fillMaxSize(), preset)
        if (isFav) Icon(Icons.Default.Star, null, Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp), tint = Color(0xFFFFC107))
        Text(name, Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 2.dp, vertical = 2.dp),
            fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
    }
}

@Composable
fun AdjustPanel(vm: EditorViewModel, c: Clip) {
    val h = R.string.hist_adjust
    val rows = listOf(
        Triple("exposure", R.string.adj_exposure, -1f..1f), Triple("brightness", R.string.adj_brightness, -1f..1f),
        Triple("contrast", R.string.adj_contrast, -1f..1f), Triple("saturation", R.string.adj_saturation, -1f..1f),
        Triple("vibrance", R.string.adj_vibrance, -1f..1f), Triple("temperature", R.string.adj_temperature, -1f..1f),
        Triple("tint", R.string.adj_tint, -1f..1f), Triple("highlights", R.string.adj_highlights, -1f..1f),
        Triple("shadows", R.string.adj_shadows, -1f..1f), Triple("hue", R.string.adj_hue, -1f..1f),
        Triple("lightness", R.string.adj_lightness, -1f..1f), Triple("fade", R.string.adj_fade, -1f..1f),
        Triple("sharpen", R.string.adj_sharpen, 0f..1f), Triple("vignette", R.string.adj_vignette, 0f..1f),
        Triple("grain", R.string.adj_grain, 0f..1f),
    )
    val pro = !easyMode(vm)
    // Botones grandes (Luz / Color / Detalle) en los dos modos; en Pro, además, "Curvas".
    var sub by remember { mutableStateOf("light") }
    val tabs = buildList {
        add(EasyTab("light", Icons.Default.WbSunny, R.string.easy_light)); add(EasyTab("color", Icons.Default.Palette, R.string.color)); add(EasyTab("detail", Icons.Default.Tune, R.string.easy_detail))
        if (pro) add(EasyTab("curves", Icons.Default.ShowChart, R.string.curves))
    }
    EasyBar(tabs, sub) { sub = it }
    if (sub == "curves" && pro) {
        var ch by remember { mutableIntStateOf(0) }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, n) in listOf("RGB", "R", "G", "B").withIndex()) FilterChip(ch == i, { ch = i }, { Text(n) })
        }
        val identity = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        val cur = (when (ch) { 0 -> c.color.curve; 1 -> c.color.curveR; 2 -> c.color.curveG; else -> c.color.curveB }).ifEmpty { identity }
        val names = listOf(R.string.curve_blacks, R.string.curve_shadows, R.string.curve_mids, R.string.curve_highlights, R.string.curve_whites)
        for (i in 0 until 5) {
            PreciseSlider(stringResource(names[i]), cur[i], { v ->
                vm.updateClip(h, c.id, live = true) { x ->
                    val base = (when (ch) { 0 -> x.color.curve; 1 -> x.color.curveR; 2 -> x.color.curveG; else -> x.color.curveB }).ifEmpty { identity }
                    val n = base.mapIndexed { k, old -> if (k == i) v else old }
                    x.copy(color = when (ch) { 0 -> x.color.copy(curve = n); 1 -> x.color.copy(curveR = n); 2 -> x.color.copy(curveG = n); else -> x.color.copy(curveB = n) })
                }
            }, range = 0f..1f, default = identity[i], onFinished = { vm.commit() })
        }
    } else {
        val keys = when (sub) {
            "light" -> setOf("exposure", "brightness", "contrast", "highlights", "shadows")
            "color" -> setOf("saturation", "vibrance", "temperature", "tint", "hue")
            else -> setOf("lightness", "fade", "sharpen", "vignette", "grain")
        }
        for ((prop, name, range) in rows) if (prop in keys) PropSlider(vm, c, prop, stringResource(name), h, range, 0f)
    }
    TextButton({ vm.updateClip(h, c.id) { it.copy(color = ColorAdjust(), keyframes = it.keyframes.filterKeys { k -> k.startsWith("fx:") || k in setOf("x", "y", "scale", "rotation", "opacity", "volume") || k.startsWith("mask.") || zoneKeyIndex(k) >= 0 }) } },
        Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.reset_all)) }
}

@Composable
fun EffectsPanel(vm: EditorViewModel, c: Clip) {
    val h = R.string.hist_effects
    var adding by remember { mutableStateOf(c.effects.isEmpty()) }
    for (e in c.effects) {
        val def = Registry.effect(e.defId) ?: continue
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(def.nameRes), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(e.enabled, { on -> vm.updateClip(h, c.id) { x -> x.copy(effects = x.effects.map { if (it.id == e.id) it.copy(enabled = on) else it }) } })
            IconButton({ vm.updateClip(h, c.id) { x -> x.copy(effects = x.effects.filter { it.id != e.id }, keyframes = x.keyframes.filterKeys { k -> !k.startsWith("fx:" + e.id + ":") }) } }) {
                Icon(Icons.Default.Delete, stringResource(R.string.delete))
            }
        }
        for (pd in def.params) PropSlider(vm, c, "fx:" + e.id + ":" + pd.key, stringResource(pd.nameRes), h, pd.min..pd.max, pd.def)
    }
    if (!adding) {
        OutlinedButton({ adding = true }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { Text(stringResource(R.string.add_effect)) }
    } else {
        val cats = remember { Registry.effects.map { it.catRes }.distinct() }
        var cat by remember { mutableIntStateOf(cats.first()) }
        ChipRow(cats, cat, { stringResource(it) }) { cat = it }
        TileGrid(Modifier.padding(vertical = 6.dp)) { tile ->
            val list = Registry.effects.filter { it.catRes == cat }.favFirst(rememberFavs()) { "fx:${it.id}" }
            for (d in list) {
                // Al elegirlo se aplica al clip y la vista previa del editor lo muestra al instante.
                FxTile(d, stringResource(d.nameRes), c.effects.any { it.defId == d.id }, tile.height(68.dp), favKey = "fx:${d.id}") {
                    vm.updateClip(h, c.id) { it.copy(effects = it.effects + EffectInstance(defId = d.id)) }
                    adding = false
                }
            }
        }
    }
}

@Composable
fun TransitionPanel(vm: EditorViewModel, c: Clip, isFirst: Boolean) {
    if (isFirst) { Text(stringResource(R.string.transition_first_hint), Modifier.padding(16.dp)); return }
    val h = R.string.hist_transition
    val defDur = vm.settings.transitionDurationMs * 1000L
    val cats = remember { Registry.transitions.map { it.catRes }.distinct() }
    var cat by remember { mutableIntStateOf(Registry.transition(c.transitionIn?.id ?: "")?.catRes ?: cats.first()) }
    val prog = rememberLoopProgress()
    // Una sola vez por ejecución: compila todos los shaders nuevos y deja en el registro de errores los que fallen.
    androidx.compose.runtime.LaunchedEffect(Unit) { com.dskmusic.vixa.engine.FxThumbs.validateRegistry() }
    ChipRow(cats, cat, { stringResource(it) }) { cat = it }
    TileGrid(Modifier.padding(vertical = 6.dp)) { tile ->
        PreviewTile(stringResource(R.string.none), c.transitionIn == null, { vm.updateClip(h, c.id) { it.copy(transitionIn = null) } }, tile.height(68.dp)) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest))
        }
        for (t in Registry.transitions.filter { it.catRes == cat }.favFirst(rememberFavs()) { "tr:${it.id}" }) {
            TransitionTile(t.id, stringResource(t.nameRes), c.transitionIn?.id == t.id, prog, tile.height(68.dp), favKey = "tr:${t.id}") {
                vm.updateClip(h, c.id) { it.copy(transitionIn = Transition(t.id, it.transitionIn?.durationUs ?: defDur)) }
                // Vista previa inmediata de la transición.
                vm.project.value?.clip(c.id)?.let { n -> vm.player.playFrom((n.startUs - 400_000L).coerceAtLeast(0)) }
            }
        }
    }
    val tr = c.transitionIn
    // En una superposición la duración es el solape entre los clips (se cambia moviéndolos o recortándolos).
    val onOverlay = vm.project.value?.trackOf(c.id)?.type == com.dskmusic.vixa.model.TrackType.OVERLAY
    if (tr != null && !onOverlay) {
        PreciseSlider(stringResource(R.string.duration), tr.durationUs / 1_000_000f, { v -> vm.updateClip(h, c.id, live = true) { it.copy(transitionIn = tr.copy(durationUs = (v * 1_000_000f).toLong())) } },
            range = 0.1f..3f, default = 0.5f, onFinished = { vm.commit() }, format = ::secs, inputScale = 1f)
    }
    if (!onOverlay) OutlinedButton({ vm.edit(h) { it.applyTransitionToAll(tr) } }, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.apply_to_all)) }
}

@Composable
fun ChromaPanel(vm: EditorViewModel, c: Clip) {
    val h = R.string.hist_chroma
    val k = c.chroma
    SwitchRow(stringResource(R.string.chroma_key), k.enabled) { on -> vm.updateClip(h, c.id) { it.copy(chroma = it.chroma.copy(enabled = on)) } }
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip({ vm.updateClip(h, c.id) { it.copy(chroma = it.chroma.copy(enabled = true, color = 0xFF00FF00.toInt())) } }, { Text(stringResource(R.string.green)) })
        AssistChip({ vm.updateClip(h, c.id) { it.copy(chroma = it.chroma.copy(enabled = true, color = 0xFF0000FF.toInt())) } }, { Text(stringResource(R.string.blue)) })
    }
    ColorRow(k.color, { col -> vm.updateClip(h, c.id) { it.copy(chroma = it.chroma.copy(enabled = true, color = col)) } })
    PreciseSlider(stringResource(R.string.similarity), k.similarity, { v -> vm.updateClip(h, c.id, live = true) { it.copy(chroma = it.chroma.copy(similarity = v)) } }, range = 0f..1f, default = 0.35f, onFinished = { vm.commit() })
    PreciseSlider(stringResource(R.string.smoothness), k.smoothness, { v -> vm.updateClip(h, c.id, live = true) { it.copy(chroma = it.chroma.copy(smoothness = v)) } }, range = 0f..1f, default = 0.1f, onFinished = { vm.commit() })
    PreciseSlider(stringResource(R.string.spill), k.spill, { v -> vm.updateClip(h, c.id, live = true) { it.copy(chroma = it.chroma.copy(spill = v)) } }, range = 0f..1f, default = 0.5f, onFinished = { vm.commit() })
}

@Composable
fun MaskPanel(vm: EditorViewModel, c: Clip) {
    val h = R.string.hist_mask
    ChipRow(MaskType.entries, c.mask.type, { stringResource(when (it) { MaskType.NONE -> R.string.none; MaskType.LINEAR -> R.string.mask_linear; MaskType.CIRCLE -> R.string.mask_circle; MaskType.RECT -> R.string.mask_rect }) }) { t ->
        vm.updateClip(h, c.id) { it.copy(mask = it.mask.copy(type = t)) }
    }
    if (c.mask.type != MaskType.NONE) {
        Text(stringResource(R.string.mask_hint), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PropSlider(vm, c, "mask.cx", stringResource(R.string.position_x), h, 0f..1f, 0.5f)
        PropSlider(vm, c, "mask.cy", stringResource(R.string.position_y), h, 0f..1f, 0.5f)
        if (c.mask.type != MaskType.LINEAR) {
            PropSlider(vm, c, "mask.w", stringResource(R.string.width), h, 0.02f..2f, 0.6f)
            PropSlider(vm, c, "mask.h", stringResource(R.string.height), h, 0.02f..2f, 0.6f)
        }
        PreciseSlider(stringResource(R.string.feather), c.mask.feather, { v -> vm.updateClip(h, c.id, live = true) { it.copy(mask = it.mask.copy(feather = v)) } }, range = 0f..0.5f, default = 0.05f, onFinished = { vm.commit() })
        PreciseSlider(stringResource(R.string.rotation), c.mask.rotation, { v -> vm.updateClip(h, c.id, live = true) { it.copy(mask = it.mask.copy(rotation = v)) } }, range = -180f..180f, onFinished = { vm.commit() },
            format = { String.format(Locale.US, "%.0f°", it) }, inputScale = 1f)
        SwitchRow(stringResource(R.string.invert), c.mask.invert) { on -> vm.updateClip(h, c.id) { it.copy(mask = it.mask.copy(invert = on)) } }
    }
}

@Composable
fun ZonePanel(vm: EditorViewModel, c: Clip) {
    val h = R.string.hist_zone
    val all = c.allZones
    val selRaw by vm.zoneSel.collectAsState()
    val i = selRaw.coerceIn(0, all.lastIndex)
    val z = all[i]
    val pre = zoneKey(i)
    fun upd(live: Boolean = false, f: (Zone) -> Zone) = vm.updateClip(h, c.id, live) { cl -> cl.allZones.getOrNull(i)?.let { cl.withZone(i, f(it)) } ?: cl }
    // Una ficha por zona; "+" añade otra y la papelera quita la seleccionada (con sus keyframes).
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (all.size > 1) for (k in all.indices) FilterChip(k == i, { vm.zoneSel.value = k }, { Text(stringResource(R.string.zone_n, k + 1)) })
        if (all.size < MAX_ZONES && all.last().type != ZoneType.NONE) {
            FilterChip(false, { vm.updateClip(h, c.id) { it.copy(zones = it.zones + Zone(type = ZoneType.BLUR)) }; vm.zoneSel.value = all.size }, { Text("+ " + stringResource(R.string.add)) })
        }
        if (all.size > 1 || z.type != ZoneType.NONE) {
            IconButton({ vm.updateClip(h, c.id) { it.removeZone(i) }; vm.zoneSel.value = (i - 1).coerceAtLeast(0) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        }
    }
    ChipRow(ZoneType.entries, z.type, { stringResource(when (it) { ZoneType.NONE -> R.string.none; ZoneType.BLUR -> R.string.zone_blur; ZoneType.MOSAIC -> R.string.zone_mosaic }) }) { t ->
        upd { it.copy(type = t) }
    }
    if (z.type != ZoneType.NONE) {
        Text(stringResource(R.string.zone_hint), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow(stringResource(R.string.zone_ellipse), z.ellipse) { on -> upd { it.copy(ellipse = on) } }
        PreciseSlider(stringResource(R.string.intensity), z.strength, { v -> upd(true) { it.copy(strength = v) } }, range = 0f..1f, default = 0.5f, onFinished = { vm.commit() })
        PropSlider(vm, c, "$pre.cx", stringResource(R.string.position_x), h, 0f..1f, 0.5f)
        PropSlider(vm, c, "$pre.cy", stringResource(R.string.position_y), h, 0f..1f, 0.5f)
        PropSlider(vm, c, "$pre.w", stringResource(R.string.width), h, 0.02f..1f, 0.3f)
        PropSlider(vm, c, "$pre.h", stringResource(R.string.height), h, 0.02f..1f, 0.3f)
    }
}

/** Nombre legible de una propiedad animable. */
@Composable
private fun propLabel(c: Clip, prop: String): String {
    val res = when (prop) {
        "x" -> R.string.position_x; "y" -> R.string.position_y; "scale" -> R.string.scale; "rotation" -> R.string.rotation
        "opacity" -> R.string.opacity; "volume" -> R.string.volume
        else -> 0
    }
    if (res != 0) return stringResource(res)
    if (prop.startsWith("fx:")) {
        val parts = prop.split(':')
        val def = Registry.effect(c.effects.firstOrNull { it.id == parts.getOrNull(1) }?.defId ?: "")
        val pd = def?.params?.firstOrNull { it.key == parts.getOrNull(2) }
        if (def != null && pd != null) return stringResource(def.nameRes) + " · " + stringResource(pd.nameRes)
    }
    return prop
}

@Composable
fun KeyframesPanel(vm: EditorViewModel, c: Clip) {
    val local = localUs(vm, c)
    val h = R.string.hist_keyframe
    Text(stringResource(R.string.keyframes_hint), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val basics = if (c.kind == ClipKind.AUDIO) listOf("volume") else listOf("x", "y", "scale", "rotation", "opacity") + if (c.audible) listOf("volume") else emptyList()
    val props = (basics + c.keyframes.keys).distinct()
    for (prop in props) {
        val list = c.keyframes[prop].orEmpty()
        val at = c.keyframeNear(prop, local)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(propLabel(c, prop) + if (list.isNotEmpty()) "  (" + list.size + ")" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton({ list.lastOrNull { it.tUs < local - 16_000L }?.let { vm.player.seekTo(c.startUs + it.tUs) } }, enabled = list.any { it.tUs < local - 16_000L }) { Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.prev_keyframe)) }
            IconButton({ vm.updateClip(h, c.id) { if (at != null) it.withoutKeyframe(prop, local) else it.withKeyframe(prop, local, it.prop(prop, local)) } }) {
                Icon(Icons.Default.Diamond, stringResource(R.string.keyframe), tint = if (at != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            }
            IconButton({ list.firstOrNull { it.tUs > local + 16_000L }?.let { vm.player.seekTo(c.startUs + it.tUs) } }, enabled = list.any { it.tUs > local + 16_000L }) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.next_keyframe)) }
            if (list.isNotEmpty()) IconButton({ vm.updateClip(h, c.id) { it.copy(keyframes = it.keyframes - prop) } }) { Icon(Icons.Default.Close, stringResource(R.string.delete)) }
        }
        if (at != null) {
            ChipRow(listOf(Easing.LINEAR, Easing.EASE_IN, Easing.EASE_OUT, Easing.EASE_IN_OUT, Easing.BACK_OUT, Easing.BOUNCE_OUT, Easing.ELASTIC_OUT, Easing.HOLD), at.easing, {
                stringResource(when (it) {
                    Easing.LINEAR -> R.string.ease_linear; Easing.EASE_IN -> R.string.ease_in; Easing.EASE_OUT -> R.string.ease_out; Easing.EASE_IN_OUT -> R.string.ease_in_out
                    Easing.BACK_OUT -> R.string.ease_back; Easing.BOUNCE_OUT -> R.string.ease_bounce; Easing.ELASTIC_OUT -> R.string.ease_elastic; else -> R.string.ease_hold
                })
            }) { e -> vm.updateClip(h, c.id) { it.withKeyframe(prop, at.tUs, at.value, e) } }
        }
    }
    Spacer(Modifier.width(1.dp))
}
