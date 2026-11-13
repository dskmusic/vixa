package com.dskmusic.vixa.ui.editor

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.emoji2.emojipicker.EmojiPickerView
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.engine.ObjectTracker
import com.dskmusic.vixa.engine.OverlayRender
import com.dskmusic.vixa.media.ImageCache
import com.dskmusic.vixa.media.Importer
import com.dskmusic.vixa.media.MediaType
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.Easing
import com.dskmusic.vixa.model.FitMode
import com.dskmusic.vixa.model.Keyframe
import com.dskmusic.vixa.model.Layer
import com.dskmusic.vixa.model.ObjTrack
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.StickerSpec
import com.dskmusic.vixa.model.StickerType
import com.dskmusic.vixa.model.TextSpec
import com.dskmusic.vixa.model.Track
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.Transform
import com.dskmusic.vixa.model.addToTrack
import com.dskmusic.vixa.model.newId
import com.dskmusic.vixa.model.sourceOffsetAt
import com.dskmusic.vixa.model.trackType
import com.dskmusic.vixa.ui.AiJobBanner
import com.dskmusic.vixa.ui.ColorRow
import com.dskmusic.vixa.ui.PreciseSlider
import com.dskmusic.vixa.ui.SectionTitle
import com.dskmusic.vixa.ui.SwitchRowSimple
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Relación entre el fotograma del vídeo (coordenadas 0..1) y el lienzo del proyecto, con la posición, escala, estirado,
 * recorte, giro y espejo actuales del clip. Las posiciones del lienzo son desplazamientos desde el centro en fracción
 * del lienzo (los valores x e y de Transform).
 */
private class VideoMap(p: Project, val c: Clip) {
    private val tr = c.transform
    val cw = p.width.toFloat()
    val ch = p.height.toFloat()
    val ar: Float = (c.media?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1f)
    private val cropW = (1f - tr.cropL - tr.cropR).coerceAtLeast(0.01f)
    private val cropH = (1f - tr.cropT - tr.cropB).coerceAtLeast(0.01f)
    val bw: Float
    val bh: Float
    val qw: Float
    val qh: Float
    private val cs = cos(Math.toRadians(tr.rotation.toDouble())).toFloat()
    private val sn = sin(Math.toRadians(tr.rotation.toDouble())).toFloat()
    /** -1 si el vídeo está reflejado en un solo eje (el giro del objeto se ve al revés). */
    val flipSign: Float = if (tr.flipH != tr.flipV) -1f else 1f

    init {
        val srcAspect = (ar * cropW) / cropH
        val canvasAspect = cw / ch
        var a = 1f
        var b = 1f
        when (tr.fit) {
            FitMode.FIT -> if (srcAspect > canvasAspect) b = canvasAspect / srcAspect else a = srcAspect / canvasAspect
            FitMode.FILL -> if (srcAspect > canvasAspect) a = srcAspect / canvasAspect else b = canvasAspect / srcAspect
            FitMode.STRETCH -> {}
        }
        bw = a; bh = b
        qw = a * tr.scale * tr.sx
        qh = b * tr.scale * tr.sy
    }

    fun toCanvas(u: Float, v: Float): Pair<Float, Float> {
        var a = (u - tr.cropL) / cropW - 0.5f
        var b = (v - tr.cropT) / cropH - 0.5f
        if (tr.flipH) a = -a
        if (tr.flipV) b = -b
        val ox = a * qw * cw
        val oy = b * qh * ch
        return (tr.x + (ox * cs - oy * sn) / cw) to (tr.y + (ox * sn + oy * cs) / ch)
    }

    fun fromCanvas(x: Float, y: Float): Pair<Float, Float> {
        val rx = (x - tr.x) * cw
        val ry = (y - tr.y) * ch
        val ox = rx * cs + ry * sn
        val oy = -rx * sn + ry * cs
        var a = ox / (qw * cw)
        var b = oy / (qh * ch)
        if (tr.flipH) a = -a
        if (tr.flipV) b = -b
        return ((a + 0.5f) * cropW + tr.cropL) to ((b + 0.5f) * cropH + tr.cropT)
    }
}

