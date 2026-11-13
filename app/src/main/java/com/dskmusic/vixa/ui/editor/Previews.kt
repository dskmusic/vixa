package com.dskmusic.vixa.ui.editor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.combinedClickable
import com.dskmusic.vixa.model.AnimRef
import com.dskmusic.vixa.engine.Anims
import com.dskmusic.vixa.engine.AnimState
import com.dskmusic.vixa.engine.AnimKind
import com.dskmusic.vixa.engine.AnimDef
import com.dskmusic.vixa.engine.CharFx
import com.dskmusic.vixa.engine.charFx
import androidx.compose.foundation.layout.Row
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.dskmusic.vixa.R
import com.dskmusic.vixa.engine.FxDef
import com.dskmusic.vixa.engine.FxThumbs
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.dskmusic.vixa.engine.FilterPreset
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// ponytail: vistas previas aproximadas en Compose (no usan el shader real); basta para distinguir el estilo.

private val LayerA = Color(0xFF3B82F6)
private val LayerB = Color(0xFFF59E0B)

/** Progreso 0..1 compartido por todas las casillas (una sola animación para toda la rejilla). */
@Composable
fun rememberLoopProgress(): Float {
    val p by rememberInfiniteTransition(label = "tr").animateFloat(0f, 1.5f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "p")
    return (p / 1.2f).coerceIn(0f, 1f)
}

/**
 * Rejilla de casillas: columnas según el ancho (mínimo 3, más en tablet/apaisado) y ancho fijo por casilla,
 * de modo que una fila incompleta mantiene el mismo tamaño que las demás. [content] recibe el modificador de ancho.
 */
@Composable
fun TileGrid(modifier: Modifier = Modifier, minTile: Dp = 104.dp, gap: Dp = 6.dp, content: @Composable FlowRowScope.(Modifier) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val cols = (maxWidth / (minTile + gap)).toInt().coerceIn(3, 8)
        // -1.dp: el redondeo a píxeles hacía que la última casilla de la fila no cupiera y FlowRow la bajaba (quedaban 2 por fila)
        val w = (maxWidth - gap * (cols - 1)) / cols - 1.dp
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap), maxItemsInEachRow = cols) { content(Modifier.width(w)) }
    }
}

/** Versión de la imagen de muestra (0 = la de serie): cambia al elegirla o restaurarla en Ajustes. */
@Composable
fun rememberSampleVersion(): Long {
    val c = com.dskmusic.vixa.ui.ScrollHints.container ?: return 0L
    return c.settings.flow.collectAsState().value.fxSampleVersion
}

/** Imagen de muestra para las vistas previas: la elegida en Ajustes o la de serie. */
@Composable
fun rememberSamplePainter(): androidx.compose.ui.graphics.painter.Painter {
    val ver = rememberSampleVersion()
    val ctx = LocalContext.current.applicationContext
    val img = remember(ver) { com.dskmusic.vixa.engine.FxSamples.bitmap(ctx)?.asImageBitmap() }
    return if (img != null) androidx.compose.ui.graphics.painter.BitmapPainter(img) else androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFF808080))
}

