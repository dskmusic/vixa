@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.dskmusic.vixa.ui.editor

import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.foundation.layout.RowScope
import android.Manifest
import android.graphics.Typeface
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.data.SnapshotInfo
import com.dskmusic.vixa.engine.AnimKind
import com.dskmusic.vixa.engine.Anims
import com.dskmusic.vixa.engine.Fonts
import com.dskmusic.vixa.engine.OverlayRender
import com.dskmusic.vixa.engine.Registry
import com.dskmusic.vixa.engine.Shapes
import com.dskmusic.vixa.engine.Templates
import com.dskmusic.vixa.model.AnimRef
import com.dskmusic.vixa.model.Attr
import com.dskmusic.vixa.model.BgType
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.Layer
import com.dskmusic.vixa.model.LayerKind
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.StickerType
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.US
import com.dskmusic.vixa.model.addBlack
import com.dskmusic.vixa.model.insertGapAtStart
import com.dskmusic.vixa.model.addMarker
import com.dskmusic.vixa.model.addTrack
import com.dskmusic.vixa.model.addToTrack
import com.dskmusic.vixa.model.TextSpec
import com.dskmusic.vixa.model.fadeBlack
import com.dskmusic.vixa.model.groupClips
import com.dskmusic.vixa.model.reframed
import com.dskmusic.vixa.model.removeClips
import com.dskmusic.vixa.model.removeMarker
import com.dskmusic.vixa.model.removeTrack
import com.dskmusic.vixa.model.moveTrack
import com.dskmusic.vixa.model.ungroupClips
import com.dskmusic.vixa.model.updateClip
import com.dskmusic.vixa.ui.ChipRow
import com.dskmusic.vixa.ui.ColorRow
import com.dskmusic.vixa.ui.PreciseSlider
import com.dskmusic.vixa.ui.SectionTitle
import com.dskmusic.vixa.ui.TextInputDialog
import com.dskmusic.vixa.ui.formatTime
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

enum class PickKind { MEDIA, FILE, FOLDER }

/** Petición de selección de archivos/medios que atiende la pantalla del editor. */
class PickRequest(
    val kind: PickKind,
    val target: AddTarget = AddTarget.MAIN,
    val exts: Set<String> = emptySet(),
    val multi: Boolean = false,
    val onPicked: (List<String>) -> Unit,
)

private fun secs(v: Float) = String.format(Locale.US, "%.2f s", v)

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onChange)
    }
}

// ---------------------------------------------------------------------------------------------
// Texto
// ---------------------------------------------------------------------------------------------

