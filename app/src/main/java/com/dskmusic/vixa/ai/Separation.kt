package com.dskmusic.vixa.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Separación voz / música con modelos UVR MDX-Net sobre ONNX Runtime. Es un port directo de la
 * implementación de sherpa-onnx (csrc/offline-source-separation-uvr-impl.h): su AAR de Android no expone
 * la separación por JNI, así que aquí se hace la STFT y el troceado en Kotlin.
 */
object Separation {

    /** FFT compleja de tamaño arbitrario (Bluestein sobre una radix-2). Los n_fft de MDX (6144, 7680) no son potencias de 2. */
    // ponytail: Bluestein cuesta 2 FFT de 16384 por transformada; si la STFT pesa más que el modelo, pasar a mixed-radix.
    private class Fft(val n: Int) {
        private val m = Integer.highestOneBit(2 * n - 1).let { if (it < 2 * n - 1) it shl 1 else it }
        private val cosT = DoubleArray(m / 2) { cos(2 * PI * it / m) }
        private val sinT = DoubleArray(m / 2) { sin(2 * PI * it / m) }
        private val wr = DoubleArray(n)
        private val wi = DoubleArray(n)
        private val br = DoubleArray(m)
        private val bi = DoubleArray(m)
        private val ar = DoubleArray(m)
        private val ai = DoubleArray(m)

        init {
            for (k in 0 until n) {
                // k² mod 2n evita perder precisión en el ángulo.
                val a = PI * ((k.toLong() * k) % (2L * n)) / n
                wr[k] = cos(a)
                wi[k] = -sin(a)
                br[k] = wr[k]
                bi[k] = -wi[k]
                if (k > 0) { br[m - k] = br[k]; bi[m - k] = bi[k] }
            }
            radix2(br, bi, false)
        }