/** Animación de entrada/salida/bucle aplicada a la foto de muestra (mismo motor [Anims] que el vídeo). */
@Composable
fun AnimTile(def: AnimDef, kind: AnimKind, name: String, selected: Boolean, progress: Float, modifier: Modifier = Modifier.size(96.dp, 68.dp), favKey: String? = null, onClick: () -> Unit) {
    PreviewTile(name, selected, onClick, modifier, favKey) {
        val st = remember { AnimState() }
        st.reset()
        val durUs = if (kind == AnimKind.LOOP) 2_000_000L else 1_000_000L
        val local = (progress * durUs).toLong()
        val ref = AnimRef(def.id, durUs)
        when (kind) {
            AnimKind.IN -> Anims.apply(st, ref, null, null, local, durUs)
            AnimKind.OUT -> Anims.apply(st, null, ref, null, local, durUs)
            AnimKind.LOOP -> Anims.apply(st, null, null, ref, local, durUs)
        }
        // Copias en vals de tipo Float: las lambdas capturan valores que cambian cada fotograma. Si capturaran `st`
        // (misma instancia, clase inestable) Compose reutiliza la lambda y la casilla no se vuelve a dibujar.
        val rl = st.revealL; val rt = st.revealT; val rr = st.revealR; val rb = st.revealB
        val sc = st.scale; val sx = st.sx; val sy = st.sy; val dx = st.dx; val dy = st.dy; val rot = st.rotation; val al = st.alpha.coerceIn(0f, 1f)
        Image(
            rememberSamplePainter(), null,
            Modifier.fillMaxSize()
                .drawWithContent { clipRect(size.width * rl, size.height * rt, size.width * (1f - rr), size.height * (1f - rb)) { this@drawWithContent.drawContent() } }
                .graphicsLayer { scaleX = sc * sx; scaleY = sc * sy; translationX = dx * size.width; translationY = dy * size.height; rotationZ = rot; alpha = al },
            contentScale = ContentScale.Crop,
        )
    }
}

/** Animación aplicada a un texto de muestra (mismo motor [Anims]); las animaciones por letra revelan los caracteres. */
@Composable
fun TextAnimTile(def: AnimDef, kind: AnimKind, name: String, selected: Boolean, progress: Float, modifier: Modifier = Modifier.size(96.dp, 68.dp), favKey: String? = null, onClick: () -> Unit) {
    PreviewTile(name, selected, onClick, modifier, favKey) {
        val st = remember { AnimState() }
        st.reset()
        val durUs = if (kind == AnimKind.LOOP) 2_000_000L else 1_000_000L
        val local = (progress * durUs).toLong()
        val ref = AnimRef(def.id, durUs)
        when (kind) {
            AnimKind.IN -> Anims.apply(st, ref, null, null, local, durUs)
            AnimKind.OUT -> Anims.apply(st, null, ref, null, local, durUs)
            AnimKind.LOOP -> Anims.apply(st, null, null, ref, local, durUs)
        }
        val sample = "Texto"
        val rl = st.revealL; val rt = st.revealT; val rr = st.revealR; val rb = st.revealB
        val sc = st.scale; val sx = st.sx; val sy = st.sy; val dx = st.dx; val dy = st.dy; val rot = st.rotation; val al = st.alpha.coerceIn(0f, 1f)
        val fx = remember { CharFx() }
        Box(Modifier.fillMaxSize().background(Color(0xFF20222B)).padding(bottom = 14.dp), contentAlignment = Alignment.Center) {
            // Letra a letra con el mismo cálculo que el render del vídeo (AnimState.charFx).
            Row(Modifier
                .drawWithContent { clipRect(size.width * rl, size.height * rt, size.width * (1f - rr), size.height * (1f - rb)) { this@drawWithContent.drawContent() } }
                .graphicsLayer { scaleX = sc * sx; scaleY = sc * sy; translationX = dx * size.width; translationY = dy * size.height; rotationZ = rot; alpha = al }) {
                for ((i, ch) in sample.withIndex()) {
                    st.charFx(i, sample.length, fx)
                    val ca = if (fx.hidden) 0f else fx.a.coerceIn(0f, 1f)
                    val cdx = fx.dx; val cdy = fx.dy; val csc = fx.sc; val crot = fx.rot
                    Text(ch.toString(), color = Color.White, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, softWrap = false,
                        modifier = Modifier.graphicsLayer { alpha = ca; translationX = cdx * 20.sp.toPx(); translationY = cdy * 20.sp.toPx(); scaleX = csc; scaleY = csc; rotationZ = crot })
                }
            }
        }
    }
}