/** Lo que se va a insertar y cómo: contenido, posición (original o relativa) y ajustes de tamaño, giro y opacidad. */
private class Placement(
    val type: Int, val text: String, val textColor: Int, val emoji: String, val path: String,
    val relative: Boolean, val hideLost: Boolean,
    /** Centro del elemento en el lienzo (desplazamiento desde el centro, fracción). Solo cuenta en modo relativo. */
    val ex: Float, val ey: Float, val scale: Float, val rotDeg: Float, val opacity: Float,
)

private const val T_TEXT = 0
private const val T_EMOJI = 1
private const val T_IMAGE = 2
private const val T_VIDEO = 3

/**
 * Construye el clip del elemento con keyframes de posición a partir del seguimiento. La posición relativa se guarda como
 * un vector en el fotograma respecto al objeto, que se mantiene constante mientras el objeto se mueve.
 */
private suspend fun buildElement(ctx: android.content.Context, p: Project, clip: Clip, ot: ObjTrack, track: ObjectTracker.Track, pl: Placement): Clip? {
    val map = VideoMap(p, clip)
    val ar = map.ar
    val n = track.size
    // Muestra del fotograma marcado.
    var i0 = 0
    for (i in 0 until n) if (kotlin.math.abs(track.times[i] - ot.startUs) < kotlin.math.abs(track.times[i0] - ot.startUs)) i0 = i
    val o0 = map.toCanvas(track.cx[i0], track.cy[i0])
    val e0 = if (pl.relative) (pl.ex to pl.ey) else o0
    // Vector objeto → elemento en el fotograma, en unidades de ancho del fotograma (así el giro es isotrópico).
    val (ue, ve) = map.fromCanvas(e0.first, e0.second)
    val dx = ue - track.cx[i0]
    val dy = (ve - track.cy[i0]) / ar
    // Fotogramas en los que el objeto no se ve (varios seguidos): el elemento se oculta con opacidad 0 en vez de quedarse parado.
    val visible = BooleanArray(n) { true }
    if (pl.hideLost) {
        var i = 0
        while (i < n) {
            if (track.found[i]) { i++; continue }
            var j = i
            while (j < n && !track.found[j]) j++
            if (j - i > 4) for (k in i until j) visible[k] = false
            i = j
        }
    }
    val xs = ArrayList<Keyframe>(); val ys = ArrayList<Keyframe>(); val os = ArrayList<Keyframe>()
    var lastT = -1L
    for (i in 0 until n) {
        // Solo posición: el elemento mantiene su distancia al objeto (el vector objeto → elemento no cambia).
        val (x, y) = map.toCanvas(track.cx[i] + dx, (track.cy[i] / ar + dy) * ar)
        val t = ((track.times[i] - clip.srcInUs) / clip.speed).toLong().coerceIn(0L, clip.durationUs)
        if (t == lastT) continue
        lastT = t
        xs.add(Keyframe(t, x.coerceIn(-0.75f, 0.75f), Easing.LINEAR)); ys.add(Keyframe(t, y.coerceIn(-0.75f, 0.75f), Easing.LINEAR))
        os.add(Keyframe(t, if (visible[i]) pl.opacity else 0f, Easing.LINEAR))
    }
    val kf = HashMap<String, List<Keyframe>>()
    kf["x"] = xs; kf["y"] = ys
    if (visible.any { !it }) kf["opacity"] = os
    val tf = Transform(x = xs.first().value, y = ys.first().value, scale = pl.scale, rotation = pl.rotDeg, opacity = pl.opacity)
    return when (pl.type) {
        T_TEXT -> Clip(kind = ClipKind.TEXT, startUs = clip.startUs, durationUs = clip.durationUs, text = TextSpec(listOf(Layer(text = pl.text, color = pl.textColor))), transform = tf, keyframes = kf)
        T_EMOJI -> Clip(kind = ClipKind.STICKER, startUs = clip.startUs, durationUs = clip.durationUs, sticker = StickerSpec(StickerType.EMOJI, pl.emoji, size = 0.25f), transform = tf, keyframes = kf)
        else -> {
            val kind = Importer.typeOf(pl.path)
            if (kind == MediaType.VIDEO) {
                val ref = Importer.probe(ctx, pl.path, MediaType.VIDEO) ?: return null
                val base = Importer.clipFor(ref, 0)
                val dur = minOf(ref.durationUs, clip.durationUs).coerceAtLeast(100_000L)
                base.copy(startUs = clip.startUs, srcOutUs = dur, durationUs = dur, muted = true, transform = tf, keyframes = kf)
            } else {
                val animated = ImageCache.isAnimatedFile(pl.path)
                Clip(
                    kind = ClipKind.STICKER, startUs = clip.startUs, durationUs = clip.durationUs,
                    sticker = StickerSpec(if (animated) StickerType.ANIMATED else StickerType.IMAGE, pl.path, size = 0.25f), transform = tf, keyframes = kf,
                )
            }
        }
    }
}