        private fun radix2(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
            var j = 0
            for (i in 1 until m) {
                var bit = m shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
            }
            var len = 2
            while (len <= m) {
                val half = len / 2
                val step = m / len
                var i = 0
                while (i < m) {
                    var k = 0
                    for (p in i until i + half) {
                        val c = cosT[k]
                        val s = if (inverse) sinT[k] else -sinT[k]
                        val q = p + half
                        val tr = re[q] * c - im[q] * s
                        val ti = re[q] * s + im[q] * c
                        re[q] = re[p] - tr; im[q] = im[p] - ti
                        re[p] += tr; im[p] += ti
                        k += step
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        /** DFT directa in situ de (re, im), ambos de tamaño n. */
        fun forward(re: DoubleArray, im: DoubleArray) {
            java.util.Arrays.fill(ar, 0.0)
            java.util.Arrays.fill(ai, 0.0)
            for (k in 0 until n) {
                ar[k] = re[k] * wr[k] - im[k] * wi[k]
                ai[k] = re[k] * wi[k] + im[k] * wr[k]
            }
            radix2(ar, ai, false)
            for (k in 0 until m) {
                val r = ar[k] * br[k] - ai[k] * bi[k]
                ai[k] = ar[k] * bi[k] + ai[k] * br[k]
                ar[k] = r
            }
            radix2(ar, ai, true)
            for (k in 0 until n) {
                val r = ar[k] / m
                val i = ai[k] / m
                re[k] = r * wr[k] - i * wi[k]
                im[k] = r * wi[k] + i * wr[k]
            }
        }

        /** DFT inversa (ya dividida por n) mediante conj(fft(conj(x))). */
        fun inverse(re: DoubleArray, im: DoubleArray) {
            for (k in 0 until n) im[k] = -im[k]
            forward(re, im)
            for (k in 0 until n) { re[k] /= n; im[k] = -im[k] / n }
        }
    }

    private class Meta(val rate: Int, val nFft: Int, val hop: Int, val dimT: Int, val dimF: Int)

    private fun meta(session: OrtSession): Meta {
        val m = session.metadata.customMetadata
        fun int(key: String, def: Int = -1): Int = m[key]?.trim()?.toIntOrNull() ?: def
        if (m["model_type"]?.trim() != "UVR") throw IOException("not an UVR model")
        val nFft = int("n_fft")
        val dimT = int("dim_t")
        val dimF = int("dim_f")
        if (int("stems", 2) != 2 || int("dim_c", 4) != 4 || nFft <= 0 || dimT <= 1 || dimF <= 0 || dimF > nFft / 2 + 1) throw IOException("unsupported UVR model")
        return Meta(int("sample_rate", 44100), nFft, int("hop_length", 1024), dimT, dimF)
    }

    /** STFT + modelo + ISTFT de un trozo de [n] muestras por canal. Escribe la voz estimada en [outL]/[outR]. */
    private suspend fun processChunk(
        env: OrtEnvironment, session: OrtSession, input: String, mt: Meta, fft: Fft, win: DoubleArray,
        l: FloatArray, r: FloatArray, n: Int, outL: FloatArray, outR: FloatArray,
    ) {
        val nFft = mt.nFft
        val bins = nFft / 2 + 1
        val trim = nFft / 2
        val cs = mt.hop * (mt.dimT - 1)
        val gen = cs - 2 * trim
        val pad = gen - n % gen
        val pl = FloatArray(trim + n + pad + trim)
        val pr = FloatArray(pl.size)
        System.arraycopy(l, 0, pl, trim, n)
        System.arraycopy(r, 0, pr, trim, n)
        val plane = mt.dimF * mt.dimT
        val x = FloatArray(4 * plane)
        val y = FloatArray(4 * plane)
        val specRe = Array(2) { FloatArray(mt.dimT * bins) }
        val specIm = Array(2) { FloatArray(mt.dimT * bins) }
        val seg = DoubleArray(cs + nFft)
        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)
        val acc = DoubleArray(nFft + (mt.dimT - 1) * mt.hop)
        val den = DoubleArray(acc.size)
        for (f in 0 until mt.dimT) for (j in 0 until nFft) den[f * mt.hop + j] += win[j] * win[j]

        var start = 0
        while (start < n + pad) {
            coroutineContext.ensureActive()
            for (ch in 0 until 2) {
                val src = if (ch == 0) pl else pr
                // center = true con relleno reflejado, como torch.stft.
                for (i in 0 until cs) seg[trim + i] = src[start + i].toDouble()
                for (i in 0 until trim) {
                    seg[trim - 1 - i] = src[start + 1 + i].toDouble()
                    seg[trim + cs + i] = src[start + cs - 2 - i].toDouble()
                }
                for (f in 0 until mt.dimT) {
                    val o = f * mt.hop
                    for (j in 0 until nFft) { re[j] = seg[o + j] * win[j]; im[j] = 0.0 }
                    fft.forward(re, im)
                    val b = f * bins
                    for (k in 0 until bins) { specRe[ch][b + k] = re[k].toFloat(); specIm[ch][b + k] = im[k].toFloat() }
                }
                val base = ch * 2 * plane
                for (j in 0 until mt.dimF) for (k in 0 until mt.dimT) {
                    x[base + j * mt.dimT + k] = specRe[ch][k * bins + j]
                    x[base + plane + j * mt.dimT + k] = specIm[ch][k * bins + j]
                }
            }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 4, mt.dimF.toLong(), mt.dimT.toLong())).use { t ->
                session.run(mapOf(input to t)).use { res ->
                    val fb = (res.get(0) as OnnxTensor).floatBuffer
                    if (fb.remaining() < y.size) throw IOException("unexpected model output")
                    fb.get(y)
                }
            }
            for (ch in 0 until 2) {
                val base = ch * 2 * plane
                java.util.Arrays.fill(acc, 0.0)
                for (f in 0 until mt.dimT) {
                    // Espectro hermítico completo: los bins por encima de dim_f quedan a cero.
                    java.util.Arrays.fill(re, 0.0)
                    java.util.Arrays.fill(im, 0.0)
                    for (k in 0 until mt.dimF) {
                        val a = y[base + k * mt.dimT + f].toDouble()
                        val b = y[base + plane + k * mt.dimT + f].toDouble()
                        re[k] = a
                        im[k] = b
                        if (k in 1 until nFft / 2) { re[nFft - k] = a; im[nFft - k] = -b }
                    }
                    im[0] = 0.0
                    fft.inverse(re, im)
                    val o = f * mt.hop
                    for (j in 0 until nFft) acc[o + j] += re[j] * win[j]
                }
                val out = if (ch == 0) outL else outR
                // Sin el relleno de "center" (trim) ni el recorte propio de MDX (otro trim por lado).
                for (i in 0 until gen) {
                    val dst = start + i
                    if (dst >= n) break
                    val p = trim + trim + i
                    val d = den[p]
                    out[dst] = (if (d > 1e-9) acc[p] / d else acc[p]).toFloat()
                }
            }
            start += gen
        }
    }