/** Casilla con vista previa dentro y el nombre encima, abajo. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun PreviewTile(name: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier.size(96.dp, 68.dp), favKey: String? = null, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    val isFav = favKey != null && favKey in rememberFavs()
    Box(
        modifier.clip(shape).combinedClickable(onClick = onClick, onLongClick = if (favKey != null) ({ Favs.pending.value = favKey }) else null)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape),
        contentAlignment = Alignment.BottomCenter,
    ) {
        content()
        if (isFav) androidx.compose.material3.Icon(Icons.Default.Star, null, Modifier.align(Alignment.TopEnd).padding(3.dp).size(14.dp), tint = Color(0xFFFFC107))
        Text(name, Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 2.dp, vertical = 2.dp),
            fontSize = 11.sp, lineHeight = 12.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
    }
}

@Composable
fun TransitionTile(id: String, name: String, selected: Boolean, progress: Float, modifier: Modifier = Modifier.size(96.dp, 68.dp), favKey: String? = null, onClick: () -> Unit) =
    PreviewTile(name, selected, onClick, modifier, favKey) { Canvas(Modifier.fillMaxSize()) { drawTransition(id, progress) } }

/** Efecto aplicado con su propio shader sobre la imagen de muestra. */
@Composable
fun FxTile(def: FxDef, name: String, selected: Boolean, modifier: Modifier = Modifier.size(96.dp, 68.dp), favKey: String? = null, onClick: () -> Unit) {
    FxThumbs.appContext = LocalContext.current.applicationContext
    val ver = rememberSampleVersion()
    val img by produceState<ImageBitmap?>(null, def.id, ver) { value = null; value = FxThumbs.get(def)?.asImageBitmap() }
    PreviewTile(name, selected, onClick, modifier, favKey) {
        val b = img
        if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else FilterSample(null, Modifier.fillMaxSize())
    }
}

private fun DrawScope.layer(c: Color, round: Boolean, alpha: Float = 1f) {
    drawRect(c, alpha = alpha)
    val r = min(size.width, size.height) / 4f
    val mark = Color.White.copy(alpha = 0.85f * alpha)
    if (round) drawCircle(mark, r, center) else drawRect(mark, Offset(center.x - r, center.y - r), Size(2 * r, 2 * r))
}

private fun fr(x: Float) = x - floor(x)
private fun hash2(x: Float, y: Float) = fr(sin(x * 127.1f + y * 311.7f) * 43758.547f)

/** Transiciones de máscara: su vista previa evalúa en una rejilla la misma cobertura que calcula el shader. */
private val CoverIds = setOf(
    "dissolve", "stripes", "clock", "heart", "wipe_diagonal", "wipe_soft", "fan", "sunburst", "blinds_diag", "zigzag", "mosaic", "rings",
    "spiral", "corners", "star", "box_open", "cross", "dots", "eye", "ink", "scan", "shatter", "digital", "strobe",
    "drip", "curtain_wave", "cloud_dissolve", "window_zoom", "iris_zoom",
)

