package com.dskmusic.vixa.engine

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.kf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Ganancia de un clip en el instante local [localUs]: volumen, fundidos, transición y atenuación automática. */
fun clipGain(p: Project, c: Clip, localUs: Long): Float {
    val track = p.trackOf(c.id) ?: return 0f
    if (c.muted || track.muted) return 0f
    var g = c.kf("volume", localUs, c.volume) * c.audioFx.normalizeGain
    if (c.fadeInUs > 0 && localUs < c.fadeInUs) g *= localUs.toFloat() / c.fadeInUs
    val rest = c.durationUs - localUs
    if (c.fadeOutUs > 0 && rest < c.fadeOutUs) g *= (rest.toFloat() / c.fadeOutUs).coerceAtLeast(0f)
    if (track.type == TrackType.MAIN || track.type == TrackType.OVERLAY) {
        // Fundido cruzado de audio durante las transiciones.
        val ti = c.transitionIn
        if (ti != null && ti.durationUs > 0 && localUs < ti.durationUs) g *= localUs.toFloat() / ti.durationUs
        val i = track.clips.indexOf(c)
        val to = track.clips.getOrNull(i + 1)?.transitionIn
        if (to != null && to.durationUs > 0 && rest < to.durationUs) g *= (rest.toFloat() / to.durationUs).coerceAtLeast(0f)
    }
    if (track.type == TrackType.AUDIO) {
        // Crossfade automático con el clip anterior / siguiente de la misma pista cuando se solapan (potencia constante).
        val i = track.clips.indexOfFirst { it.id == c.id }
        val prev = track.clips.getOrNull(i - 1)
        val next = track.clips.getOrNull(i + 1)
        val inOv = if (prev != null) prev.endUs - c.startUs else 0L
        if (inOv > 0 && localUs < inOv) g *= sin(localUs.toFloat() / inOv * (PI.toFloat() / 2f))
        val outOv = if (next != null) c.endUs - next.startUs else 0L
        if (outOv > 0 && rest < outOv) g *= sin((rest.toFloat() / outOv).coerceAtLeast(0f) * (PI.toFloat() / 2f))
    }
    val ducks = c.audioFx.duck || (p.autoDuck && c.kind == ClipKind.AUDIO && !c.audioFx.isVoice)
    if (ducks) {
        val t = c.startUs + localUs
        var d = Long.MAX_VALUE
        for (tr in p.tracks) {
            if (tr.muted) continue
            for (v in tr.clips) {
                if (!v.audioFx.isVoice || v.id == c.id || !v.audible) continue
                if (v.voiceRangesUs.size >= 2) {
                    // Voz detectada por IA: solo cuentan los tramos en que se habla de verdad.
                    var i = 0
                    while (i + 1 < v.voiceRangesUs.size) {
                        val a = v.startUs + v.voiceRangesUs[i]
                        val b = v.startUs + v.voiceRangesUs[i + 1]
                        val dr = if (t < a) a - t else if (t > b) t - b else 0L
                        if (dr < d) d = dr
                        i += 2
                    }
                    continue
                }
                val dist = if (t < v.startUs) v.startUs - t else if (t > v.endUs) t - v.endUs else 0L
                if (dist < d) d = dist
            }
        }
        if (d < 300_000L) {
            val k = d / 300_000f
            g *= p.duckAmount + (1f - p.duckAmount) * k
        }
    }
    return g.coerceIn(0f, 4f)
}

/** Filtro bicuadrático (fórmulas RBJ) con estado por canal. */
private class Biquad(channels: Int) {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private val z1 = FloatArray(channels)
    private val z2 = FloatArray(channels)
    var active = false

    fun reset() { z1.fill(0f); z2.fill(0f) }

    /** type: 0 = low shelf, 1 = peak, 2 = high shelf. */
    fun set(type: Int, freq: Float, sampleRate: Int, gainDb: Float, q: Float = 0.707f) {
        active = abs(gainDb) > 0.05f
        if (!active) return
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * min(freq, sampleRate * 0.45f) / sampleRate
        val cw = cos(w0)
        val sw = sin(w0)
        val alpha = sw / (2.0 * q)
        val nb0: Double; val nb1: Double; val nb2: Double; val na0: Double; val na1: Double; val na2: Double
        when (type) {
            0 -> {
                val s = 2.0 * sqrt(a) * alpha
                nb0 = a * ((a + 1) - (a - 1) * cw + s); nb1 = 2 * a * ((a - 1) - (a + 1) * cw); nb2 = a * ((a + 1) - (a - 1) * cw - s)
                na0 = (a + 1) + (a - 1) * cw + s; na1 = -2 * ((a - 1) + (a + 1) * cw); na2 = (a + 1) + (a - 1) * cw - s
            }
            2 -> {
                val s = 2.0 * sqrt(a) * alpha
                nb0 = a * ((a + 1) + (a - 1) * cw + s); nb1 = -2 * a * ((a - 1) + (a + 1) * cw); nb2 = a * ((a + 1) + (a - 1) * cw - s)
                na0 = (a + 1) - (a - 1) * cw + s; na1 = 2 * ((a - 1) - (a + 1) * cw); na2 = (a + 1) - (a - 1) * cw - s
            }
            else -> {
                nb0 = 1 + alpha * a; nb1 = -2 * cw; nb2 = 1 - alpha * a
                na0 = 1 + alpha / a; na1 = -2 * cw; na2 = 1 - alpha / a
            }
        }
        b0 = (nb0 / na0).toFloat(); b1 = (nb1 / na0).toFloat(); b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat(); a2 = (na2 / na0).toFloat()
    }

