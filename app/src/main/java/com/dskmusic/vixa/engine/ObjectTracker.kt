package com.dskmusic.vixa.engine

import android.graphics.Bitmap
import kotlinx.coroutines.ensureActive
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Seguimiento de la POSICIÓN de un objeto, sin modelos (el tamaño y el giro no se calculan: eran inestables).
 *
 * - **Posición**: la plantilla del objeto (el recuadro marcado) se busca en cada fotograma con correlación cruzada
 *   normalizada (ZNCC, aguanta cambios de luz). Primero cerca de donde se esperaba; si no aparece se busca en todo el
 *   fotograma con la plantilla original (así se recupera el objeto cuando vuelve a salir tras una sacudida o un giro).
 * - Cuando el objeto no se ve, las posiciones se interpolan entre los fotogramas en que sí se encontró (y se marcan como no
 *   encontrados para poder ocultar lo que se haya pegado).
 */
object ObjectTracker {

    /**
     * Seguimiento resultante. [cx]/[cy] son la posición del centro del objeto (0..1 del fotograma); [scale] y [rot] se
     * conservan en el formato del archivo pero valen siempre 1 y 0. [found] indica
     * [found] si en ese fotograma el objeto se encontró de verdad (false = posición interpolada).
     */
    class Track(val times: LongArray, val cx: FloatArray, val cy: FloatArray, val scale: FloatArray, val rot: FloatArray, val found: BooleanArray, val lost: Int) {
        val size: Int get() = times.size

        fun save(f: File) {
            f.parentFile?.mkdirs()
            DataOutputStream(f.outputStream().buffered()).use { o ->
                o.writeBytes("VXT2")
                o.writeInt(size)
                for (i in 0 until size) { o.writeLong(times[i]); o.writeFloat(cx[i]); o.writeFloat(cy[i]); o.writeFloat(scale[i]); o.writeFloat(rot[i]); o.writeBoolean(found[i]) }
            }
        }

        companion object {
            fun load(f: File): Track? = try {
                DataInputStream(f.inputStream().buffered()).use { i ->
                    val m = ByteArray(4)
                    i.readFully(m)
                    // Los análisis de la versión anterior (VXTK) no llevan datos de "encontrado": se calculan de nuevo.
                    if (String(m, Charsets.US_ASCII) != "VXT2") return null
                    val n = i.readInt()
                    if (n <= 0 || n > 100_000) return null
                    val t = LongArray(n); val x = FloatArray(n); val y = FloatArray(n); val s = FloatArray(n); val r = FloatArray(n); val fd = BooleanArray(n)
                    for (k in 0 until n) { t[k] = i.readLong(); x[k] = i.readFloat(); y[k] = i.readFloat(); s[k] = i.readFloat(); r[k] = i.readFloat(); fd[k] = i.readBoolean() }
                    Track(t, x, y, s, r, fd, fd.count { !it })
                }
            } catch (e: Throwable) {
                null
            }
        }
    }

    private const val DISPLAY_W = 240
    /** Coste máximo (1 - ZNCC) para dar por buena una coincidencia local o una global. */
    private const val LOCAL_OK = 0.5f
    private const val GLOBAL_OK = 0.42f
    private const val UPDATE_OK = 0.3f

