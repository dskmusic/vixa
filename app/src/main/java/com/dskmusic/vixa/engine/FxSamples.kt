package com.dskmusic.vixa.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.dskmusic.vixa.ui.editor.FilterThumbs
import java.util.concurrent.ConcurrentHashMap

/**
 * Imágenes de muestra para las vistas previas de efectos, filtros y animaciones.
 *
 * Las de serie son todos los archivos `fx_sample_N` (N = 1, 2, 3…) que haya en res/drawable-nodpi: se buscan por nombre al
 * ejecutar, así que basta copiar la imagen ahí. La clave de cada una es N ("1", "2"…); además puede existir la imagen propia
 * del usuario (clave [CUSTOM]). La elegida por defecto es la 1.
 */
object FxSamples {
    const val CUSTOM = "custom"
    private const val MAX = 99

    /** Clave de la imagen que se usa ahora (con el modo aleatorio, la que tocó al abrir el editor). */
    @Volatile var effective: String = "1"
        private set

    @Volatile private var cache: Pair<String, Bitmap>? = null
    @Volatile private var ids: List<Pair<String, Int>>? = null
    private val thumbs = ConcurrentHashMap<String, Bitmap>()

    /** (clave, id de recurso) de las imágenes de serie encontradas, por orden de N. */
    fun bundled(ctx: Context): List<Pair<String, Int>> = ids ?: (1..MAX).mapNotNull { n ->
        val id = ctx.resources.getIdentifier("fx_sample_$n", "drawable", ctx.packageName)
        if (id != 0) n.toString() to id else null
    }.also { ids = it }

    fun hasCustom(ctx: Context) = FxThumbs.customFile(ctx).exists()

    /** Claves disponibles: las de serie y, si existe, la propia. */
    fun keys(ctx: Context): List<String> = bundled(ctx).map { it.first } + (if (hasCustom(ctx)) listOf(CUSTOM) else emptyList())

    /**
     * Fija la imagen en uso según la elección del usuario. Con [random] se sortea entre todas las disponibles cuando [reroll]
     * es true (al abrir el editor o al activar el modo); si no, se mantiene la de antes. Si la elegida ya no existe, la primera.
     */
    fun refresh(ctx: Context, selected: String, random: Boolean, reroll: Boolean) {
        val ks = keys(ctx)
        val next = when {
            ks.isEmpty() -> ""
            random -> if (!reroll && effective in ks) effective else ks[java.util.Random().nextInt(ks.size)]
            selected in ks -> selected
            else -> ks.first()
        }
        if (next != effective) {
            effective = next
            FxThumbs.resetSample()
            FilterThumbs.clear()
        }
    }

    /** Olvida la imagen decodificada (cambió el contenido de la propia o de las miniaturas). */
    fun dropCache() {
        cache = null
        thumbs.remove(CUSTOM)
    }

    private fun decode(ctx: Context, key: String, maxSide: Int): Bitmap? = try {
        if (key == CUSTOM) FxThumbs.customBitmap(ctx)?.let { scaleDown(it, maxSide) }
        else bundled(ctx).firstOrNull { it.first == key }?.second?.let { id ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(ctx.resources, id, bounds)
            var k = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (k * 2) >= maxSide) k *= 2
            BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inSampleSize = k })
        }
    } catch (e: Throwable) { null }

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    /** Imagen en uso (a un máximo de 1000 px), o null si no hay ninguna. */
    fun bitmap(ctx: Context): Bitmap? {
        val key = effective
        cache?.let { if (it.first == key) return it.second }
        val bmp = decode(ctx, key, 1000) ?: return null
        cache = key to bmp
        return bmp
    }

    /** Miniatura pequeña de una imagen para el selector de Ajustes. */
    fun thumb(ctx: Context, key: String): Bitmap? =
        thumbs[key] ?: decode(ctx, key, 240)?.also { thumbs[key] = it }
}
