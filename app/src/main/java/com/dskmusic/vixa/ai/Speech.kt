package com.dskmusic.vixa.ai

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Voz con sherpa-onnx: detección de voz, transcripción, mejora de voz y texto a voz. Todo bloqueante (Dispatchers.Default). */
object Speech {
    const val RATE = 16000
    private const val WINDOW = 512

    class Word(val text: String, val startUs: Long, val endUs: Long)

    /** Frase transcrita, en tiempo del medio. */
    class Line(val startUs: Long, val endUs: Long, val text: String, val lang: String)

    /** Subtítulo listo para la línea de tiempo: [wordsUs] es el inicio de cada palabra relativo a [startUs]. */
    class Sub(val startUs: Long, val endUs: Long, val text: String, val wordsUs: List<Long>)

    private fun threads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    private fun newVad(store: ModelStore, maxSpeech: Float, minSilence: Float): Vad {
        val silero = SileroVadModelConfig()
        silero.model = store.file(Models.VAD, "silero_vad.onnx").path
        silero.threshold = 0.5f
        silero.minSilenceDuration = minSilence
        silero.minSpeechDuration = 0.25f
        silero.windowSize = WINDOW
        silero.maxSpeechDuration = maxSpeech
        val cfg = VadModelConfig()
        cfg.sileroVadModelConfig = silero
        cfg.sampleRate = RATE
        cfg.numThreads = 1
        return Vad(null, cfg)
    }

    /** Pasa el audio por el VAD por bloques; [onSegment] recibe el inicio (en muestras a 16 kHz desde [fromUs]) y las muestras. */
    private suspend fun stream(
        vad: Vad, path: String, fromUs: Long, toUs: Long, totalUs: Long, progress: (Float) -> Unit,
        onSegment: suspend (start: Long, samples: FloatArray) -> Unit,
    ) {
        val win = FloatArray(WINDOW)
        var fill = 0
        var fed = 0L
        suspend fun drain() {
            while (!vad.empty()) {
                val s = vad.front()
                vad.pop()
                onSegment(s.start.toLong(), s.samples)
            }
        }
        val ok = AiAudio.decode(path, fromUs, toUs, RATE, false) { b, n ->
            var i = 0
            while (i < n) {
                val k = minOf(WINDOW - fill, n - i)
                System.arraycopy(b, i, win, fill, k)
                fill += k
                i += k
                if (fill == WINDOW) {
                    vad.acceptWaveform(win)
                    fill = 0
                    fed += WINDOW
                    drain()
                }
            }
            progress((fed * 1_000_000.0 / RATE / totalUs.coerceAtLeast(1)).toFloat().coerceIn(0f, 1f))
        }
        if (!ok) throw IOException("no audio")
        if (fill > 0) {
            java.util.Arrays.fill(win, fill, WINDOW, 0f)
            vad.acceptWaveform(win)
        }
        vad.flush()
        drain()
    }

    private fun spanUs(path: String, fromUs: Long, toUs: Long): Long =
        if (toUs > fromUs) toUs - fromUs else (AiAudio.durationUs(path) - fromUs).coerceAtLeast(1)

    /** Tramos con voz (inicio/fin en µs del medio) dentro de [fromUs, toUs]. */
    suspend fun speechRanges(store: ModelStore, path: String, fromUs: Long, toUs: Long, progress: (Float) -> Unit = {}): List<Pair<Long, Long>> {
        val vad = newVad(store, 30f, 0.35f)
        val out = ArrayList<Pair<Long, Long>>()
        try {
            stream(vad, path, fromUs, toUs, spanUs(path, fromUs, toUs), progress) { start, samples ->
                val a = fromUs + start * 1_000_000L / RATE
                out.add(a to a + samples.size * 1_000_000L / RATE)
            }
        } finally {
            vad.release()
        }
        return out
    }