/** ¿Cubre ya el clip entrante el punto (u, v) (0..1, origen arriba a la izquierda) con el progreso [p]? */
private fun covered(id: String, u: Float, v: Float, p: Float, asp: Float): Boolean {
    val x = u - 0.5f
    val y = v - 0.5f
    val d = hypot(x * asp, y)
    val ang = atan2(x, -y) // 0 arriba, sentido horario
    val turn = ang / 6.2831855f + 0.5f
    return when (id) {
        "dissolve" -> p * 1.12f - hash2(floor(u * 36f), floor(v * 24f)) > 0.06f
        "stripes" -> p * 1.3f - hash2(floor(u * 18f), 3f) > 0.15f
        "clock" -> turn <= p
        "heart" -> {
            val s = max(p * 1.6f, 0.001f)
            val hx = x * asp / s
            val hy = -(v - 0.46f) / s
            val a2 = hx * hx + hy * hy - 0.25f
            a2 * a2 * a2 - hx * hx * hy * hy * hy * 4f < 0f
        }
        "wipe_diagonal" -> (u + v) * 0.5f < p * 1.06f - 0.03f
        "wipe_soft" -> p * 1.4f - u > 0.2f
        "fan" -> abs(ang) / 3.1415927f <= p
        "sunburst" -> fr(turn * 12f) <= p
        "blinds_diag" -> fr((u + v / asp) * 5f) <= p
        "zigzag" -> u + abs(fr(v * 7f) - 0.5f) * 0.14f <= p * 1.08f
        "mosaic" -> p * 1.12f - hash2(floor(u * 10f), floor(v * 10f / asp)) > 0.06f
        "rings" -> fr(d * 6f) <= p
        "spiral" -> fr(turn + d * 3f) <= p
        "corners" -> abs(x) + abs(y) >= (1f - p) * 1.02f
        "star" -> d <= p * (0.55f + 0.5f * asp) / 0.42f * (0.42f + 0.58f * (1f - abs(sin(ang * 2.5f))))
        "box_open", "window_zoom" -> max(abs(x), abs(y)) < p * 0.51f
        "iris_zoom" -> d <= p * (0.55f + 0.5f * asp)
        "drip" -> p * 1.35f - hash2(floor(u * 14f), 1f) * 0.35f >= v
        "curtain_wave" -> abs(x) + sin(v * 12f) * 0.03f < p * 0.55f
        "cloud_dissolve" -> p * 1.15f - (0.5f + 0.5f * sin(u * 17f + 1f) * sin(v * 13f + 2f)) > 0.075f
        "cross" -> min(abs(x), abs(y)) < p * 0.51f
        "dots" -> {
            val gx = u * 5f * asp
            val gy = v * 5f
            hypot(fr(gx) - 0.5f, fr(gy) - 0.5f) < (p * 1.6f - hash2(floor(gx), floor(gy)) * 0.6f) * 0.75f
        }
        "eye" -> { val x2 = abs(x) * 2f; abs(y) < p * 1.3f * (1f - 0.6f * x2 * x2) }
        "ink" -> p * (1.2f + 0.6f * asp) - d - (0.3f + 0.25f * sin(u * 11f + 1f) * sin(v * 9f)) > 0.025f
        "scan" -> v <= p * 1.06f - 0.03f
        "shatter" -> p * 1.15f - hash2(floor(u * 6f * asp + v * 2f), floor(v * 6f + u * 2f)) > 0.075f
        "digital" -> hash2(floor(u * 5f) * 2.1f, floor(v * 9f) * 2.1f) <= p
        "strobe" -> p > 0.8f || (p >= 0.15f && fr(p * 5f) >= 0.5f)
        else -> p >= 0.5f
    }
}

private fun DrawScope.drawCover(id: String, p: Float) {
    layer(LayerA, true)
    val nx = 36
    val ny = 24
    val cw = size.width / nx
    val ch = size.height / ny
    val asp = size.width / size.height
    val path = Path()
    // Las celdas cubiertas se unen por tramos de fila para recortar con pocos rectángulos.
    for (j in 0 until ny) {
        var run = -1
        for (i in 0..nx) {
            val on = i < nx && covered(id, (i + 0.5f) / nx, (j + 0.5f) / ny, p, asp)
            if (on && run < 0) run = i
            if (!on && run >= 0) { path.addRect(androidx.compose.ui.geometry.Rect(run * cw, j * ch, i * cw, (j + 1) * ch + 0.5f)); run = -1 }
        }
    }
    clipPath(path) { layer(LayerB, false) }
}

