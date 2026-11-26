package com.dskmusic.vixa.engine

import com.dskmusic.vixa.data.ErrorLog
import java.io.File

/**
 * Compresión de GIF con giflossy (gifsicle con --lossy), compilado como biblioteca nativa (app/src/main/cpp). Se aplica al GIF ya
 * codificado: optimiza los fotogramas (-O3) y, según el nivel, altera ligeramente los colores para que LZW comprima mucho más.
 * Si la biblioteca no carga o falla, el GIF se queda como estaba.
 */
object GifLossy {
    private val loaded: Boolean = try {
        System.loadLibrary("giflossy")
        true
    } catch (e: Throwable) {
        ErrorLog.log("GifLossy/load", e)
        false
    }

    /** true si la biblioteca nativa está disponible. */
    val available: Boolean get() = loaded

    /** Ejecuta gifsicle con esos argumentos (como la línea de comandos); 0 = bien. Corre en un proceso hijo. */
    private external fun nativeRun(args: Array<String>): Int

    /** Argumentos de cada nivel de compresión (1 ligera, 2 media, 3 fuerte); null = sin comprimir. */
    private fun levelArgs(level: Int): List<String>? = when (level) {
        1 -> listOf("-w", "-O3", "--lossy=30")
        2 -> listOf("-w", "-O3", "--lossy=80", "--colors=192")
        3 -> listOf("-w", "-O3", "--lossy=150", "--colors=128")
        else -> null
    }

    /** Comprime [file] en el sitio con el [level] dado; solo lo sustituye si el resultado pesa menos. Devuelve true si lo redujo. */
    fun shrink(file: File, level: Int): Boolean {
        val a = levelArgs(level) ?: return false
        if (!loaded || !file.exists() || file.length() == 0L) return false
        val tmp = File(file.parentFile, file.name + ".lossy")
        return try {
            val rc = nativeRun((a + listOf(file.path, "-o", tmp.path)).toTypedArray())
            val ok = rc == 0 && tmp.length() in 1 until file.length()
            if (ok) tmp.copyTo(file, overwrite = true)
            ok
        } catch (e: Throwable) {
            ErrorLog.log("GifLossy/run", e)
            false
        } finally {
            tmp.delete()
        }
    }
}
