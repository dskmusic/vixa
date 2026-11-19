package com.dskmusic.vixa.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dskmusic.vixa.R
import com.dskmusic.vixa.ai.Cutout
import com.dskmusic.vixa.ai.Models
import com.dskmusic.vixa.ai.Vision
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.media.Importer
import com.dskmusic.vixa.media.MediaType
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.PersonCut
import com.dskmusic.vixa.model.StickerSpec
import com.dskmusic.vixa.model.StickerType
import com.dskmusic.vixa.model.replaceMedia
import com.dskmusic.vixa.ui.ColorRow
import com.dskmusic.vixa.ui.PreciseSlider
import com.dskmusic.vixa.ui.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Fondo de personas sin croma: detectar (máscara en caché) y ajustar en vivo el fondo, los bordes y el contorno. */
@Composable
fun PersonPage(vm: EditorViewModel, clip: Clip?, needs: (List<String>, () -> Unit) -> Unit) {
    if (clip == null || !vm.ai.canSegment(clip)) { Text(stringResource(R.string.ai_hint_visual), Modifier.padding(16.dp)); return }
    val store = vm.container.models
    val pc = clip.personCut
    var quality by remember { mutableStateOf(if (pc?.model == Models.SELFIE_HQ) "high" else "fast") }
    fun upd(live: Boolean = false, f: (PersonCut) -> PersonCut) =
        vm.updateClip(R.string.hist_ai_person, clip.id, live) { it.copy(personCut = it.personCut?.let(f)) }
    Text(stringResource(R.string.ai_person_note), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(quality == "fast", { quality = "fast" }, { Text(stringResource(R.string.ai_person_fast)) })
        FilterChip(quality == "high", { quality = "high" }, { Text(stringResource(R.string.ai_person_high)) }, enabled = store.unsupported(Models.SELFIE_HQ) == 0)
    }
    Button(
        { needs(listOf(if (quality == "high") Models.SELFIE_HQ else Models.SELFIE)) { vm.ai.personCut(clip, quality) } },
        Modifier.fillMaxWidth().padding(16.dp),
    ) { Text(stringResource(if (pc == null) R.string.ai_person_detect else R.string.ai_person_redo)) }
    if (pc == null) return
    SectionTitle(stringResource(R.string.ai_person_background))
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((mode, label) in listOf(1 to R.string.ai_person_transparent, 2 to R.string.ai_person_color, 3 to R.string.ai_person_blur, 0 to R.string.none)) {
            FilterChip(pc.mode == mode, { upd { it.copy(mode = mode) } }, { Text(stringResource(label)) })
        }
    }
    if (pc.mode == 1) Text(stringResource(R.string.ai_person_transparent_hint), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (pc.mode == 2) ColorRow(pc.color, { c -> upd { it.copy(color = c) } })
    PreciseSlider(stringResource(R.string.ai_person_edge), pc.edge, { v -> upd(true) { it.copy(edge = v) } }, range = 0f..1f, default = 0.5f, onFinished = { vm.commit() })
    if (pc.mode == 3) PreciseSlider(stringResource(R.string.ai_person_blur_amount), pc.blur, { v -> upd(true) { it.copy(blur = v) } }, range = 0f..1f, default = 0.6f, onFinished = { vm.commit() })
    PreciseSlider(stringResource(R.string.ai_person_outline), pc.outline, { v -> upd(true) { it.copy(outline = v) } }, range = 0f..1f, default = 0f, onFinished = { vm.commit() })
    if (pc.outline > 0f) ColorRow(pc.outlineColor, { c -> upd { it.copy(outlineColor = c) } })
    OutlinedButton({ vm.updateClip(R.string.hist_ai_person, clip.id) { it.copy(personCut = null) } }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { Text(stringResource(R.string.ai_remove_result)) }
}

private val FACE_EMOJIS = listOf("😎", "🥸", "🤡", "😀", "🎩", "👑", "🕶️", "🐶", "🐱", "🦄", "🎭", "⭐")

/** Sticker o emoji que sigue la cara del vídeo seleccionado. */
@Composable
fun FacePage(vm: EditorViewModel, clip: Clip?, pick: (PickRequest) -> Unit, needs: (List<String>, () -> Unit) -> Unit) {
    if (clip == null || clip.kind != ClipKind.VIDEO || clip.media == null) { Text(stringResource(R.string.ai_hint_video), Modifier.padding(16.dp)); return }
    var emoji by remember { mutableStateOf("😎") }
    var image by remember { mutableStateOf("") }
    var anchor by remember { mutableStateOf("head") }
    Text(stringResource(R.string.ai_face_note), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SectionTitle(stringResource(R.string.ai_face_sticker))
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (e in FACE_EMOJIS) FilterChip(image.isEmpty() && emoji == e, { emoji = e; image = "" }, { Text(e, fontSize = 20.sp) })
        FilterChip(image.isNotEmpty(), { pick(PickRequest(PickKind.MEDIA, AddTarget.STICKER, multi = false) { paths -> paths.firstOrNull()?.let { image = it } }) }, { Text(stringResource(R.string.ai_face_image)) })
    }
    SectionTitle(stringResource(R.string.ai_face_anchor))
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((id, label) in listOf("head" to R.string.ai_anchor_head, "eyes" to R.string.ai_anchor_eyes, "nose" to R.string.ai_anchor_nose, "mouth" to R.string.ai_anchor_mouth, "face" to R.string.ai_anchor_face)) {
            FilterChip(anchor == id, { anchor = id }, { Text(stringResource(label)) })
        }
    }
    Button(
        {
            needs(listOf(Models.FACE_MESH)) {
                val spec = if (image.isNotEmpty()) StickerSpec(StickerType.IMAGE, image) else StickerSpec(StickerType.EMOJI, emoji)
                vm.ai.faceSticker(clip, spec, anchor)
                vm.aiPage.value = ""
            }
        },
        Modifier.fillMaxWidth().padding(16.dp),
    ) { Text(stringResource(R.string.ai_start)) }
}