    private fun gray(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return ByteArray(w * h) {
            val p = px[it]
            ((((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8).toByte()
        }
    }

    /**
     * Sigue el objeto marcado en ([u0], [v0]) del instante [startUs] por todo el tramo [fromUs, toUs] del medio.
     * [sizeFrac] es el tamaño del recuadro como fracción del ancho del fotograma. Devuelve null si no se puede leer el vídeo.
     */
    suspend fun track(path: String, fromUs: Long, toUs: Long, startUs: Long, u0: Float, v0: Float, sizeFrac: Float, progress: (Float) -> Unit): Track? {
        val stepUs = maxOf(1_000_000L / 15, (toUs - fromUs) / 900)
        val frames = ArrayList<ByteArray>()
        var w = 0
        var h = 0
        val ok = FrameStrip.extract(path, fromUs, toUs, stepUs, 1000, DISPLAY_W) { bmp ->
            if (w == 0) { w = bmp.width; h = bmp.height }
            if (bmp.width == w && bmp.height == h) frames.add(gray(bmp))
            progress(minOf(0.3f, frames.size * stepUs.toFloat() / (toUs - fromUs).coerceAtLeast(1) * 0.3f))
        }
        if (!ok || frames.isEmpty()) return null
        val n = frames.size
        val i0 = (((startUs - fromUs).toFloat() / stepUs) + 0.5f).toInt().coerceIn(0, n - 1)

        // Plantilla = el recuadro del usuario (con un mínimo para que tenga detalle suficiente).
        val rp = maxOf(8, (sizeFrac * w / 2f).toInt()).coerceAtMost(minOf(w, h) / 3)
        val search = maxOf(24, (rp * 1.6f).toInt())
        val m = rp + 1f
        val cx0 = (u0 * w).coerceIn(m, w - 1 - m)
        val cy0 = (v0 * h).coerceIn(m, h - 1 - m)

        val cxs = FloatArray(n); val cys = FloatArray(n); val ss = FloatArray(n) { 1f }; val rs = FloatArray(n)
        val found = BooleanArray(n)
        cxs[i0] = cx0; cys[i0] = cy0; found[i0] = true
        for (dir in intArrayOf(1, -1)) {
            val center = Matcher(w, h, rp)
            center.setTemplate(frames[i0], cx0.toInt(), cy0.toInt())
            val anchor = Matcher(w, h, rp)
            anchor.setTemplate(frames[i0], cx0.toInt(), cy0.toInt())
            var cx = cx0; var cy = cy0
            var vx = 0f; var vy = 0f
            var i = i0 + dir
            while (i in 0 until n) {
                coroutineContext.ensureActive()
                val f = frames[i]
                val px = (cx + vx * 0.8f).coerceIn(m, w - 1 - m)
                val py = (cy + vy * 0.8f).coerceIn(m, h - 1 - m)
                var q = center.findLocal(f, px.toInt(), py.toInt(), search)
                var reacquired = false
                if (q.cost > LOCAL_OK) {
                    // Perdido: se busca en todo el fotograma con la plantilla original y con la actual.
                    val g1 = anchor.findGlobal(f)
                    val g2 = center.findGlobal(f)
                    val g = if (g1.cost <= g2.cost) g1 else g2
                    if (g.cost <= GLOBAL_OK) { q = g; reacquired = true } else q = Match(cx.toInt(), cy.toInt(), 1f)
                }
                val good = q.cost <= (if (reacquired) GLOBAL_OK else LOCAL_OK)
                if (good) {
                    if (reacquired) { vx = 0f; vy = 0f } else { vx = q.x - cx; vy = q.y - cy }
                    cx = q.x.toFloat(); cy = q.y.toFloat()
                    if (q.cost <= UPDATE_OK) center.update(f, q.x, q.y)
                } else { vx = 0f; vy = 0f }
                cxs[i] = cx; cys[i] = cy; found[i] = good
                i += dir
                progress(0.3f + 0.7f * (if (dir == 1) (i - i0).toFloat() / (2 * n) else 0.5f + (i0 - i).toFloat() / (2 * n)).coerceIn(0f, 1f))
            }
        }
        interpolateLost(cxs, cys, ss, rs, found)
        // Giros continuos (sin saltos de 2π) y suavizado de 3 puntos.
        val two = (2 * Math.PI).toFloat()
        for (a in intArrayOf(1, -1)) {
            var j = i0 + a
            while (j in 0 until n) {
                while (rs[j] - rs[j - a] > Math.PI) rs[j] -= two
                while (rs[j] - rs[j - a] < -Math.PI) rs[j] += two
                j += a
            }
        }
        fun sm(a: FloatArray) = FloatArray(n) { (a[maxOf(it - 1, 0)] + a[it] * 2 + a[minOf(it + 1, n - 1)]) / 4f }
        val sx = sm(cxs); val sy = sm(cys)
        return Track(LongArray(n) { fromUs + it * stepUs }, FloatArray(n) { sx[it] / w }, FloatArray(n) { sy[it] / h }, sm(ss), sm(rs), found, found.count { !it })
    }

    /** Rellena los fotogramas no encontrados interpolando entre los encontrados (o repitiendo el más cercano en los extremos). */
    private fun interpolateLost(x: FloatArray, y: FloatArray, s: FloatArray, r: FloatArray, found: BooleanArray) {
        val n = found.size
        var i = 0
        while (i < n) {
            if (found[i]) { i++; continue }
            var a = i - 1
            var b = i
            while (b < n && !found[b]) b++
            for (j in i until b) {
                val t = if (a >= 0 && b < n) (j - a).toFloat() / (b - a) else 0f
                val ref1 = if (a >= 0) a else b.coerceAtMost(n - 1)
                val ref2 = if (b < n) b else ref1
                x[j] = x[ref1] + (x[ref2] - x[ref1]) * t
                y[j] = y[ref1] + (y[ref2] - y[ref1]) * t
                s[j] = s[ref1] + (s[ref2] - s[ref1]) * t
                r[j] = r[ref1] + (r[ref2] - r[ref1]) * t
            }
            i = b
        }
    }

    private class Match(val x: Int, val y: Int, val cost: Float)

    /** Plantilla de (2r+1)² píxeles y búsqueda por correlación cruzada normalizada (coste = 1 - ZNCC). */
    private class Matcher(val w: Int, val h: Int, val r: Int) {
        private val side = 2 * r + 1
        private val tpl = FloatArray(side * side)

        private fun at(f: ByteArray, x: Int, y: Int): Int = f[y.coerceIn(0, h - 1) * w + x.coerceIn(0, w - 1)].toInt() and 0xFF

        fun setTemplate(f: ByteArray, cx: Int, cy: Int) {
            for (j in 0 until side) for (i in 0 until side) tpl[j * side + i] = at(f, cx - r + i, cy - r + j).toFloat()
        }

        /** 1 - ZNCC entre la plantilla y la ventana centrada en (cx, cy), muestreando de [step] en [step] píxeles. */
        private fun cost(f: ByteArray, cx: Int, cy: Int, step: Int): Float {
            var n = 0
            var sw = 0f; var st = 0f; var sww = 0f; var stt = 0f; var swt = 0f
            var j = 0
            while (j < side) {
                var i = 0
                while (i < side) {
                    val a = at(f, cx - r + i, cy - r + j).toFloat()
                    val b = tpl[j * side + i]
                    sw += a; st += b; sww += a * a; stt += b * b; swt += a * b; n++
                    i += step
                }
                j += step
            }
            val vw = sww - sw * sw / n
            val vt = stt - st * st / n
            if (vw < 1f || vt < 1f) return 1f // ventana o plantilla sin textura
            if (vw < 0.3f * vt) return 1f      // ventana mucho más lisa que la plantilla (cielo, pared): no puede ser el objeto
            val zncc = (swt - sw * st / n) / sqrt(vw * vt)
            return 1f - zncc
        }

        /** Búsqueda cerca de (px, py): paso grueso y afinado a resolución completa. */
        fun findLocal(f: ByteArray, px: Int, py: Int, search: Int): Match {
            var bx = px
            var by = py
            var best = Float.MAX_VALUE
            val step = if (r >= 14) 3 else 2
            var dy = -search
            while (dy <= search) {
                var dx = -search
                while (dx <= search) {
                    val x = (px + dx).coerceIn(r, w - 1 - r)
                    val y = (py + dy).coerceIn(r, h - 1 - r)
                    val c = cost(f, x, y, if (r >= 14) 3 else 2) + 0.0003f * (abs(dx) + abs(dy)) // ligera preferencia por no moverse
                    if (c < best) { best = c; bx = x; by = y }
                    dx += step
                }
                dy += step
            }
            return refine(f, bx, by, step)
        }

        /** Búsqueda en todo el fotograma (rejilla gruesa) para recuperar un objeto perdido. */
        fun findGlobal(f: ByteArray): Match {
            val stride = maxOf(3, r / 5)
            val sub = maxOf(2, r / 6)
            var bx = r
            var by = r
            var best = Float.MAX_VALUE
            var y = r
            while (y <= h - 1 - r) {
                var x = r
                while (x <= w - 1 - r) {
                    val c = cost(f, x, y, sub)
                    if (c < best) { best = c; bx = x; by = y }
                    x += stride
                }
                y += stride
            }
            return refine(f, bx, by, stride)
        }

        private fun refine(f: ByteArray, bx: Int, by: Int, reach: Int): Match {
            var fx = bx
            var fy = by
            var fb = Float.MAX_VALUE
            for (oy in -reach..reach) for (ox in -reach..reach) {
                val x = (bx + ox).coerceIn(r, w - 1 - r)
                val y = (by + oy).coerceIn(r, h - 1 - r)
                val c = cost(f, x, y, 1)
                if (c < fb) { fb = c; fx = x; fy = y }
            }
            return Match(fx, fy, fb)
        }

        /** Mezcla un poco del aspecto actual en la plantilla para seguir cambios graduales. */
        fun update(f: ByteArray, cx: Int, cy: Int) {
            for (j in 0 until side) for (i in 0 until side) {
                val q = j * side + i
                tpl[q] = tpl[q] * 0.9f + at(f, cx - r + i, cy - r + j) * 0.1f
            }
        }
    }
}