private fun DrawScope.drawTransition(id: String, p: Float) {
    val w = size.width
    val h = size.height
    val e = p * p * (3f - 2f * p)
    val diag = hypot(w, h)
    val pi = Math.PI.toFloat()
    val bump = sin(p * pi)
    when {
        id.startsWith("slide_") || id.startsWith("push_") || id == "roll" || id.startsWith("whip") || id == "swap" -> {
            val (dx, dy) = when (id.substringAfterLast('_')) { "right" -> -1f to 0f; "up" -> 0f to 1f; "down" -> 0f to -1f; "corner" -> 1f to 1f; else -> 1f to 0f }
            val push = id.startsWith("push_") || id.startsWith("whip") || id == "swap"
            scale(if (id == "swap") 1f - 0.22f * bump else 1f, center) {
                if (id == "swap") drawRect(Color.Black)
                if (push) translate(-dx * e * w, -dy * e * h) { layer(LayerA, true) } else layer(LayerA, true)
                translate(dx * (1f - e) * w, dy * (1f - e) * h) { layer(LayerB, false) }
            }
        }
        id in CoverIds -> {
            drawCover(id, p)
            if (id == "scan") drawRect(Color(0xFF66FFE6), Offset(0f, h * (p * 1.06f - 0.03f) - 2f), Size(w, 4f))
            if (id == "strobe") drawRect(Color.White, alpha = (if ((p * 5f + 0.4f) % 1f > 0.8f) 0.6f else 0f) * bump)
        }
        id == "split_v" -> {
            scale(1.15f - 0.15f * e, center) { layer(LayerB, false) }
            clipRect(right = w / 2f * (1f - e)) { translate(-e * w / 2f, 0f) { layer(LayerA, true) } }
            clipRect(left = w / 2f * (1f + e)) { translate(e * w / 2f, 0f) { layer(LayerA, true) } }
        }
        id == "split_h" -> {
            scale(1.15f - 0.15f * e, center) { layer(LayerB, false) }
            clipRect(bottom = h / 2f * (1f - e)) { translate(0f, -e * h / 2f) { layer(LayerA, true) } }
            clipRect(top = h / 2f * (1f + e)) { translate(0f, e * h / 2f) { layer(LayerA, true) } }
        }
        id == "slices" -> {
            layer(LayerA, true)
            for (i in 0 until 8) clipRect(top = i * h / 8f, bottom = (i + 1) * h / 8f) { translate((if (i % 2 == 0) 1f else -1f) * (1f - e) * w, 0f) { layer(LayerB, false) } }
        }
        id == "rain_bars" -> {
            layer(LayerA, true)
            for (i in 0 until 12) {
                val k = (p * 1.5f - hash2(i.toFloat(), 5f) * 0.5f).coerceIn(0f, 1f)
                clipRect(left = i * w / 12f, right = (i + 1) * w / 12f) { translate(0f, (k * k * (3f - 2f * k) - 1f) * h) { layer(LayerB, false) } }
            }
        }
        id == "bounce" -> {
            layer(LayerA, true)
            translate(0f, (com.dskmusic.vixa.model.Easing.BOUNCE_OUT.ease(p) - 1f) * h) { layer(LayerB, false) }
        }
        id == "zoom_blur" || id == "twist" || id == "swirl" -> {
            val ang = if (id == "zoom_blur") 0f else 35f
            rotate(e * ang, center) { scale(1f + (if (id == "swirl") 0.3f else 1.2f) * e, center) { layer(LayerA, true, 1f - p) } }
            rotate(-(1f - e) * ang, center) { scale(if (id == "zoom_blur") 0.55f + 0.45f * e else 1f + (1f - e) * 0.8f, center) { layer(LayerB, false, p) } }
        }
        id == "bulge" || id == "dream" || id == "ripple" || id == "light_leak" -> {
            scale(1f + 0.25f * bump, center) { layer(LayerA, true); layer(LayerB, false, p) }
            if (id == "dream") drawRect(Color.White, alpha = bump * 0.35f)
            if (id == "light_leak") drawRect(Brush.horizontalGradient(listOf(Color(0xFFFF8A1E), Color(0x00FFC850)), startX = w * (p - 0.5f), endX = w * (p + 0.7f)), alpha = bump)
        }
        id == "stretch" -> {
            scale(1f + 4f * p * p, 1f, center) { layer(LayerA, true, 1f - p) }
            scale(1f + 4f * (1f - p) * (1f - p), 1f, center) { layer(LayerB, false, p) }
        }
        id == "punch" || id == "shake" || id == "tv_off" || id == "static_tv" -> {
            val k = if (p < 0.5f) p * 2f else (1f - p) * 2f
            val z = when (id) { "punch" -> 1f + (if (p < 0.5f) 0.12f else 0.35f) * k * k; "shake" -> 1f + 0.14f * bump; else -> 1f }
            val t = floor(p * 14f)
            val jx = if (id == "shake") (hash2(t, 1f) - 0.5f) * 0.1f * bump * w else 0f
            val jy = if (id == "shake") (hash2(t, 2f) - 0.5f) * 0.1f * bump * h else 0f
            translate(jx, jy) { scale(z, center) { if (p < 0.5f) layer(LayerA, true) else layer(LayerB, false) } }
            if (id == "tv_off") {
                val a = 1f - k
                val band = max(a * a, 0.02f) * h
                drawRect(Color.Black, Offset.Zero, Size(w, (h - band) / 2f))
                drawRect(Color.Black, Offset(0f, (h + band) / 2f), Size(w, (h - band) / 2f))
                drawRect(Color.White, Offset(0f, (h - band) / 2f), Size(w, band), alpha = k * 0.85f)
            }
            if (id == "static_tv") for (i in 0 until 12) for (j in 0 until 8) drawRect(Color.White, Offset(i * w / 12f, j * h / 8f), Size(w / 12f + 0.5f, h / 8f + 0.5f), alpha = hash2(i + t * 7f, j.toFloat()) * (k * 0.9f).coerceAtMost(0.9f))
        }
        id == "film_roll" -> { translate(0f, -e * h) { layer(LayerA, true) }; translate(0f, (1f - e) * h) { layer(LayerB, false) } }
        id == "wipe_left" -> { layer(LayerA, true); clipRect(left = w * (1f - p)) { layer(LayerB, false) } }
        id == "wipe_right" -> { layer(LayerA, true); clipRect(right = w * p) { layer(LayerB, false) } }
        id == "wipe_up" -> { layer(LayerA, true); clipRect(top = h * (1f - p)) { layer(LayerB, false) } }
        id == "wipe_down" -> { layer(LayerA, true); clipRect(bottom = h * p) { layer(LayerB, false) } }
        id == "barn_doors" -> { layer(LayerA, true); clipRect(left = w * (0.5f - p * 0.5f), right = w * (0.5f + p * 0.5f)) { layer(LayerB, false) } }
        id == "blinds_h" || id == "blinds_v" || id == "stripes" -> {
            layer(LayerA, true)
            val vertical = id != "blinds_h"
            val n = 8
            for (i in 0 until n) {
                val cw = w / n
                val ch = h / n
                if (vertical) clipRect(left = i * cw, right = i * cw + cw * p) { layer(LayerB, false) }
                else clipRect(top = i * ch, bottom = i * ch + ch * p) { layer(LayerB, false) }
            }
        }
        id == "checker" -> {
            layer(LayerA, true)
            for (i in 0 until 6) for (j in 0 until 4) {
                val cw = w / 6f
                val ch = h / 4f
                clipRect(i * cw, j * ch, i * cw + cw * p, j * ch + ch * p) { layer(LayerB, false) }
            }
        }
        id == "circle_open" || id == "heart" || id == "clock" -> {
            layer(LayerA, true)
            clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, p * diag / 2f)) }) { layer(LayerB, false) }
        }
        id == "circle_close" -> {
            layer(LayerB, false)
            clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, (1f - p) * diag / 2f)) }) { layer(LayerA, true) }
        }
        id == "diamond" -> {
            layer(LayerA, true)
            val d = p * (w + h) / 2f
            val path = Path().apply { moveTo(center.x, center.y - d); lineTo(center.x + d, center.y); lineTo(center.x, center.y + d); lineTo(center.x - d, center.y); close() }
            clipPath(path) { layer(LayerB, false) }
        }
        id == "zoom_in" || id == "zoom_out" || id == "spin" -> {
            layer(LayerA, true, 1f - p)
            val s = when (id) { "zoom_in" -> 0.35f + 0.65f * e; "zoom_out" -> 1.9f - 0.9f * e; else -> 0.25f + 0.75f * e }
            rotate(if (id == "spin") (1f - e) * -180f else 0f, center) { scale(s, center) { layer(LayerB, false, p) } }
        }
        id == "pixelate" -> {
            layer(LayerA, true)
            val n = 10
            for (i in 0 until n) for (j in 0 until 6) {
                val r = ((i * 7 + j * 13) % 17) / 17f
                if (r < p) drawRect(LayerB, Offset(i * w / n, j * h / 6f), Size(w / n + 0.5f, h / 6f + 0.5f))
            }
            if (p > 0.5f) layer(LayerB, false, (p - 0.5f) * 2f)
        }
        id == "glitch" -> {
            layer(LayerA, true)
            val bands = 9
            for (i in 0 until bands) {
                val r = ((i * 11) % 7) / 7f - 0.5f
                val off = r * w * 0.35f * sin(p * Math.PI.toFloat())
                clipRect(top = i * h / bands, bottom = (i + 1) * h / bands) { translate(off, 0f) { layer(LayerB, false, p) } }
            }
        }
        id == "wave" -> {
            layer(LayerA, true)
            val path = Path().apply {
                moveTo(0f, h)
                var x = 0f
                while (x <= w) { lineTo(x, h * (1f - e) + sin(x / w * 12f + p * 10f) * h * 0.06f); x += w / 24f }
                lineTo(w, h); close()
            }
            clipPath(path) { layer(LayerB, false) }
        }
        id == "burn" -> {
            layer(LayerA, true)
            val r = e * diag * 0.6f
            drawCircle(Color(0xFFFF7A1A), r + 6f, center, alpha = 0.9f)
            clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(center.x - r, center.y - r, center.x + r, center.y + r)) }) { layer(LayerB, false) }
        }
        id == "dip_black" || id == "dip_white" -> {
            if (p < 0.5f) layer(LayerA, true) else layer(LayerB, false)
            drawRect(if (id == "dip_white") Color.White else Color.Black, alpha = if (p < 0.5f) p * 2f else (1f - p) * 2f)
        }
        else -> {
            // Fundido (también fallback del resto: flash, desenfoque, disolver, glitch…).
            layer(LayerA, true)
            layer(LayerB, false, p)
            if (id == "flash") drawRect(Color.White, alpha = sin(p * Math.PI.toFloat()) * 0.9f)
        }
    }
}

