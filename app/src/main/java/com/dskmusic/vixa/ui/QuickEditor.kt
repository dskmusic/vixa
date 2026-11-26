package com.dskmusic.vixa.ui

import android.graphics.Bitmap
import android.net.Uri
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil3.compose.AsyncImage
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.engine.FrameStrip
import com.dskmusic.vixa.engine.GifEncoder
import com.dskmusic.vixa.engine.GifLossy
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.ExportKind
import com.dskmusic.vixa.model.ExportSettings
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.normalized
import com.dskmusic.vixa.model.updateClip
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Vídeo único de un proyecto rápido (herramientas de Inicio): lo que estas pantallas pueden recortar. */
class QuickMedia(val clipId: String, val path: String, val offsetUs: Long, val durUs: Long, val w: Int, val h: Int, val fps: Int)

fun quickMediaOf(p: Project): QuickMedia? {
    val c = p.allClips().singleOrNull() ?: return null
    val m = c.media ?: return null
    if (c.startUs != 0L || c.kind != ClipKind.VIDEO || c.speed != 1f || c.speedCurve.isNotEmpty() || c.reversed || !File(m.path).exists() || m.width <= 0 || c.srcSpanUs < 500_000L) return null
    return QuickMedia(c.id, m.path, c.srcInUs, c.srcSpanUs, m.width, m.height, Math.round(m.fps).coerceIn(5, 60))
}

/**
 * Selector de tramo para el diálogo de exportar (un solo vídeo): la tira con tiradores, zoom de dos dedos, arrastre de la selección
 * y ajuste fino al fotograma, igual que en Recortar y GIF. [onRange] recibe el inicio y el fin (tiempo del proyecto).
 */