/** Fotograma (de un instante del medio) reducido a 1024 px, para marcar el objeto y para colocar el elemento. */
private fun frameAt(path: String, srcUs: Long): Bitmap? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(path)
        val raw = r.getFrameAtTime(srcUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val k = minOf(1f, 1024f / maxOf(raw.width, raw.height))
        if (k < 1f) Bitmap.createScaledBitmap(raw, (raw.width * k).toInt(), (raw.height * k).toInt(), true) else raw
    } catch (e: Throwable) {
        ErrorLog.log("Track/frame", e)
        null
    } finally {
        r.release()
    }
}

/**
 * Seguimiento de objetos en dos fases. 1) Analizar: se toca el objeto y se calcula su posición, tamaño y giro en todo el
 * clip; el análisis queda guardado en el clip. 2) Insertar: se elige el contenido (texto, emoji, sticker, imagen, GIF o
 * vídeo), se coloca con los dedos y se decide si va en el punto exacto del objeto o en una posición relativa a él.
 * El resultado es un clip normal con keyframes, editables.
 */
@Composable
fun TrackDialog(vm: EditorViewModel, clip: Clip, pick: (PickRequest) -> Unit, onDismiss: () -> Unit) {
    val container = vm.container
    val project by vm.project.collectAsState()
    val c = project?.clip(clip.id) ?: clip
    val path = c.media?.path ?: ""
    var screen by remember { mutableStateOf(if (c.objTracks.isEmpty()) "analyze" else "list") }
    var activeId by remember { mutableStateOf("") }
    val running by container.aiJobs.state.collectAsState()

    Dialog(onDismissRequest = { if (running == null) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ if (running == null) onDismiss() }) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    Text(stringResource(R.string.track_object), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                }
                when (screen) {
                    "list" -> TrackList(vm, c, { id -> activeId = id; screen = "insert" }, { screen = "analyze" })
                    "analyze" -> TrackAnalyze(vm, c, path) { id -> activeId = id; screen = "insert" }
                    else -> {
                        val ot = c.objTracks.firstOrNull { it.id == activeId }
                        if (ot == null) { screen = "list" } else TrackInsert(vm, c, ot, pick, { screen = "list" }, onDismiss)
                    }
                }
            }
        }
    }
}

/** Lista de seguimientos del clip: insertar objetos con uno ya analizado, borrarlo o hacer otro. */
@Composable
private fun TrackList(vm: EditorViewModel, c: Clip, onInsert: (String) -> Unit, onNew: () -> Unit) {
    Text(stringResource(R.string.track_list_note), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        for (t in c.objTracks) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Button({ onInsert(t.id) }) { Text(stringResource(R.string.track_insert)) }
                IconButton({ vm.updateClip(R.string.hist_track_object, c.id) { it.copy(objTracks = it.objTracks.filter { x -> x.id != t.id }) }; try { File(t.path).delete() } catch (e: Throwable) { } }) {
                    Icon(Icons.Default.Delete, stringResource(R.string.delete))
                }
            }
        }
        OutlinedButton(onNew, Modifier.padding(16.dp)) { Text(stringResource(R.string.track_new)) }
    }
}