/**
 * Recortar sujeto en una foto: se toca el objeto, se ve el resultado sobre un fondo de cuadros, se refinan los bordes y
 * se añade como sticker (PNG con transparencia) o sustituye a la foto del clip. La foto original nunca se modifica.
 */
@Composable
fun CutoutDialog(vm: EditorViewModel, clip: Clip, onDismiss: () -> Unit) {
    val store = vm.container.models
    val scope = rememberCoroutineScope()
    val path = clip.media?.path ?: ""
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var mask by remember { mutableStateOf<Triple<FloatArray, Int, Int>?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var soft by remember { mutableFloatStateOf(0.3f) }
    var grow by remember { mutableFloatStateOf(0f) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var tap by remember { mutableStateOf<Offset?>(null) }
    // Avisos propios: los de vm.toast se pintan en el editor, debajo de este diálogo, y no se verían.
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(msg) { if (msg != null) { kotlinx.coroutines.delay(3500); msg = null } }
    LaunchedEffect(path) { bmp = withContext(Dispatchers.IO) { try { Vision.decode(path, 1024) } catch (e: Throwable) { ErrorLog.log("Cutout/decode", e); null } } }
    LaunchedEffect(mask, soft, grow) {
        val m = mask
        val b = bmp
        preview = if (m != null && b != null && !b.isRecycled) {
            try { withContext(Dispatchers.Default) { Cutout.render(b, m.first, m.second, m.third, soft, grow) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { ErrorLog.log("Cutout/preview", e); null }
        } else null
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ai_f_cutout), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.ai_cutout_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val shown = preview ?: bmp
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(8.dp)
                        .drawBehind {
                            // Fondo de cuadros: así se ve qué queda transparente.
                            val s = 16.dp.toPx()
                            var y = 0f
                            var row = 0
                            while (y < size.height) {
                                var x = 0f
                                var col = row
                                while (x < size.width) {
                                    drawRect(if (col % 2 == 0) Color(0xFF3A3A44) else Color(0xFF2A2A32), Offset(x, y), Size(s, s))
                                    x += s; col++
                                }
                                y += s; row++
                            }
                        }
                        .onSizeChanged { box = it }
                        .pointerInput(bmp) {
                            detectTapGestures { off ->
                                val b = bmp ?: return@detectTapGestures
                                // El dibujo es ContentScale.Fit: se descuentan las bandas vacías para obtener la posición en la foto.
                                val k = minOf(box.width.toFloat() / b.width, box.height.toFloat() / b.height)
                                val dw = b.width * k
                                val dh = b.height * k
                                val nx = (off.x - (box.width - dw) / 2f) / dw
                                val ny = (off.y - (box.height - dh) / 2f) / dh
                                if (nx !in 0f..1f || ny !in 0f..1f || busy) return@detectTapGestures
                                busy = true
                                tap = off
                                scope.launch {
                                    try {
                                        val r = Vision.subjectMask(store, b, nx, ny)
                                        // Si no se ha seleccionado nada (o casi todo), se avisa en vez de dejar la pantalla igual.
                                        val cover = r.first.count { it > 0.5f }.toFloat() / r.first.size.coerceAtLeast(1)
                                        if (cover < 0.004f || cover > 0.97f) msg = vm.str(R.string.ai_cutout_none) else mask = r
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Throwable) {
                                        ErrorLog.log("Cutout", e)
                                        msg = vm.str(R.string.err_generic)
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (shown != null) Image(shown.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    if (busy || shown == null) CircularProgressIndicator()
                    msg?.let { Text(it, Modifier.align(Alignment.BottomCenter).padding(8.dp).background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 8.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium) }
                    // Marca del último toque: confirma dónde se ha tocado.
                    tap?.let { t -> Canvas(Modifier.fillMaxSize()) { drawCircle(Color.Black, 14.dp.toPx(), t, style = androidx.compose.ui.graphics.drawscope.Stroke(5.dp.toPx())); drawCircle(Color.White, 14.dp.toPx(), t, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx())) } }
                }
                if (mask != null) {
                    PreciseSlider(stringResource(R.string.ai_cutout_soft), soft, { soft = it }, range = 0f..1f, default = 0.3f)
                    PreciseSlider(stringResource(R.string.ai_cutout_grow), grow, { grow = it }, range = -1f..1f, default = 0f)
                }
                FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onDismiss) { Text(stringResource(R.string.cancel)) }
                    for (asSticker in listOf(true, false)) {
                        Button(
                            {
                                val m = mask ?: return@Button
                                busy = true
                                scope.launch {
                                    try {
                                        // Se genera a mayor resolución (hasta 2048 px) y se guarda como medio del proyecto.
                                        val f = withContext(Dispatchers.IO) {
                                            val full = Vision.decode(path, 2048)
                                            val out = Cutout.render(full, m.first, m.second, m.third, soft, grow)
                                            File(Importer.mediaDir(store.context), "cut_" + System.currentTimeMillis() + ".png").also { Cutout.savePng(out, it) }
                                        }
                                        if (asSticker) vm.addSticker(StickerType.IMAGE, f.path)
                                        else {
                                            val ref = Importer.probe(store.context, f.path, MediaType.IMAGE)
                                            if (ref != null) vm.edit(R.string.hist_ai_cutout) { it.replaceMedia(clip.id, ref) }
                                        }
                                        onDismiss()
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Throwable) {
                                        ErrorLog.log("Cutout/save", e)
                                        msg = vm.str(R.string.err_generic)
                                        busy = false
                                    }
                                }
                            },
                            enabled = mask != null && !busy,
                        ) { Text(stringResource(if (asSticker) R.string.ai_cutout_sticker else R.string.ai_cutout_replace)) }
                    }
                }
            }
        }
    }
}
