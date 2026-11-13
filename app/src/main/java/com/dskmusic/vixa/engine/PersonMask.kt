package com.dskmusic.vixa.engine

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Máscara de personas precalculada: un archivo con un fotograma de 8 bits por muestra (0 = fondo, 255 = persona) a
 * pocos fotogramas por segundo y baja resolución. El efecto GL la lee según el tiempo de presentación, así que en la
 * vista previa y en la exportación sale exactamente lo mismo y el modelo nunca se ejecuta dentro del bucle de render.
 *
 * Formato: "VXMK", ancho (int), alto (int), fps (int), inicio en µs del medio (long), nº de fotogramas (int) y los datos.
 */
class MaskReader(val file: File) {
    val w: Int
    val h: Int
    private val fps: Int
    private val startUs: Long
    private val count: Int
    private val raf = RandomAccessFile(file, "r")
    private val buf: ByteArray
    private var last = -1

    init {
        val magic = ByteArray(4)
        raf.readFully(magic)
        require(String(magic, Charsets.US_ASCII) == "VXMK") { "not a mask file" }
        w = raf.readInt(); h = raf.readInt(); fps = raf.readInt(); startUs = raf.readLong(); count = raf.readInt()
        require(w > 0 && h > 0 && fps > 0 && count > 0) { "empty mask" }
        buf = ByteArray(w * h)
    }

    /** Índice del fotograma de máscara para el instante [srcUs] del medio (se queda en el primero o el último fuera de rango). */
    fun indexAt(srcUs: Long): Int = (((srcUs - startUs) * fps) / 1_000_000L).toInt().coerceIn(0, count - 1)

    /** Rellena [px] (ancho × alto) con el fotograma [idx] como píxeles ARGB opacos en escala de grises. */
    @Synchronized
    fun fill(idx: Int, px: IntArray) {
        if (idx != last) {
            raf.seek(HEADER + idx.toLong() * w * h)
            raf.readFully(buf)
            last = idx
        }
        for (i in buf.indices) {
            val g = buf[i].toInt() and 0xFF
            px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
    }

    companion object { const val HEADER = 28L }
}

/** Lectores abiertos por ruta; se vuelven a abrir si el archivo cambia (p. ej. tras regenerarlo). */
object MaskStore {
    private val readers = ConcurrentHashMap<String, Pair<Long, MaskReader>>()

    fun get(path: String): MaskReader? {
        val f = File(path)
        if (!f.exists()) return null
        val stamp = f.lastModified() xor f.length()
        readers[path]?.let { if (it.first == stamp) return it.second }
        return try {
            MaskReader(f).also { readers[path] = stamp to it }
        } catch (e: Throwable) {
            null
        }
    }
}
