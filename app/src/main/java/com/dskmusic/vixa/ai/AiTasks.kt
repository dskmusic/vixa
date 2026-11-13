package com.dskmusic.vixa.ai

import com.dskmusic.vixa.R
import com.dskmusic.vixa.model.AiAudioRef
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/**
 * Tareas de IA con caché en disco. Cada resultado tiene una ruta determinista (medio + parámetros + modelo):
 * repetir una tarea ya hecha es instantáneo y un archivo borrado se regenera con los datos guardados en el proyecto.
 */
object AiTasks {
    const val DENOISE = "denoise"
    const val VOCALS = "vocals"
    const val MUSIC = "music"
    const val TTS = "tts"

    fun denoiseRef(store: ModelStore, media: String, model: String, amount: Float): AiAudioRef {
        val a = ((amount.coerceIn(0f, 1f) * 20f).roundToInt() / 20f)
        return AiAudioRef(DENOISE, model, a, AiCache.file(store.context, AiCache.key(media, DENOISE, model, (a * 100).roundToInt()), "wav").path)
    }

    fun stemRef(store: ModelStore, media: String, model: String, vocals: Boolean): AiAudioRef =
        AiAudioRef(if (vocals) VOCALS else MUSIC, model, 1f, AiCache.file(store.context, AiCache.key(media, "sep", model) + (if (vocals) "_v" else "_m"), "wav").path)

    fun ttsRef(store: ModelStore, voice: String, text: String, speed: Float, speaker: Int = 0): AiAudioRef {
        val s = (speed.coerceIn(0.5f, 2f) * 20f).roundToInt() / 20f
        return AiAudioRef(TTS, voice, s, AiCache.file(store.context, AiCache.key("", TTS, voice, (s * 100).roundToInt(), speaker, text), "wav").path, text.trim(), speaker = speaker)
    }

    private fun need(store: ModelStore, id: String) {
        if (!store.isReady(id)) throw IOException(store.context.getString(R.string.ai_err_model_missing, Models.get(id)?.name ?: id))
    }

    /** Genera el archivo de [ref] si no existe ya. [media] es el medio de origen (no se usa en "tts"). */
    suspend fun ensure(store: ModelStore, media: String, ref: AiAudioRef, progress: (Float) -> Unit = {}) {
        val out = File(ref.path)
        if (out.exists() && out.length() > 44) { progress(1f); return }
        out.parentFile?.mkdirs()
        need(store, ref.model)
        when (ref.kind) {
            DENOISE -> Speech.denoise(store, ref.model, media, ref.amount, out, progress)
            VOCALS, MUSIC -> {
                // La separación produce las dos pistas a la vez; la hermana queda en caché para la otra acción.
                val base = ref.path.removeSuffix(if (ref.kind == VOCALS) "_v.wav" else "_m.wav")
                Separation.separate(store, ref.model, media, File(base + "_v.wav"), File(base + "_m.wav"), progress)
            }
            TTS -> Speech.tts(store, ref.model, ref.text, ref.amount, out, ref.speaker)
            else -> throw IOException("unknown AI result: " + ref.kind)
        }
        progress(1f)
    }

    suspend fun transcribe(
        store: ModelStore, path: String, fromUs: Long, toUs: Long, quality: String, language: String, translate: Boolean,
        progress: (Float) -> Unit = {},
    ): List<Speech.Line> {
        val f = AiCache.file(store.context, AiCache.key(path, "asr", quality, language, translate, fromUs, toUs), "tsv")
        if (f.exists()) {
            progress(1f)
            return f.readLines().mapNotNull { row ->
                val p = row.split('\t', limit = 4)
                if (p.size < 4) null else Speech.Line(p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toLongOrNull() ?: return@mapNotNull null, p[3], p[2])
            }
        }
        need(store, Models.VAD)
        need(store, Models.whisper(quality))
        val lines = Speech.transcribe(store, path, fromUs, toUs, quality, language, translate, progress)
        f.writeText(lines.joinToString("\n") { "${it.startUs}\t${it.endUs}\t${it.lang}\t${it.text}" })
        return lines
    }

    /** Máscara de personas del medio (caché): ruta determinista según medio, modelo y tramo. */
    fun personFile(store: ModelStore, media: String, model: String, fromUs: Long, toUs: Long): File =
        AiCache.file(store.context, AiCache.key(media, "person", model, fromUs, toUs), "msk")

    suspend fun ensurePerson(store: ModelStore, media: String, image: Boolean, model: String, fromUs: Long, toUs: Long, progress: (Float) -> Unit = {}): File {
        val f = personFile(store, media, model, fromUs, toUs)
        if (f.exists() && f.length() > 28) { progress(1f); return f }
        need(store, model)
        f.parentFile?.mkdirs()
        Vision.personMask(store, model, media, image, fromUs, toUs, f, progress)
        return f
    }

    /** Pista de la primera cara del vídeo en [fromUs, toUs] (µs del medio), con caché. */
    suspend fun faceTrack(store: ModelStore, media: String, fromUs: Long, toUs: Long, progress: (Float) -> Unit = {}): List<Vision.FaceSample> {
        val f = AiCache.file(store.context, AiCache.key(media, "face", fromUs, toUs), "tsv")
        if (f.exists()) {
            val lines = f.readLines()
            val l = lines.mapNotNull { Vision.FaceSample.fromLine(it) }
            // Un archivo de una versión anterior (sin la boca) no se aprovecha: se vuelve a analizar.
            if (l.isNotEmpty() && l.size == lines.size) { progress(1f); return l }
        }
        need(store, Models.FACE_MESH)
        val track = Vision.faceTrack(store, media, fromUs, toUs, progress)
        f.writeText(track.joinToString("\n") { it.toLine() })
        return track
    }

    /** Tramos con voz del medio (µs de origen), con caché. */
    suspend fun speechRanges(store: ModelStore, path: String, fromUs: Long, toUs: Long, progress: (Float) -> Unit = {}): List<Pair<Long, Long>> {
        val f = AiCache.file(store.context, AiCache.key(path, "vad", fromUs, toUs), "tsv")
        if (f.exists()) {
            progress(1f)
            return f.readLines().mapNotNull { row ->
                val p = row.split('\t')
                if (p.size < 2) null else (p[0].toLongOrNull() ?: return@mapNotNull null) to (p[1].toLongOrNull() ?: return@mapNotNull null)
            }
        }
        need(store, Models.VAD)
        val r = Speech.speechRanges(store, path, fromUs, toUs, progress)
        f.writeText(r.joinToString("\n") { "${it.first}\t${it.second}" })
        return r
    }

    /** Une tramos separados por menos de [gapUs] y les añade [padUs] por cada lado sin salirse de [fromUs, toUs]. */
    fun merge(ranges: List<Pair<Long, Long>>, gapUs: Long, padUs: Long, fromUs: Long, toUs: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        for ((a0, b0) in ranges.sortedBy { it.first }) {
            val a = (a0 - padUs).coerceAtLeast(fromUs)
            val b = (b0 + padUs).coerceAtMost(toUs)
            if (b <= a) continue
            val last = out.lastOrNull()
            if (last != null && a - last.second < gapUs) out[out.size - 1] = last.first to maxOf(last.second, b) else out.add(a to b)
        }
        return out
    }
}
