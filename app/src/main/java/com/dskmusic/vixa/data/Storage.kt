package com.dskmusic.vixa.data

import android.os.Environment
import com.dskmusic.vixa.model.ExportKind
import java.io.File

/**
 * Carpeta única de la app en la raíz del almacenamiento (`/Vixa`), con una subcarpeta por tipo de contenido. Todo lo que
 * Vixa genera para el usuario (exportaciones, copias de proyectos, subtítulos, resultados de IA) se guarda aquí.
 * Se crea sola; necesita el acceso a todos los archivos que la app ya pide al empezar.
 */
object Storage {
    val root: File get() = File(Environment.getExternalStorageDirectory(), "Vixa")

    fun videos() = File(root, "Videos")
    fun audio() = File(root, "Audio")
    fun gifs() = File(root, "GIF")
    fun images() = File(root, "Imágenes")
    fun projects() = File(root, "Proyectos")
    fun subtitles() = File(root, "Subtítulos")
    fun ai() = File(root, "IA")

    fun forKind(kind: ExportKind): File = when (kind) {
        ExportKind.VIDEO -> videos()
        ExportKind.AUDIO -> audio()
        ExportKind.GIF -> gifs()
        ExportKind.FRAME_PNG, ExportKind.FRAME_JPG -> images()
    }

    /** Carpetas predeterminadas de versiones anteriores: se tratan como "sin carpeta elegida". */
    @Suppress("DEPRECATION")
    private fun legacy(): List<File> = listOf(
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Vixa"),
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Vixa"),
    )

    /** La carpeta elegida por el usuario en Ajustes, o null si está vacía o es una predeterminada antigua. */
    fun custom(path: String): File? = path.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { f -> legacy().none { it.path == f.path } && f.path != root.path }

    /** Carpeta de exportación: la elegida o, si no hay, la subcarpeta de Vixa para ese tipo. */
    fun exportDir(customPath: String, kind: ExportKind): File = custom(customPath) ?: forKind(kind)

    /** Crea la carpeta raíz y todas las subcarpetas (si no hay permiso, no hace nada). */
    fun ensure() {
        try { listOf(root, videos(), audio(), gifs(), images(), projects(), subtitles(), ai()).forEach { it.mkdirs() } } catch (e: Throwable) { ErrorLog.log("Storage", e) }
    }
}