@Composable
fun TextPanel(vm: EditorViewModel, project: Project, c: Clip, pick: (PickRequest) -> Unit) {
    val spec = c.text ?: return
    val context = LocalContext.current
    val h = R.string.hist_text
    var tab by remember { mutableIntStateOf(0) }
    var layerId by remember(c.id) { mutableStateOf(spec.layers.firstOrNull { it.kind == LayerKind.TEXT }?.id ?: spec.layers.firstOrNull()?.id ?: "") }
    val rawLayer = spec.layers.firstOrNull { it.id == layerId } ?: spec.layers.firstOrNull() ?: return
    // Los subtítulos comparten un estilo común guardado en el proyecto.
    val layer = if (spec.subtitle) project.subtitleStyle.copy(id = rawLayer.id, text = rawLayer.text) else rawLayer
    fun upd(live: Boolean = false, f: (Layer) -> Layer) {
        if (spec.subtitle) vm.edit(h, live) { p ->
            val n = f(p.subtitleStyle.copy(text = rawLayer.text))
            p.copy(subtitleStyle = n.copy(text = "")).updateClip(c.id) { x -> x.copy(text = x.text?.copy(layers = x.text.layers.map { if (it.id == rawLayer.id) it.copy(text = n.text) else it })) }
        } else vm.updateClip(h, c.id, live) { x -> x.copy(text = x.text?.copy(layers = x.text.layers.map { if (it.id == rawLayer.id) f(it) else it })) }
    }
    if (spec.layers.size > 1) {
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(spec.layers) { l ->
                FilterChip(l.id == rawLayer.id, { layerId = l.id }, { Text(if (l.kind == LayerKind.TEXT) l.text.replace('\n', ' ').take(14).ifBlank { "T" } else stringResource(R.string.shape), maxLines = 1) })
            }
        }
    }
    // Botones grandes (Texto, Letra, Estilo, Animar, Camino) en los dos modos.
    var drawingPath by remember { mutableStateOf(false) }
    val hasPath = !c.keyframes["x"].isNullOrEmpty() && !c.keyframes["y"].isNullOrEmpty()
    EasyBar(
        listOf(
            EasyTab("0", Icons.Default.TextFields, R.string.text_content), EasyTab("1", Icons.Default.FontDownload, R.string.text_font),
            EasyTab("2", Icons.Default.Palette, R.string.text_style), EasyTab("3", Icons.Default.Animation, R.string.animation),
            EasyTab("path", Icons.Default.Gesture, R.string.stk_act_path, hasPath),
        ),
        tab.toString(),
    ) { k -> if (k == "path") drawingPath = true else tab = k.toInt() }
    if (drawingPath) DrawPathDialog(vm, c) { drawingPath = false }
    when (tab) {
        0 -> {
            if (layer.kind == LayerKind.TEXT) {
                OutlinedTextField(layer.text, { t -> upd(true) { it.copy(text = t) } }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), minLines = 2, maxLines = 5)
                PreciseSlider(stringResource(R.string.size), layer.size, { v -> upd(true) { it.copy(size = v) } }, range = 0.01f..0.5f, default = 0.07f, onFinished = { vm.commit() }, format = { String.format(Locale.US, "%.1f", it * 100f) })
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((i, r) in listOf(R.string.align_left, R.string.align_center, R.string.align_right).withIndex()) FilterChip(layer.align == i, { upd { it.copy(align = i) } }, { Text(stringResource(r)) })
                    FilterChip(layer.upper, { upd { it.copy(upper = !layer.upper) } }, { Text("AA") })
                }
            } else {
                PreciseSlider(stringResource(R.string.width), layer.w, { v -> upd(true) { it.copy(w = v) } }, range = 0.002f..1.5f, default = 0.3f, onFinished = { vm.commit() })
                PreciseSlider(stringResource(R.string.height), layer.h, { v -> upd(true) { it.copy(h = v) } }, range = 0f..1.5f, default = 0.01f, onFinished = { vm.commit() })
                PreciseSlider(stringResource(R.string.corner_radius), layer.cornerRadius, { v -> upd(true) { it.copy(cornerRadius = v) } }, range = 0f..0.5f, default = 0f, onFinished = { vm.commit() })
                ColorRow(layer.color, { col -> upd { it.copy(color = col, gradient = emptyList()) } }, alpha = true)
            }
            if (!spec.subtitle) {
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip({ val n = Layer(text = context.getString(R.string.default_text), y = layer.y + 0.1f); vm.updateClip(h, c.id) { x -> x.copy(text = x.text?.copy(layers = x.text.layers + n)) }; layerId = n.id }, { Text(stringResource(R.string.add_text_layer)) })
                    AssistChip({ val n = Layer(kind = LayerKind.RECT, w = 0.3f, h = 0.008f, y = layer.y + 0.06f); vm.updateClip(h, c.id) { x -> x.copy(text = x.text?.copy(layers = listOf(n) + x.text.layers)) }; layerId = n.id }, { Text(stringResource(R.string.add_shape_layer)) })
                    if (spec.layers.size > 1) AssistChip({ vm.updateClip(h, c.id) { x -> x.copy(text = x.text?.copy(layers = x.text.layers.filter { it.id != rawLayer.id })) } }, { Text(stringResource(R.string.delete_layer)) })
                }
            } else {
                PreciseSlider(stringResource(R.string.position_y), layer.y, { v -> upd(true) { it.copy(y = v) } }, range = -0.48f..0.48f, default = 0.36f, onFinished = { vm.commit() })
            }
        }
        1 -> {
            val fonts = remember(tab) { Fonts.all(context) }
            val bigFonts = com.dskmusic.vixa.ui.ScrollHints.container?.settings?.flow?.collectAsState()?.value?.bigTiles ?: true
            if (bigFonts) TileGrid(Modifier.padding(vertical = 4.dp)) { tile ->
                for (f in fonts) {
                    val family = remember(f.id) { FontFamily(Fonts.typeface(f.id)) }
                    val sel = layer.fontId == f.id
                    Column(
                        tile.height(tileH()).clip(RoundedCornerShape(14.dp)).background(if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { upd { it.copy(fontId = f.id) } }.padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text("Aa", fontFamily = family, fontSize = 28.sp, maxLines = 1)
                        Text(f.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!bigFonts) for (f in fonts) {
                    val family = remember(f.id) { FontFamily(Fonts.typeface(f.id)) }
                    FilterChip(layer.fontId == f.id, { upd { it.copy(fontId = f.id) } }, { Text(f.name, fontFamily = family, maxLines = 1) })
                }
                AssistChip({
                    pick(PickRequest(PickKind.FILE, exts = setOf("ttf", "otf")) { files ->
                        for (p in files) try { File(p).copyTo(File(Fonts.customDir(context), File(p).name), overwrite = true) } catch (e: Throwable) { ErrorLog.log("Font", e) }
                        Fonts.invalidateCustom()
                        files.firstOrNull()?.let { p -> upd { it.copy(fontId = "custom:" + File(p).name) } }
                    })
                }, { Text(stringResource(R.string.import_font)) })
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(layer.bold, { upd { it.copy(bold = !layer.bold) } }, { Text(stringResource(R.string.bold)) })
                FilterChip(layer.italic, { upd { it.copy(italic = !layer.italic) } }, { Text(stringResource(R.string.italic)) })
            }
            PreciseSlider(stringResource(R.string.line_spacing), layer.lineSpacing, { v -> upd(true) { it.copy(lineSpacing = v) } }, range = 0.7f..2.5f, default = 1.1f, onFinished = { vm.commit() }, format = { String.format(Locale.US, "%.2f", it) }, inputScale = 1f)
            PreciseSlider(stringResource(R.string.letter_spacing), layer.letterSpacing, { v -> upd(true) { it.copy(letterSpacing = v) } }, range = -0.1f..1f, default = 0f, onFinished = { vm.commit() })
        }
        2 -> {
            SectionCard(stringResource(R.string.color)) {
            ColorRow(layer.color, { col -> upd { it.copy(color = col, gradient = emptyList()) } })
            SwitchLine(stringResource(R.string.gradient), layer.gradient.size >= 2) { on -> upd { it.copy(gradient = if (on) listOf(it.color, 0xFF5CE1E6.toInt()) else emptyList()) } }
            if (layer.gradient.size >= 2) {
                ColorRow(layer.gradient[1], { col -> upd { it.copy(gradient = listOf(it.gradient.firstOrNull() ?: it.color, col)) } })
                PreciseSlider(stringResource(R.string.angle), layer.gradientAngle, { v -> upd(true) { it.copy(gradientAngle = v) } }, range = 0f..360f, onFinished = { vm.commit() }, format = { String.format(Locale.US, "%.0f°", it) }, inputScale = 1f)
            }
            }
            SectionCard(stringResource(R.string.stroke)) {
            PreciseSlider(stringResource(R.string.width), layer.strokeWidth, { v -> upd(true) { it.copy(strokeWidth = v) } }, range = 0f..0.4f, default = 0f, onFinished = { vm.commit() })
            if (layer.strokeWidth > 0f) ColorRow(layer.strokeColor, { col -> upd { it.copy(strokeColor = col) } })
            }
            SectionCard(stringResource(R.string.shadow)) {
            PreciseSlider(stringResource(R.string.radius), layer.shadowRadius, { v -> upd(true) { it.copy(shadowRadius = v) } }, range = 0f..0.5f, default = 0f, onFinished = { vm.commit() })
            if (layer.shadowRadius > 0f) {
                ColorRow(layer.shadowColor, { col -> upd { it.copy(shadowColor = col) } }, alpha = true)
                PreciseSlider(stringResource(R.string.offset), layer.shadowDy, { v -> upd(true) { it.copy(shadowDy = v, shadowDx = v * 0.5f) } }, range = 0f..0.3f, default = 0.06f, onFinished = { vm.commit() })
            }
            }
            SectionCard(stringResource(R.string.background)) {
            ColorRow(layer.bgColor, { col -> upd { it.copy(bgColor = col) } }, alpha = true, allowNone = true)
            if (layer.bgColor != 0) {
                PreciseSlider(stringResource(R.string.padding), layer.bgPadding, { v -> upd(true) { it.copy(bgPadding = v) } }, range = 0f..1f, default = 0.3f, onFinished = { vm.commit() })
                PreciseSlider(stringResource(R.string.corner_radius), layer.bgRadius, { v -> upd(true) { it.copy(bgRadius = v) } }, range = 0f..1f, default = 0.2f, onFinished = { vm.commit() })
            }
            }
        }
        else -> {
            var kindIdx by remember { mutableIntStateOf(0) }
            val kinds = listOf(AnimKind.IN, AnimKind.OUT, AnimKind.LOOP)
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((i, r) in listOf(R.string.anim_in, R.string.anim_out, R.string.anim_loop).withIndex()) FilterChip(kindIdx == i, { kindIdx = i }, { Text(stringResource(r)) })
            }
            val kind = kinds[kindIdx]
            val cur = when (kind) { AnimKind.IN -> layer.animIn; AnimKind.OUT -> layer.animOut; AnimKind.LOOP -> layer.animLoop }
            fun set(r: AnimRef?) = upd { when (kind) { AnimKind.IN -> it.copy(animIn = r); AnimKind.OUT -> it.copy(animOut = r); AnimKind.LOOP -> it.copy(animLoop = r) } }
            val animProg = rememberLoopProgress()
            TileGrid(Modifier.padding(vertical = 6.dp)) { tile ->
                PreviewTile(stringResource(R.string.none), cur == null, { set(null) }, tile.height(tileH())) { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) }
                for (a in Anims.list(kind, true).favFirst(rememberFavs()) { "an:$kind:${it.id}" }) {
                    TextAnimTile(a, kind, stringResource(a.nameRes), cur?.id == a.id, animProg, tile.height(tileH()), favKey = "an:$kind:${a.id}") { set(AnimRef(a.id, cur?.durationUs ?: 500_000L)); vm.player.seekTo(c.startUs); vm.player.play() }
                }
            }
            if (cur != null && kind != AnimKind.LOOP) {
                PreciseSlider(stringResource(R.string.duration), cur.durationUs / 1_000_000f, { v ->
                    val r = cur.copy(durationUs = (v * 1_000_000f).toLong())
                    upd(true) { if (kind == AnimKind.IN) it.copy(animIn = r) else it.copy(animOut = r) }
                }, range = 0.1f..5f, default = 0.5f, onFinished = { vm.commit() }, format = ::secs, inputScale = 1f)
            }
            PreciseSlider(stringResource(R.string.delay), layer.delayUs / 1_000_000f, { v -> upd(true) { it.copy(delayUs = (v * 1_000_000f).toLong()) } }, range = 0f..5f, default = 0f, onFinished = { vm.commit() }, format = ::secs, inputScale = 1f)
        }
    }
    if (!spec.subtitle) {
        var naming by remember { mutableStateOf(false) }
        TextButton({ naming = true }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.save_as_template)) }
        if (naming) TextInputDialog(stringResource(R.string.template_name), layer.text.take(20), { naming = false }) { vm.saveTextTemplate(it); naming = false }
    }
}

