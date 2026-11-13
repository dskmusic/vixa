package com.dskmusic.vixa.ai

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/** Recorte de sujeto en fotos: convierte la máscara del segmentador en un PNG con transparencia. */
object Cutout {

    /**
     * Foto con el alfa de la máscara [m] ([mw]×[mh], probabilidad 0..1), escalada con interpolación bilineal al tamaño de [src].
     * [soft] 0..1 suaviza los bordes y [grow] -1..1 los contrae o expande (refinado de bordes).
     */
    fun render(src: Bitmap, m: FloatArray, mw: Int, mh: Int, soft: Float, grow: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val s = 0.02f + soft.coerceIn(0f, 1f) * 0.4f
        val thr = 0.5f - grow.coerceIn(-1f, 1f) * 0.25f
        val lo = thr - s
        val span = 2f * s
        for (y in 0 until h) {
            val fy = ((y + 0.5f) * mh / h - 0.5f).coerceIn(0f, mh - 1f)
            val y0 = fy.toInt(); val y1 = minOf(y0 + 1, mh - 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) * mw / w - 0.5f).coerceIn(0f, mw - 1f)
                val x0 = fx.toInt(); val x1 = minOf(x0 + 1, mw - 1); val tx = fx - x0
                val a = m[y0 * mw + x0] * (1 - tx) + m[y0 * mw + x1] * tx
                val b = m[y1 * mw + x0] * (1 - tx) + m[y1 * mw + x1] * tx
                val v = a * (1 - ty) + b * ty
                val t = ((v - lo) / span).coerceIn(0f, 1f)
                val alpha = (t * t * (3f - 2f * t) * 255f + 0.5f).toInt()
                val i = y * w + x
                px[i] = (px[i] and 0x00FFFFFF) or (alpha shl 24)
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    fun savePng(bmp: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
