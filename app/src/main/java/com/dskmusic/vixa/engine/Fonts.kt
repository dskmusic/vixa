package com.dskmusic.vixa.engine

import android.content.Context
import android.graphics.Typeface
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class FontDef(val id: String, val name: String, val asset: String, val weight: Int = 0, val category: String = "sans")

/** Fuentes OFL incluidas en assets/fonts y fuentes TTF/OTF importadas por el usuario (files/fonts). */
object Fonts {
    val bundled: List<FontDef> = listOf(
        FontDef("poppins", "Poppins", "poppins_regular.ttf"),
        FontDef("poppins_bold", "Poppins Bold", "poppins_bold.ttf"),
        FontDef("montserrat", "Montserrat", "montserrat.ttf", 400),
        FontDef("montserrat_black", "Montserrat Black", "montserrat.ttf", 900),
        FontDef("oswald", "Oswald", "oswald.ttf", 500),
        FontDef("archivo_black", "Archivo Black", "archivo_black.ttf"),
        FontDef("bebas_neue", "Bebas Neue", "bebas_neue.ttf", category = "display"),
        FontDef("anton", "Anton", "anton.ttf", category = "display"),
        FontDef("righteous", "Righteous", "righteous.ttf", category = "display"),
        FontDef("bangers", "Bangers", "bangers.ttf", category = "display"),
        FontDef("monoton", "Monoton", "monoton.ttf", category = "display"),
        FontDef("abril_fatface", "Abril Fatface", "abril_fatface.ttf", category = "serif"),
        FontDef("playfair", "Playfair Display", "playfair_display.ttf", 500, "serif"),
        FontDef("playfair_bold", "Playfair Display Bold", "playfair_display.ttf", 800, "serif"),
        FontDef("lora", "Lora", "lora.ttf", 500, "serif"),
        FontDef("dm_serif", "DM Serif Display", "dm_serif_display.ttf", category = "serif"),
        FontDef("pacifico", "Pacifico", "pacifico.ttf", category = "script"),
        FontDef("lobster", "Lobster", "lobster.ttf", category = "script"),
        FontDef("dancing_script", "Dancing Script", "dancing_script.ttf", 600, "script"),
        FontDef("caveat", "Caveat", "caveat.ttf", 600, "script"),
        FontDef("shadows", "Shadows Into Light", "shadows_into_light.ttf", category = "script"),
        FontDef("space_mono", "Space Mono", "space_mono.ttf", category = "mono"),
        FontDef("space_mono_bold", "Space Mono Bold", "space_mono_bold.ttf", category = "mono"),
        FontDef("press_start", "Press Start 2P", "press_start_2p.ttf", category = "mono"),
        FontDef("permanent_marker", "Permanent Marker", "permanent_marker.ttf", category = "script"),
        FontDef("luckiest_guy", "Luckiest Guy", "luckiest_guy.ttf", category = "display"),
        FontDef("chewy", "Chewy", "chewy.ttf", category = "display"),
    )

    private val cache = ConcurrentHashMap<String, Typeface>()
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun customDir(context: Context): File = File(context.filesDir, "fonts").apply { mkdirs() }

    fun custom(context: Context): List<FontDef> =
        customDir(context).listFiles().orEmpty()
            .filter { it.extension.lowercase() in setOf("ttf", "otf") }
            .sortedBy { it.name.lowercase() }
            .map { FontDef("custom:" + it.name, it.nameWithoutExtension, it.absolutePath, category = "custom") }

    fun all(context: Context): List<FontDef> = bundled + custom(context)

    fun typeface(id: String): Typeface {
        cache[id]?.let { return it }
        val ctx = appContext ?: return Typeface.DEFAULT_BOLD
        val loaded: Typeface? = try {
            if (id.startsWith("custom:")) {
                Typeface.createFromFile(File(customDir(ctx), id.removePrefix("custom:")))
            } else {
                val def = bundled.firstOrNull { it.id == id } ?: bundled[1]
                if (def.weight > 0) {
                    Typeface.Builder(ctx.assets, "fonts/" + def.asset)
                        .setFontVariationSettings("'wght' " + def.weight)
                        .build()
                } else {
                    Typeface.createFromAsset(ctx.assets, "fonts/" + def.asset)
                }
            }
        } catch (e: Exception) {
            null
        }
        val tf = loaded ?: Typeface.DEFAULT_BOLD
        cache[id] = tf
        return tf
    }

    fun invalidateCustom() {
        cache.keys.filter { it.startsWith("custom:") }.forEach { cache.remove(it) }
    }
}
