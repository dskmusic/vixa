package com.dskmusic.vixa.ai

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import com.dskmusic.vixa.engine.FrameStrip
import com.dskmusic.vixa.engine.MorphMath
import com.dskmusic.vixa.engine.WmPatch
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.WmRemove
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Quita una marca de agua rellenando la zona con MI-GAN (modelo ligero de inpainting, ~30 MB, en el móvil). Por cada muestra del vídeo
 * (10 por segundo) recorta una ventana alrededor de la zona, deja el modelo rellenar el hueco y guarda solo el contenido de la zona en un
 * archivo de parches que el efecto GL pega sobre el vídeo. Así no se recodifica nada y la exportación sale igual que la vista previa.
 */
object WmInpaint {
    private const val MAX_SIDE = 384
    const val PATCH_FPS = 10

    class Result(val patchPath: String, val startUs: Long, val fps: Int, val w: Int, val h: Int, val count: Int)

    private class Frame(val rgb: ByteArray, val w: Int, val h: Int)

    suspend fun generate(context: Context, store: ModelStore, clip: Clip, wr: WmRemove, progress: (Float) -> Unit): Result {
        val m = clip.media ?: throw IOException("no media")
        val model = store.file(Models.INPAINT, "model.onnx")
        if (!model.exists()) throw IOException("model missing")
        val env = OrtEnvironment.getEnvironment()
        val dir = File(context.filesDir, "wmpatch").apply { mkdirs() }
        val out = File(dir, clip.id + "_" + System.currentTimeMillis() + ".wmp")
        OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(4)
            env.createSession(model.path, opts).use { session ->
                RandomAccessFile(out, "rw").use { raf ->
                    raf.seek(WmPatch.HEADER)
                    var count = 0
                    var pw = 0
                    var ph = 0
                    fun handle(bmp: Bitmap, total: Int) {
                        val f = inpaint(env, session, bmp, wr)
                        pw = f.w; ph = f.h
                        raf.write(f.rgb)
                        count++
                        progress(count.toFloat() / total)
                    }
                    if (clip.kind == ClipKind.IMAGE) {
                        val up = MorphMath.prepare(context, m.path) ?: throw IOException("cannot read image")
                        handle(BitmapFactory.decodeFile(up) ?: throw IOException("cannot decode image"), 1)
                    } else {
                        val span = (clip.srcOutUs - clip.srcInUs).coerceAtLeast(100_000L)
                        val total = (span * PATCH_FPS / 1_000_000L).toInt() + 1
                        val ok = FrameStrip.extract(m.path, clip.srcInUs, clip.srcOutUs, 1_000_000L / PATCH_FPS, total, min(m.width, 1280).coerceAtLeast(64)) { bmp ->
                            handle(bmp, total)
                            bmp.recycle()
                        }
                        if (!ok) throw IOException("unsupported video format")
                    }
                    if (count == 0) throw IOException("no frames")
                    WmPatch.writeHeader(raf, count, pw, ph)
                    // Los parches antiguos de este clip ya no sirven.
                    dir.listFiles()?.filter { it.name.startsWith(clip.id + "_") && it != out }?.forEach { it.delete() }
                    return Result(out.path, if (clip.kind == ClipKind.IMAGE) 0L else clip.srcInUs, PATCH_FPS, pw, ph, count)
                }
            }
        }
        throw IOException("unreachable")
    }

    private fun tensor(env: OrtEnvironment, data: ByteArray, vararg shape: Long): OnnxTensor {
        val bb = ByteBuffer.allocateDirect(data.size)
        bb.put(data); bb.rewind()
        return OnnxTensor.createTensor(env, bb, longArrayOf(*shape), OnnxJavaType.UINT8)
    }

    /** Rellena la zona en una ventana alrededor y devuelve solo el contenido de la zona (RGB entrelazado). */
    private fun inpaint(env: OrtEnvironment, session: OrtSession, frame: Bitmap, wr: WmRemove): Frame {
        val fw = frame.width
        val fh = frame.height
        val rx0 = (wr.x0 * fw).toInt().coerceIn(0, fw - 2)
        val ry0 = (wr.y0 * fh).toInt().coerceIn(0, fh - 2)
        val rx1 = (wr.x1 * fw).toInt().coerceIn(rx0 + 1, fw)
        val ry1 = (wr.y1 * fh).toInt().coerceIn(ry0 + 1, fh)
        val mx = max(((rx1 - rx0) * 0.6f).toInt(), 32)
        val my = max(((ry1 - ry0) * 0.6f).toInt(), 32)
        val cx0 = max(0, rx0 - mx); val cy0 = max(0, ry0 - my)
        val cx1 = min(fw, rx1 + mx); val cy1 = min(fh, ry1 + my)
        val cw = cx1 - cx0
        val ch = cy1 - cy0
        val s = min(1f, MAX_SIDE.toFloat() / max(cw, ch))
        val w = max(16, (cw * s).toInt())
        val h = max(16, (ch * s).toInt())
        val crop = Bitmap.createScaledBitmap(Bitmap.createBitmap(frame, cx0, cy0, cw, ch), w, h, true)
        // La zona en las coordenadas de la ventana reducida (con 2 px de margen para que no queden bordes de la marca).
        val sx0 = (((rx0 - cx0) * w) / cw.toFloat()).toInt().minus(2).coerceIn(0, w - 1)
        val sy0 = (((ry0 - cy0) * h) / ch.toFloat()).toInt().minus(2).coerceIn(0, h - 1)
        val sx1 = ceil(((rx1 - cx0) * w) / cw.toFloat()).toInt().plus(2).coerceIn(sx0 + 1, w)
        val sy1 = ceil(((ry1 - cy0) * h) / ch.toFloat()).toInt().plus(2).coerceIn(sy0 + 1, h)
        val px = IntArray(w * h)
        crop.getPixels(px, 0, w, 0, 0, w, h)
        crop.recycle()
        val img = ByteArray(3 * w * h)
        val mask = ByteArray(w * h) { 255.toByte() }
        for (i in px.indices) {
            val p = px[i]
            img[i] = ((p shr 16) and 255).toByte()
            img[w * h + i] = ((p shr 8) and 255).toByte()
            img[2 * w * h + i] = (p and 255).toByte()
        }
        for (y in sy0 until sy1) for (x in sx0 until sx1) mask[y * w + x] = 0
        val res = tensor(env, img, 1, 3, h.toLong(), w.toLong()).use { ti ->
            tensor(env, mask, 1, 1, h.toLong(), w.toLong()).use { tm ->
                session.run(mapOf("image" to ti, "mask" to tm)).use { r ->
                    val bb = (r.get(0) as OnnxTensor).byteBuffer
                    ByteArray(3 * w * h).also { bb.rewind(); bb.get(it) }
                }
            }
        }
        // Se extrae exactamente la zona (sin el margen de la máscara): así el parche encaja con el rectángulo del efecto.
        val ex0 = (((rx0 - cx0) * w) / cw.toFloat()).toInt().coerceIn(0, w - 1)
        val ey0 = (((ry0 - cy0) * h) / ch.toFloat()).toInt().coerceIn(0, h - 1)
        val ex1 = (((rx1 - cx0) * w) / cw.toFloat()).toInt().coerceIn(ex0 + 1, w)
        val ey1 = (((ry1 - cy0) * h) / ch.toFloat()).toInt().coerceIn(ey0 + 1, h)
        val pw = ex1 - ex0
        val ph = ey1 - ey0
        val rgb = ByteArray(pw * ph * 3)
        for (y in 0 until ph) for (x in 0 until pw) {
            val src = (ey0 + y) * w + (ex0 + x)
            val dst = (y * pw + x) * 3
            rgb[dst] = res[src]
            rgb[dst + 1] = res[w * h + src]
            rgb[dst + 2] = res[2 * w * h + src]
        }
        return Frame(rgb, pw, ph)
    }
}