    fun process(x: Float, ch: Int): Float {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y
    }
}

/**
 * Procesador de audio por clip: volumen con keyframes, fundidos, atenuación automática, ecualizador de
 * 3 bandas, puerta de ruido y efectos de voz. Lee el proyecto en vivo, como los efectos de vídeo.
 */
class VixaAudioProcessor(private val project: () -> Project, private val clipId: String) : BaseAudioProcessor() {

    private var positionUs = 0L
    private var frames = 0L
    private var channels = 2
    private var sampleRate = 44100
    private var isFloat = false
    private var eq: Array<Biquad> = emptyArray()
    private var eqKey = 0
    private var lastGain = -1f
    private var gate = 1f
    private var env = 0f
    private var echoBuf = FloatArray(0)
    private var echoPos = 0
    private var phase = 0.0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        positionUs = streamMetadata.positionOffsetUs.coerceAtLeast(0)
        frames = 0
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        eq = Array(3) { Biquad(channels) }
        eqKey = 0
        lastGain = -1f
        gate = 1f
        env = 0f
        echoBuf = FloatArray((sampleRate * 0.22f).toInt() * channels)
        echoPos = 0
        phase = 0.0
    }

    override fun onReset() {
        eq = emptyArray()
        echoBuf = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerSample = if (isFloat) 4 else 2
        val frameBytes = bytesPerSample * channels
        val frameCount = inputBuffer.remaining() / frameBytes
        if (frameCount <= 0) return
        val out = replaceOutputBuffer(frameCount * frameBytes).order(ByteOrder.nativeOrder())
        val inp = inputBuffer.order(ByteOrder.nativeOrder())

        val p = project()
        val c = p.clip(clipId)
        val localUs = positionUs + frames * 1_000_000L / sampleRate
        val target = if (c == null) 0f else clipGain(p, c, localUs.coerceIn(0, c.durationUs))
        val startGain = if (lastGain < 0f) target else lastGain
        lastGain = target

        val fx = c?.audioFx
        val voice = if (fx != null && fx.voice.isNotEmpty()) Registry.voice(fx.voice) else null
        val radio = fx?.voice == "radio"
        val low = if (radio) -1f else fx?.eqLow ?: 0f
        val mid = if (radio) 0.6f else fx?.eqMid ?: 0f
        val high = if (radio) -0.8f else fx?.eqHigh ?: 0f
        val key = (low * 1000).toInt() * 31 * 31 + (mid * 1000).toInt() * 31 + (high * 1000).toInt() + 1
        if (key != eqKey && eq.size == 3) {
            eqKey = key
            eq[0].set(0, 200f, sampleRate, low * 12f)
            eq[1].set(1, 1000f, sampleRate, mid * 12f, 0.8f)
            eq[2].set(2, 5000f, sampleRate, high * 12f)
        }
        val useEq = eq.size == 3 && (eq[0].active || eq[1].active || eq[2].active)
        val denoise = fx?.denoise ?: 0f
        val threshold = denoise * 0.06f
        val robot = voice?.robot ?: 0f
        val echo = voice?.echo ?: 0f
        val tremolo = voice?.tremolo ?: 0f
        val useOsc = robot > 0f || tremolo > 0f
        val robotStep = 2.0 * PI * 55.0 / sampleRate
        val tremStep = 2.0 * PI * 6.0 / sampleRate
        val attack = 1f - Math.exp(-1.0 / (sampleRate * 0.005)).toFloat()
        val release = 1f - Math.exp(-1.0 / (sampleRate * 0.12)).toFloat()

        for (i in 0 until frameCount) {
            val g = startGain + (target - startGain) * (i.toFloat() / frameCount)
            var osc = 1f
            if (useOsc) {
                if (robot > 0f) {
                    osc = (1f - robot) + robot * sin(phase).toFloat()
                    phase += robotStep
                } else {
                    osc = 1f - tremolo * 0.5f * (1f + sin(phase).toFloat())
                    phase += tremStep
                }
                if (phase > 2.0 * PI) phase -= 2.0 * PI
            }
            var peak = 0f
            val base = inp.position()
            for (ch in 0 until channels) {
                var s = if (isFloat) inp.getFloat(base + ch * 4) else inp.getShort(base + ch * 2) / 32768f
                if (useEq) s = eq[2].process(eq[1].process(eq[0].process(s, ch), ch), ch)
                s *= osc
                if (echo > 0f && echoBuf.isNotEmpty()) {
                    val d = echoBuf[echoPos]
                    echoBuf[echoPos] = s + d * 0.4f
                    echoPos++
                    if (echoPos >= echoBuf.size) echoPos = 0
                    s += d * echo * 0.7f
                }
                val av = abs(s)
                if (av > peak) peak = av
                s *= g * gate
                if (isFloat) out.putFloat(s.coerceIn(-1f, 1f)) else out.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            inp.position(base + frameBytes)
            if (threshold > 0f) {
                env += (peak - env) * (if (peak > env) attack else release)
                val want = if (env >= threshold) 1f else (env / threshold) * (env / threshold)
                gate += (want - gate) * 0.002f
            } else gate = 1f
        }
        frames += frameCount
        out.flip()
    }
}