/** Galería de plantillas de texto con vista previa animada (el mismo render que el vídeo final). */
@Composable
fun TemplatesPanel(vm: EditorViewModel, project: Project) {
    val context = LocalContext.current
    val es = context.resources.configuration.locales[0].language == "es"
    val cats = Templates.categories
    var cat by remember { mutableIntStateOf(cats.first()) }
    var mine by remember { mutableStateOf<List<Clip>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { mine = vm.container.projects.textTemplates() }
    val render = remember { OverlayRender() }
    var timeMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { timeMs = it - t0 } }
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { FilterChip(cat == -1, { cat = -1 }, { Text(stringResource(R.string.my_templates)) }) }
        items(cats) { r -> FilterChip(cat == r, { cat = r }, { Text(stringResource(r)) }) }
    }
    val clips: List<Pair<String, Clip>> = remember(cat, mine, es) {
        if (cat == -1) mine.map { "user:" + it.id to it } else Templates.all.filter { it.catRes == cat }.map { it.id to it.toClip(es, 0) }
    }
    val aspect = 1f // cuadradas, como el resto de previsualizaciones
    val fake = remember { Project(width = 1000, height = 1000) }
    if (clips.isEmpty()) Text(stringResource(R.string.no_templates), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    // Rejilla de 3 por fila; el panel ya se desplaza en vertical.
    TileGrid(Modifier.padding(top = 8.dp), gap = 8.dp) { tile ->
        for ((id, clip) in clips.favFirst(rememberFavs()) { "tpl:" + it.first }) {
            Box(tile.aspectRatio(aspect).clip(RoundedCornerShape(10.dp)).background(Color(0xFF20222B)).combinedClickable(onLongClick = { Favs.pending.value = "tpl:$id" }) {
                if (id.startsWith("user:")) vm.addClipCopy(clip) else vm.addTemplate(id)
                vm.tool.value = "text" // abre el editor de texto para cambiar los textos predefinidos
            }) {
                Canvas(Modifier.fillMaxSize()) {
                    val loop = clip.durationUs + 600_000L
                    val t = (timeMs * 1000L) % loop
                    if (t < clip.durationUs) render.drawClip(drawContext.canvas.nativeCanvas, fake, clip, t, size.width.toInt(), size.height.toInt())
                }
                if (id.startsWith("user:")) {
                    IconButton({ vm.launch { vm.container.projects.deleteTextTemplate(clip.id); reload++ } }, Modifier.align(Alignment.TopEnd).size(28.dp)) {
                        Icon(Icons.Default.Delete, stringResource(R.string.delete), Modifier.size(16.dp), tint = Color.White)
                    }
                    Text(clip.label, Modifier.align(Alignment.BottomStart).padding(4.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Stickers
// ---------------------------------------------------------------------------------------------

@Composable
fun StickersPanel(vm: EditorViewModel, selected: Clip?, pick: (PickRequest) -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val st = selected?.sticker
    if (selected != null && st != null) {
        PreciseSlider(stringResource(R.string.size), st.size, { v -> vm.updateClip(R.string.hist_sticker, selected.id, live = true) { it.copy(sticker = it.sticker?.copy(size = v)) } }, range = 0.03f..1.2f, default = 0.25f, onFinished = { vm.commit() })
        if (st.type == StickerType.SHAPE) ColorRow(st.color, { col -> vm.updateClip(R.string.hist_sticker, selected.id) { it.copy(sticker = it.sticker?.copy(color = col)) } }, alpha = true)
    }
    // FlowRow: con la etiqueta larga de la tercera pestaña la fila no cabe y se cortaba fuera de la pantalla.
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((i, r) in listOf(R.string.emoji, R.string.shapes, R.string.images_gifs).withIndex()) FilterChip(tab == i, { tab = i }, { Text(stringResource(r)) })
    }
    when (tab) {
        0 -> AndroidView(
            factory = { ctx -> EmojiPickerView(ctx).apply { emojiGridColumns = 8; setOnEmojiPickedListener { item -> vm.addSticker(StickerType.EMOJI, item.emoji) } } },
            modifier = Modifier.fillMaxWidth().height(300.dp),
        )
        1 -> {
            val favs = rememberFavs()
            ShapePicker(
                tint = MaterialTheme.colorScheme.onSurface,
                sort = { ids -> ids.favFirst(favs) { "shape:" + it } },
                onLong = { Favs.pending.value = "shape:" + it },
            ) { vm.addSticker(StickerType.SHAPE, it) }
        }
        else -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ pick(PickRequest(PickKind.MEDIA, AddTarget.STICKER, multi = true) { vm.addMedia(it, AddTarget.STICKER) }) }) { Text(stringResource(R.string.pick_images_gifs)) }
            Text(stringResource(R.string.sticker_online_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Subtítulos
// ---------------------------------------------------------------------------------------------

private fun srtTime(us: Long): String {
    val ms = us / 1000
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)
}

private fun parseSrtTime(s: String): Long? {
    val m = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)").find(s.trim()) ?: return null
    val (h, mi, se, ms) = m.destructured
    return ((h.toLong() * 3600 + mi.toLong() * 60 + se.toLong()) * 1000 + ms.padEnd(3, '0').take(3).toLong()) * 1000
}

fun parseSrt(text: String): List<Triple<Long, Long, String>> {
    val out = ArrayList<Triple<Long, Long, String>>()
    for (block in text.replace("\r", "").replace("﻿", "").split(Regex("\n\\s*\n"))) {
        val lines = block.trim().lines()
        val ti = lines.indexOfFirst { it.contains("-->") }
        if (ti < 0) continue
        val parts = lines[ti].split("-->")
        val a = parseSrtTime(parts[0]) ?: continue
        val b = parseSrtTime(parts.getOrNull(1) ?: "") ?: continue
        val body = lines.drop(ti + 1).joinToString("\n").replace(Regex("<[^>]+>"), "").trim()
        if (body.isNotEmpty() && b > a) out.add(Triple(a, b, body))
    }
    return out
}

fun subtitleClips(p: Project): List<Clip> = p.allClips().filter { it.text?.subtitle == true }.sortedBy { it.startUs }

fun toSrt(p: Project): String = subtitleClips(p).mapIndexed { i, c ->
    (i + 1).toString() + "\n" + srtTime(c.startUs) + " --> " + srtTime(c.endUs) + "\n" + (c.text?.layers?.firstOrNull()?.text ?: "") + "\n"
}.joinToString("\n")

@Composable
fun SubtitlesPanel(vm: EditorViewModel, project: Project, pick: (PickRequest) -> Unit) {
    val context = LocalContext.current
    val subs = subtitleClips(project)
    // Tres botones iguales (icono arriba, texto debajo): caben siempre en una fila, sin partir el texto.
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SubBtn(Icons.Default.Add, stringResource(R.string.add_subtitle)) { vm.addSubtitle(context.getString(R.string.subtitle_default), vm.player.positionUs.value, 2 * US) }
        SubBtn(Icons.Default.FolderOpen, stringResource(R.string.import_srt)) {
            pick(PickRequest(PickKind.FILE, exts = setOf("srt", "txt")) { files ->
                val f = files.firstOrNull() ?: return@PickRequest
                val items = try { parseSrt(File(f).readText()) } catch (e: Throwable) { emptyList() }
                if (items.isEmpty()) vm.toast(vm.str(R.string.err_srt)) else {
                    vm.edit(R.string.hist_subtitles) { p ->
                        var r = p
                        for ((a, b, t) in items) r = r.addToTrack(Clip(kind = ClipKind.TEXT, startUs = a, durationUs = b - a, text = TextSpec(listOf(Layer(text = t)), subtitle = true)), TrackType.TEXT)
                        r
                    }
                    vm.toast(vm.str(R.string.subtitles_imported, items.size))
                }
            })
        }
        SubBtn(Icons.Default.Save, stringResource(R.string.export_srt), enabled = subs.isNotEmpty()) {
            try {
                val dir = com.dskmusic.vixa.data.Storage.subtitles().apply { mkdirs() }
                val f = File(dir, (project.name.ifBlank { "Vixa" }).replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".srt")
                f.writeText(toSrt(project))
                vm.toast(vm.str(R.string.saved_to, f.path))
            } catch (e: Throwable) { ErrorLog.log("SRT", e); vm.toast(vm.str(R.string.err_generic)) }
        }
    }
    // Acceso directo a los subtítulos automáticos (IA en el dispositivo) y color del karaoke.
    FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip({ vm.aiPage.value = "subtitles"; vm.tool.value = "ai" }, { Text("✨ " + stringResource(R.string.ai_f_subtitles)) })
        AssistChip({ vm.aiPage.value = "translate"; vm.tool.value = "ai" }, { Text("✨ " + stringResource(R.string.ai_f_translate)) })
    }
    if (subs.any { it.text?.wordsUs?.isNotEmpty() == true }) {
        SwitchLine(stringResource(R.string.ai_karaoke), project.karaokeColor != 0) { on -> vm.edit(R.string.hist_subtitles) { it.copy(karaokeColor = if (on) 0xFFFFD60A.toInt() else 0) } }
        if (project.karaokeColor != 0) ColorRow(project.karaokeColor, { c -> vm.edit(R.string.hist_subtitles) { it.copy(karaokeColor = c) } })
    }
    if (subs.isEmpty()) Text(stringResource(R.string.no_subtitles), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (c in subs) {
        val layer = c.text?.layers?.firstOrNull() ?: continue
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(c.startUs), Modifier.width(54.dp).clickable { vm.player.seekTo(c.startUs); vm.selectOnly(c.id) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(layer.text, { t -> vm.updateClip(R.string.hist_subtitles, c.id, live = true) { x -> x.copy(text = x.text?.copy(layers = x.text.layers.map { it.copy(text = t) })) } },
                Modifier.weight(1f), textStyle = MaterialTheme.typography.bodySmall, maxLines = 2)
            IconButton({ vm.edit(R.string.hist_delete) { it.removeClips(listOf(c.id)) } }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        }
    }
    if (subs.isNotEmpty()) {
        TextButton({ vm.selectOnly(subs.first().id); vm.tool.value = "text" }, Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.subtitle_style)) }
    }
}

// ---------------------------------------------------------------------------------------------
// Audio
// ---------------------------------------------------------------------------------------------

@Composable
fun AudioFxPanel(vm: EditorViewModel, project: Project, c: Clip) {
    val h = R.string.hist_audio_fx
    val fx = c.audioFx
    fun upd(live: Boolean = false, f: (com.dskmusic.vixa.model.AudioFx) -> com.dskmusic.vixa.model.AudioFx) = vm.updateClip(h, c.id, live) { it.copy(audioFx = f(it.audioFx)) }
    SectionTitle(stringResource(R.string.equalizer))
    PreciseSlider(stringResource(R.string.eq_low), fx.eqLow, { v -> upd(true) { it.copy(eqLow = v) } }, onFinished = { vm.commit() }, format = { String.format(Locale.US, "%+.0f dB", it * 12f) }, inputScale = 12f)
    PreciseSlider(stringResource(R.string.eq_mid), fx.eqMid, { v -> upd(true) { it.copy(eqMid = v) } }, onFinished = { vm.commit() }, format = { String.format(Locale.US, "%+.0f dB", it * 12f) }, inputScale = 12f)
    PreciseSlider(stringResource(R.string.eq_high), fx.eqHigh, { v -> upd(true) { it.copy(eqHigh = v) } }, onFinished = { vm.commit() }, format = { String.format(Locale.US, "%+.0f dB", it * 12f) }, inputScale = 12f)
    SectionTitle(stringResource(R.string.pitch_and_voice))
    PreciseSlider(stringResource(R.string.pitch), fx.pitch, { v -> upd(true) { it.copy(pitch = Math.round(v).toFloat()) } }, range = -12f..12f, onFinished = { vm.commit() }, format = { String.format(Locale.US, "%+.0f st", it) }, inputScale = 1f)
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(fx.voice.isEmpty(), { upd { it.copy(voice = "") } }, { Text(stringResource(R.string.none)) })
        for (v in Registry.voicePresets) FilterChip(fx.voice == v.id, { upd { it.copy(voice = v.id) } }, { Text(stringResource(v.nameRes)) })
    }
    SectionTitle(stringResource(R.string.cleanup))
    // Accesos directos a las funciones de IA de audio (se ejecutan en el dispositivo).
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip({ vm.aiPage.value = "denoise"; vm.tool.value = "ai" }, { Text("✨ " + stringResource(R.string.ai_f_denoise)) })
        AssistChip({ vm.aiPage.value = "separate"; vm.tool.value = "ai" }, { Text("✨ " + stringResource(R.string.ai_f_separate)) })
        AssistChip({ vm.aiPage.value = "tts"; vm.tool.value = "ai" }, { Text("✨ " + stringResource(R.string.ai_f_tts)) })
    }
    PreciseSlider(stringResource(R.string.noise_reduction), fx.denoise, { v -> upd(true) { it.copy(denoise = v) } }, range = 0f..1f, onFinished = { vm.commit() })
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ vm.normalizeAudio() }) { Text(stringResource(R.string.normalize)) }
        if (fx.normalizeGain != 1f) AssistChip({ upd { it.copy(normalizeGain = 1f) } }, { Text(String.format(Locale.US, "×%.2f  ✕", fx.normalizeGain)) })
    }
    SectionTitle(stringResource(R.string.ducking))
    SwitchLine(stringResource(R.string.is_voice), fx.isVoice) { on -> upd { it.copy(isVoice = on) } }
    SwitchLine(stringResource(R.string.duck_this), fx.duck) { on -> upd { it.copy(duck = on) } }
    SwitchLine(stringResource(R.string.auto_duck), project.autoDuck) { on -> vm.edit(h) { it.copy(autoDuck = on) } }
    PreciseSlider(stringResource(R.string.duck_amount), project.duckAmount, { v -> vm.edit(h, live = true) { it.copy(duckAmount = v) } }, range = 0f..1f, default = 0.25f, onFinished = { vm.commit() })
}