/** Matriz de color que aproxima un filtro (sin LUT) a intensidad 1. */
fun filterMatrix(f: FilterPreset): ColorMatrix {
    val a = f.adj
    val s = max(0f, (1f + a.saturation) * (1f - f.mono))
    val k = 1f + a.contrast * (if (a.contrast > 0f) 1f else 0.75f)
    val g = 2f.pow(a.exposure * 1.5f) * k
    val gain = floatArrayOf(g * (1f + a.temperature * 0.18f), g * (1f + a.tint * 0.12f), g * (1f - a.temperature * 0.18f))
    val off = (0.5f * (1f - k) + a.brightness * 0.25f + a.lightness * 0.3f + a.fade * 0.1f) * 255f
    val lum = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
    val m = FloatArray(20)
    for (i in 0 until 3) {
        for (j in 0 until 3) m[i * 5 + j] = (lum[j] * (1f - s) + (if (i == j) s else 0f)) * gain[i]
        val tintShadow = Color(f.tintH).let { if (i == 0) it.red else if (i == 1) it.green else it.blue } * Color(f.tintH).alpha
        m[i * 5 + 4] = off + (if (f.tintH != 0) tintShadow * 40f else 0f)
    }
    m[18] = 1f
    return ColorMatrix(m)
}

/** Foto de muestra con el filtro aplicado (si el filtro lleva LUT .cube, se aplica de verdad a la foto además de la matriz). */
@Composable
fun FilterSample(matrix: ColorMatrix?, modifier: Modifier = Modifier, preset: FilterPreset? = null) {
    val ctx = LocalContext.current.applicationContext
    val ver = rememberSampleVersion()
    val samplePainter = rememberSamplePainter()
    val lutImg = remember(preset?.id, ver) { if (preset != null && preset.lut.isNotEmpty()) FilterThumbs.lutBitmap(ctx, preset)?.asImageBitmap() else null }
    if (lutImg != null) Image(lutImg, null, modifier, contentScale = ContentScale.Crop, colorFilter = matrix?.let { ColorFilter.colorMatrix(it) })
    else Image(samplePainter, null, modifier, contentScale = ContentScale.Crop, colorFilter = matrix?.let { ColorFilter.colorMatrix(it) })
}

