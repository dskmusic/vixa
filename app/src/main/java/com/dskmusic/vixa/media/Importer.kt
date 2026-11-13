package com.dskmusic.vixa.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.MediaItem
import androidx.media3.inspector.MetadataRetriever
import androidx.media3.inspector.frame.FrameExtractor
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.MediaRef
import com.dskmusic.vixa.model.US
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

enum class MediaType { VIDEO, IMAGE, AUDIO, OTHER }

object Importer {
    private val videoExt = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp", "3g2", "avi", "ts", "mts", "m2ts", "flv", "wmv", "mpg", "mpeg")
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "dng")
    private val audioExt = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr", "mid", "midi", "wma", "mka", "awb")

    fun typeOf(path: String): MediaType {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when {
            ext in videoExt -> MediaType.VIDEO
            ext in imageExt -> MediaType.IMAGE
            ext in audioExt -> MediaType.AUDIO
            else -> {
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: ""
                when {
                    mime.startsWith("video/") -> MediaType.VIDEO
                    mime.startsWith("image/") -> MediaType.IMAGE
                    mime.startsWith("audio/") -> MediaType.AUDIO
                    else -> MediaType.OTHER
                }
            }
        }
    }

    fun mediaDir(context: Context): File = File(context.filesDir, "media").apply { mkdirs() }

    private fun hash(s: String): String = Integer.toHexString(s.hashCode()) + java.lang.Long.toHexString(s.length.toLong() * 2654435761L)

    /** Copia un content:// (compartir / abrir con) a la carpeta de medios de la app y devuelve su ruta. */
    suspend fun copyUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            if (uri.scheme == "file") return@withContext uri.path
            var name = "import_" + System.currentTimeMillis()
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
            if (!name.contains('.')) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(context.contentResolver.getType(uri) ?: "")
                if (!ext.isNullOrEmpty()) name = "$name.$ext"
            }
            name = name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val dir = File(mediaDir(context), "imports").apply { mkdirs() }
            var out = File(dir, name)
            var n = 1
            while (out.exists()) { out = File(dir, out.nameWithoutExtension + "_" + n + "." + out.extension); n++ }
            context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(out).use { input.copyTo(it) } } ?: return@withContext null
            out.absolutePath
        } catch (e: Throwable) {
            ErrorLog.log("Import", e)
            null
        }
    }

    /**
     * Sondea un archivo y devuelve su MediaRef, o null si no se puede leer. Las imágenes se normalizan
     * (rotación EXIF aplicada, tamaño máximo razonable, JPEG/PNG) para que el motor las cargue siempre.
     */
    suspend fun probe(context: Context, path: String, forceType: MediaType? = null): MediaRef? = withContext(Dispatchers.IO) {
        val f = File(path)
        if (!f.exists() || f.length() == 0L) return@withContext null
        try {
            when (forceType ?: typeOf(path)) {
                MediaType.IMAGE -> probeImage(context, f)
                MediaType.AUDIO -> probeTimed(context, f, audioOnly = true)
                MediaType.VIDEO -> probeTimed(context, f, audioOnly = false)
                MediaType.OTHER -> probeTimed(context, f, audioOnly = false) ?: probeImage(context, f)
            }
        } catch (e: Throwable) {
            ErrorLog.log("Probe " + f.name, e)
            null
        }
    }

    private fun probeImage(context: Context, f: File): MediaRef? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxSide = 2560
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
        val out = File(mediaDir(context), "img_" + hash(f.path + f.lastModified()) + ".png")
        val jpg = File(out.path.removeSuffix(".png") + ".jpg")
        val existing = if (out.exists()) out else if (jpg.exists()) jpg else null
        if (existing != null) {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(existing.path, b)
            if (b.outWidth > 0) return MediaRef(existing.path, 0, b.outWidth, b.outHeight, hasVideo = true, hasAudio = false, isImage = true, origin = f.path, title = f.name)
        }
        var bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val orientation = try {
            ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Throwable) { ExifInterface.ORIENTATION_NORMAL }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val s = minOf(1f, maxSide.toFloat() / maxOf(bmp.width, bmp.height))
        if (s < 1f) m.postScale(s, s)
        if (!m.isIdentity) {
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        val target = if (bmp.hasAlpha()) out else jpg
        FileOutputStream(target).use { bmp.compress(if (bmp.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 93, it) }
        val ref = MediaRef(target.path, 0, bmp.width, bmp.height, hasVideo = true, hasAudio = false, isImage = true, origin = f.path, title = f.name)
        bmp.recycle()
        return ref
    }

    private fun probeTimed(context: Context, f: File, audioOnly: Boolean): MediaRef? {
        // Media3 (media3-inspector) primero; MediaMetadataRetriever de plataforma como respaldo.
        try {
            MetadataRetriever.Builder(context, MediaItem.fromUri(Uri.fromFile(f))).build().use { r ->
                val groups = r.retrieveTrackGroups().get(15, TimeUnit.SECONDS)
                val durUs = r.retrieveDurationUs().get(15, TimeUnit.SECONDS)
                var w = 0; var h = 0; var fps = 30f; var hdr = false; var hasVideo = false; var hasAudio = false
                for (i in 0 until groups.length) {
                    val g = groups.get(i)
                    if (g.length == 0) continue
                    val fmt = g.getFormat(0)
                    if (g.type == C.TRACK_TYPE_VIDEO && !hasVideo) {
                        hasVideo = true
                        val rot = fmt.rotationDegrees
                        w = if (rot == 90 || rot == 270) fmt.height else fmt.width
                        h = if (rot == 90 || rot == 270) fmt.width else fmt.height
                        if (fmt.frameRate > 0f) fps = fmt.frameRate
                        hdr = ColorInfo.isTransferHdr(fmt.colorInfo)
                    } else if (g.type == C.TRACK_TYPE_AUDIO) hasAudio = true
                }
                if (durUs != null && durUs > 0 && durUs != C.TIME_UNSET && (hasVideo || hasAudio)) {
                    if (hasVideo && (w <= 0 || h <= 0)) { w = 1280; h = 720 }
                    return MediaRef(f.path, durUs, w, h, hasVideo = hasVideo && !audioOnly, hasAudio = hasAudio, fps = fps, isHdr = hdr, title = f.name)
                }
            }
        } catch (e: Throwable) {
            ErrorLog.log("Probe/media3 " + f.name, e)
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.path)
            val dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
            if (dur <= 0) return null
            val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            if (!hasVideo && !hasAudio) return null
            return MediaRef(f.path, dur, w, h, hasVideo = hasVideo && !audioOnly, hasAudio = hasAudio, title = f.name)
        } finally {
            r.release()
        }
    }

    /** Crea el clip por defecto para un medio. */
    fun clipFor(media: MediaRef, imageDurationUs: Long, asAudio: Boolean = false): Clip = when {
        media.isImage -> Clip(kind = ClipKind.IMAGE, durationUs = imageDurationUs, media = media, label = media.title)
        asAudio || !media.hasVideo -> Clip(kind = ClipKind.AUDIO, media = media, srcInUs = 0, srcOutUs = media.durationUs, durationUs = media.durationUs, label = media.title)
        else -> Clip(kind = ClipKind.VIDEO, media = media, srcInUs = 0, srcOutUs = media.durationUs, durationUs = media.durationUs, label = media.title)
    }

    /** Extrae un fotograma exacto de un vídeo (media3-inspector-frame) y lo guarda como imagen. */
    suspend fun extractFrame(context: Context, path: String, positionUs: Long): MediaRef? = withContext(Dispatchers.IO) {
        try {
            val bmp = FrameExtractor.Builder(context, MediaItem.fromUri(Uri.fromFile(File(path)))).build().use { fe ->
                fe.getFrame(positionUs / 1000L).get(30, TimeUnit.SECONDS).bitmap
            }
            val out = File(mediaDir(context), "frame_" + hash(path + positionUs) + "_" + System.currentTimeMillis() % 100000 + ".jpg")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            MediaRef(out.path, 0, bmp.width, bmp.height, hasVideo = true, hasAudio = false, isImage = true, origin = path, title = File(path).name)
        } catch (e: Throwable) {
            ErrorLog.log("Frame", e)
            // Respaldo de plataforma.
            try {
                val r = MediaMetadataRetriever()
                val b = try { r.setDataSource(path); r.getFrameAtTime(positionUs, MediaMetadataRetriever.OPTION_CLOSEST) } finally { r.release() }
                if (b == null) null else {
                    val out = File(mediaDir(context), "frame_" + hash(path + positionUs) + "_" + System.currentTimeMillis() % 100000 + ".jpg")
                    FileOutputStream(out).use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    MediaRef(out.path, 0, b.width, b.height, hasVideo = true, hasAudio = false, isImage = true, origin = path, title = File(path).name)
                }
            } catch (e2: Throwable) {
                ErrorLog.log("Frame", e2)
                null
            }
        }
    }

    @Suppress("unused")
    val DEFAULT_IMAGE_US = 3 * US
}