    /**
     * Transcribe (o traduce al inglés con [translate]) el tramo del medio. [language]: "" = detección
     * automática, o un código de Whisper ("es", "en"). Cada frase del VAD es una [Line].
     */
    suspend fun transcribe(
        store: ModelStore, path: String, fromUs: Long, toUs: Long, quality: String, language: String, translate: Boolean,
        progress: (Float) -> Unit = {},
    ): List<Line> {
        val id = Models.whisper(quality)
        val whisper = OfflineWhisperModelConfig()
        whisper.encoder = store.file(id, "encoder.onnx").path
        whisper.decoder = store.file(id, "decoder.onnx").path
        whisper.language = language
        whisper.task = if (translate) "translate" else "transcribe"
        whisper.tailPaddings = 1000
        val model = OfflineModelConfig()
        model.whisper = whisper
        model.tokens = store.file(id, "tokens.txt").path
        model.numThreads = threads()
        model.debug = false
        model.provider = "cpu"
        model.modelType = "whisper"
        val cfg = OfflineRecognizerConfig()
        cfg.featConfig = FeatureConfig(RATE, 80, 0f)
        cfg.modelConfig = model
        cfg.decodingMethod = "greedy_search"
        // Whisper admite 30 s como máximo: el VAD parte las frases largas antes.
        val vad = newVad(store, 12f, 0.3f)
        var rec: OfflineRecognizer? = null
        val out = ArrayList<Line>()
        try {
            val r = OfflineRecognizer(null, cfg)
            rec = r
            stream(vad, path, fromUs, toUs, spanUs(path, fromUs, toUs), progress) { start, samples ->
                coroutineContext.ensureActive()
                val st = r.createStream()
                try {
                    st.acceptWaveform(samples, RATE)
                    r.decode(st)
                    val res = r.getResult(st)
                    val text = res.text.replace(Regex("\\s+"), " ").trim()
                    if (text.isNotEmpty() && text.any { it.isLetterOrDigit() }) {
                        val a = fromUs + start * 1_000_000L / RATE
                        out.add(Line(a, a + samples.size * 1_000_000L / RATE, text, res.lang))
                    }
                } finally {
                    st.release()
                }
            }
        } finally {
            vad.release()
            rec?.release()
        }
        return out
    }

    /** Reparte las palabras de una frase dentro de su duración, en proporción a su longitud. */
    fun words(l: Line): List<Word> {
        val parts = l.text.split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val total = parts.sumOf { it.length + 1 }.toFloat()
        val span = (l.endUs - l.startUs).coerceAtLeast(1)
        var acc = 0
        return parts.map { w ->
            val a = l.startUs + (span * (acc / total)).toLong()
            acc += w.length + 1
            Word(w, a, l.startUs + (span * (acc / total)).toLong())
        }
    }

    /** Agrupa las palabras en subtítulos de como mucho [maxLines] líneas de [maxChars] caracteres. */
    fun subtitles(lines: List<Line>, maxChars: Int, maxLines: Int): List<Sub> {
        val out = ArrayList<Sub>()
        val mc = maxChars.coerceAtLeast(8)
        val ml = maxLines.coerceAtLeast(1)
        for (l in lines) {
            val cur = ArrayList<Word>()
            val rows = ArrayList<StringBuilder>()
            fun flush() {
                if (cur.isEmpty()) return
                val start = cur.first().startUs
                out.add(Sub(start, cur.last().endUs, rows.joinToString("\n"), cur.map { it.startUs - start }))
                cur.clear()
                rows.clear()
            }
            for (w in words(l)) {
                val row = rows.lastOrNull()
                if (row == null) rows.add(StringBuilder(w.text))
                else if (row.length + 1 + w.text.length <= mc) row.append(' ').append(w.text)
                else if (rows.size < ml) rows.add(StringBuilder(w.text))
                else { flush(); rows.add(StringBuilder(w.text)) }
                cur.add(w)
                // Un final de oración con el subtítulo ya medio lleno cierra el bloque: se lee mejor.
                val last = w.text.last()
                if ((last == '.' || last == '?' || last == '!') && rows.sumOf { it.length } >= mc * ml / 2) flush()
            }
            flush()
        }
        return out
    }

    fun srtTime(us: Long): String {
        val ms = us / 1000
        return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }

    fun toSrt(subs: List<Sub>): String = subs.mapIndexed { i, s -> "${i + 1}\n${srtTime(s.startUs)} --> ${srtTime(s.endUs)}\n${s.text}\n" }.joinToString("\n")

