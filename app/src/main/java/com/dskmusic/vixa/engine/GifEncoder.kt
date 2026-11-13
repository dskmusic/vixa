package com.dskmusic.vixa.engine

import android.graphics.Bitmap
import java.io.OutputStream
import kotlin.math.abs

/**
 * Codificador GIF89a animado con paleta ADAPTATIVA por fotograma (corte por la mediana sobre los colores que de
 * verdad aparecen), tramado ordenado opcional y compresión LZW.
 *
 * Cómo reduce el tamaño sin estropear la imagen:
 *  - Transparencia entre fotogramas: los píxeles que no cambian respecto a lo que ya se ve se escriben como
 *    transparentes y cada fotograma solo ocupa el rectángulo que cambió. Con [level] > 0 se toleran diferencias
 *    muy pequeñas (el ruido de la compresión del vídeo), imperceptibles a niveles bajos.
 *  - Cada fotograma lleva su propia paleta, calculada solo con los píxeles que cambian: los colores disponibles se
 *    concentran donde hace falta, mucho mejor que una paleta fija.
 * [speed] multiplica la velocidad y [loop] = false reproduce el GIF una sola vez.
 */
class GifEncoder(
    private val out: OutputStream, private val width: Int, private val height: Int, fps: Int,
    level: Int = 0, private val dither: Boolean = true, loop: Boolean = true, speed: Float = 1f,
) {
    private val delayCs = Math.round(100f / (fps * speed).coerceIn(1f, 50f)).coerceAtLeast(2)
    private val pixels = IntArray(width * height)
    private val shown = IntArray(width * height)
    private val indexed = ByteArray(width * height)
    private var hasPrev = false

    /** Tolerancia por canal para dar un píxel por "igual" y número máximo de colores por fotograma. */
    private val tol: Int
    private val maxColors: Int

    init {
        when (level.coerceIn(0, 3)) {
            0 -> { tol = 0; maxColors = 255 }
            1 -> { tol = 4; maxColors = 255 }
            2 -> { tol = 8; maxColors = 160 }
            else -> { tol = 14; maxColors = 96 }
        }
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(width); short(height)
        out.write(0x00) // sin paleta global: cada fotograma lleva la suya
        out.write(0); out.write(0)
        if (loop) {
            // Extensión de bucle infinito. Sin ella el GIF se reproduce una sola vez.
            out.write(0x21); out.write(0xFF); out.write(11)
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(3); out.write(1); short(0); out.write(0)
        }
    }

    private fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    private fun close(a: Int, b: Int): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tol &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tol &&
            abs((a and 0xFF) - (b and 0xFF)) <= tol

    // --- Paleta adaptativa (corte por la mediana sobre un histograma RGB555) -----------------------------------
    private val hist = IntArray(32768)
    private val sumR = IntArray(32768)
    private val sumG = IntArray(32768)
    private val sumB = IntArray(32768)
    private val binToIdx = IntArray(32768)
    private val palR = IntArray(256)
    private val palG = IntArray(256)
    private val palB = IntArray(256)
    private var palCount = 0

    private fun binOf(r: Int, g: Int, b: Int) = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)

    /** Construye [palR]/[palG]/[palB] con como mucho [maxColors] colores a partir del histograma. */
    private fun buildPalette() {
        var distinct = 0
        for (i in 0 until 32768) if (hist[i] > 0) distinct++
        val ids = IntArray(distinct)
        var k = 0
        for (i in 0 until 32768) if (hist[i] > 0) ids[k++] = i
        if (distinct == 0) { palCount = 0; return }
        // Cajas como tramos [lo, hi) de ids; se parte siempre la de más población que aún se pueda dividir.
        val lo = ArrayList<Int>(); val hi = ArrayList<Int>()
        lo.add(0); hi.add(distinct)
        while (lo.size < maxColors) {
            var best = -1
            var bestPop = 0L
            for (b in lo.indices) {
                if (hi[b] - lo[b] < 2) continue
                var pop = 0L
                for (j in lo[b] until hi[b]) pop += hist[ids[j]]
                if (pop > bestPop) { bestPop = pop; best = b }
            }
            if (best < 0) break
            val a = lo[best]; val z = hi[best]
            // Canal con mayor rango dentro de la caja.
            var rMin = 31; var rMax = 0; var gMin = 31; var gMax = 0; var bMin = 31; var bMax = 0
            for (j in a until z) {
                val id = ids[j]
                val r = id shr 10; val g = (id shr 5) and 31; val bb = id and 31
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (bb < bMin) bMin = bb; if (bb > bMax) bMax = bb
            }
            // El verde pesa más: el ojo lo distingue mejor.
            val rr = (rMax - rMin) * 3; val gr = (gMax - gMin) * 4; val br = (bMax - bMin) * 2
            val chan = if (gr >= rr && gr >= br) 1 else if (rr >= br) 0 else 2
            val keys = LongArray(z - a)
            for (j in a until z) {
                val id = ids[j]
                val v = when (chan) { 0 -> id shr 10; 1 -> (id shr 5) and 31; else -> id and 31 }
                keys[j - a] = (v.toLong() shl 20) or id.toLong()
            }
            java.util.Arrays.sort(keys)
            for (j in a until z) ids[j] = (keys[j - a] and 0xFFFFF).toInt()
            // Corte por la mediana de la población.
            var half = bestPop / 2
            var cut = a
            while (cut < z - 1) { half -= hist[ids[cut]]; cut++; if (half <= 0) break }
            if (cut <= a) cut = a + 1
            hi[best] = cut
            lo.add(cut); hi.add(z)
        }
        palCount = lo.size
        for (b in 0 until palCount) {
            var n = 0L; var r = 0L; var g = 0L; var bl = 0L
            for (j in lo[b] until hi[b]) {
                val id = ids[j]
                n += hist[id]; r += sumR[id]; g += sumG[id]; bl += sumB[id]
            }
            palR[b] = (r / n).toInt(); palG[b] = (g / n).toInt(); palB[b] = (bl / n).toInt()
        }
    }

    private fun nearest(r: Int, g: Int, b: Int): Int {
        var best = 0
        var bd = Int.MAX_VALUE
        for (i in 0 until palCount) {
            val dr = r - palR[i]; val dg = g - palG[i]; val db = b - palB[i]
            val d = 3 * dr * dr + 4 * dg * dg + 2 * db * db
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    fun addFrame(bitmap: Bitmap) {
        val src = if (bitmap.width != width || bitmap.height != height) Bitmap.createScaledBitmap(bitmap, width, height, true) else bitmap
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        if (src !== bitmap) src.recycle()
        java.util.Arrays.fill(hist, 0); java.util.Arrays.fill(sumR, 0); java.util.Arrays.fill(sumG, 0); java.util.Arrays.fill(sumB, 0)
        var minX = width; var maxX = -1; var minY = height; var maxY = -1
        // 1) Qué píxeles cambian (los demás serán transparentes) y histograma de colores de los que cambian.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val p = pixels[i]
                if (hasPrev && close(p, shown[i])) { indexed[i] = TRANSPARENT; continue }
                shown[i] = p
                indexed[i] = 0
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                val bin = binOf(r, g, b)
                hist[bin]++; sumR[bin] += r; sumG[bin] += g; sumB[bin] += b
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        hasPrev = true
        buildPalette()
        java.util.Arrays.fill(binToIdx, -1)
        // 2) Índice de paleta de cada píxel que cambia (con tramado ordenado si está activado).
        if (maxX >= 0) {
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val i = y * width + x
                    if (indexed[i] == TRANSPARENT) continue
                    val p = pixels[i]
                    val d = if (dither) (BAYER[(y and 3) * 4 + (x and 3)] - 8) * 2 else 0
                    val r = (((p shr 16) and 0xFF) + d).coerceIn(0, 255)
                    val g = (((p shr 8) and 0xFF) + d).coerceIn(0, 255)
                    val b = ((p and 0xFF) + d).coerceIn(0, 255)
                    val bin = binOf(r, g, b)
                    var idx = binToIdx[bin]
                    if (idx < 0) { idx = nearest(r, g, b); binToIdx[bin] = idx }
                    indexed[i] = idx.toByte()
                }
            }
        }
        // Sin cambios: un fotograma de un solo píxel transparente conserva el tiempo de espera.
        if (maxX < 0) { minX = 0; maxX = 0; minY = 0; maxY = 0; indexed[0] = TRANSPARENT }
        val w = maxX - minX + 1
        val h = maxY - minY + 1
        val sub = ByteArray(w * h)
        for (y in 0 until h) System.arraycopy(indexed, (minY + y) * width + minX, sub, y * w, w)
        // Método de eliminación 1 (dejar en su sitio) + color transparente 255.
        out.write(0x21); out.write(0xF9); out.write(4); out.write(0x05); short(delayCs); out.write(255); out.write(0)
        out.write(0x2C); short(minX); short(minY); short(w); short(h)
        out.write(0x87) // paleta local de 256 entradas
        for (i in 0 until 256) {
            if (i < palCount) { out.write(palR[i]); out.write(palG[i]); out.write(palB[i]) } else { out.write(0); out.write(0); out.write(0) }
        }
        lzw(sub)
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    private val block = ByteArray(255)
    private var blockLen = 0
    private var bitBuf = 0
    private var bitCount = 0

    private fun emit(code: Int, size: Int) {
        bitBuf = bitBuf or (code shl bitCount)
        bitCount += size
        while (bitCount >= 8) {
            block[blockLen++] = (bitBuf and 0xFF).toByte()
            if (blockLen == 255) flushBlock()
            bitBuf = bitBuf ushr 8
            bitCount -= 8
        }
    }

    private fun flushBlock() {
        if (blockLen > 0) {
            out.write(blockLen)
            out.write(block, 0, blockLen)
            blockLen = 0
        }
    }

    private val table = IntArray(HASH_SIZE)
    private val codes = IntArray(HASH_SIZE)

    private fun lzw(data: ByteArray) {
        val minCode = 8
        out.write(minCode)
        val clear = 1 shl minCode
        val eoi = clear + 1
        var next = eoi + 1
        var size = minCode + 1
        table.fill(-1)
        bitBuf = 0; bitCount = 0; blockLen = 0
        emit(clear, size)
        var prefix = data[0].toInt() and 0xFF
        for (i in 1 until data.size) {
            val c = data[i].toInt() and 0xFF
            val key = (prefix shl 8) or c
            var h = ((key * 0x9E3779B1.toInt()) ushr 19) and (HASH_SIZE - 1)
            var found = -1
            while (table[h] != -1) {
                if (table[h] == key) { found = codes[h]; break }
                h = (h + 1) and (HASH_SIZE - 1)
            }
            if (found >= 0) {
                prefix = found
            } else {
                emit(prefix, size)
                if (next < 4096) {
                    table[h] = key
                    codes[h] = next
                    if (next == (1 shl size)) size++
                    next++
                } else {
                    emit(clear, size)
                    table.fill(-1)
                    next = eoi + 1
                    size = minCode + 1
                }
                prefix = c
            }
        }
        emit(prefix, size)
        emit(eoi, size)
        if (bitCount > 0) { block[blockLen++] = (bitBuf and 0xFF).toByte(); if (blockLen == 255) flushBlock() }
        flushBlock()
        out.write(0)
    }

    private companion object {
        const val HASH_SIZE = 8192
        const val TRANSPARENT: Byte = -1 // índice 255
        val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    }
}