/** Grabación de voz en off sobre la línea de tiempo (la reproducción avanza mientras se graba). */
@Composable
fun VoiceOverPanel(vm: EditorViewModel) {
    val context = LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var startUs by remember { mutableLongStateOf(0L) }
    val pos by vm.player.positionUs.collectAsState()

    fun stop(add: Boolean) {
        val r = recorder ?: return
        recorder = null
        vm.player.pause()
        try { r.stop() } catch (e: Throwable) { ErrorLog.log("VoiceOver", e) }
        try { r.release() } catch (e: Throwable) { }
        val f = file
        if (add && f != null && f.exists() && f.length() > 1000) vm.addVoiceOver(f.path, startUs) else f?.delete()
    }

    fun start() {
        try {
            val dir = File(context.filesDir, "media/voice").apply { mkdirs() }
            val f = File(dir, "voz_" + System.currentTimeMillis() + ".m4a")
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(48000)
            r.setAudioEncodingBitRate(256_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            file = f
            startUs = vm.player.positionUs.value
            recorder = r
            vm.player.play()
        } catch (e: Throwable) {
            ErrorLog.log("VoiceOver", e)
            vm.toast(vm.str(R.string.err_mic))
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) start() else vm.toast(vm.str(R.string.err_mic)) }
    DisposableEffect(Unit) {
        val unguard = com.dskmusic.vixa.engine.PlaybackGuard.register { stop(true) }
        onDispose { unguard(); stop(true) }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (recorder != null) stringResource(R.string.recording, formatTime(pos - startUs)) else stringResource(R.string.voice_over_hint), style = MaterialTheme.typography.bodyMedium)
        // Sin auriculares el micrófono recoge el sonido del altavoz (eco y calidad pobre): avisar.
        val am = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }
        val headset = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
        if (!headset) Text(stringResource(R.string.voice_headset_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        Button({
            if (recorder != null) stop(true)
            else if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) start()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }) {
            Icon(if (recorder != null) Icons.Default.Stop else Icons.Default.Mic, null)
            Text(stringResource(if (recorder != null) R.string.stop else R.string.record), Modifier.padding(start = 8.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Lienzo, extras y proyecto
// ---------------------------------------------------------------------------------------------

val ASPECTS: List<Pair<Int, Int>> = listOf(9 to 16, 16 to 9, 1 to 1, 4 to 5, 4 to 3, 21 to 9)

fun sizeFor(aw: Int, ah: Int, shortSide: Int): Pair<Int, Int> =
    if (aw >= ah) ((shortSide * aw / ah) / 2 * 2) to shortSide else shortSide to ((shortSide * ah / aw) / 2 * 2)

@Composable
fun CanvasPanel(vm: EditorViewModel, project: Project) {
    val h = R.string.hist_canvas
    val short = minOf(project.width, project.height)
    SectionTitle(stringResource(R.string.aspect_ratio))
    var fill by remember { mutableStateOf(true) }
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(ASPECTS) { (aw, ah) ->
            val (w, hh) = sizeFor(aw, ah, short)
            com.dskmusic.vixa.ui.AspectChip(aw, ah, project.width == w && project.height == hh) { vm.edit(R.string.hist_reframe) { it.reframed(w, hh, fill) } }
        }
    }
    SwitchLine(stringResource(R.string.reframe_fill), fill) { fill = it }
    SectionTitle(stringResource(R.string.resolution) + " · " + project.width + "×" + project.height)
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in listOf(480, 720, 1080, 1440, 2160)) {
            FilterChip(short == s, {
                val k = s.toFloat() / short
                vm.edit(h) { it.copy(width = (Math.round(it.width * k) / 2 * 2), height = (Math.round(it.height * k) / 2 * 2)) }
            }, { Text(if (s == 2160) "4K" else s.toString() + "p") })
        }
    }
    SectionTitle(stringResource(R.string.frame_rate))
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (f in listOf(24, 25, 30, 50, 60)) FilterChip(project.fps == f, { vm.edit(h) { it.copy(fps = f) } }, { Text(f.toString()) })
    }
    SectionTitle(stringResource(R.string.background))
    val bg = project.background
    ChipRow(BgType.entries, bg.type, { stringResource(when (it) { BgType.COLOR -> R.string.color; BgType.GRADIENT -> R.string.gradient; BgType.BLUR -> R.string.bg_blur }) }) { t ->
        vm.edit(h) { it.copy(background = it.background.copy(type = t, colors = if (t == BgType.GRADIENT && it.background.colors.size < 2) it.background.colors + 0xFF7C5CFF.toInt() else it.background.colors)) }
    }
    when (bg.type) {
        BgType.COLOR -> ColorRow(bg.colors.firstOrNull() ?: 0xFF000000.toInt(), { col -> vm.edit(h) { it.copy(background = it.background.copy(colors = listOf(col))) } })
        BgType.GRADIENT -> {
            ColorRow(bg.colors.getOrElse(0) { 0xFF000000.toInt() }, { col -> vm.edit(h) { it.copy(background = it.background.copy(colors = listOf(col, it.background.colors.getOrElse(1) { col }))) } })
            ColorRow(bg.colors.getOrElse(1) { 0xFF000000.toInt() }, { col -> vm.edit(h) { it.copy(background = it.background.copy(colors = listOf(it.background.colors.getOrElse(0) { col }, col))) } })
            PreciseSlider(stringResource(R.string.angle), bg.angle, { v -> vm.edit(h, live = true) { it.copy(background = it.background.copy(angle = v)) } }, range = 0f..360f, default = 90f, onFinished = { vm.commit() }, format = { String.format(Locale.US, "%.0f°", it) }, inputScale = 1f)
        }
        BgType.BLUR -> PreciseSlider(stringResource(R.string.intensity), bg.blur, { v -> vm.edit(h, live = true) { it.copy(background = it.background.copy(blur = v)) } }, range = 0f..1f, default = 0.6f, onFinished = { vm.commit() })
    }
}

/** Duración del fundido desde / a negro de todo el vídeo: 0,5 a 5 s (2 s por defecto). Si ya hay uno, permite quitarlo. */
@Composable
fun FadeDialog(vm: EditorViewModel, project: Project, fromBlack: Boolean, onDismiss: () -> Unit) {
    val current = if (fromBlack) project.fadeInUs else project.fadeOutUs
    var secs by remember { mutableStateOf(if (current > 0) (current / 1_000_000f).coerceIn(0.5f, 5f) else 2f) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (fromBlack) R.string.fade_from_black else R.string.fade_to_black)) },
        text = {
            Column {
                Text(stringResource(R.string.duration) + ": " + String.format(Locale.US, "%.1f s", secs))
                androidx.compose.material3.Slider(secs, { secs = Math.round(it * 2f) / 2f }, valueRange = 0.5f..5f, steps = 8)
            }
        },
        confirmButton = {
            TextButton({ vm.edit(R.string.hist_fade_black) { it.fadeBlack(fromBlack, (secs * 1_000_000f).toLong()) }; onDismiss() }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            Row {
                if (current > 0) TextButton({ vm.edit(R.string.hist_fade_black) { it.fadeBlack(fromBlack, 0L) }; onDismiss() }) { Text(stringResource(R.string.delete)) }
                TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
fun ExtrasPanel(vm: EditorViewModel, project: Project, pick: (PickRequest) -> Unit) {
    val selection by vm.selection.collectAsState()
    val multi by vm.multiSelect.collectAsState()
    val black = vm.settings.colorDurationMs * 1000L
    var pasting by remember { mutableStateOf(false) }
    var fadeAsk by remember { mutableStateOf<Boolean?>(null) }
    SectionTitle(stringResource(R.string.extras_auto))
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip({ vm.beatSync(false) }, { Text(stringResource(R.string.beat_markers)) })
        AssistChip({ vm.beatSync(true) }, { Text(stringResource(R.string.beat_cuts)) })
        AssistChip({ vm.removeSilences() }, { Text(stringResource(R.string.remove_silences)) })
        AssistChip({ vm.detectScenes() }, { Text(stringResource(R.string.detect_scenes)) })
        AssistChip({
            pick(PickRequest(PickKind.MEDIA, AddTarget.MAIN, multi = true) { media ->
                pick(PickRequest(PickKind.MEDIA, AddTarget.AUDIO) { song -> song.firstOrNull()?.let { vm.autoMontage(media, it) } })
            })
        }, { Text(stringResource(R.string.auto_montage)) })
    }
    SectionTitle(stringResource(R.string.move_all))
    var gapDialog by remember { mutableStateOf(false) }
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip({ gapDialog = true }, { Text(stringResource(R.string.gap_at_start)) })
    }
    if (gapDialog) {
        var secs by remember { mutableStateOf("1") }
        var withAudio by remember { mutableStateOf(true) }
        val us = secs.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }?.let { (it * 1_000_000f).toLong() }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { gapDialog = false },
            title = { Text(stringResource(R.string.gap_at_start)) },
            text = {
                Column {
                    OutlinedTextField(secs, { secs = it }, singleLine = true, label = { Text(stringResource(R.string.gap_seconds)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    Row(Modifier.fillMaxWidth().clickable { withAudio = !withAudio }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(withAudio, { withAudio = it })
                        Text(stringResource(R.string.gap_move_audio))
                    }
                }
            },
            confirmButton = {
                TextButton({ us?.let { d -> vm.edit(R.string.gap_at_start) { it.insertGapAtStart(d, withAudio) } }; gapDialog = false }, enabled = us != null) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton({ gapDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    SectionTitle(stringResource(R.string.black_and_fades))
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip({ vm.edit(R.string.hist_add_color) { it.addBlack(true, black) } }, { Text(stringResource(R.string.black_at_start)) })
        AssistChip({ vm.edit(R.string.hist_add_color) { it.addBlack(false, black) } }, { Text(stringResource(R.string.black_at_end)) })
        AssistChip({ fadeAsk = true }, { Text(stringResource(R.string.fade_from_black)) })
        AssistChip({ fadeAsk = false }, { Text(stringResource(R.string.fade_to_black)) })
    }
    fadeAsk?.let { from -> FadeDialog(vm, project, from) { fadeAsk = null } }
    SectionTitle(stringResource(R.string.split_screen))
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((id, label) in listOf("2v" to "▯▯", "2h" to "▭/▭", "3" to "▭/▯▯", "4" to "▯▯/▯▯")) {
            AssistChip({ pick(PickRequest(PickKind.MEDIA, AddTarget.OVERLAY, multi = true) { vm.addSplitScreen(it, id) }) }, { Text(label) })
        }
    }
    SectionTitle(stringResource(R.string.selection_and_markers))
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(multi, { vm.multiSelect.value = !multi }, { Text(stringResource(R.string.multi_select)) })
        AssistChip({ vm.edit(R.string.hist_group) { it.groupClips(selection) } }, { Text(stringResource(R.string.group)) }, enabled = selection.size > 1)
        AssistChip({ vm.edit(R.string.hist_group) { it.ungroupClips(selection) } }, { Text(stringResource(R.string.ungroup)) }, enabled = selection.isNotEmpty())
        AssistChip({ vm.copyAttributes() }, { Text(stringResource(R.string.copy_attributes)) }, enabled = selection.size == 1)
        AssistChip({ pasting = true }, { Text(stringResource(R.string.paste_attributes)) }, enabled = vm.clipboard != null && selection.isNotEmpty())
        AssistChip({ vm.edit(R.string.hist_marker) { it.addMarker(vm.player.positionUs.value) } }, { Text(stringResource(R.string.add_marker)) })
    }
    for (m in project.markers) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("◆ " + formatTime(m.timeUs), Modifier.weight(1f).clickable { vm.player.seekTo(m.timeUs) }, color = Color(m.color))
            IconButton({ vm.edit(R.string.hist_marker) { it.removeMarker(m.id) } }, Modifier.size(32.dp)) { Icon(Icons.Default.Delete, stringResource(R.string.delete), Modifier.size(18.dp)) }
        }
    }
    SectionTitle(stringResource(R.string.tracks))
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((t, r) in listOf(TrackType.OVERLAY to R.string.track_overlay, TrackType.AUDIO to R.string.track_audio, TrackType.TEXT to R.string.track_text, TrackType.STICKER to R.string.track_sticker, TrackType.ADJUST to R.string.track_adjust)) {
            AssistChip({ vm.edit(R.string.hist_track) { it.addTrack(t) } }, { Text("+ " + stringResource(r)) })
        }
    }
    for (t in project.tracks) {
        if (t.type == TrackType.MAIN) continue
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t.type.name.lowercase().replaceFirstChar { it.uppercase() } + " · " + t.clips.size, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton({ vm.edit(R.string.hist_track) { it.moveTrack(t.id, -1) } }) { Text("↑") }
            TextButton({ vm.edit(R.string.hist_track) { it.moveTrack(t.id, 1) } }) { Text("↓") }
            IconButton({ vm.edit(R.string.hist_track) { it.removeTrack(t.id) } }, Modifier.size(32.dp)) { Icon(Icons.Default.Delete, stringResource(R.string.delete), Modifier.size(18.dp)) }
        }
    }
    if (pasting) {
        var attrs by remember { mutableStateOf(setOf(Attr.COLOR, Attr.FILTER, Attr.EFFECTS)) }
        val names = mapOf(Attr.TRANSFORM to R.string.transform, Attr.COLOR to R.string.adjust, Attr.FILTER to R.string.filters, Attr.EFFECTS to R.string.effects, Attr.MASK to R.string.mask,
            Attr.ANIMATION to R.string.animation, Attr.AUDIO to R.string.audio, Attr.SPEED to R.string.speed, Attr.TEXT_STYLE to R.string.text_style)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text(stringResource(R.string.paste_attributes)) },
            text = { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for ((a, r) in names) FilterChip(a in attrs, { attrs = if (a in attrs) attrs - a else attrs + a }, { Text(stringResource(r)) }) } },
            confirmButton = { TextButton({ vm.pasteAttributes(attrs); pasting = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton({ pasting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun ProjectPanel(vm: EditorViewModel, project: Project, pick: (PickRequest) -> Unit, onOpenProject: (String) -> Unit) {
    val missing by vm.missing.collectAsState()
    var dialog by remember { mutableStateOf("") }
    var snaps by remember { mutableStateOf<List<SnapshotInfo>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { snaps = vm.snapshots() }
    if (missing.isNotEmpty()) {
        SectionTitle(stringResource(R.string.missing_media))
        for (m in missing) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(File(m).name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.error)
                TextButton({ pick(PickRequest(PickKind.FILE) { it.firstOrNull()?.let { n -> vm.relink(m, n) } }) }) { Text(stringResource(R.string.relink)) }
            }
        }
    }
    FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AssistChip({ vm.save() }, { Text(stringResource(R.string.save)) })
        AssistChip({ dialog = "saveas" }, { Text(stringResource(R.string.save_as)) })
        AssistChip({ dialog = "rename" }, { Text(stringResource(R.string.rename)) })
        AssistChip({ dialog = "template" }, { Text(stringResource(R.string.save_project_template)) })
        AssistChip({ dialog = "snapshot" }, { Text(stringResource(R.string.new_snapshot)) })
    }
    if (snaps.isNotEmpty()) SectionTitle(stringResource(R.string.snapshots))
    for (s in snaps) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.label.ifBlank { stringResource(R.string.snapshot) }, style = MaterialTheme.typography.bodyMedium)
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.timeMs)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton({ vm.restoreSnapshot(s) }) { Text(stringResource(R.string.restore)) }
            IconButton({ vm.deleteSnapshot(s); reload++ }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        }
    }
    SectionTitle(stringResource(R.string.watermark))
    val wm = project.watermark
    val h = R.string.hist_watermark
    SwitchLine(stringResource(R.string.watermark_enable), wm.enabled) { on -> vm.edit(h) { it.copy(watermark = it.watermark.copy(enabled = on, text = it.watermark.text.ifEmpty { if (it.watermark.imagePath.isEmpty()) "Vixa" else "" })) } }
    if (wm.enabled) {
        OutlinedTextField(wm.text, { t -> vm.edit(h, live = true) { it.copy(watermark = it.watermark.copy(text = t)) } }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true, label = { Text(stringResource(R.string.text)) })
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip({ pick(PickRequest(PickKind.FILE, exts = setOf("png", "jpg", "jpeg", "webp")) { f -> f.firstOrNull()?.let { p -> vm.edit(h) { it.copy(watermark = it.watermark.copy(imagePath = p)) } } }) }, { Text(stringResource(R.string.pick_logo)) })
            if (wm.imagePath.isNotEmpty()) AssistChip({ vm.edit(h) { it.copy(watermark = it.watermark.copy(imagePath = "")) } }, { Text(stringResource(R.string.remove_logo)) })
        }
        PreciseSlider(stringResource(R.string.position_x), wm.x, { v -> vm.edit(h, live = true) { it.copy(watermark = it.watermark.copy(x = v)) } }, range = 0f..1f, default = 0.85f, onFinished = { vm.commit() })
        PreciseSlider(stringResource(R.string.position_y), wm.y, { v -> vm.edit(h, live = true) { it.copy(watermark = it.watermark.copy(y = v)) } }, range = 0f..1f, default = 0.92f, onFinished = { vm.commit() })
        PreciseSlider(stringResource(R.string.size), wm.scale, { v -> vm.edit(h, live = true) { it.copy(watermark = it.watermark.copy(scale = v)) } }, range = 0.03f..0.6f, default = 0.15f, onFinished = { vm.commit() })
        PreciseSlider(stringResource(R.string.opacity), wm.opacity, { v -> vm.edit(h, live = true) { it.copy(watermark = it.watermark.copy(opacity = v)) } }, range = 0f..1f, default = 0.7f, onFinished = { vm.commit() })
    }
    when (dialog) {
        "rename" -> TextInputDialog(stringResource(R.string.rename), project.name, { dialog = "" }) { n -> vm.rename(n); dialog = "" }
        "saveas" -> TextInputDialog(stringResource(R.string.save_as), project.name + " 2", { dialog = "" }) { n -> vm.saveAs(n, onOpenProject); dialog = "" }
        "template" -> TextInputDialog(stringResource(R.string.save_project_template), project.name, { dialog = "" }) { n -> vm.saveAsTemplate(n); dialog = "" }
        "snapshot" -> TextInputDialog(stringResource(R.string.new_snapshot), "", { dialog = "" }) { n -> vm.saveSnapshot(n); dialog = ""; reload++ }
    }
}

@Composable
fun HistoryPanel(vm: EditorViewModel) {
    val tick by vm.historyTick.collectAsState()
    val list = remember(tick) { vm.historyList() }
    val pos = vm.historyPosition()
    Text(stringResource(R.string.history_hint), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (i in list.indices.reversed()) {
        val e = list[i]
        Row(
            Modifier.fillMaxWidth().clickable { vm.jumpTo(i) }.background(if (i == pos) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text((i + 1).toString() + ".  " + e.label, Modifier.weight(1f), color = if (i > pos) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface)
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.timeMs)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Botón de la fila de subtítulos: ocupa un tercio del ancho, icono encima y texto centrado debajo. */
@Composable
private fun RowScope.SubBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.FilledTonalButton(onClick, Modifier.weight(1f).height(64.dp), enabled = enabled, shape = RoundedCornerShape(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(20.dp))
            Text(label, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
