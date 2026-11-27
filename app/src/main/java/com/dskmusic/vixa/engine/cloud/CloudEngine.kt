package com.dskmusic.vixa.engine.cloud

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.CornerPathEffect
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Una palabra ya colocada. Posicion y tamano en pixeles de la rejilla de colocacion. */
class Placed(
    val text: String,
    val cx: Float,
    val cy: Float,
    val size: Float,
    val angle: Float,
    val fontIdx: Int,
    /** Peso normalizado 0..1, para colorear por importancia. */
    val nw: Float,
)

class Layout(val w: Int, val h: Int, val items: List<Placed>)

/**
 * Motor de la nube de palabras en forma.
 *
 * Todo el calculo pesado (mascara, colocacion) se hace en una rejilla pequena y la imagen
 * final se dibuja a la resolucion pedida escalando las posiciones, de modo que la vista
 * previa y la exportacion en alta resolucion salen identicas.
 */
object CloudEngine {

    const val LAYOUT_SIDE = 640

    fun dims(aspect: Float, longSide: Int): Pair<Int, Int> =
        if (aspect >= 1f) longSide to (longSide / aspect).roundToInt().coerceAtLeast(1)
        else (longSide * aspect).roundToInt().coerceAtLeast(1) to longSide

    // ====================================================================================
    // Mascara de la forma
    // ====================================================================================

    private fun shapeMatrix(fx: ShapeFx, w: Int, h: Int, side: Float, srcW: Float, srcH: Float): Matrix {
        val m = Matrix()
        val k = side / max(srcW, srcH)
        m.postScale(k, k)
        m.postTranslate(-srcW * k / 2f, -srcH * k / 2f)
        m.postScale(if (fx.flipH) -1f else 1f, if (fx.flipV) -1f else 1f)
        m.postRotate(fx.rotation)
        m.postTranslate(w / 2f + fx.posX * w, h / 2f + fx.posY * h)
        return m
    }

    /** Trazado de la forma ya colocado en el lienzo (con esquinas redondeadas). */
    private fun placedPath(g: Geom, fx: ShapeFx, w: Int, h: Int): Path? {
        val base = g.path ?: return null
        val side = min(w, h) * fx.scale
        val p = Path(base)
        p.transform(shapeMatrix(fx, w, h, side, 1f, 1f))
        if (fx.corner > 0f) {
            val dst = Path()
            Paint().apply { pathEffect = CornerPathEffect(fx.corner / 100f * side * 0.5f) }.getFillPath(p, dst)
            return dst
        }
        return p
    }

    /** Mascara blanca con alfa a la resolucion pedida, con todos los ajustes de la forma. */
    fun buildMask(g: Geom, cfg: WordArtConfig, w: Int, h: Int): Bitmap {
        val fx = cfg.fx
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val side = min(w, h) * fx.scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
        val path = placedPath(g, fx, w, h)
        if (path != null) {
            c.drawPath(path, paint)
            if (g.strokeNorm > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = g.strokeNorm * side
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeCap = Paint.Cap.ROUND
                c.drawPath(path, paint)
            }
        } else g.bitmap?.let {
            c.drawBitmap(it, shapeMatrix(fx, w, h, side, it.width.toFloat(), it.height.toFloat()), paint)
        }
        val u = min(w, h) / 100f
        var out = bmp
        if (fx.dilate > 0f) out = grow(out, fx.dilate * u, true)
        if (fx.erode > 0f) out = grow(out, fx.erode * u, false)
        if (fx.smooth > 0f) out = blurThreshold(out, fx.smooth * u, 0.5f)
        return out
    }

    /** Dilata o erosiona una mascara (desenfoque + umbral). Consume [src]. */
    private fun grow(src: Bitmap, d: Float, outward: Boolean): Bitmap =
        blurThreshold(src, d.coerceAtLeast(0.6f), if (outward) 0.16f else 0.84f)

