package com.dskmusic.vixa.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.LruCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fotogramas decodificados de un GIF / WebP animado, con sus tiempos de inicio. */
class AnimatedFrames(val frames: List<Bitmap>, val startsUs: LongArray, val totalUs: Long) {
    fun frameAt(timeUs: Long): Bitmap? {
        if (frames.isEmpty()) return null
        if (frames.size == 1 || totalUs <= 0) return frames[0]
        val t = ((timeUs % totalUs) + totalUs) % totalUs
        var i = startsUs.size - 1
        while (i > 0 && startsUs[i] > t) i--
        return frames[i]
    }
}

/** Caché de imágenes para el overlay (stickers, marca de agua) y decodificación de animados. */
object ImageCache {
    private const val MAX_SIDE = 480
    private const val MAX_FRAMES = 160

    private val stills = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val animated = ConcurrentHashMap<String, AnimatedFrames>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun isAnimatedFile(path: String): Boolean {
        val f = File(path)
        if (!f.exists()) return false
        val ext = f.extension.lowercase()
        if (ext == "gif") return true
        if (ext != "webp") return false
        return try {
            f.inputStream().use { s ->
                val head = ByteArray(64)
                val n = s.read(head)
                n >= 30 && String(head, 12, 4, Charsets.US_ASCII) == "VP8X" && (head[20].toInt() and 0x02) != 0
            }
        } catch (e: Exception) {
            false
        }
    }

    fun still(path: String, maxSide: Int = 1024): Bitmap? {
        val key = "$path@$maxSide"
        stills.get(key)?.let { return it }
        if (failed.contains(key)) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            if (bmp == null) { failed.add(key); null } else { stills.put(key, bmp); bmp }
        } catch (e: Throwable) {
            failed.add(key)
            null
        }
    }

    /** Devuelve los fotogramas si ya están decodificados; si no, null (llama a [load] en segundo plano). */
    fun animatedIfLoaded(path: String): AnimatedFrames? = animated[path]

    /** Decodifica (bloqueante, llamar fuera del hilo principal). */
    fun load(path: String): AnimatedFrames? {
        animated[path]?.let { return it }
        if (failed.contains(path)) return null
        val result = try {
            if (File(path).extension.lowercase() == "gif") decodeGif(path) else decodeWebp(path)
        } catch (e: Throwable) {
            null
        }
        if (result == null || result.frames.isEmpty()) {
            failed.add(path)
            return null
        }
        animated[path] = result
        return result
    }

    fun clear() {
        stills.evictAll()
        animated.clear()
        failed.clear()
    }

    @Suppress("DEPRECATION")
    private fun decodeGif(path: String): AnimatedFrames? {
        val movie = Movie.decodeFile(path) ?: return null
        val w = movie.width()
        val h = movie.height()
        if (w <= 0 || h <= 0) return null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
        val ow = (w * scale).toInt().coerceAtLeast(1)
        val oh = (h * scale).toInt().coerceAtLeast(1)
        val durMs = movie.duration()
        if (durMs <= 0) {
            val b = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            Canvas(b).apply { scale(scale, scale); movie.setTime(0); movie.draw(this, 0f, 0f) }
            return AnimatedFrames(listOf(b), longArrayOf(0), 0)
        }
        // Movie no expone los retardos por fotograma: se muestrea a paso fijo.
        val stepMs = maxOf(40, durMs / MAX_FRAMES)
        val frames = ArrayList<Bitmap>()
        val starts = ArrayList<Long>()
        var t = 0
        while (t < durMs) {
            val b = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.scale(scale, scale)
            movie.setTime(t)
            movie.draw(c, 0f, 0f)
            frames.add(b)
            starts.add(t * 1000L)
            t += stepMs
        }
        return AnimatedFrames(frames, starts.toLongArray(), durMs * 1000L)
    }

    /** WebP animado: la plataforma no da acceso por fotograma, así que se captura una reproducción completa. */
    private fun decodeWebp(path: String): AnimatedFrames? {
        if (Build.VERSION.SDK_INT < 28) {
            val b = still(path, MAX_SIDE) ?: return null
            return AnimatedFrames(listOf(b), longArrayOf(0), 0)
        }
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = minOf(1f, MAX_SIDE.toFloat() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * s).toInt().coerceAtLeast(1), (info.size.height * s).toInt().coerceAtLeast(1))
        }
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        drawable.setBounds(0, 0, w, h)
        if (drawable !is AnimatedImageDrawable) {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawable.draw(Canvas(b))
            return AnimatedFrames(listOf(b), longArrayOf(0), 0)
        }
        val frames = ArrayList<Bitmap>()
        val starts = ArrayList<Long>()
        val done = CountDownLatch(1)
        val thread = HandlerThread("vixa-webp").apply { start() }
        val handler = Handler(thread.looper)
        var startMs = 0L
        var endMs = 0L
        fun capture() {
            if (frames.size >= MAX_FRAMES) return
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawable.draw(Canvas(b))
            frames.add(b)
            starts.add((SystemClock.uptimeMillis() - startMs) * 1000L)
        }
        handler.post {
            drawable.repeatCount = 0
            drawable.callback = object : Drawable.Callback {
                override fun invalidateDrawable(who: Drawable) = capture()
                override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                    handler.postAtTime(what, who, `when`)
                }
                override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                    handler.removeCallbacks(what, who)
                }
            }
            drawable.registerAnimationCallback(object : android.graphics.drawable.Animatable2.AnimationCallback() {
                override fun onAnimationEnd(d: Drawable?) {
                    endMs = SystemClock.uptimeMillis()
                    done.countDown()
                }
            })
            startMs = SystemClock.uptimeMillis()
            capture()
            drawable.start()
        }
        done.await(20, TimeUnit.SECONDS)
        if (endMs == 0L) endMs = SystemClock.uptimeMillis()
        handler.post { drawable.stop() }
        thread.quitSafely()
        if (frames.isEmpty()) return null
        return AnimatedFrames(frames, starts.toLongArray(), ((endMs - startMs) * 1000L).coerceAtLeast(40_000L))
    }
}