/** Miniaturas de filtros con LUT: la LUT se aplica por CPU a la foto de muestra (192x120, barato) y se cachea. */
object FilterThumbs {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()

    /** Olvida las miniaturas calculadas (al cambiar la imagen de muestra en Ajustes). */
    fun clear() = cache.clear()
    fun lutBitmap(ctx: android.content.Context, f: FilterPreset): android.graphics.Bitmap? {
        cache[f.id]?.let { return it }
        return try {
            FxThumbs.appContext = ctx
            val lut = com.dskmusic.vixa.engine.LutStore.get(ctx, f.lut) ?: return null
            val src = FxThumbs.sampleBitmap()
            val w = src.width; val h = src.height; val n = lut.size
            val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
            val lp = IntArray(n * n * n); lut.bitmap.getPixels(lp, 0, n * n, 0, 0, n * n, n)
            for (i in px.indices) {
                val c = px[i]
                val r = (android.graphics.Color.red(c) * (n - 1) + 127) / 255
                val g = (android.graphics.Color.green(c) * (n - 1) + 127) / 255
                val b = (android.graphics.Color.blue(c) * (n - 1) + 127) / 255
                px[i] = lp[g * n * n + b * n + r]
            }
            android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888).also { cache[f.id] = it }
        } catch (e: Throwable) { null }
    }
}