    /**
     * Separa el audio de [path] en voz ([vocals]) y resto ([music]), ambos WAV estéreo a la frecuencia del
     * modelo. Procesa por trozos de 15 s con 1 s de margen a cada lado; nunca carga el audio entero.
     */
    suspend fun separate(store: ModelStore, modelId: String, path: String, vocals: File, music: File, progress: (Float) -> Unit = {}) {
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        val session = env.createSession(store.file(modelId, "model.onnx").path, opts)
        val tv = File(vocals.parentFile, vocals.name + ".part")
        val tm = File(music.parentFile, music.name + ".part")
        try {
            val mt = meta(session)
            val input = session.inputNames.first()
            val fft = Fft(mt.nFft)
            // Ventana de Hann periódica (la de torch.hann_window).
            val win = DoubleArray(mt.nFft) { 0.5 - 0.5 * cos(2 * PI * it / mt.nFft) }
            val margin = mt.rate
            val chunk = mt.rate * 15
            val cap = margin + chunk + margin
            val l = FloatArray(cap)
            val r = FloatArray(cap)
            val ol = FloatArray(cap)
            val orr = FloatArray(cap)
            var have = 0
            var lead = 0 // muestras de margen previo al principio del búfer
            var written = 0L
            val totalUs = AiAudio.durationUs(path).coerceAtLeast(1)
            val inter = FloatArray(cap * 2)
            WavWriter(tv, mt.rate, 2).use { wv ->
                WavWriter(tm, mt.rate, 2).use { wm ->
                    suspend fun flush(last: Boolean) {
                        if (have <= lead) return
                        processChunk(env, session, input, mt, fft, win, l, r, have, ol, orr)
                        val end = if (last) have else have - margin
                        val count = end - lead
                        for (i in 0 until count) { inter[i * 2] = ol[lead + i]; inter[i * 2 + 1] = orr[lead + i] }
                        wv.write(inter, 0, count * 2)
                        for (i in 0 until count) { inter[i * 2] = l[lead + i] - ol[lead + i]; inter[i * 2 + 1] = r[lead + i] - orr[lead + i] }
                        wm.write(inter, 0, count * 2)
                        written += count
                        if (!last) {
                            // Conserva el último segundo ya emitido (margen previo) y el segundo adelantado.
                            val keep = 2 * margin
                            System.arraycopy(l, have - keep, l, 0, keep)
                            System.arraycopy(r, have - keep, r, 0, keep)
                            have = keep
                            lead = margin
                        }
                        progress((written * 1_000_000.0 / mt.rate / totalUs).toFloat().coerceIn(0f, 1f))
                    }
                    val ok = AiAudio.decode(path, 0, 0, mt.rate, true) { b, n ->
                        var i = 0
                        while (i < n) {
                            l[have] = b[i]
                            r[have] = b[i + 1]
                            have++
                            i += 2
                            if (have == lead + chunk + margin) flush(false)
                        }
                    }
                    if (!ok) throw IOException("no audio")
                    flush(true)
                }
            }
            if (vocals.exists()) vocals.delete()
            if (music.exists()) music.delete()
            if (!tv.renameTo(vocals) || !tm.renameTo(music)) throw IOException("rename")
        } finally {
            try { session.close() } catch (e: Throwable) { }
            try { opts.close() } catch (e: Throwable) { }
            tv.delete()
            tm.delete()
        }
    }
}