/** Fase 1: se muestra el fotograma del cabezal, se toca el objeto, se ajusta su tamaño y se analiza. */
@Composable
private fun ColumnScope.TrackAnalyze(vm: EditorViewModel, c: Clip, path: String, onDone: (String) -> Unit) {
    val container = vm.container
    val local = (vm.player.positionUs.value - c.startUs).coerceIn(0L, c.durationUs)
    val startSrc = (c.srcInUs + c.sourceOffsetAt(local)).coerceIn(c.srcInUs, c.srcOutUs)
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var point by remember { mutableStateOf<Offset?>(null) }
    var objSize by remember { mutableFloatStateOf(0.2f) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val job by container.aiJobs.state.collectAsState()
    var running by remember { mutableStateOf(false) }
    LaunchedEffect(path, startSrc) { bmp = withContext(Dispatchers.IO) { frameAt(path, startSrc) } }
    LaunchedEffect(job) { if (job == null) running = false }
    Text(stringResource(R.string.track_hint), Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box(
        Modifier.weight(1f).fillMaxWidth().padding(8.dp).onSizeChanged { box = it }
            .pointerInput(bmp, running) {
                detectTapGestures { off ->
                    val b = bmp ?: return@detectTapGestures
                    if (running) return@detectTapGestures
                    val k = minOf(box.width.toFloat() / b.width, box.height.toFloat() / b.height)
                    val dw = b.width * k
                    val dh = b.height * k
                    val nx = (off.x - (box.width - dw) / 2f) / dw
                    val ny = (off.y - (box.height - dh) / 2f) / dh
                    if (nx in 0f..1f && ny in 0f..1f) point = Offset(nx, ny)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b == null) CircularProgressIndicator()
        else {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            // Recuadro del objeto marcado (su tamaño es el del deslizador).
            Canvas(Modifier.fillMaxSize()) {
                val pt = point ?: return@Canvas
                val k = minOf(this.size.width / b.width, this.size.height / b.height)
                val dw = b.width * k
                val dh = b.height * k
                val side = objSize * dw
                val cx = (this.size.width - dw) / 2f + pt.x * dw
                val cy = (this.size.height - dh) / 2f + pt.y * dh
                drawRect(Color.Black, Offset(cx - side / 2 - 2f, cy - side / 2 - 2f), Size(side + 4f, side + 4f), style = Stroke(5f))
                drawRect(Color(0xFFFFD60A), Offset(cx - side / 2, cy - side / 2), Size(side, side), style = Stroke(3f))
            }
        }
    }
    AiJobBanner(container)
    PreciseSlider(stringResource(R.string.track_size), objSize, { objSize = it }, range = 0.05f..0.5f, default = 0.2f, format = { (it * 100).toInt().toString() + " %" }, inputScale = 100f)
    Button(
        {
            val pt = point ?: return@Button
            running = true
            val id = newId()
            val file = File(container.models.context.filesDir, "tracks/$id.trk")
            container.aiJobs.run(vm.str(R.string.track_job), { progress ->
                ObjectTracker.track(path, c.srcInUs, c.srcOutUs, startSrc, pt.x, pt.y, objSize, progress)?.also { it.save(file) }
            }) { t ->
                running = false
                if (t == null) vm.toast(vm.str(R.string.track_none))
                else {
                    val ot = ObjTrack(id, vm.str(R.string.track_name, c.objTracks.size + 1), file.path, c.srcInUs, c.srcOutUs, startSrc, pt.x, pt.y, objSize)
                    vm.updateClip(R.string.hist_track_object, c.id) { it.copy(objTracks = it.objTracks + ot) }
                    onDone(id)
                }
            }
        },
        Modifier.fillMaxWidth().padding(16.dp), enabled = point != null && !running,
    ) { Text(stringResource(R.string.track_start)) }
}

/** Fase 2: elegir y personalizar el contenido, colocarlo (con los dedos) y añadirlo como clip con keyframes. */
@Composable
private fun ColumnScope.TrackInsert(vm: EditorViewModel, c: Clip, ot: ObjTrack, pick: (PickRequest) -> Unit, onBack: () -> Unit, onClose: () -> Unit) {
    val container = vm.container
    val ctx = container.models.context
    val project = vm.project.value ?: return
    val map = remember(c.transform, project.width, project.height) { VideoMap(project, c) }
    var trk by remember(ot.id) { mutableStateOf<ObjectTracker.Track?>(null) }
    var frame by remember(ot.id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(ot.id) { mutableStateOf(false) }
    val job by container.aiJobs.state.collectAsState()
    LaunchedEffect(ot.id, job == null) {
        if (trk == null) {
            trk = withContext(Dispatchers.IO) { ObjectTracker.Track.load(File(ot.path)) }
            if (trk == null && job == null && !failed) {
                // El archivo del análisis no está (caché borrada): se vuelve a calcular con los mismos datos.
                failed = true
                container.aiJobs.run(vm.str(R.string.track_job), { progress ->
                    ObjectTracker.track(c.media?.path ?: "", ot.fromUs, ot.toUs, ot.startUs, ot.u, ot.v, ot.size, progress)?.also { it.save(File(ot.path)) }
                }) { t -> if (t != null) { trk = t } else vm.toast(vm.str(R.string.track_none)) }
            }
        }
    }
    LaunchedEffect(ot.id) { frame = withContext(Dispatchers.IO) { frameAt(c.media?.path ?: "", ot.startUs) } }

    var type by remember { mutableIntStateOf(T_TEXT) }
    var text by remember { mutableStateOf(vm.str(R.string.default_text)) }
    var textColor by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    var emoji by remember { mutableStateOf("😎") }
    var imagePath by remember { mutableStateOf("") }
    var videoPath by remember { mutableStateOf("") }
    var videoFrame by remember { mutableStateOf<Bitmap?>(null) }
    var relative by remember { mutableStateOf(false) }
    var hideLost by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var rotDeg by remember { mutableFloatStateOf(0f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    // Centro del objeto en el lienzo en el fotograma marcado y centro del elemento (desplazamientos desde el centro).
    val o0 = map.toCanvas(ot.u, ot.v)
    var ex by remember(ot.id) { mutableFloatStateOf(o0.first) }
    var ey by remember(ot.id) { mutableFloatStateOf(o0.second) }
    var added by remember { mutableIntStateOf(0) }
    val renderer = remember { OverlayRender() }
    var tick by remember { mutableIntStateOf(0) }
    val path = if (type == T_VIDEO) videoPath else imagePath
    LaunchedEffect(path, type) {
        if (path.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            if (type == T_VIDEO && Importer.typeOf(path) == MediaType.VIDEO) videoFrame = frameAt(path, 0) else { ImageCache.still(path); if (ImageCache.isAnimatedFile(path)) ImageCache.load(path) }
        }
        tick++
    }

    // Elemento de la vista previa (texto, emoji, imagen o GIF) como un proyecto temporal que se dibuja con el mismo motor que el vídeo final.
    fun previewClip(): Clip? {
        val tf = Transform(x = if (relative) ex else o0.first, y = if (relative) ey else o0.second, scale = scale, rotation = rotDeg, opacity = opacity)
        return when (type) {
            T_TEXT -> Clip(kind = ClipKind.TEXT, durationUs = 1_000_000L, text = TextSpec(listOf(Layer(text = text.ifBlank { " " }, color = textColor))), transform = tf)
            T_EMOJI -> Clip(kind = ClipKind.STICKER, durationUs = 1_000_000L, sticker = StickerSpec(StickerType.EMOJI, emoji, size = 0.25f), transform = tf)
            T_IMAGE -> if (imagePath.isEmpty()) null else Clip(kind = ClipKind.STICKER, durationUs = 1_000_000L, sticker = StickerSpec(if (ImageCache.isAnimatedFile(imagePath)) StickerType.ANIMATED else StickerType.IMAGE, imagePath, size = 0.25f), transform = tf)
            else -> if (videoPath.isNotEmpty() && Importer.typeOf(videoPath) != MediaType.VIDEO) Clip(kind = ClipKind.STICKER, durationUs = 1_000_000L, sticker = StickerSpec(StickerType.IMAGE, videoPath, size = 0.25f), transform = tf) else null
        }
    }

    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onBack) { Text(ot.name) }
        if (added > 0) Text(stringResource(R.string.track_added, added), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    // Vista previa del lienzo: el fotograma del análisis colocado como en el proyecto, el punto del objeto y el elemento.
    Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        var boxSize by remember { mutableStateOf(IntSize.Zero) }
        Canvas(
            Modifier.aspectRatio(project.width.toFloat() / project.height).onSizeChanged { boxSize = it }
                .pointerInput(relative, type) {
                    detectTransformGestures { _, pan, zoom, rotation ->
                        if (pan.x != 0f || pan.y != 0f) {
                            // Arrastrar mueve el elemento: pasa a posición relativa (la original lo deja sobre el objeto).
                            if (!relative) { relative = true; ex = o0.first; ey = o0.second }
                            ex += pan.x / size.width
                            ey += pan.y / size.height
                        }
                        scale = (scale * zoom).coerceIn(0.05f, 12f)
                        rotDeg += rotation
                    }
                },
        ) {
            val k = size.width / project.width
            val fr = frame
            @Suppress("UNUSED_VARIABLE") val t = tick
            drawRect(Color.Black)
            drawIntoCanvas { cv ->
                val nc = cv.nativeCanvas
                nc.save()
                nc.scale(k, k)
                if (fr != null) {
                    val tr = c.transform
                    val cw = project.width.toFloat()
                    val ch = project.height.toFloat()
                    nc.translate((0.5f + tr.x) * cw, (0.5f + tr.y) * ch)
                    nc.rotate(tr.rotation)
                    nc.scale(if (tr.flipH) -1f else 1f, if (tr.flipV) -1f else 1f)
                    val src = Rect((tr.cropL * fr.width).toInt(), (tr.cropT * fr.height).toInt(), ((1f - tr.cropR) * fr.width).toInt(), ((1f - tr.cropB) * fr.height).toInt())
                    val dst = RectF(-map.qw * cw / 2f, -map.qh * ch / 2f, map.qw * cw / 2f, map.qh * ch / 2f)
                    nc.drawBitmap(fr, src, dst, null)
                }
                nc.restore()
                val pc = previewClip()
                if (pc != null) {
                    nc.save()
                    nc.scale(k, k)
                    val tp = Project(width = project.width, height = project.height, tracks = listOf(Track(type = pc.kind.trackType(), clips = listOf(pc))))
                    try { renderer.drawAll(nc, tp, 0L, project.width, project.height) } catch (e: Throwable) { ErrorLog.log("Track/preview", e) }
                    nc.restore()
                } else if (type == T_VIDEO && videoFrame != null) {
                    // Vídeo: se muestra su primer fotograma con la misma colocación (cabe en el lienzo × escala).
                    val vf = videoFrame!!
                    val cw = project.width.toFloat()
                    val ch = project.height.toFloat()
                    val fit = minOf(cw / vf.width, ch / vf.height)
                    nc.save()
                    nc.scale(k, k)
                    nc.translate((0.5f + (if (relative) ex else o0.first)) * cw, (0.5f + (if (relative) ey else o0.second)) * ch)
                    nc.rotate(rotDeg)
                    nc.scale(scale, scale)
                    val p = Paint().apply { alpha = (opacity * 255).toInt() }
                    nc.drawBitmap(vf, null, RectF(-vf.width * fit / 2f, -vf.height * fit / 2f, vf.width * fit / 2f, vf.height * fit / 2f), p)
                    nc.restore()
                }
            }
            // Punto del objeto (donde se marcó).
            val ox = (0.5f + o0.first) * size.width
            val oy = (0.5f + o0.second) * size.height
            drawCircle(Color.Black, 9f, Offset(ox, oy), style = Stroke(5f))
            drawCircle(Color(0xFFFFD60A), 9f, Offset(ox, oy), style = Stroke(3f))
            drawLine(Color(0xFFFFD60A), Offset(ox - 16f, oy), Offset(ox + 16f, oy), strokeWidth = 2f)
            drawLine(Color(0xFFFFD60A), Offset(ox, oy - 16f), Offset(ox, oy + 16f), strokeWidth = 2f)
        }
        if (trk == null) CircularProgressIndicator()
    }
    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        AiJobBanner(container)
        SectionTitle(stringResource(R.string.track_content))
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((id, label) in listOf(T_TEXT to R.string.track_c_text, T_EMOJI to R.string.track_c_emoji, T_IMAGE to R.string.track_c_image, T_VIDEO to R.string.track_c_video)) {
                FilterChip(type == id, { type = id }, { Text(stringResource(label)) })
            }
        }
        when (type) {
            T_TEXT -> {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), label = { Text(stringResource(R.string.ai_tts_text)) }, minLines = 1, maxLines = 4)
                ColorRow(textColor, { textColor = it })
            }
            T_EMOJI -> {
                Text(emoji, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.headlineMedium)
                AndroidView(
                    // El contenedor impide que el scroll del panel le robe los gestos a la lista de emojis.
                    factory = { x ->
                        object : android.widget.FrameLayout(x) {
                            override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
                                parent?.requestDisallowInterceptTouchEvent(true)
                                return super.dispatchTouchEvent(e)
                            }
                        }.apply { addView(EmojiPickerView(x).apply { emojiGridColumns = 8; setOnEmojiPickedListener { item -> emoji = item.emoji } }) }
                    },
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                )
            }
            T_IMAGE -> Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ pick(PickRequest(PickKind.MEDIA, AddTarget.STICKER, multi = false) { paths -> paths.firstOrNull()?.let { imagePath = it } }) }) { Text(stringResource(R.string.track_pick)) }
                if (imagePath.isNotEmpty()) Text(File(imagePath).name, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
            else -> Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ pick(PickRequest(PickKind.MEDIA, AddTarget.OVERLAY, multi = false) { paths -> paths.firstOrNull()?.let { videoPath = it; videoFrame = null } }) }) { Text(stringResource(R.string.track_pick)) }
                if (videoPath.isNotEmpty()) Text(File(videoPath).name, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionTitle(stringResource(R.string.track_position))
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!relative, { relative = false }, { Text(stringResource(R.string.track_pos_original)) })
            FilterChip(relative, { relative = true }, { Text(stringResource(R.string.track_pos_relative)) })
        }
        Text(stringResource(R.string.track_gesture_hint), Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRowSimple(stringResource(R.string.track_hide_lost), hideLost) { hideLost = it }
        PreciseSlider(stringResource(R.string.size), scale, { scale = it }, range = 0.05f..6f, default = 1f, format = { String.format(java.util.Locale.US, "×%.2f", it) }, inputScale = 1f)
        PreciseSlider(stringResource(R.string.rotation), rotDeg, { rotDeg = it }, range = -180f..180f, default = 0f, format = { String.format(java.util.Locale.US, "%.0f°", it) }, inputScale = 1f)
        PreciseSlider(stringResource(R.string.opacity), opacity, { opacity = it }, range = 0f..1f, default = 1f)
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClose) { Text(stringResource(R.string.close)) }
            val ready = trk != null && (type == T_TEXT && text.isNotBlank() || type == T_EMOJI || type == T_IMAGE && imagePath.isNotEmpty() || type == T_VIDEO && videoPath.isNotEmpty())
            Button(
                {
                    val t = trk ?: return@Button
                    val pl = Placement(type, text, textColor, emoji, if (type == T_VIDEO) videoPath else imagePath, relative, hideLost, ex, ey, scale, rotDeg, opacity)
                    vm.launch {
                        val el = try { buildElement(ctx, project, c, ot, t, pl) } catch (e: Throwable) { ErrorLog.log("Track/build", e); null }
                        if (el == null) vm.toast(vm.str(R.string.err_generic))
                        else {
                            vm.edit(R.string.hist_track_object) { it.addToTrack(el, el.kind.trackType()) }
                            vm.selectOnly(el.id)
                            added++
                        }
                    }
                },
                enabled = ready,
            ) { Text(stringResource(R.string.track_add)) }
        }
    }
}