/** Favoritos: se marcan/desmarcan con pulsación larga (con confirmación) y salen primero en su categoría. */
object Favs {
    val pending = androidx.compose.runtime.mutableStateOf<String?>(null)
}

@Composable
fun rememberFavs(): Set<String> {
    val c = com.dskmusic.vixa.ui.ScrollHints.container ?: return emptySet()
    return c.settings.flow.collectAsState().value.favorites
}

/** Ordena poniendo antes los favoritos (estable). */
fun <T> List<T>.favFirst(favs: Set<String>, key: (T) -> String): List<T> = if (favs.isEmpty()) this else sortedByDescending { key(it) in favs }

@Composable
fun FavoritesHost() {
    val key = Favs.pending.value ?: return
    val c = com.dskmusic.vixa.ui.ScrollHints.container ?: run { Favs.pending.value = null; return }
    val isFav = key in c.settings.flow.collectAsState().value.favorites
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { Favs.pending.value = null },
        title = { Text(androidx.compose.ui.res.stringResource(if (isFav) R.string.fav_remove_title else R.string.fav_add_title)) },
        text = { Text(androidx.compose.ui.res.stringResource(if (isFav) R.string.fav_remove_msg else R.string.fav_add_msg)) },
        confirmButton = {
            androidx.compose.material3.TextButton({ c.settings.update { it.copy(favorites = if (isFav) it.favorites - key else it.favorites + key) }; Favs.pending.value = null }) {
                Text(androidx.compose.ui.res.stringResource(R.string.ok))
            }
        },
        dismissButton = { androidx.compose.material3.TextButton({ Favs.pending.value = null }) { Text(androidx.compose.ui.res.stringResource(R.string.cancel)) } },
    )
}