    /** Frecuencia de trabajo de cada modelo de mejora de voz. */
    fun denoiseRate(modelId: String): Int = if (modelId == Models.DENOISE_FAST) 16000 else 48000

    /**
     * Reduce el ruido de todo el audio de [path] y lo escribe en [out] (WAV mono a la frecuencia del modelo).
     * [amount] 0..1 mezcla el original con el procesado. Trabaja en bloques de 20 s con 1 s de contexto.
     */
    suspend fun denoise(store: ModelStore, modelId: String, path: String, amount: Float, out: File, progress: (Float) -> Unit = {}) {
        val rate = denoiseRate(modelId)
        val mc = OfflineSpeechDenoiserModelConfig()
        if (modelId == Models.DENOISE_FAST) mc.gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(store.file(modelId, "model.onnx").path)
        else mc.dpdfnet = OfflineSpeechDenoiserDpdfNetModelConfig(store.file(modelId, "model.onnx").path, 0f)
        mc.numThreads = threads()
        mc.debug = false
        mc.provider = "cpu"
        val den = OfflineSpeechDenoiser(null, OfflineSpeechDenoiserConfig(mc))
        val totalUs = spanUs(path, 0, 0)
        val ctx = rate // 1 s de contexto previo para que el modelo arranque "caliente" en cada bloque
        val block = rate * 20
        val buf = FloatArray(ctx + block)
        var have = ctx // los primeros [ctx] son silencio en el primer bloque
        var written = 0L
        val a = amount.coerceIn(0f, 1f)
        val tmp = File(out.parentFile, out.name + ".part")
        suspend fun process(n: Int, w: WavWriter) {
            coroutineContext.ensureActive()
            val d = den.run(buf.copyOf(n), rate)
            val y = if (d.sampleRate == rate) d.samples else AiAudio.resample(d.samples, d.sampleRate, rate)
            val mix = FloatArray(n - ctx)
            for (i in mix.indices) {
                val o = buf[ctx + i]
                val p = if (ctx + i < y.size) y[ctx + i] else o
                mix[i] = o * (1f - a) + p * a
            }
            w.write(mix)
            written += mix.size
            System.arraycopy(buf, n - ctx, buf, 0, ctx)
            have = ctx
            progress((written * 1_000_000.0 / rate / totalUs).toFloat().coerceIn(0f, 1f))
        }
        try {
            WavWriter(tmp, rate, 1).use { w ->
                val ok = AiAudio.decode(path, 0, 0, rate, false) { b, n ->
                    var i = 0
                    while (i < n) {
                        val k = minOf(buf.size - have, n - i)
                        System.arraycopy(b, i, buf, have, k)
                        have += k
                        i += k
                        if (have == buf.size) process(have, w)
                    }
                }
                if (!ok) throw IOException("no audio")
                if (have > ctx) process(have, w)
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw IOException("rename")
        } finally {
            den.release()
            tmp.delete()
        }
    }

    /** Sintetiza [text] con una voz Piper y lo guarda en [out] (WAV mono). [speed] 0,5..2. */
    suspend fun tts(store: ModelStore, voiceId: String, text: String, speed: Float, out: File, speaker: Int = 0) {
        val onnx = store.find(voiceId, "onnx") ?: throw IOException("voice model missing")
        val dir = onnx.parentFile!!
        val vits = OfflineTtsVitsModelConfig()
        vits.model = onnx.path
        vits.tokens = File(dir, "tokens.txt").path
        vits.dataDir = File(dir, "espeak-ng-data").path
        val model = OfflineTtsModelConfig()
        model.vits = vits
        model.numThreads = threads()
        model.debug = false
        model.provider = "cpu"
        val cfg = OfflineTtsConfig()
        cfg.model = model
        cfg.maxNumSentences = 1
        val tts = OfflineTts(null, cfg)
        val tmp = File(out.parentFile, out.name + ".part")
        try {
            coroutineContext.ensureActive()
            val audio = tts.generate(text, speaker.coerceAtLeast(0), speed.coerceIn(0.5f, 2f))
            if (audio.samples.isEmpty()) throw IOException("empty audio")
            WavWriter(tmp, audio.sampleRate, 1).use { it.write(audio.samples) }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) throw IOException("rename")
        } finally {
            tts.release()
            tmp.delete()
        }
    }
}