@Composable
fun RangeTrimEditor(qm: QuickMedia, startUs: Long, endUs: Long, onRange: (Long, Long) -> Unit) {
    val dur = qm.durUs
    val frameUs = 1_000_000L / qm.fps
    val minGap = max(frameUs * 2, 100_000L)
    var winStart by remember(dur) { mutableLongStateOf(0L) }
    var winEnd by remember(dur) { mutableLongStateOf(dur) }
    val baseFilm = rememberFilmstrip(qm.path, qm.offsetUs, 0L, dur)
    val detailFilm = rememberFilmstrip(qm.path, qm.offsetUs, winStart, winEnd, enabled = winEnd - winStart < dur)
    Column(Modifier.fillMaxWidth()) {
        TrimBar(baseFilm, detailFilm, winStart, winEnd, { a, b -> winStart = a; winEnd = b }, dur, startUs, endUs, -1L, minGap, onRange, {}, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        Text(stringResource(R.string.qe_trim_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FineAdjust(stringResource(R.string.qe_start), startUs, frameUs, { onRange(it.coerceIn(0L, endUs - minGap), endUs) })
        FineAdjust(stringResource(R.string.qe_end), endUs, frameUs, { onRange(startUs, it.coerceIn(startUs + minGap, dur)) })
    }
}

/** El proyecto con el tramo [startUs, endUs] y el encuadre aplicados (el lienzo toma la proporción del recorte). */
fun quickApply(p: Project, qm: QuickMedia, startUs: Long, endUs: Long, crop: CropRect): Project {
    val np = p.updateClip(qm.clipId) { c ->
        c.copy(
            srcInUs = qm.offsetUs + startUs, srcOutUs = qm.offsetUs + endUs, durationUs = endUs - startUs,
            transform = c.transform.copy(cropL = crop.l, cropR = crop.r, cropT = crop.t, cropB = crop.b),
        )
    }
    val cw = ((qm.w * crop.w).toInt() / 2 * 2).coerceAtLeast(2)
    val ch = ((qm.h * crop.h).toInt() / 2 * 2).coerceAtLeast(2)
    return np.copy(width = cw, height = ch).normalized()
}

private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

/** Recorta [src] al encuadre y lo escala a [gw]×[gh] (siempre una copia nueva si [levels] > 0, para poder tocar sus píxeles). */
private fun cropScale(src: Bitmap, crop: CropRect, gw: Int, gh: Int): Bitmap {
    val x = (crop.l * src.width).toInt().coerceIn(0, src.width - 1)
    val y = (crop.t * src.height).toInt().coerceIn(0, src.height - 1)
    val cw = (crop.w * src.width).toInt().coerceIn(1, src.width - x)
    val ch = (crop.h * src.height).toInt().coerceIn(1, src.height - y)
    return Bitmap.createScaledBitmap(Bitmap.createBitmap(src, x, y, cw, ch), gw, gh, true)
}

/**
 * Un fotograma como lo verías en el GIF, pero barato: recorte, ancho y, con compresión media o fuerte, una reducción de colores
 * (con trama ordenada si el tramado está activo). Es una aproximación inmediata; el GIF exacto llega poco después.
 */
private fun liveFrame(src: Bitmap, crop: CropRect, gw: Int, comp: Int, dither: Boolean): Bitmap {
    val cw = (crop.w * src.width).toInt().coerceAtLeast(1)
    val ch = (crop.h * src.height).toInt().coerceAtLeast(1)
    val ow = min(gw, cw).coerceAtLeast(2)
    val oh = (ow.toLong() * ch / cw).toInt().coerceAtLeast(2)
    val levels = when (comp) { 2 -> 5; 3 -> 4; else -> 0 }
    val scaled = cropScale(src, crop, ow, oh)
    if (levels == 0) return scaled
    val s = scaled.copy(Bitmap.Config.ARGB_8888, true)
    val px = IntArray(ow * oh)
    s.getPixels(px, 0, ow, 0, 0, ow, oh)
    val step = 255f / (levels - 1)
    for (j in px.indices) {
        val t = if (dither) (BAYER[((j / ow) and 3) * 4 + ((j % ow) and 3)] / 16f - 0.5f) * step else 0f
        val p = px[j]
        val r = (Math.round((((p shr 16) and 255) + t) / step) * step).toInt().coerceIn(0, 255)
        val g = (Math.round((((p shr 8) and 255) + t) / step) * step).toInt().coerceIn(0, 255)
        val b = (Math.round(((p and 255) + t) / step) * step).toInt().coerceIn(0, 255)
        px[j] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    s.setPixels(px, 0, ow, 0, 0, ow, oh)
    return s
}

/** Índices (sobre los fotogramas guardados) que se reproducen a [fps], con la vuelta si es boomerang (sin repetir el primero ni el último). */
private fun playSequence(count: Int, stepUs: Long, fps: Int, boomerang: Boolean): List<Int> {
    val desired = 1_000_000.0 / fps
    val fwd = ArrayList<Int>()
    var k = 0
    while (true) {
        val i = Math.round(k * desired / stepUs).toInt()
        if (i >= count) break
        fwd.add(i)
        k++
    }
    if (fwd.isEmpty()) fwd.add(0)
    return if (boomerang && fwd.size > 2) fwd + fwd.subList(1, fwd.size - 1).reversed() else fwd
}

/**
 * GIF exacto con los ajustes actuales, codificado a partir de los fotogramas que ya están en memoria (no hay que volver a decodificar).
 * Sirve para ver el resultado real y dar el tamaño de verdad. Limitado a 480 px de ancho. Devuelve el archivo y su número de fotogramas.
 */
private fun buildExactGif(
    context: android.content.Context, frames: List<Bitmap>, stepUs: Long, crop: CropRect, gw0: Int, gh0: Int, fps: Int,
    boomerang: Boolean, comp: Int, dither: Boolean, loop: Boolean, speed: Float,
): Pair<File, Int>? {
    val k = min(1f, 480f / gw0)
    val gw = (gw0 * k).toInt() / 2 * 2
    val gh = (gh0 * k).toInt() / 2 * 2
    if (gw < 2 || gh < 2 || frames.isEmpty()) return null
    val seq = playSequence(frames.size, stepUs, fps, boomerang)
    val dir = File(context.cacheDir, "gifprev").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val f = File(dir, "p" + System.nanoTime() + ".gif")
    FileOutputStream(f).buffered().use { os ->
        val enc = GifEncoder(os, gw, gh, fps, if (GifLossy.available && comp >= 1) 0 else comp, dither, loop, speed)
        for (i in seq) enc.addFrame(cropScale(frames[i], crop, gw, gh))
        enc.finish()
    }
    // El mismo paso de compresión que la exportación: el aspecto y el tamaño de la vista previa son los del archivo final.
    GifLossy.shrink(f, comp)
    return f to seq.size
}

/**
 * Vista previa del GIF "en caliente": reproduce los fotogramas ya decodificados con fps, velocidad, boomerang y bucle al instante;
 * ancho, encuadre y compresión se aplican a cada fotograma (preparados en segundo plano, con caché) para que no se entrecorte.
 */
@Composable
private fun LivePreview(
    frames: List<Bitmap>, stepUs: Long, crop: CropRect, gw: Int, fps: Int, speed: Float, boomerang: Boolean, loop: Boolean, comp: Int, dither: Boolean, modifier: Modifier,
    sharedCache: java.util.concurrent.ConcurrentHashMap<Int, ImageBitmap>? = null,
    playing: Boolean = true,
    onPos: ((Long) -> Unit)? = null,
) {
    val pl by rememberUpdatedState(playing)
    val posCb by rememberUpdatedState(onPos)
    val seq = remember(frames, stepUs, fps, boomerang) { playSequence(frames.size, stepUs, fps, boomerang) }
    val delayMs = (1000.0 / fps / speed).toLong().coerceAtLeast(20L)
    // Con caché compartida (ya calentada en segundo plano por el editor) los fotogramas están listos antes de verlos.
    val cache = sharedCache ?: remember(frames, crop, gw, comp, dither) { java.util.concurrent.ConcurrentHashMap<Int, ImageBitmap>() }
    LaunchedEffect(cache, seq) {
        withContext(Dispatchers.Default) {
            for (idx in seq.distinct()) {
                if (!isActive) break
                cache.getOrPut(idx) { liveFrame(frames[idx], crop, gw, comp, dither).asImageBitmap() }
            }
        }
    }
    // Se conserva el último fotograma al cambiar de secuencia (p. ej. al activar el boomerang): sin parpadeo en blanco.
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(seq, delayMs, loop, cache) {
        var k = 0
        while (true) {
            while (!pl) delay(50)
            val idx = seq[k]
            shown = cache.getOrPut(idx) { liveFrame(frames[idx], crop, gw, comp, dither).asImageBitmap() }
            // El cabezal y la hora siguen al fotograma que se ve (en el boomerang, también de vuelta).
            posCb?.invoke(idx * stepUs)
            delay(delayMs)
            k++
            if (k >= seq.size) {
                if (loop) k = 0 else while (true) delay(1_000_000L)
            }
        }
    }
    Canvas(modifier) {
        shown?.let { drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low) }
    }
}

/** Reproductor mudo del vídeo original: en bucle entre [start] y [end], con salto a [seekReq] cuando cambia. */
@Composable
private fun QuickPlayer(path: String, offsetUs: Long, start: Long, end: Long, speed: Float, playing: Boolean, seekReq: Pair<Long, Int>, onPos: (Long) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            volume = 0f
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }
    LaunchedEffect(playing) { player.playWhenReady = playing }
    LaunchedEffect(seekReq) { player.seekTo((offsetUs + seekReq.first) / 1000) }
    val st by rememberUpdatedState(start)
    val en by rememberUpdatedState(end)
    val pl by rememberUpdatedState(playing)
    val cb by rememberUpdatedState(onPos)
    LaunchedEffect(player) {
        while (true) {
            delay(30)
            val p = player.currentPosition * 1000 - offsetUs
            if (pl && (p >= en || p < st - 150_000L)) player.seekTo((offsetUs + st) / 1000)
            cb(p)
        }
    }
    AndroidView(factory = { SurfaceView(it) }, update = { player.setVideoSurfaceView(it) }, modifier = modifier)
}

/**
 * Pantalla completa de las herramientas rápidas GIF y Recortar: vista previa en bucle, tira de fotogramas con tiradores y ajuste
 * fino, encuadre con presets y (GIF) ajustes del archivo. [gif] = true exporta el GIF al confirmar; si no, devuelve el proyecto
 * recortado para que siga el diálogo de exportación normal.
 */
@Composable
fun QuickEditorDialog(container: AppContainer, project: Project, gif: Boolean, onDismiss: () -> Unit, onWhatsapp: ((Project) -> Unit)? = null, onDone: (Project) -> Unit) {
    val qm = remember(project) { quickMediaOf(project) }
    if (qm == null) { onDismiss(); return }
    val context = LocalContext.current
    val saved = remember { container.settings.value.export }
    val dur = qm.durUs
    val frameUs = 1_000_000L / qm.fps
    val minGap = max(frameUs * 2, 100_000L)
    // Zoom de la tira y sus fotogramas: viven aquí (no en la pestaña) para que no se pierdan al cambiar de pestaña y volver.
    var winStart by remember(dur) { mutableLongStateOf(0L) }
    var winEnd by remember(dur) { mutableLongStateOf(dur) }
    val baseFilm = rememberFilmstrip(qm.path, qm.offsetUs, 0L, dur)
    val detailFilm = rememberFilmstrip(qm.path, qm.offsetUs, winStart, winEnd, enabled = winEnd - winStart < dur)
    var start by remember { mutableLongStateOf(0L) }
    var end by remember { mutableLongStateOf(if (gif) min(dur, 5_000_000L) else dur) }
    var pos by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var seekReq by remember { mutableStateOf(0L to 0) }
    var tab by remember { mutableIntStateOf(0) }
    // Recortar: exportar en partes de 60 s como mucho (estados de WhatsApp), por orden.
    var whatsapp by remember { mutableStateOf(false) }
    var crop by remember { mutableStateOf(CropRect()) }
    var ratioId by remember { mutableStateOf("free") }
    var boomerang by remember { mutableStateOf(saved.boomerang) }
    var speed by remember { mutableFloatStateOf(saved.gifSpeed) }
    var width by remember { mutableFloatStateOf(saved.gifWidth.toFloat()) }
    var fps by remember { mutableFloatStateOf(saved.gifFps.toFloat()) }
    var maxFrames by remember { mutableFloatStateOf(150f) }
    var comp by remember { mutableIntStateOf(saved.gifCompression) }
    var dither by remember { mutableStateOf(saved.gifDither) }
    var loop by remember { mutableStateOf(saved.gifLoop) }
    // Vista previa del GIF real (con todos los ajustes) en lugar del vídeo.
    // Vista previa por defecto: el vídeo (instantánea). La del GIF solo bajo demanda (botón "Vista GIF" o al activar el boomerang).
    var gifView by remember { mutableStateOf(false) }
    // GIF exacto (con giflossy) solo cuando se pide: se guarda para qué ajustes se pidió y si se está generando.
    var exactReqKey by remember { mutableStateOf<List<Any>?>(null) }
    var exactBusy by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubTick by remember { mutableIntStateOf(0) }
    var live by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var liveStep by remember { mutableLongStateOf(40_000L) }
    var liveStart by remember { mutableLongStateOf(0L) }
    var liveEnd by remember { mutableLongStateOf(0L) }
    var liveBusy by remember { mutableStateOf(false) }
    var exactFile by remember { mutableStateOf<File?>(null) }
    var exactKey by remember { mutableStateOf<List<Any>>(emptyList()) }
    var exactBytes by remember { mutableLongStateOf(0L) }
    var exactFrames by remember { mutableIntStateOf(1) }

    // Cualquier cosa que sea mover el tramo o el encuadre vuelve al vídeo (es lo que hay que ver para colocar los tiradores).
    fun seek(us: Long) { scrubbing = true; scrubTick++; seekReq = us to seekReq.second + 1 }
    fun pickRatio(id: String) {
        ratioId = id
        when (id) {
            "free" -> {}
            "orig" -> crop = CropRect()
            else -> ratioOf(id)?.let { crop = fitRatio(it, qm.w, qm.h) }
        }
    }

    // Cálculos del GIF: fotogramas reales (fps limitado por el máximo de fotogramas), tamaño y estimación del archivo.
    val spanUs = end - start
    // Los fotogramas son los del tramo a los fps elegidos (la velocidad solo cambia cuánto dura cada uno); el máximo cuenta también la vuelta del boomerang.
    val secs = (spanUs / 1_000_000.0).coerceAtLeast(0.1)
    val fpsEff = min(fps.toDouble(), (if (boomerang) maxFrames / 2.0 else maxFrames.toDouble()) / secs).coerceIn(2.0, 30.0).toInt()
    val baseFrames = (secs * fpsEff).toInt().coerceAtLeast(1)
    val gifFrames = if (boomerang && baseFrames > 2) 2 * baseFrames - 2 else baseFrames
    val gifSecs = gifFrames / (fpsEff * speed.toDouble())
    val cropW = (qm.w * crop.w).toInt().coerceAtLeast(2)
    val cropH = (qm.h * crop.h).toInt().coerceAtLeast(2)
    val gw = min(width.toInt(), cropW) / 2 * 2
    val gh = (gw.toLong() * cropH / cropW).toInt() / 2 * 2
    val compFactor = when (comp) { 0 -> 0.8; 1 -> 0.6; 2 -> 0.45; else -> 0.3 }
    val gifBytes = (gifFrames.toDouble() * gw * gh * 0.18 * compFactor).toLong()
    val fileBytes = remember(qm.path) { File(qm.path).length() }
    val editedBytes = (fileBytes * (spanUs.toDouble() / dur) * crop.w * crop.h).toLong()

    // Para saber si el GIF exacto que hay corresponde a los ajustes actuales, y su tamaño real (extrapolado a todos los fotogramas).
    val curKey: List<Any> = listOf(start, end, crop, gw, gh, fpsEff, boomerang, comp, dither, loop, speed)
    // Fotogramas del tramo actual: si cae dentro de lo ya decodificado se recorta al instante, sin volver a decodificar.
    // Mientras se decodifica este mismo tramo se aprovecha lo que ya hay (a partir de 8 fotogramas): la vista previa arranca enseguida y crece.
    val partialOk = liveBusy && start == liveStart && live.size >= 8
    val liveSlice: List<Bitmap> = remember(live, liveStep, liveStart, liveEnd, start, end, partialOk) {
        if (live.isEmpty() || start < liveStart || (end > liveEnd && !partialOk)) emptyList() else {
            val a = ((start - liveStart) / liveStep).toInt().coerceIn(0, live.size - 1)
            val b = (((end - liveStart) / liveStep).toInt() + 1).coerceIn(a + 1, live.size)
            live.subList(a, b)
        }
    }
    val liveReady = liveSlice.isNotEmpty()
    // Instante (tiempo del clip) del primer fotograma de la vista del GIF: base para el cabezal.
    val sliceStartUs = liveStart + ((start - liveStart) / liveStep).coerceAtLeast(0L) * liveStep
    val exactOk = exactFile != null && exactKey == curKey && tab != 1
    val k480 = min(1f, 480f / gw.coerceAtLeast(2))
    val shownBytes = if (exactOk) (exactBytes * (gifFrames.toDouble() / exactFrames) / (k480 * k480)).toLong() else gifBytes

    // Mover un tirador o el cabezal enseña el vídeo; 0,7 s después de dejar de tocar vuelve la vista previa del GIF.
    LaunchedEffect(scrubTick) { if (scrubTick > 0) { delay(700); scrubbing = false } }
    // Fotogramas ya procesados (recorte, ancho, compresión) preparados en segundo plano en cuanto hay fotogramas decodificados y tras cada
    // cambio de esos ajustes (0,25 s después): al pedir la vista del GIF, o activar el boomerang, ya no hay nada que calcular.
    val liveCache = remember(liveSlice, crop, gw, comp, dither) { java.util.concurrent.ConcurrentHashMap<Int, ImageBitmap>() }
    LaunchedEffect(liveCache) {
        if (!gif || liveSlice.isEmpty()) return@LaunchedEffect
        delay(250)
        withContext(Dispatchers.Default) {
            for (idx in liveSlice.indices) {
                if (!isActive) break
                liveCache.getOrPut(idx) { liveFrame(liveSlice[idx], crop, gw, comp, dither).asImageBitmap() }
            }
        }
    }
    // Activar el boomerang enseña el GIF (el reproductor del vídeo no puede ir hacia atrás); el resto de ajustes no cambian la vista.
    var firstBoomerang by remember { mutableStateOf(true) }
    LaunchedEffect(boomerang) {
        if (firstBoomerang) firstBoomerang = false else if (gif && boomerang) {
            android.util.Log.i("VixaGif", "boomerang: liveReady=" + liveReady + " liveBusy=" + liveBusy + " live=" + live.size + " rango vivo=" + liveStart + "-" + liveEnd + " actual=" + start + "-" + end)
            gifView = true; scrubbing = false
        }
    }
    // Fotogramas en memoria: se preparan en segundo plano nada más abrir y cada vez que el tramo se sale de lo ya decodificado
    // (hasta 110 y ~48 MB), aunque se esté viendo el vídeo: al pedir la vista del GIF ya están listos. Si el tramo se estrecha,
    // se recorta lo que hay al instante, sin decodificar.
    LaunchedEffect(gif, start, end) {
        if (!gif) return@LaunchedEffect
        if (live.isNotEmpty() && start >= liveStart && end <= liveEnd) return@LaunchedEffect
        liveBusy = true
        try {
            delay(if (live.isEmpty()) 150 else 600)
            val span = end - start
            val step = max(40_000L, span / 110)
            val cnt = (span / step).toInt().coerceIn(1, 110)
            val fw = (Math.sqrt(12_000_000.0 / cnt * qm.w / qm.h).toInt() / 2 * 2).coerceIn(200, 720)
            val list = ArrayList<Bitmap>()
            val t0 = System.currentTimeMillis()
            var nextPublish = 8
            val ok = withContext(Dispatchers.IO) {
                FrameStrip.extract(qm.path, qm.offsetUs + start, qm.offsetUs + end, step, cnt, fw) { b ->
                    list.add(b)
                    // Se publica lo decodificado hasta ahora (8, 16, 32… fotogramas) para que la vista previa no espere a tenerlos todos.
                    if (list.size >= nextPublish && list.size < cnt) {
                        nextPublish *= 2
                        live = ArrayList(list); liveStep = step; liveStart = start; liveEnd = start + list.size * step
                    }
                }
            }
            android.util.Log.i("VixaGif", "decode " + start + "-" + end + " cnt=" + cnt + " fw=" + fw + " -> " + list.size + " fotogramas, ok=" + ok + ", " + (System.currentTimeMillis() - t0) + " ms")
            if (ok && list.isNotEmpty()) { live = list; liveStep = step; liveStart = start; liveEnd = end } else gifView = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ErrorLog.log("GifLive", e)
            gifView = false
        } finally {
            liveBusy = false
        }
    }
    // El GIF exacto (con giflossy) solo se genera cuando se pide, para los ajustes de ese momento: nada cambia solo mientras miras.
    val exactWanted = exactReqKey == curKey
    LaunchedEffect(exactWanted, curKey, liveSlice, liveBusy, tab) {
        if (!gif || !exactWanted || liveBusy || !liveReady || tab == 1) return@LaunchedEffect
        exactBusy = true
        try {
            val r = withContext(Dispatchers.Default) { buildExactGif(context, liveSlice, liveStep, crop, gw, gh, fpsEff, boomerang, comp, dither, loop, speed) }
            if (r != null) { exactFile = r.first; exactFrames = r.second.coerceAtLeast(1); exactBytes = r.first.length(); exactKey = curKey }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ErrorLog.log("GifExact", e)
        } finally {
            exactBusy = false
        }
    }

    fun confirm() {
        val p2 = quickApply(project, qm, start, end, crop)
        if (!gif) { if (whatsapp && onWhatsapp != null) onWhatsapp(p2) else onDone(p2); return }
        val short = min(p2.width, p2.height)
        val s = ExportSettings(
            kind = ExportKind.GIF, shortSide = listOf(480, 720, 1080, 1440, 2160).lastOrNull { it <= short } ?: 480, fps = p2.fps,
            gifWidth = width.toInt() / 2 * 2, gifFps = fpsEff, boomerang = boomerang, gifCompression = comp, gifDither = dither, gifLoop = loop, gifSpeed = speed,
            passthrough = false,
            outDir = com.dskmusic.vixa.data.Storage.exportDir(container.settings.value.exportDir, ExportKind.GIF).path,
        )
        container.exporter.start(p2, s)
        onDone(p2)
    }

    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val topBar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            Text(stringResource(if (gif) R.string.qt_gif else R.string.qt_trim), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(::confirm, enabled = end - start >= minGap) { Icon(Icons.Default.Check, stringResource(R.string.export)) }
        }
    }
    // Vista previa. En GIF es la del GIF (con boomerang, velocidad, etc.) en todas las pestañas; solo al mover tiradores o el cabezal
    // se enseña el vídeo un momento. El reproductor del vídeo está siempre montado debajo, así no hay espera al volver a él.
    val liveMode = gif && gifView && (tab == 1 || !scrubbing)
    val showLive = liveMode && liveReady
    val preview: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(qm.w.toFloat() / qm.h).clipToBounds()) {
                QuickPlayer(qm.path, qm.offsetUs, start, end, speed, playing && !showLive, seekReq, { if (!showLive) pos = it }, Modifier.fillMaxSize())
                if (!showLive && (tab == 1 || crop != CropRect())) CropBox(crop, ratioOf(ratioId), { crop = it }, tab == 1, Modifier.fillMaxSize())
            }
            if (showLive) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                    if (tab == 1) {
                        // Encuadre: el fotograma entero moviéndose (con el boomerang) y el cuadro encima.
                        Box(Modifier.aspectRatio(qm.w.toFloat() / qm.h).clipToBounds()) {
                            LivePreview(liveSlice, liveStep, CropRect(), 4096, fpsEff, speed, boomerang, loop, comp, dither, Modifier.fillMaxSize(), playing = playing, onPos = { pos = sliceStartUs + it })
                            CropBox(crop, ratioOf(ratioId), { crop = it }, true, Modifier.fillMaxSize())
                        }
                    } else {
                        val ef = exactFile
                        Box(Modifier.aspectRatio(cropW.toFloat() / cropH)) {
                            if (ef != null && exactOk) AsyncImage(ef, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            else LivePreview(liveSlice, liveStep, crop, gw, fpsEff, speed, boomerang, loop, comp, dither, Modifier.fillMaxSize(), liveCache, playing, { pos = sliceStartUs + it })
                        }
                    }
                }
            }
            if (liveMode && !liveReady) CircularProgressIndicator(Modifier.size(32.dp))
        }
    }
    val playRow: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ playing = !playing }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) }
            Text(fmtTime((pos - start).coerceIn(0L, spanUs.coerceAtLeast(0L))) + "  /  " + fmtTime(spanUs.coerceAtLeast(0L)), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        Text(
            if (gif) stringResource(R.string.qe_gif_info, gifFrames, String.format(java.util.Locale.US, "%.1f s", gifSecs), formatBytes(shownBytes))
            else stringResource(R.string.qe_trim_info, formatBytes(fileBytes), fmtTime(dur), fmtTime(spanUs), formatBytes(editedBytes)),
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val controls: @Composable () -> Unit = {
        when (tab) {
            0 -> {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (secsPreset in listOf(2, 3, 5, 10, 30)) {
                        val us = secsPreset * 1_000_000L
                        if (us < dur) FilterChip(false, { end = min(dur, start + us); seek(start) }, { Text(secsPreset.toString() + " s") })
                    }
                    FilterChip(false, { start = 0L; end = dur; seek(0L) }, { Text(stringResource(R.string.qe_all)) })
                }
                TrimBar(baseFilm, detailFilm, winStart, winEnd, { a, b -> winStart = a; winEnd = b }, dur, start, end, pos, minGap, { s, e -> start = s; end = e }, { seek(it) }, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                Text(stringResource(R.string.qe_trim_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (whatsapp) {
                    val wp = whatsappParts(spanUs.coerceAtLeast(1L))
                    Text(
                        stringResource(R.string.qe_wa_parts, wp.size, wp.joinToString(" + ") { String.format(java.util.Locale.US, "%.0f s", (it.second - it.first) / 1_000_000.0) }),
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    )
                }
                FineAdjust(stringResource(R.string.qe_start), start, frameUs, { start = it.coerceIn(0L, end - minGap); seek(start) })
                FineAdjust(stringResource(R.string.qe_end), end, frameUs, { end = it.coerceIn(start + minGap, dur); seek(end) })
            }
            1 -> {
                RatioChips(ratioId) { pickRatio(it) }
                Text(
                    stringResource(R.string.qe_size_dims, qm.w, qm.h, cropW, cropH) + "\n" + stringResource(R.string.qe_ratio_flip_hint),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                // Asa más baja y algo de aire entre deslizadores: así no se pisan.
                val thumb = DpSize(4.dp, 24.dp)
                val pad = Modifier.padding(vertical = 6.dp)
                PreciseSlider(stringResource(R.string.width), width, { width = it }, pad, range = 120f..720f, default = 480f, format = { it.toInt().toString() + " px" }, inputScale = 1f, thumbSize = thumb)
                PreciseSlider(stringResource(R.string.qe_fps), fps, { fps = it }, pad, range = 5f..25f, default = 12f, format = { it.toInt().toString() }, inputScale = 1f, thumbSize = thumb)
                PreciseSlider(stringResource(R.string.qe_max_frames), maxFrames, { maxFrames = it }, pad, range = 10f..400f, default = 150f, format = { it.toInt().toString() }, inputScale = 1f, thumbSize = thumb)
                Text(stringResource(R.string.qe_max_frames_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionTitle(stringResource(R.string.gif_compression))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in 0..3) FilterChip(comp == c, { comp = c }, { Text(stringResource(when (c) { 0 -> R.string.gif_comp_none; 1 -> R.string.gif_comp_light; 2 -> R.string.gif_comp_medium; else -> R.string.gif_comp_strong })) })
                }
                SectionTitle(stringResource(R.string.gif_speed))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (v in listOf(0.5f, 1f, 1.5f, 2f)) FilterChip(speed == v, { speed = v }, { Text("×" + (if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString())) })
                }
                SwitchRowSimple(stringResource(R.string.gif_dither), dither) { dither = it }
                SwitchRowSimple(stringResource(R.string.gif_loop), loop) { loop = it }
            }
        }
    }
    // Pestañas inferiores (y el boomerang y la vista del GIF siempre a mano).
    val tabsRow: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == 0, { tab = 0 }, { Text(stringResource(R.string.qe_time)) })
            FilterChip(tab == 1, { tab = 1 }, { Text(stringResource(R.string.qe_frame)) })
            if (!gif && onWhatsapp != null) FilterChip(whatsapp, { whatsapp = !whatsapp }, { Text(stringResource(R.string.qe_whatsapp)) })
            if (gif) {
                FilterChip(tab == 2, { tab = 2 }, { Text(stringResource(R.string.qe_settings)) })
                FilterChip(boomerang, { boomerang = !boomerang }, { Text(stringResource(R.string.gif_boomerang)) })
                FilterChip(gifView, { gifView = !gifView; scrubbing = false }, { Text(stringResource(R.string.qe_gif_preview)) })
                FilterChip(exactOk, { exactReqKey = curKey; gifView = true; scrubbing = false }, { Text(stringResource(if (exactBusy) R.string.qe_gif_exact_busy else R.string.qe_gif_exact)) })
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (landscape) {
                // Horizontal: la vista previa a la izquierda y todos los controles en una columna a la derecha.
                Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    preview(Modifier.weight(1.25f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        topBar()
                        playRow()
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { controls() }
                        tabsRow()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    topBar()
                    preview(Modifier.weight(1f))
                    playRow()
                    Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(bottom = 4.dp)) { controls() }
                    tabsRow()
                }
            }
        }
    }
}
