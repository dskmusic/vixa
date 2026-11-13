package com.dskmusic.vixa.model

import kotlinx.serialization.Serializable

@Serializable
enum class ExportKind { VIDEO, AUDIO, GIF, FRAME_PNG, FRAME_JPG }

@Serializable
enum class VideoCodec(val mime: String) {
    H264("video/avc"), H265("video/hevc"), AV1("video/av01"), VP9("video/x-vnd.on2.vp9")
}

@Serializable
enum class Container { MP4, WEBM }

@Serializable
enum class Quality(val bpp: Float) { LOW(0.05f), MEDIUM(0.09f), HIGH(0.14f), CUSTOM(0f) }

@Serializable
data class ExportSettings(
    val presetName: String = "",
    val kind: ExportKind = ExportKind.VIDEO,
    /** Lado menor en píxeles (480, 720, 1080, 1440, 2160). */
    val shortSide: Int = 1080,
    val fps: Int = 30,
    val codec: VideoCodec = VideoCodec.H264,
    val container: Container = Container.MP4,
    val quality: Quality = Quality.HIGH,
    /** Bitrate de vídeo en bits/s cuando quality = CUSTOM. */
    val customBitrate: Int = 8_000_000,
    /** Tramo a exportar; -1 = todo. */
    val rangeStartUs: Long = -1,
    val rangeEndUs: Long = -1,
    val outDir: String = "",
    val fileName: String = "",
    /** Instante del fotograma para FRAME_PNG / FRAME_JPG. */
    val frameUs: Long = 0,
    val gifWidth: Int = 480,
    val gifFps: Int = 12,
    /** GIF boomerang: el tramo se reproduce hacia delante y luego hacia atrás, para un bucle perfecto. */
    val boomerang: Boolean = false,
    /** Compresión del GIF: 0 = sin pérdida, 1 = ligera, 2 = media, 3 = fuerte (menos colores y diferencias pequeñas ignoradas). */
    val gifCompression: Int = 1,
    val gifDither: Boolean = true,
    /** false = el GIF se reproduce una sola vez. */
    val gifLoop: Boolean = true,
    val gifSpeed: Float = 1f,
    /** Intentar copiar sin recodificar cuando el proyecto lo permita. */
    val passthrough: Boolean = true,
    /** Guardar también la portada elegida como JPG junto al vídeo. */
    val saveCover: Boolean = false,
    /** Cambia el lienzo del proyecto antes de exportar (0 = usar el del proyecto). */
    val aspectW: Int = 0,
    val aspectH: Int = 0,
    /** Codificar con el chip de vídeo del dispositivo (GPU/codec hardware). Desactivar si da problemas. */
    val hardware: Boolean = true,
) {
    fun videoBitrate(w: Int, h: Int): Int {
        if (quality == Quality.CUSTOM) return customBitrate.coerceIn(200_000, 200_000_000)
        val eff = if (codec == VideoCodec.H264) 1f else 0.65f
        return (w.toLong() * h * fps * quality.bpp * eff).toLong().coerceIn(300_000L, 150_000_000L).toInt()
    }

    /** Tamaño de salida (pares) para un lienzo de proyecto dado. */
    fun outputSize(projectW: Int, projectH: Int): Pair<Int, Int> {
        val minSide = minOf(projectW, projectH).coerceAtLeast(2)
        val s = shortSide.toFloat() / minSide
        val w = (Math.round(projectW * s) / 2 * 2).coerceAtLeast(2)
        val h = (Math.round(projectH * s) / 2 * 2).coerceAtLeast(2)
        return w to h
    }

    /** Estimación del tamaño final en bytes. */
    fun estimateBytes(projectW: Int, projectH: Int, durationUs: Long): Long {
        val secs = durationUs / 1_000_000.0
        return when (kind) {
            ExportKind.AUDIO -> (secs * 192_000 / 8).toLong()
            ExportKind.GIF -> (secs * gifFps * gifWidth * gifWidth * 0.18 * (if (boomerang) 2 else 1) * (when (gifCompression) { 0 -> 0.8; 1 -> 0.6; 2 -> 0.45; else -> 0.3 })).toLong()
            ExportKind.FRAME_PNG, ExportKind.FRAME_JPG -> 3_000_000L
            ExportKind.VIDEO -> {
                val (w, h) = outputSize(projectW, projectH)
                (secs * (videoBitrate(w, h) + 192_000) / 8).toLong()
            }
        }
    }

    val extension: String
        get() = when (kind) {
            ExportKind.VIDEO -> if (container == Container.WEBM) "webm" else "mp4"
            ExportKind.AUDIO -> "m4a"
            ExportKind.GIF -> "gif"
            ExportKind.FRAME_PNG -> "png"
            ExportKind.FRAME_JPG -> "jpg"
        }

    companion object {
        val SOCIAL_PRESETS: List<ExportSettings> = listOf(
            ExportSettings(presetName = "9:16 · 1080p", shortSide = 1080, fps = 30, aspectW = 9, aspectH = 16),
            ExportSettings(presetName = "16:9 · 1080p", shortSide = 1080, fps = 30, aspectW = 16, aspectH = 9),
            ExportSettings(presetName = "1:1 · 1080p", shortSide = 1080, fps = 30, aspectW = 1, aspectH = 1),
            ExportSettings(presetName = "16:9 · 4K", shortSide = 2160, fps = 30, codec = VideoCodec.H265, aspectW = 16, aspectH = 9),
        )
    }
}
