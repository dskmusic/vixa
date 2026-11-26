package com.dskmusic.vixa.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.dskmusic.vixa.engine.ThumbComposer
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.FORMAT_VERSION
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.aiPaths
import com.dskmusic.vixa.model.allPaths
import com.dskmusic.vixa.model.mapPaths
import com.dskmusic.vixa.model.newId
import com.dskmusic.vixa.model.normalized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class ProjectInfo(
    val id: String,
    val name: String,
    val modifiedAt: Long,
    val createdAt: Long = 0,
    val durationUs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val isTemplate: Boolean = false,
)

class SnapshotInfo(val file: File, val label: String, val timeMs: Long)

/**
 * Proyectos en disco: files/projects/<id>/{project.json, meta.json, thumb.jpg, snapshots/}.
 * El formato .vixa es un ZIP con project.json (versionado) y, opcionalmente, media/ con los medios.
 */
class ProjectStore(private val context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }

    // Cambia cada vez que se guarda un proyecto: la pantalla de inicio se refresca sola (p. ej. cuando el editor termina
    // de guardar en segundo plano después de volver, o tras renombrar).
    private val _changes = kotlinx.coroutines.flow.MutableStateFlow(0)
    val changes: kotlinx.coroutines.flow.StateFlow<Int> = _changes

    // Los guardados van de uno en uno: uno antiguo no puede terminar después de uno nuevo y pisarlo.
    private val saveLock = kotlinx.coroutines.sync.Mutex()
    private val templatesDir = File(context.filesDir, "text_templates").apply { mkdirs() }

    private fun dir(id: String) = File(root, id)
    fun thumbFile(id: String) = File(dir(id), "thumb.jpg")

    companion object {
        /** Días que un proyecto eliminado permanece en la papelera. */
        const val TRASH_DAYS = 10

        /**
         * Migraciones del formato: MIGRATIONS[n] convierte un project.json de versión n a n+1.
         * v1 → v2 (funciones con IA): solo añade campos opcionales (Clip.aiAudio, TextSpec.wordsUs,
         * Project.karaokeColor), así que el JSON no cambia. v2 → v3: Clip.personCut. v3 → v4: Clip.objTracks (seguimiento de objetos); todos opcionales.
         */
        val MIGRATIONS: Map<Int, (JsonObject) -> JsonObject> = mapOf(1 to { it }, 2 to { it }, 3 to { it })

        fun decode(text: String): Project {
            var obj: JsonObject = VixaJson.parseToJsonElement(text).jsonObject
            var version = (obj["formatVersion"] as? JsonPrimitive)?.int ?: 1
            if (version > FORMAT_VERSION) throw IllegalStateException("Formato de proyecto más nuevo (v$version) que esta versión de Vixa")
            while (version < FORMAT_VERSION) {
                val step = MIGRATIONS[version] ?: throw IllegalStateException("Falta la migración del formato v$version")
                obj = step(obj)
                version++
            }
            val map = obj.toMutableMap()
            map["formatVersion"] = JsonPrimitive(FORMAT_VERSION)
            val element: JsonElement = JsonObject(map)
            return VixaJson.decodeFromJsonElement(Project.serializer(), element).normalized()
        }

        fun encode(p: Project): String = VixaJson.encodeToString(Project.serializer(), p.copy(formatVersion = FORMAT_VERSION))
    }

    suspend fun list(): List<ProjectInfo> = withContext(Dispatchers.IO) { infos(root) }

    private fun infos(base: File): List<ProjectInfo> =
        base.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { d ->
            try {
                val meta = File(d, "meta.json")
                if (meta.exists()) VixaJson.decodeFromString(ProjectInfo.serializer(), meta.readText())
                else if (File(d, "project.json").exists()) info(decode(File(d, "project.json").readText())) else null
            } catch (e: Throwable) {
                ErrorLog.log("ProjectStore/list " + d.name, e)
                null
            }
        }.sortedByDescending { it.modifiedAt }

    // ---------------------------------------------------------------------------------------------
    // Papelera: los proyectos eliminados se guardan [TRASH_DAYS] días y después se borran solos.
    // ---------------------------------------------------------------------------------------------

    private val trash = File(context.filesDir, "trash").apply { mkdirs() }

    private fun deletedAt(d: File): Long = File(d, "deleted_at").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: d.lastModified()

    fun trashThumbFile(id: String) = File(File(trash, id), "thumb.jpg")

    /** Proyectos en la papelera con los días que les quedan (los más recientes primero). Borra de paso los caducados. */
    suspend fun trashed(): List<Pair<ProjectInfo, Int>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        for (d in trash.listFiles().orEmpty()) if (now - deletedAt(d) >= TRASH_DAYS * day) d.deleteRecursively()
        infos(trash).map { it to deletedAt(File(trash, it.id)) }.sortedByDescending { it.second }
            .map { (i, at) -> i to (TRASH_DAYS - ((now - at) / day).toInt()).coerceIn(1, TRASH_DAYS) }
    }

    suspend fun restore(id: String) = withContext(Dispatchers.IO) {
        val src = File(trash, id)
        if (src.exists() && !dir(id).exists()) { File(src, "deleted_at").delete(); move(src, dir(id)) }
        Unit
    }

    /** Borrado definitivo de un proyecto de la papelera, o de toda la papelera con [id] vacío. */
    suspend fun purge(id: String = "") = withContext(Dispatchers.IO) {
        if (id.isEmpty()) trash.listFiles().orEmpty().forEach { it.deleteRecursively() } else File(trash, id).deleteRecursively()
        Unit
    }

    private fun move(src: File, dst: File) {
        dst.deleteRecursively()
        if (!src.renameTo(dst)) { src.copyRecursively(dst, overwrite = true); src.deleteRecursively() }
    }

    private fun info(p: Project) = ProjectInfo(p.id, p.name, p.modifiedAt, p.createdAt, p.durationUs, p.width, p.height, p.isTemplate)

    /** Guardado atómico (archivo temporal + renombrado) para no dejar nunca un project.json a medias. */
    suspend fun save(project: Project, updateThumb: Boolean = false, thumb: Bitmap? = null, positionUs: Long = 0L): Project = withContext(Dispatchers.IO) {
        saveLock.lock()
        try { saveLocked(project, updateThumb, thumb, positionUs) } finally { saveLock.unlock(); _changes.value++ }
    }

    private fun saveLocked(project: Project, updateThumb: Boolean, thumb: Bitmap?, positionUs: Long): Project {
        val p = project.copy(modifiedAt = System.currentTimeMillis())
        val d = dir(p.id).apply { mkdirs() }
        val tmp = File(d, "project.json.tmp")
        tmp.writeText(encode(p))
        val target = File(d, "project.json")
        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        File(d, "meta.json").writeText(VixaJson.encodeToString(ProjectInfo.serializer(), info(p)))
        // Miniatura: la captura real de la vista previa si vale; si no, el fotograma compuesto en la posición del cursor; si no, otro
        // al azar con contenido (nunca un fondo liso). Solo si nada de eso es posible, el primer medio o una portada dibujada.
        if (updateThumb) {
            val chosen = try { ThumbComposer.best(p, positionUs, thumb) } catch (e: Throwable) { ErrorLog.log("ProjectStore/composer", e); null }
            if (chosen != null) { writeThumb(p.id, chosen); try { realMarker(p.id).createNewFile() } catch (e: Throwable) {} } else makeThumb(p)
            try { v2Marker(p.id).createNewFile() } catch (e: Throwable) {}
        } else if (!thumbFile(p.id).exists()) makeThumb(p)
        return p
    }

    /** Brillo medio (muestreado) por debajo del que una imagen se considera negra. */
    private fun luma(b: Bitmap): Float {
        var sum = 0f
        var n = 0
        val stepX = maxOf(1, b.width / 16)
        val stepY = maxOf(1, b.height / 16)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val c = b.getPixel(x, y)
                sum += 0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0f else sum / n
    }

    private fun isDark(b: Bitmap) = luma(b) < 18f

    private fun writeThumb(id: String, bmp: Bitmap) {
        try {
            val s = minOf(1f, 360f / maxOf(bmp.width, bmp.height))
            val small = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(2), (bmp.height * s).toInt().coerceAtLeast(2), true) else bmp
            FileOutputStream(thumbFile(id)).use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        } catch (e: Throwable) {
            ErrorLog.log("ProjectStore/thumb", e)
        }
    }

    @kotlinx.serialization.Serializable
    private class HistEntryDto(val label: String, val timeMs: Long, val project: Project)

    @kotlinx.serialization.Serializable
    private class HistFileDto(val index: Int, val entries: List<HistEntryDto>)

    /** Historial de deshacer/rehacer entre sesiones: se guardan los últimos pasos (acotado para no inflar el disco). */
    suspend fun saveHistory(id: String, entries: List<com.dskmusic.vixa.model.History.Entry>, index: Int) = withContext(Dispatchers.IO) {
        try {
            val start = (entries.size - 25).coerceAtLeast(0)
            val dto = HistFileDto((index - start).coerceAtLeast(0), entries.subList(start, entries.size).map { HistEntryDto(it.label, it.timeMs, it.project) })
            val d = dir(id).apply { mkdirs() }
            val tmp = File(d, "history.json.tmp")
            tmp.writeText(VixaJson.encodeToString(HistFileDto.serializer(), dto))
            if (!tmp.renameTo(File(d, "history.json"))) { tmp.copyTo(File(d, "history.json"), overwrite = true); tmp.delete() }
        } catch (e: Throwable) { ErrorLog.log("ProjectStore/saveHistory $id", e) }
    }

    suspend fun loadHistory(id: String): Pair<List<com.dskmusic.vixa.model.History.Entry>, Int>? = withContext(Dispatchers.IO) {
        try {
            val f = File(dir(id), "history.json")
            if (!f.exists()) return@withContext null
            val dto = VixaJson.decodeFromString(HistFileDto.serializer(), f.readText())
            dto.entries.map { com.dskmusic.vixa.model.History.Entry(it.label, it.project, it.timeMs) } to dto.index
        } catch (e: Throwable) { null }
    }

    suspend fun load(id: String): Project? = withContext(Dispatchers.IO) {
        try {
            val f = File(dir(id), "project.json")
            if (!f.exists()) null else decode(f.readText())
        } catch (e: Throwable) {
            ErrorLog.log("ProjectStore/load $id", e)
            null
        }
    }

    /** Elimina un proyecto: va a la papelera (ver [trashed]). */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val src = dir(id)
        if (src.exists()) {
            // Un proyecto vacío (sin ningún clip) no vale la pena guardarlo: se borra directamente.
            val empty = try {
                val meta = File(src, "meta.json")
                if (meta.exists()) VixaJson.decodeFromString(ProjectInfo.serializer(), meta.readText()).durationUs <= 0L
                else decode(File(src, "project.json").readText()).allClips().isEmpty()
            } catch (e: Throwable) { false }
            if (empty) { src.deleteRecursively(); return@withContext Unit }
            val dst = File(trash, id)
            move(src, dst)
            File(dst, "deleted_at").writeText(System.currentTimeMillis().toString())
        }
        Unit
    }

    /** Cambia el nombre de un proyecto guardado. Devuelve false si no se pudo (queda en el registro de errores). */
    suspend fun rename(id: String, name: String): Boolean = try {
        val n = name.trim()
        val p = load(id) ?: throw IllegalStateException("no se puede cargar el proyecto $id")
        if (p.name != n) save(p.copy(name = n))
        // El historial guardado lleva el nombre anterior en cada paso: se actualiza para que al abrir no vuelva.
        loadHistory(id)?.let { (list, idx) -> saveHistory(id, list.map { it.copy(project = it.project.copy(name = n)) }, idx) }
        true
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        ErrorLog.log("ProjectStore/rename $id", e)
        false
    }

    suspend fun duplicate(id: String, suffix: String): Project? {
        val p = load(id) ?: return null
        return save(p.copy(id = newId(), name = p.name + " " + suffix, createdAt = System.currentTimeMillis()), updateThumb = true)
    }

    /** Rehace en segundo plano las miniaturas que aún son una portada dibujada (proyectos guardados con versiones anteriores). */
    suspend fun refreshThumbs() = withContext(Dispatchers.IO) {
        var changed = false
        for (info in infos(root)) {
            try {
                if (v2Marker(info.id).exists()) continue
                val f = File(dir(info.id), "project.json")
                if (!f.exists()) continue
                val p = decode(f.readText())
                val bmp = ThumbComposer.best(p, 0L, null)
                if (bmp != null) { writeThumb(p.id, bmp); realMarker(p.id).createNewFile(); changed = true }
                v2Marker(p.id).createNewFile()   // también si no hay nada que componer: así no se repite en cada arranque
            } catch (e: Throwable) { ErrorLog.log("ProjectStore/refreshThumbs", e) }
        }
        if (changed) _changes.value++
    }

    /** Existe si la miniatura es un fotograma real de la vista previa (no la portada dibujada). */
    private fun realMarker(id: String) = File(dir(id), "thumb.real")

    /** Existe si la miniatura ya se calculó con el sistema nuevo (fotograma real o compuesto); las antiguas se rehacen una vez. */
    private fun v2Marker(id: String) = File(dir(id), "thumb.v2")

    /** Portada para proyectos sin vídeo ni fotos: degradado del fondo con el primer texto y el primer emoji. */
    private fun placeholderThumb(p: Project): Bitmap {
        val ar = if (p.height > 0) p.width.toFloat() / p.height else 16f / 9f
        val w = if (ar >= 1f) 360 else (360 * ar).toInt().coerceAtLeast(120)
        val h = if (ar >= 1f) (360 / ar).toInt().coerceAtLeast(120) else 360
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val col = p.allClips().firstOrNull { it.kind == com.dskmusic.vixa.model.ClipKind.COLOR && !it.gap }?.fill?.colors
        val c0 = col?.firstOrNull() ?: 0xFF1E3A5F.toInt()
        val c1 = col?.getOrNull(1) ?: if (col == null) 0xFF0A84FF.toInt() else c0
        paint.shader = android.graphics.LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), c0, c1, android.graphics.Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.textAlign = android.graphics.Paint.Align.CENTER
        val sticker = p.allClips().firstOrNull { it.kind == com.dskmusic.vixa.model.ClipKind.STICKER && it.sticker?.type == com.dskmusic.vixa.model.StickerType.EMOJI }?.sticker?.value
        if (!sticker.isNullOrEmpty()) { paint.textSize = h * 0.4f; cv.drawText(sticker, w / 2f, h * 0.55f, paint) }
        val text = p.allClips().firstOrNull { it.kind == com.dskmusic.vixa.model.ClipKind.TEXT }?.text?.layers?.firstOrNull { it.text.isNotBlank() }?.text
        if (!text.isNullOrBlank()) {
            paint.color = 0xFFFFFFFF.toInt()
            paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize = h * 0.13f
            paint.setShadowLayer(6f, 0f, 2f, 0x99000000.toInt())
            cv.drawText(text.lineSequence().first().take(18), w / 2f, h * 0.82f, paint)
        }
        return bmp
    }

    private fun makeThumb(p: Project) {
        try {
            val c = p.mainTrack.clips.firstOrNull { it.media != null } ?: p.allClips().firstOrNull { it.isVisual && it.media != null }
            val m = c?.media
            val bmp: Bitmap? = when {
                c == null || m == null -> null
                m.isImage -> BitmapFactory.decodeFile(m.path, BitmapFactory.Options().apply { inSampleSize = 4 })
                else -> {
                    // Varios instantes del principio: se usa el primero que no sea negro (o, si todos lo son, el más claro).
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(m.path)
                        var best: Bitmap? = null
                        var bestL = -1f
                        for (t in listOf(minOf(500_000L, c.srcSpanUs / 2), 0L, 1_000_000L, 2_000_000L, 3_500_000L, 5_000_000L).distinct()) {
                            if (t > c.srcSpanUs && t != 0L) continue
                            val f = r.getFrameAtTime(c.srcInUs + t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                            val l = luma(f)
                            if (l > bestL) { best = f; bestL = l }
                            if (l >= 18f) break
                        }
                        best
                    } finally { r.release() }
                }
            }
            if (bmp == null) {
                // Sin medios (solo textos, emojis, stickers o color): si ya hay un fotograma real de la vista previa se conserva; si no, una portada dibujada.
                if (!(thumbFile(p.id).exists() && realMarker(p.id).exists())) writeThumb(p.id, placeholderThumb(p))
                return
            }
            realMarker(p.id).delete()
            writeThumb(p.id, bmp)
        } catch (e: Throwable) {
            ErrorLog.log("ProjectStore/thumb", e)
        }
    }

    // --------------------------------------------------------------------------------------
    // Instantáneas de versión
    // --------------------------------------------------------------------------------------

    suspend fun saveSnapshot(p: Project, label: String) = withContext(Dispatchers.IO) {
        val d = File(dir(p.id), "snapshots").apply { mkdirs() }
        val safe = label.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
        File(d, System.currentTimeMillis().toString() + "~" + safe + ".json").writeText(encode(p))
    }

    suspend fun snapshots(id: String): List<SnapshotInfo> = withContext(Dispatchers.IO) {
        File(dir(id), "snapshots").listFiles().orEmpty().filter { it.extension == "json" }.map { f ->
            val base = f.nameWithoutExtension
            SnapshotInfo(f, base.substringAfter('~', ""), base.substringBefore('~').toLongOrNull() ?: f.lastModified())
        }.sortedByDescending { it.timeMs }
    }

    suspend fun loadSnapshot(s: SnapshotInfo): Project? = withContext(Dispatchers.IO) {
        try { decode(s.file.readText()) } catch (e: Throwable) { ErrorLog.log("Snapshot", e); null }
    }

    suspend fun deleteSnapshot(s: SnapshotInfo) = withContext(Dispatchers.IO) { s.file.delete(); Unit }

    // --------------------------------------------------------------------------------------
    // Plantillas de texto propias
    // --------------------------------------------------------------------------------------

    suspend fun saveTextTemplate(clip: Clip, name: String) = withContext(Dispatchers.IO) {
        val c = clip.copy(id = newId(), startUs = 0, label = name, groupId = "")
        File(templatesDir, c.id + ".json").writeText(VixaJson.encodeToString(Clip.serializer(), c))
    }

    suspend fun textTemplates(): List<Clip> = withContext(Dispatchers.IO) {
        templatesDir.listFiles().orEmpty().sortedByDescending { it.lastModified() }.mapNotNull {
            try { VixaJson.decodeFromString(Clip.serializer(), it.readText()) } catch (e: Throwable) { null }
        }.filter { it.kind == ClipKind.TEXT }
    }

    suspend fun deleteTextTemplate(id: String) = withContext(Dispatchers.IO) { File(templatesDir, "$id.json").delete(); Unit }

    // --------------------------------------------------------------------------------------
    // Medios que faltan
    // --------------------------------------------------------------------------------------

    fun missing(p: Project): List<String> = (p.allPaths() - p.aiPaths()).filter { !File(it).exists() }

    fun relink(p: Project, oldPath: String, newPath: String): Project = p.mapPaths { if (it == oldPath) newPath else it }

    // --------------------------------------------------------------------------------------
    // .vixa
    // --------------------------------------------------------------------------------------

    /** Empaqueta el proyecto en [dest]. Con [includeMedia] los medios viajan dentro (portable a otro dispositivo). */
    suspend fun exportVixa(p: Project, dest: File, includeMedia: Boolean, onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        try {
            dest.parentFile?.mkdirs()
            val paths = if (includeMedia) p.allPaths().filter { File(it).exists() } else emptyList()
            val names = HashMap<String, String>()
            paths.forEachIndexed { i, path -> names[path] = "media/" + i + "_" + File(path).name }
            val packed = if (includeMedia) p.mapPaths { names[it] ?: it } else p
            ZipOutputStream(FileOutputStream(tmp).buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("project.json"))
                zip.write(encode(packed).toByteArray())
                zip.closeEntry()
                paths.forEachIndexed { i, path ->
                    // Los medios ya están comprimidos: se guardan con compresión mínima.
                    zip.setLevel(0)
                    zip.putNextEntry(ZipEntry(names[path]))
                    File(path).inputStream().use { it.copyTo(zip, 1 shl 16) }
                    zip.closeEntry()
                    onProgress((i + 1).toFloat() / paths.size)
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
            true
        } catch (e: Throwable) {
            ErrorLog.log("ExportVixa", e)
            tmp.delete()
            false
        }
    }

    suspend fun importVixa(uri: Uri): Project? = withContext(Dispatchers.IO) {
        try {
            val input = if (uri.scheme == "file") File(uri.path ?: return@withContext null).inputStream() else context.contentResolver.openInputStream(uri)
            input?.use { importVixa(it) }
        } catch (e: Throwable) {
            ErrorLog.log("ImportVixa", e)
            null
        }
    }

    private suspend fun importVixa(input: InputStream): Project? {
        val id = newId()
        val mediaDir = File(context.filesDir, "media/pkg_$id")
        var json: String? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name.replace('\\', '/')
                if (name == "project.json") {
                    json = zip.readBytes().toString(Charsets.UTF_8)
                } else if (name.startsWith("media/") && !e.isDirectory && !name.contains("..")) {
                    mediaDir.mkdirs()
                    FileOutputStream(File(mediaDir, name.removePrefix("media/").replace('/', '_'))).use { zip.copyTo(it, 1 shl 16) }
                }
                zip.closeEntry()
            }
        }
        val text = json ?: return null
        val p = decode(text).mapPaths { if (it.startsWith("media/")) File(mediaDir, it.removePrefix("media/").replace('/', '_')).path else it }
        return save(p.copy(id = id), updateThumb = true)
    }
}