    /** Desenfoque gaussiano del alfa y umbral nitido: suaviza, dilata o erosiona. Consume [src]. */
    private fun blurThreshold(src: Bitmap, sigma: Float, t: Float): Bitmap {
        if (sigma < 0.6f) return src
        val radius = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.5f)
        val off = IntArray(2)
        val a8 = src.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) }, off)
        val tmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(tmp).drawBitmap(a8, off[0].toFloat(), off[1].toFloat(), Paint().apply { color = Color.WHITE })
        a8.recycle()
        val k = 12f
        val cm = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, k, 128f - k * t * 255f,
        ))
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(tmp, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        tmp.recycle()
        src.recycle()
        return out
    }

    /** Desenfoque barato por reduccion y ampliacion; vale para capas con color. */
    private fun softBlur(src: Bitmap, radius: Float): Bitmap {
        if (radius < 1f) return src
        val f = (radius / 1.5f).coerceAtLeast(1f)
        var cur = src
        repeat(2) {
            val small = Bitmap.createScaledBitmap(cur, max(1, (src.width / f).toInt()), max(1, (src.height / f).toInt()), true)
            val up = Bitmap.createScaledBitmap(small, src.width, src.height, true)
            small.recycle()
            if (cur !== src) cur.recycle()
            cur = up
        }
        return cur
    }

    // ====================================================================================
    // Colocacion de palabras
    // ====================================================================================

    private class Stamp(val ox: IntArray, val oy: IntArray, val n: Int)

    private fun facesFor(tfs: List<Typeface>, st: TextStyleSpec): List<Typeface> {
        val style = (if (st.bold) Typeface.BOLD else 0) or (if (st.italic) Typeface.ITALIC else 0)
        return tfs.ifEmpty { listOf(Typeface.DEFAULT) }.map { if (style != 0) Typeface.create(it, style) else it }
    }

    private fun wordPaint(face: Typeface, size: Float, st: TextStyleSpec) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size
        letterSpacing = st.letterSpacing
    }

    /** Pixeles (relativos al centro) que ocupa la palabra, con [stroke] px extra de grosor. */
    private fun rasterize(text: String, base: Paint, angle: Float, stroke: Float, maxW: Int, maxH: Int): Stamp? {
        val p = Paint(base).apply {
            color = Color.WHITE
            style = if (stroke > 0f) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            strokeWidth = stroke
            strokeJoin = Paint.Join.ROUND
        }
        val fm = p.fontMetrics
        val tw = p.measureText(text)
        val th = fm.descent - fm.ascent
        val r = Math.toRadians(angle.toDouble())
        val c = abs(cos(r)).toFloat()
        val s = abs(sin(r)).toFloat()
        val pad = ceil(stroke).toInt() + 2
        val bw = ceil(tw * c + th * s).toInt() + 2 * pad
        val bh = ceil(tw * s + th * c).toInt() + 2 * pad
        if (bw > maxW || bh > maxH || bw < 2 || bh < 2) return null
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            translate(bw / 2f, bh / 2f)
            rotate(angle)
            drawText(text, -tw / 2f, -(fm.ascent + fm.descent) / 2f, p)
        }
        val px = IntArray(bw * bh)
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
        bmp.recycle()
        var xs = IntArray(1024)
        var ys = IntArray(1024)
        var n = 0
        for (y in 0 until bh) for (x in 0 until bw) {
            if ((px[y * bw + x] ushr 24) > 30) {
                if (n == xs.size) {
                    xs = xs.copyOf(n * 2)
                    ys = ys.copyOf(n * 2)
                }
                xs[n] = x - bw / 2
                ys[n] = y - bh / 2
                n++
            }
        }
        return Stamp(xs, ys, n)
    }

    /** Reduce a la mitad los puntos de comprobacion de palabras enormes (patron de tablero). */
    private fun thin(s: Stamp): Stamp {
        var cur = s
        while (cur.n > 6000) {
            val xs = IntArray(cur.n / 2 + 1)
            val ys = IntArray(cur.n / 2 + 1)
            var m = 0
            for (i in 0 until cur.n) if (((cur.ox[i] xor cur.oy[i]) and 1) == 0) {
                xs[m] = cur.ox[i]; ys[m] = cur.oy[i]; m++
            }
            cur = Stamp(xs, ys, m)
        }
        return cur
    }

    /** Region libre (1 = se puede escribir): forma, margen, bordes y zonas de exclusion. */
    private fun buildFree(g: Geom, cfg: WordArtConfig, w: Int, h: Int): ByteArray {
        val u = min(w, h) / 100f
        var mask = buildMask(g, cfg, w, h)
        if (cfg.margin > 0f) mask = grow(mask, cfg.margin * u, cfg.inverted)
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        mask.recycle()
        val free = ByteArray(w * h)
        for (i in px.indices) {
            val inside = (px[i] ushr 24) > 127
            free[i] = if (inside != cfg.inverted) 1 else 0
        }
        val edge = (cfg.margin * u).toInt().coerceAtLeast(1)
        for (y in 0 until h) for (x in 0 until w) {
            if (x < edge || y < edge || x >= w - edge || y >= h - edge) free[y * w + x] = 0
        }
        if (cfg.zones.isNotEmpty()) {
            val zb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val zc = Canvas(zb)
            val zp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            for (z in cfg.zones) {
                val r = RectF((z.cx - z.rx) * w, (z.cy - z.ry) * h, (z.cx + z.rx) * w, (z.cy + z.ry) * h)
                if (z.ellipse) zc.drawOval(r, zp) else zc.drawRect(r, zp)
            }
            zb.getPixels(px, 0, w, 0, 0, w, h)
            zb.recycle()
            for (i in px.indices) if ((px[i] ushr 24) > 127) free[i] = 0
        }
        return free
    }

    suspend fun layout(
        cfg: WordArtConfig,
        g: Geom,
        tfs: List<Typeface>,
        side: Int = LAYOUT_SIDE,
    ): Layout = withContext(Dispatchers.Default) {
        val scope: CoroutineScope = this
        val (w, h) = dims(cfg.aspect, side)
        val minSide = min(w, h).toFloat()
        val words = cfg.parsedWords()
        if (words.isEmpty() && cfg.mainWord.isBlank()) return@withContext Layout(w, h, emptyList())

        val st = cfg.textStyle
        val faces = facesFor(tfs, st)
        val free = buildFree(g, cfg, w, h)
        val rnd = Random(cfg.seed)
        val angles = cfg.rotAngles.split(',', ' ', ';').mapNotNull { it.trim().toFloatOrNull() }.ifEmpty { listOf(0f) }
        val spacingPx = cfg.spacing / 100f * minSide
        val outlinePx = { size: Float -> if (st.outline) st.outlineWidth / 100f * size else 0f }
        val minPx = max(7f, cfg.minSize / 100f * minSide * 0.8f)
        val maxWords = 20 + (cfg.density * 8).toInt()
        val failLimit = 12 + (cfg.density / 3).toInt()
        val tries = 60 + (cfg.density * 2).toInt()

        val wMin = words.minOfOrNull { it.weight } ?: 1f
        val wMax = words.maxOfOrNull { it.weight } ?: 1f
        val equal = wMax - wMin < 1e-6f
        fun sizeOf(nw: Float) = (cfg.minSize + (cfg.maxSize - cfg.minSize) * nw.pow(0.8f)) / 100f * minSide

        val items = ArrayList<Placed>()
        var freeList = IntArray(0)
        var sinceRebuild = Int.MAX_VALUE
        fun rebuild() {
            var n = 0
            for (b in free) if (b.toInt() != 0) n++
            val arr = IntArray(n)
            var k = 0
            for (i in free.indices) if (free[i].toInt() != 0) arr[k++] = i
            freeList = arr
            sinceRebuild = 0
        }

        fun pickAngle(): Float = when (cfg.rotMode) {
            RotMode.HORIZONTAL -> 0f
            RotMode.VERTICAL -> -90f
            RotMode.MIXED -> if (rnd.nextFloat() < 0.65f) 0f else if (rnd.nextBoolean()) 90f else -90f
            RotMode.RANDOM -> rnd.nextFloat() * 180f - 90f
            RotMode.ANGLES -> angles[rnd.nextInt(angles.size)]
        }

        fun fits(s: Stamp, px: Int, py: Int): Boolean {
            val stride = if (s.n % 37 == 0) 41 else 37
            var i = 0
            var k = 0
            while (i < s.n) {
                val x = px + s.ox[k]
                val y = py + s.oy[k]
                if (x < 0 || y < 0 || x >= w || y >= h || free[y * w + x].toInt() == 0) return false
                k += stride
                if (k >= s.n) k -= s.n
                i++
            }
            return true
        }

        fun commit(text: String, paint: Paint, angle: Float, x: Int, y: Int, size: Float, fontIdx: Int, nw: Float) {
            items += Placed(text, x.toFloat(), y.toFloat(), size, angle, fontIdx, nw)
            val dil = rasterize(text, paint, angle, 2f * spacingPx + outlinePx(size), w, h)
            if (dil != null) {
                for (i in 0 until dil.n) {
                    val xx = x + dil.ox[i]
                    val yy = y + dil.oy[i]
                    if (xx in 0 until w && yy in 0 until h) free[yy * w + xx] = 0
                }
            }
            sinceRebuild++
        }

        /** Busca sitio al azar; si lo encuentra, escribe la palabra. */
        fun tryPlace(text: String, nw: Float, startSize: Float, fixedAt: Pair<Int, Int>? = null): Boolean {
            var size = startSize
            val fontIdx = if (faces.size > 1) rnd.nextInt(faces.size) else 0
            repeat(6) {
                if (size < minPx) return false
                scope.ensureActive()
                for (rot in 0 until 2) {
                    val angle = if (fixedAt != null) 0f else pickAngle()
                    val paint = wordPaint(faces[fontIdx], size, st)
                    val stamp = rasterize(text, paint, angle, outlinePx(size), w, h)?.let { thin(it) } ?: continue
                    if (stamp.n == 0) continue
                    if (fixedAt != null) {
                        var rad = 0
                        val lim = (minSide * 0.22f).toInt()
                        while (rad <= lim) {
                            val steps = if (rad == 0) 1 else max(8, rad / 2)
                            for (j in 0 until steps) {
                                val a = 2 * Math.PI * j / steps
                                val x = fixedAt.first + (rad * cos(a)).toInt()
                                val y = fixedAt.second + (rad * sin(a)).toInt()
                                if (fits(stamp, x, y)) {
                                    commit(text, paint, angle, x, y, size, fontIdx, nw)
                                    return true
                                }
                            }
                            rad += 4
                        }
                    } else {
                        if (sinceRebuild >= 6 || freeList.isEmpty()) rebuild()
                        if (freeList.isEmpty()) return false
                        repeat(tries) {
                            val idx = freeList[rnd.nextInt(freeList.size)]
                            val x = idx % w
                            val y = idx / w
                            if (fits(stamp, x, y)) {
                                commit(text, paint, angle, x, y, size, fontIdx, nw)
                                return true
                            }
                        }
                    }
                }
                size *= 0.86f
            }
            return false
        }

        rebuild()
        if (freeList.isEmpty()) return@withContext Layout(w, h, emptyList())

        // Palabra principal: grande, en el centro de la region libre.
        if (cfg.mainWord.isNotBlank()) {
            var sx = 0L
            var sy = 0L
            for (i in freeList) { sx += i % w; sy += i / w }
            val centroid = (sx / freeList.size).toInt() to (sy / freeList.size).toInt()
            tryPlace(cfg.mainWord.trim(), 1f, cfg.maxSize * 1.3f / 100f * minSide, centroid)
        }

        val first = if (cfg.order == WordOrder.BY_WEIGHT) words.sortedByDescending { it.weight } else words.shuffled(rnd)
        for (wd in first) {
            if (items.size >= maxWords) break
            val nw = if (equal) 0.35f + 0.65f * rnd.nextFloat() else (wd.weight - wMin) / (wMax - wMin)
            tryPlace(wd.text, nw, sizeOf(nw))
        }

        if (cfg.repeatWords && words.isNotEmpty()) {
            val total = words.sumOf { it.weight.toDouble() }.toFloat()
            var fails = 0
            while (items.size < maxWords && fails < failLimit) {
                scope.ensureActive()
                var r = rnd.nextFloat() * total
                var pick = words.last()
                for (wd in words) { r -= wd.weight; if (r <= 0f) { pick = wd; break } }
                val nw = if (equal) rnd.nextFloat() * 0.6f else (pick.weight - wMin) / (wMax - wMin)
                val size = sizeOf(nw) * (0.5f + 0.5f * rnd.nextFloat())
                if (tryPlace(pick.text, nw, size.coerceAtLeast(minPx * 1.1f))) fails = 0 else fails++
            }
        }
        Layout(w, h, items)
    }

    // ====================================================================================
    // Dibujo final
    // ====================================================================================

    private fun colorFor(cfg: WordArtConfig, it: Placed, i: Int, lw: Int, lh: Int): Int {
        val pal = cfg.palette.ifEmpty { listOf(Color.BLACK) }
        val rnd = Random(cfg.seed * 31 + i * 7919L)
        return when (cfg.colorMode) {
            ColorMode.SINGLE -> cfg.color1
            ColorMode.PALETTE -> pal[rnd.nextInt(pal.size)]
            ColorMode.RANDOM -> Color.HSVToColor(floatArrayOf(rnd.nextFloat() * 360f, 0.6f + 0.35f * rnd.nextFloat(), 0.75f + 0.25f * rnd.nextFloat()))
            ColorMode.GRADIENT -> {
                val a = Math.toRadians(cfg.gradAngle.toDouble())
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                val half = (abs(lw * dx) + abs(lh * dy)) / 2f
                val t = (((it.cx - lw / 2f) * dx + (it.cy - lh / 2f) * dy) / (2f * half) + 0.5f).coerceIn(0f, 1f)
                ColorUtils.blendARGB(cfg.color1, cfg.color2, t)
            }
            ColorMode.BY_WEIGHT -> {
                val pos = it.nw.coerceIn(0f, 1f) * (pal.size - 1)
                val lo = pos.toInt().coerceAtMost(pal.size - 1)
                val hi = min(lo + 1, pal.size - 1)
                ColorUtils.blendARGB(pal[lo], pal[hi], pos - lo)
            }
        }
    }

    fun render(
        cfg: WordArtConfig,
        g: Geom,
        layout: Layout,
        tfs: List<Typeface>,
        outW: Int,
        outH: Int,
        imageOf: (String, Int) -> Bitmap?,
    ): Bitmap {
        val fx = cfg.fx
        val u = min(outW, outH) / 100f
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val mask = buildMask(g, cfg, outW, outH)

        if (cfg.bgCanvas.kind != BgKind.NONE) drawBackground(c, cfg.bgCanvas, outW, outH, imageOf)
        if (fx.shadowOn) blurFill(c, mask, fx.shadowColor, fx.shadowBlur * u, fx.shadowDx * u, fx.shadowDy * u)
        if (fx.glowOn) blurFill(c, mask, fx.glowColor, fx.glowRadius * u, 0f, 0f)

        // Capa de la forma: fondos de las dos zonas, relieve y borde.
        val layer = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        drawZone(lc, cfg.bgWords, mask, !cfg.inverted, outW, outH, imageOf)
        drawZone(lc, cfg.bgOther, mask, cfg.inverted, outW, outH, imageOf)
        if (fx.bevelOn) {
            val d = fx.bevelSize * u
            edgeLight(lc, mask, d, d, Color.WHITE, fx.bevelStrength, d * 0.6f)
            edgeLight(lc, mask, -d, -d, Color.BLACK, fx.bevelStrength * 0.8f, d * 0.6f)
        }
        if (fx.borderOn) drawBorder(lc, g, cfg, mask, outW, outH, u)
        val shown = if (fx.blur > 0f) softBlur(layer, fx.blur * u) else layer
        c.drawBitmap(shown, 0f, 0f, Paint().apply { alpha = (fx.opacity.coerceIn(0f, 1f) * 255).roundToInt() })
        if (shown !== layer) shown.recycle()
        layer.recycle()
        mask.recycle()

        drawWords(c, cfg, layout, tfs, outW.toFloat() / layout.w)
        return out
    }

    private fun blurFill(c: Canvas, mask: Bitmap, color: Int, blur: Float, dx: Float, dy: Float) {
        val off = IntArray(2)
        val bp = Paint().apply { if (blur > 0.5f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) }
        val a8 = mask.extractAlpha(bp, off)
        c.drawBitmap(a8, off[0] + dx, off[1] + dy, Paint().apply { this.color = color })
        a8.recycle()
    }

    /** Borde luminoso o sombreado de un lado de la forma (relieve). */
    private fun edgeLight(lc: Canvas, mask: Bitmap, dx: Float, dy: Float, color: Int, strength: Float, blur: Float) {
        val edge = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        val ec = Canvas(edge)
        ec.drawBitmap(mask, 0f, 0f, null)
        ec.drawBitmap(mask, dx, dy, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        val off = IntArray(2)
        val a8 = edge.extractAlpha(Paint().apply { if (blur > 0.5f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) }, off)
        edge.recycle()
        val sc = lc.saveLayer(0f, 0f, mask.width.toFloat(), mask.height.toFloat(), null)
        lc.drawBitmap(a8, off[0].toFloat(), off[1].toFloat(), Paint().apply {
            this.color = color
            alpha = (strength.coerceIn(0f, 1f) * 255).roundToInt()
        })
        lc.drawBitmap(mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        lc.restoreToCount(sc)
        a8.recycle()
    }

    private fun drawZone(c: Canvas, spec: BackgroundSpec, mask: Bitmap, insideMask: Boolean, w: Int, h: Int, imageOf: (String, Int) -> Bitmap?) {
        if (spec.kind == BgKind.NONE) return
        val sc = c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        drawBackground(c, spec, w, h, imageOf)
        c.drawBitmap(mask, 0f, 0f, Paint().apply {
            xfermode = PorterDuffXfermode(if (insideMask) PorterDuff.Mode.DST_IN else PorterDuff.Mode.DST_OUT)
        })
        c.restoreToCount(sc)
    }

    /** Fondo completo del lienzo segun su tipo. Se usa tambien para las miniaturas del editor. */
    fun drawBackground(c: Canvas, spec: BackgroundSpec, w: Int, h: Int, imageOf: (String, Int) -> Bitmap?) {
        val sc = c.saveLayerAlpha(0f, 0f, w.toFloat(), h.toFloat(), (spec.opacity.coerceIn(0f, 1f) * 255).roundToInt())
        val wf = w.toFloat()
        val hf = h.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (spec.kind) {
            BgKind.NONE -> Unit
            BgKind.SOLID -> c.drawColor(spec.color)
            BgKind.GRADIENT -> {
                val colors = spec.gradColors.ifEmpty { listOf(spec.color, spec.color2) }.let { if (it.size < 2) it + it else it }.toIntArray()
                paint.shader = if (spec.radial) {
                    RadialGradient(wf / 2f, hf / 2f, max(wf, hf) * 0.7f, colors, null, Shader.TileMode.CLAMP)
                } else {
                    val a = Math.toRadians(spec.angle.toDouble())
                    val dx = cos(a).toFloat()
                    val dy = sin(a).toFloat()
                    val half = (abs(wf * dx) + abs(hf * dy)) / 2f
                    LinearGradient(wf / 2f - dx * half, hf / 2f - dy * half, wf / 2f + dx * half, hf / 2f + dy * half, colors, null, Shader.TileMode.CLAMP)
                }
                c.drawRect(0f, 0f, wf, hf, paint)
            }
            BgKind.IMAGE -> {
                val img = spec.imagePath?.let { imageOf(it, max(w, h)) }
                if (img != null) {
                    val tmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val tc = Canvas(tmp)
                    val iw = img.width.toFloat()
                    val ih = img.height.toFloat()
                    val k = if (spec.fit == ImgFit.FIT) min(wf / iw, hf / ih) else max(wf / iw, hf / ih)
                    val m = Matrix()
                    if (spec.fit == ImgFit.STRETCH) m.setScale(wf / iw, hf / ih)
                    else { m.setScale(k, k); m.postTranslate((wf - iw * k) / 2f, (hf - ih * k) / 2f) }
                    tc.drawBitmap(img, m, Paint(Paint.FILTER_BITMAP_FLAG))
                    val shown = if (spec.imgBlur > 0f) softBlur(tmp, spec.imgBlur / 100f * min(w, h)) else tmp
                    c.drawBitmap(shown, 0f, 0f, null)
                    if (shown !== tmp) shown.recycle()
                    tmp.recycle()
                }
            }
            BgKind.PATTERN -> {
                c.drawColor(spec.color2)
                paint.color = spec.color
                val s = (spec.patternSize / 100f * min(w, h)).coerceAtLeast(4f)
                when (spec.pattern) {
                    PatternKind.STRIPES -> {
                        c.save()
                        c.rotate(45f, wf / 2f, hf / 2f)
                        val span = max(wf, hf) * 1.5f
                        var x = -span
                        while (x < span * 2) { c.drawRect(x, -span, x + s, span * 2, paint); x += s * 2 }
                        c.restore()
                    }
                    PatternKind.DOTS -> {
                        var row = 0
                        var y = s / 2
                        while (y < hf + s) {
                            var x = if (row % 2 == 0) s / 2 else s
                            while (x < wf + s) { c.drawCircle(x, y, s * 0.28f, paint); x += s }
                            y += s * 0.9f
                            row++
                        }
                    }
                    PatternKind.GRID -> {
                        paint.strokeWidth = max(1f, s * 0.08f)
                        var x = 0f
                        while (x < wf) { c.drawLine(x, 0f, x, hf, paint); x += s }
                        var y = 0f
                        while (y < hf) { c.drawLine(0f, y, wf, y, paint); y += s }
                    }
                    PatternKind.CHECKER -> {
                        var y = 0f
                        var r = 0
                        while (y < hf) {
                            var x = if (r % 2 == 0) 0f else s
                            while (x < wf) { c.drawRect(x, y, x + s, y + s, paint); x += s * 2 }
                            y += s
                            r++
                        }
                    }
                }
            }
        }
        c.restoreToCount(sc)
    }

    private fun drawBorder(lc: Canvas, g: Geom, cfg: WordArtConfig, mask: Bitmap, w: Int, h: Int, u: Float) {
        val fx = cfg.fx
        val bw = (fx.borderWidth * u).coerceAtLeast(1f)
        val ring = ringAlpha(g, cfg, mask, w, h, bw, fx.borderStyle)
        if (fx.doubleBorder) {
            val thin = ringAlpha(g, cfg, mask, w, h, bw * 0.35f, BorderStyle.SOLID)
            Canvas(ring).drawBitmap(thin, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            thin.recycle()
        }
        val alpha = (fx.borderOpacity.coerceIn(0f, 1f) * 255).roundToInt()
        if (fx.borderGradient) {
            val colored = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val cc = Canvas(colored)
            cc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), fx.borderColor, fx.borderColor2, Shader.TileMode.CLAMP)
            })
            cc.drawBitmap(ring, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
            lc.drawBitmap(colored, 0f, 0f, Paint().apply { this.alpha = alpha })
            colored.recycle()
        } else {
            lc.drawBitmap(ring, 0f, 0f, Paint().apply {
                colorFilter = android.graphics.PorterDuffColorFilter(fx.borderColor, PorterDuff.Mode.SRC_IN)
                this.alpha = alpha
            })
        }
        ring.recycle()
    }

    /** Anillo centrado en el contorno. Con trazado vectorial admite punteado y discontinuo. */
    private fun ringAlpha(g: Geom, cfg: WordArtConfig, mask: Bitmap, w: Int, h: Int, bw: Float, bstyle: BorderStyle): Bitmap {
        val fx = cfg.fx
        val ring = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rc = Canvas(ring)
        val path = placedPath(g, fx, w, h)
        val modified = fx.smooth > 0f || fx.erode > 0f || fx.dilate > 0f
        if (path != null && !modified) {
            rc.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.style = Paint.Style.STROKE
                strokeWidth = bw
                strokeJoin = Paint.Join.ROUND
                when (bstyle) {
                    BorderStyle.SOLID -> Unit
                    BorderStyle.DASHED -> pathEffect = DashPathEffect(floatArrayOf(bw * 4f, bw * 2.5f), 0f)
                    BorderStyle.DOTTED -> { strokeCap = Paint.Cap.ROUND; pathEffect = DashPathEffect(floatArrayOf(0.01f, bw * 2.2f), 0f) }
                }
            })
        } else {
            val outer = grow(mask.copy(Bitmap.Config.ARGB_8888, true), bw / 2f, true)
            val inner = grow(mask.copy(Bitmap.Config.ARGB_8888, true), bw / 2f, false)
            rc.drawBitmap(outer, 0f, 0f, null)
            rc.drawBitmap(inner, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            outer.recycle()
            inner.recycle()
        }
        return ring
    }

    private fun drawWords(c: Canvas, cfg: WordArtConfig, layout: Layout, tfs: List<Typeface>, scale: Float) {
        val st = cfg.textStyle
        val faces = facesFor(tfs, st)
        layout.items.forEachIndexed { i, it ->
            val size = it.size * scale
            val p = wordPaint(faces[it.fontIdx % faces.size], size, st)
            val color = colorFor(cfg, it, i, layout.w, layout.h)
            val tw = p.measureText(it.text)
            val fm = p.fontMetrics
            val x = -tw / 2f
            val y = -(fm.ascent + fm.descent) / 2f
            c.save()
            c.translate(it.cx * scale, it.cy * scale)
            c.rotate(it.angle)
            p.style = Paint.Style.FILL
            p.color = color
            if (st.glow) {
                p.color = st.glowColor
                p.setShadowLayer(max(1f, st.glowRadius / 100f * size), 0f, 0f, st.glowColor)
                c.drawText(it.text, x, y, p)
                p.clearShadowLayer()
                p.color = color
            }
            if (st.shadow) {
                p.setShadowLayer(max(0.1f, st.shadowBlur / 100f * size), st.shadowDx / 100f * size, st.shadowDy / 100f * size, st.shadowColor)
                c.drawText(it.text, x, y, p)
                p.clearShadowLayer()
            }
            if (st.outline) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = st.outlineWidth / 100f * size
                p.strokeJoin = Paint.Join.ROUND
                p.color = st.outlineColor
                c.drawText(it.text, x, y, p)
                p.style = Paint.Style.FILL
                p.color = color
            }
            if (st.gradient) {
                val a = Math.toRadians(st.gradAngle.toDouble())
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                val hw = tw / 2f
                val hh = (fm.descent - fm.ascent) / 2f
                val half = abs(hw * dx) + abs(hh * dy)
                p.shader = LinearGradient(-dx * half, -dy * half, dx * half, dy * half, color, st.gradColor2, Shader.TileMode.CLAMP)
            }
            c.drawText(it.text, x, y, p)
            c.restore()
        }
    }
}
