package com.dskmusic.vixa.engine

import android.content.Context
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.media.Analysis
import com.dskmusic.vixa.media.Importer
import com.dskmusic.vixa.media.MediaType
import com.dskmusic.vixa.model.AnimRef
import com.dskmusic.vixa.model.BgType
import com.dskmusic.vixa.model.CanvasBg
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.ColorAdjust
import com.dskmusic.vixa.model.ColorFill
import com.dskmusic.vixa.model.Easing
import com.dskmusic.vixa.model.EffectInstance
import com.dskmusic.vixa.model.FitMode
import com.dskmusic.vixa.model.Keyframe
import com.dskmusic.vixa.model.Layer
import com.dskmusic.vixa.model.LayerKind
import com.dskmusic.vixa.model.MediaRef
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.StickerSpec
import com.dskmusic.vixa.model.StickerType
import com.dskmusic.vixa.model.TextSpec
import com.dskmusic.vixa.model.Transform
import com.dskmusic.vixa.model.Transition
import com.dskmusic.vixa.model.addToTrack
import com.dskmusic.vixa.model.insertMain
import com.dskmusic.vixa.model.newId
import com.dskmusic.vixa.ui.editor.sizeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Proyecto de demostración: un vídeo vertical de 1 a 1,5 minutos que enseña las funciones de la app. Usa las imágenes y vídeos
 * de assets/demo (demo_img_N, demo_vid_N) y, si no hay, degradados; formas, textos animados, transiciones, efectos y filtros
 * (en capas de ajuste), todo cortado al pulso de una canción movida e instrumental (Jamendo o assets/demo/demo.mp3).
 * Cada vez sale distinto (tema de color, orden y elementos). Es un proyecto normal: se abre en el editor y se puede tocar todo.
 */
object DemoBuilder {

    class Result(val project: Project, val musicTitle: String)

    private const val W = 0xFFFFFFFF.toInt()
    private const val YEL = 0xFFFFD740.toInt()
    private const val PNK = 0xFFFF4081.toInt()
    private const val CYA = 0xFF00E5FF.toInt()
    private const val GRN = 0xFF69F0AE.toInt()
    private const val PUR = 0xFFB388FF.toInt()
    private const val ORG = 0xFFFF6E40.toInt()
    private const val DIM = 0x99000000.toInt()
    private const val GHOST = 0x26FFFFFF
    private const val GHOST2 = 0x40FFFFFF

    private fun g(angle: Float, vararg cs: Long, radial: Boolean = false) = ColorFill(cs.map { it.toInt() }, angle, radial)

    /** Tema de color de la demo: fondos de los capítulos sin medios, acentos y tipografías. */
    private class Theme(val fills: List<ColorFill>, val accents: List<Int>, val head: String, val sub: String)

    private val THEMES = listOf(
        Theme(
            listOf(g(45f, 0xFF1B1464, 0xFF7C4DFF, 0xFF00B0FF), g(120f, 0xFF0D0D2B, 0xFF512DA8, 0xFFEC407A), g(0f, 0xFF18FFFF, 0xFF2962FF, 0xFF0D0D2B, radial = true), g(60f, 0xFF00BFA5, 0xFF2979FF, 0xFFAA00FF)),
            listOf(PNK, CYA, YEL, W), "anton", "poppins_bold",
        ),
        Theme(
            listOf(g(135f, 0xFFFF6E40, 0xFFFF4081, 0xFF6200EA), g(60f, 0xFFFF4081, 0xFFFF9100, 0xFFFFEA00), g(0f, 0xFFFFD740, 0xFFFF5252, 0xFF4A148C, radial = true), g(20f, 0xFFFF1744, 0xFFF50057, 0xFF651FFF)),
            listOf(YEL, W, CYA, PNK), "montserrat_black", "poppins_bold",
        ),
        Theme(
            listOf(g(70f, 0xFF00796B, 0xFF00B8D4, 0xFF2962FF), g(150f, 0xFF004D40, 0xFF00BFA5, 0xFF76FF03), g(0f, 0xFF76FF03, 0xFF00B8D4, 0xFF0D0D2B, radial = true), g(30f, 0xFF1A237E, 0xFF00B0FF, 0xFF1DE9B6)),
            listOf(GRN, W, YEL, CYA), "righteous", "montserrat",
        ),
        Theme(
            listOf(g(40f, 0xFFF50057, 0xFF7C4DFF, 0xFF00B0FF), g(110f, 0xFF311B92, 0xFFEC407A, 0xFFFFAB40), g(0f, 0xFFFF80AB, 0xFF651FFF, 0xFF1A0033, radial = true), g(80f, 0xFFAA00FF, 0xFFFF4081, 0xFFFFD740)),
            listOf(YEL, CYA, W, GRN), "archivo_black", "poppins_bold",
        ),
    )

    private val KEN = listOf("kb_zoom_in", "kb_diagonal", "kb_pan_left", "kb_pan_right", "kb_zoom_out", "kb_arc", "kb_push", "kb_drift")

    // Elecciones de cada capítulo (solo las que existen en los registros y compilan en este dispositivo).
    private val TR_SHOWCASE = listOf("spin", "shatter", "ink", "zoom_blur", "star", "digital", "bounce", "swirl", "burn", "heart", "sunburst", "tv_off", "punch", "twist", "fan", "rain_bars", "split_v", "light_leak", "dots", "bulge", "glitch", "pixelate", "ripple", "scan", "static_tv", "slices", "film_roll", "window_zoom", "iris_zoom", "drip", "curtain_wave", "cloud_dissolve")
    private val TR_LINK = listOf("zoom_blur", "whip", "spin", "star", "slices", "punch", "ink", "digital", "bounce", "light_leak", "swap", "circle_open", "dream", "cloud_dissolve", "curtain_wave")
    private val FX_SHOWCASE = listOf(
        "kaleidoscope", "glitch", "prism", "hologram", "ripple", "swirl", "mirror_quad", "pinch", "rgb_wave", "thermal", "comic", "neon_edges", "fisheye", "droste", "tunnel", "liquid",
        "anaglyph", "radial_blur", "oil_paint", "xray", "ascii", "dolly_zoom", "paper_cut", "crosshatch", "gameboy", "rainbow", "color_trail", "zoom_blur", "stained_glass", "halftone",
        "pixel_stretch", "paper_fold", "cel_shading", "pixel_art", "double_exposure", "risograph", "tiny_planet", "triangle_kaleido", "lake_reflection", "fire_silhouette", "pointillism",
        "ink_wash", "comic_panels", "ice_refraction", "flag_wave", "lego", "knit", "cyanotype", "linocut", "roman_mosaic", "polaroid_frame", "spin_burst", "whip_blur", "crt_on",
        "channel_switch", "lcd_lines", "aurora", "meteors", "fireworks", "neon_sign", "laser_beams", "drone_hud", "radar", "rec_timecode", "film_countdown", "water_caustics",
        "whirlpool", "holo_foil", "gradient_map", "led_screen", "frost",
    )
    private val FILTERS = listOf("teal_orange", "blockbuster", "noir", "vaporwave", "cinestill", "kodachrome", "sepia", "neon", "cyberpunk", "golden_hour", "bw_contrast", "dream", "autumn", "moody", "tropical", "synth_sunset", "bleach", "matrix", "polaroid", "candy")
    private val TEXT_ANIMS = listOf("letter_scramble", "letter_drop", "letter_spin", "letter_zoom", "letter_random", "typewriter", "letter_pop", "letter_slide", "letter_rise", "letter_fade")
    private val AMBIENT = listOf(
        listOf("bokeh"), listOf("sparkles"), listOf("confetti"), listOf("lens_flare"), listOf("fireworks"), listOf("aurora"), listOf("disco_lights"),
        listOf("light_rays"), listOf("starfield"), listOf("fireflies"), listOf("light_leak", "sparkles"), listOf("bubbles"), listOf("scanner_beam"),
        listOf("meteors"), listOf("shooting_stars"), listOf("magic_dust"), listOf("butterflies"), listOf("petals"), listOf("smoke"), listOf("water_caustics"), listOf("sparkler"),
        listOf("dust_motes"), listOf("neon_sign"), listOf("laser_beams"), listOf("cross_stars"), listOf("film_burn"), listOf("frost"), listOf("color_vignette"),
    )
    private val STICKER_IN = listOf("pop", "bounce", "elastic", "spin", "drop", "jelly", "zoom_in", "swoop", "squash")
    private val STICKER_LOOP = listOf("float", "pulse", "wobble", "hop", "sway", "tada", "breathe", "drift")
    private val EMOJIS = listOf("🎬", "✨", "🔥", "💫", "🌈", "🎵", "🚀", "💎", "🎉", "❤️", "⚡", "🌟", "🎨", "📸", "🪩", "🦄")

    /** Copia un archivo de assets/demo a la carpeta de medios de la app (los medios necesitan una ruta de archivo). */
    private suspend fun copyAsset(context: Context, name: String): File? = withContext(Dispatchers.IO) {
        try {
            val dest = File(File(Importer.mediaDir(context), "demo").apply { mkdirs() }, name)
            val len = try { context.assets.openFd("demo/$name").use { it.length } } catch (e: Throwable) { -1L }
            if (!(dest.exists() && len > 0 && dest.length() == len)) {
                context.assets.open("demo/$name").use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
            }
            dest
        } catch (e: Throwable) {
            ErrorLog.log("Demo/asset $name", e)
            null
        }
    }

    private fun assetNames(context: Context): List<String> =
        try { context.assets.list("demo")?.toList() } catch (e: Throwable) { null } ?: emptyList()

    /** Imágenes (demo_img_N) y vídeos (demo_vid_N) de assets/demo, por orden de N: se usan todos los que haya. */
    private suspend fun demoMedia(context: Context): Pair<List<MediaRef>, List<MediaRef>> {
        fun num(n: String) = Regex("[0-9]+").find(n)?.value?.toIntOrNull() ?: 0
        val names = assetNames(context)
        val imgs = ArrayList<MediaRef>()
        val vids = ArrayList<MediaRef>()
        for (n in names.filter { it.lowercase().startsWith("demo_img_") }.sortedBy { num(it) }) {
            val f = copyAsset(context, n) ?: continue
            Importer.probe(context, f.path)?.takeIf { it.isImage }?.let { imgs.add(it) }
        }
        for (n in names.filter { it.lowercase().startsWith("demo_vid_") }.sortedBy { num(it) }) {
            val f = copyAsset(context, n) ?: continue
            Importer.probe(context, f.path)?.takeIf { it.hasVideo && !it.isImage }?.let { vids.add(it) }
        }
        return imgs to vids
    }

    /** Canción de respaldo (assets/demo/demo.mp3) para cuando no se puede descargar ninguna. */
    private suspend fun fallbackSong(context: Context): MediaRef? {
        val n = assetNames(context).firstOrNull { it.lowercase() == "demo.mp3" } ?: return null
        val f = copyAsset(context, n) ?: return null
        return Importer.probe(context, f.path, MediaType.AUDIO)?.copy(title = "Demo")
    }

    /** [online] = false monta la demo sin descargar música (usa solo assets/demo/demo.mp3): para prepararla en segundo plano sin gastar datos. */
    suspend fun build(context: Context, container: AppContainer, es: Boolean, online: Boolean = true): Result = withContext(Dispatchers.Default) {
        val rnd = Random(System.nanoTime())
        fun <T> List<T>.pick(): T = this[rnd.nextInt(size)]
        fun s(a: String, b: String) = if (es) a else b
        val th = THEMES.pick()

        // --- Qué efectos y transiciones funcionan de verdad en este dispositivo (los que no compilan no se usan)
        FxThumbs.validateRegistry()
        val okFx = { id: String -> Registry.effect(id) != null && id !in FxThumbs.brokenEffects }
        val okTr = { id: String -> Registry.transition(id) != null && id !in FxThumbs.brokenTransitions }

        // --- Música movida e instrumental de Jamendo y, si no hay, la de assets/demo/demo.mp3
        var found: MediaRef? = null
        if (online) for (id in listOf("dynamic", "party", "sport", "happy", "fun", "futuristic")) {
            val m = AutoEdit.mood(id) ?: continue
            found = try {
                AutoEdit.fetchMusic(context, container, m, rnd) {}
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { ErrorLog.log("Demo/music $id", e); null }
            if (found != null) break
        }
        val music: MediaRef? = found ?: fallbackSong(context)
        if (music == null) ErrorLog.log("Demo/music", "no se encontró ninguna canción (¿sin conexión y sin assets/demo/demo.mp3?)")
        var beats: List<Long> = emptyList()
        if (music != null) beats = try { Analysis.beats(music.path) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { ErrorLog.log("Demo/beats", e); emptyList() }
        val grid = if (music != null) AutoEdit.fitGrid(beats, 124f) else null
        val period = grid?.periodUs ?: (60e6 / 124).toLong()
        val phase = if (music != null && grid != null) grid.phaseUs else if (music != null) (beats.firstOrNull() ?: 0L) % period else 0L

        // --- Imágenes y vídeos de assets/demo: se usan los que haya, sean cuantos sean
        val (demoImgs, demoVids) = demoMedia(context)
        val imgPool = demoImgs.shuffled(rnd)
        val vidPool = demoVids.shuffled(rnd)
        var imgIdx = 0
        var vidIdx = 0
        fun pickImg(): MediaRef? = if (imgPool.isEmpty()) null else imgPool[imgIdx++ % imgPool.size]
        fun pickVid(needUs: Long): MediaRef? {
            repeat(vidPool.size) {
                val m = vidPool[vidIdx++ % vidPool.size]
                if (m.durationUs >= needUs + 500_000L) return m
            }
            return null
        }

        // --- Escenas, capas y utilidades
        fun tb(beats: Float) = (beats * period).toLong()
        val stickers = ArrayList<Clip>()
        val texts = ArrayList<Clip>()
        val adjusts = ArrayList<Clip>()
        class Scn(val beats: Int, val fill: ColorFill, val tr: String?, val media: MediaRef?, val plain: Boolean)
        val scenes = ArrayList<Scn>()
        var cursor = 0
        var fillIdx = rnd.nextInt(th.fills.size)
        var kenIdx = rnd.nextInt(KEN.size)
        val links = TR_LINK.filter(okTr).shuffled(rnd)
        var linkIdx = 0
        /** media: 0 = degradado, 1 = imagen, 2 = vídeo si hay uno que dure lo bastante (si no, imagen). */
        fun scene(n: Int, tr: String? = null, fill: ColorFill? = null, media: Int = 0, plain: Boolean = false): Float {
            val start = cursor
            val t = if (scenes.isEmpty()) null else (tr ?: links[linkIdx++ % links.size])
            val m = when (media) { 1 -> pickImg(); 2 -> pickVid(n * period + 1_500_000L) ?: pickImg(); else -> null }
            scenes.add(Scn(n, fill ?: th.fills[fillIdx++ % th.fills.size], t, m, plain))
            cursor += n
            return start.toFloat()
        }
        fun hasMedia() = scenes.last().media != null

        // Los stickers se mantienen dentro del lienzo (vertical: alto = 1,78 anchos); los fondos enormes quedan centrados.
        fun cx(x: Float, size: Float) = x.coerceIn(-max(0f, 0.5f - size / 2f - 0.02f), max(0f, 0.5f - size / 2f - 0.02f))
        fun cy(y: Float, size: Float) = y.coerceIn(-max(0f, 0.5f - size / 2f / 1.78f - 0.02f), max(0f, 0.5f - size / 2f / 1.78f - 0.02f))
        fun shape(id: String, color: Int, size: Float, x: Float, y: Float, start: Float, len: Float, inA: String? = "pop", loop: String? = null, rot: Float = 0f) {
            stickers.add(Clip(
                kind = ClipKind.STICKER, startUs = tb(start), durationUs = tb(len), transform = Transform(x = cx(x, size), y = cy(y, size), rotation = rot),
                sticker = StickerSpec(StickerType.SHAPE, id, color, size),
                animIn = inA?.let { AnimRef(it, 450_000L) }, animOut = AnimRef("fade", 250_000L), animLoop = loop?.let { AnimRef(it) },
            ))
        }
        fun emoji(e: String, size: Float, x: Float, y: Float, start: Float, len: Float, inA: String? = STICKER_IN.pick(), loop: String? = STICKER_LOOP.pick()) {
            stickers.add(Clip(
                kind = ClipKind.STICKER, startUs = tb(start), durationUs = tb(len), transform = Transform(x = cx(x, size * 1.3f), y = cy(y, size * 1.3f)),
                sticker = StickerSpec(StickerType.EMOJI, e, W, size),
                animIn = inA?.let { AnimRef(it, 450_000L) }, animOut = AnimRef("fade", 250_000L), animLoop = loop?.let { AnimRef(it) },
            ))
        }

        // Texto: se mide con la tipografía real y se reduce hasta que la palabra más larga cabe con margen (fondo, contorno y bucles).
        Fonts.init(context)
        fun wordEm(font: String, spacing: Float, w: String): Float {
            val paint = android.graphics.Paint().apply { typeface = Fonts.typeface(font); textSize = 1000f; letterSpacing = spacing }
            return paint.measureText(w) / 1000f
        }
        fun rawLayer(
            text: String, font: String, size: Float, color: Int, x: Float, y: Float, inA: String?, inMs: Int,
            outA: String?, loop: String?, delayMs: Int, stroke: Float, grad: List<Int>,
            spacing: Float, shadow: Float, bg: Int, rot: Float, upper: Boolean,
        ) = Layer(
            kind = LayerKind.TEXT, text = text, fontId = font, size = size, color = color, x = x, y = y,
            animIn = inA?.let { AnimRef(it, inMs * 1000L) }, animOut = outA?.let { AnimRef(it, 300_000L) }, animLoop = loop?.let { AnimRef(it) },
            delayUs = delayMs * 1000L, strokeWidth = stroke, shadowRadius = shadow, gradient = grad, letterSpacing = spacing, bgColor = bg,
            rotation = rot, upper = upper, maxWidth = 0.84f, bgRadius = 0.35f, bgPadding = 0.35f,
        )
        fun layer(
            text: String, font: String, size0: Float, color: Int = W, x: Float = 0f, y: Float = 0f, inA: String? = "fade", inMs: Int = 500,
            outA: String? = "fade", loop: String? = null, delayMs: Int = 0, stroke: Float = 0f, grad: List<Int> = emptyList(),
            spacing: Float = 0f, shadow: Float = 0.1f, bg: Int = 0, rot: Float = 0f, upper: Boolean = false,
        ): Layer {
            val words = text.split("\n", " ").filter { it.isNotEmpty() }.map { if (upper) it.uppercase() else it }
            val longest = words.maxOfOrNull { wordEm(font, spacing, it) } ?: 1f
            val denom = 1.12f * longest + (if (bg != 0) 0.7f else 0f) + stroke
            val size = min(size0, 0.9f / denom)
            return rawLayer(text, font, size, color, x, y, inA, inMs, outA, loop, delayMs, stroke, grad, spacing, shadow, bg, rot, upper)
        }
        fun text(start: Float, len: Float, vararg ls: Layer) {
            texts.add(Clip(kind = ClipKind.TEXT, startUs = tb(start), durationUs = tb(len), text = TextSpec(ls.toList())))
        }
        /** Cabecera de capítulo: "02 · TRANSICIONES" en una pastilla arriba, siempre igual para dar unidad. */
        fun header(start: Float, len: Float, num: Int, label: String) =
            text(start, len, layer("0$num · $label", th.sub, 0.04f, W, y = -0.42f, inA = "slide_down", spacing = 0.1f, bg = DIM, upper = true))
        fun adjust(start: Float, len: Float, fx: List<String>, filter: String = "") {
            adjusts.add(Clip(kind = ClipKind.ADJUST, startUs = tb(start), durationUs = tb(len), effects = fx.filter(okFx).map { EffectInstance(defId = it) }, filterId = filter))
        }
        fun ambient(start: Float, len: Float) = adjust(start, len, AMBIENT.filter { l -> l.all(okFx) }.pick())
        /** Golpe de luz al empezar un capítulo (destello que se apaga solo). */
        fun punch(start: Float) {
            if (!okFx("flash")) return
            val inst = EffectInstance(defId = "flash", params = mapOf("amount" to 0f))
            adjusts.add(Clip(
                kind = ClipKind.ADJUST, startUs = tb(start), durationUs = tb(1f), effects = listOf(inst),
                keyframes = mapOf("fx:" + inst.id + ":amount" to listOf(Keyframe(0L, 0.6f, Easing.EASE_OUT), Keyframe(tb(0.8f), 0f, Easing.EASE_OUT))),
            ))
        }
        /** Círculos y anillos grandes y translúcidos que flotan por detrás: dan profundidad a los fondos de degradado. */
        fun deco(start: Float, len: Float) {
            shape("circle", GHOST, 0.9f, 0f, -0.18f, start, len, "zoom_in", "drift")
            shape("ring", GHOST2, 0.75f, 0f, 0.2f, start + 0.3f, len - 0.3f, "spin", "rotate")
            shape("circle", GHOST, 0.45f, -0.3f, 0.34f, start + 0.6f, len - 0.6f, "zoom_in", "breathe")
        }
        fun name(res: Int) = context.getString(res)
        val acc = th.accents
        val shapesFill = Shapes.all.filter { !it.stroke }.map { it.id }

        // ---------------- A · Intro (8)
        val a = scene(8, fill = th.fills[0])
        deco(a, 8f)
        shape("burst", acc[0], 0.95f, 0f, -0.06f, a, 8f, "zoom_out", "rotate")
        shape("sparkle", acc[1], 0.2f, -0.32f, -0.3f, a + 1f, 7f, "elastic", "pulse")
        shape("star4", acc[2], 0.16f, 0.34f, 0.24f, a + 1.5f, 6.5f, "pop", "tada")
        text(a, 8f,
            layer("VIXA", th.head, 0.36f, grad = listOf(acc[0], acc[2]), inA = "zoom_blur", inMs = 700, loop = "beat", stroke = 0.05f, shadow = 0.12f),
            layer("by DSK", th.sub, 0.065f, W, y = 0.14f, inA = "slide_up", delayMs = 800, spacing = 0.3f, upper = true),
            layer(s("Edita vídeos increíbles", "Edit amazing videos"), "caveat", 0.085f, acc[2], y = 0.27f, inA = "letter_fade", inMs = 900, delayMs = 1400))
        adjust(a, 8f, listOf("light_leak", "sparkles"))

        // ---------------- B · Qué es (8)
        val b = scene(8)
        deco(b, 8f)
        text(b, 8f,
            layer(s("MONTA", "EDIT"), th.head, 0.2f, W, y = -0.2f, inA = "slide_left", stroke = 0.04f),
            layer(s("CREA", "CREATE"), th.head, 0.2f, acc[0], y = -0.03f, inA = "slide_right", delayMs = 350, stroke = 0.04f),
            layer(s("IMPACTA", "IMPRESS"), th.head, 0.2f, acc[2], y = 0.14f, inA = "slide_left", delayMs = 700, stroke = 0.04f),
            layer(s("todo desde tu móvil", "all from your phone"), th.sub, 0.045f, W, y = 0.3f, inA = "letter_fade", delayMs = 1300, spacing = 0.2f, upper = true))
        for (i in 0 until 4) shape(listOf("camera", "music_note", "sparkle", "bolt")[i], acc[i % acc.size], 0.15f, if (i % 2 == 0) -0.36f else 0.36f, -0.3f + i * 0.2f, b + 1f + i * 0.6f, 6f, "pop", "float")
        ambient(b, 8f)

        // ---------------- C · Transiciones (6 × 5)
        val trCount = Registry.transitions.size
        val trPick = TR_SHOWCASE.filter(okTr).shuffled(rnd).take(6)
        val c0 = cursor.toFloat()
        for ((i, id) in trPick.withIndex()) {
            val sc = scene(5, tr = id, media = 1)
            if (!hasMedia()) {
                shape(shapesFill.pick(), acc[i % acc.size], 0.28f, -0.28f, -0.2f, sc + 0.3f, 4.4f, STICKER_IN.pick(), "float")
                shape(shapesFill.pick(), acc[(i + 1) % acc.size], 0.22f, 0.3f, 0.0f, sc + 0.6f, 4.1f, STICKER_IN.pick(), "float")
            }
            text(sc, 5f,
                layer(name(Registry.transition(id)!!.nameRes), th.head, 0.12f, W, y = 0.3f, inA = "letter_zoom", inMs = 600, stroke = 0.06f, upper = true),
                layer("${i + 1} / ${trPick.size}", "space_mono_bold", 0.04f, acc[0], y = 0.38f, inA = "fade", delayMs = 400))
        }
        header(c0, 30f, 2, s("$trCount transiciones", "$trCount transitions"))
        punch(c0 + 0.5f)

        // ---------------- D · Efectos (6 × 5): primero los vídeos (juntos, para no cortar la reproducción) y luego las fotos
        val fxCount = Registry.effects.size
        val fxPick = FX_SHOWCASE.filter(okFx).shuffled(rnd).take(6)
        val d0 = cursor.toFloat()
        for ((i, id) in fxPick.withIndex()) {
            val sc = scene(5, fill = th.fills.pick(), media = if (i < vidPool.size) 2 else 1)
            if (!hasMedia()) {
                val ids = shapesFill.shuffled(rnd)
                for (k in 0 until 4) shape(ids[k], acc[(k + i) % acc.size], 0.34f, if (k % 2 == 0) -0.22f else 0.22f, -0.22f + k * 0.17f, sc, 5f, null, if (k % 2 == 0) "rotate" else "pulse")
            }
            adjust(sc + 0.5f, 4.5f, listOf(id))
            text(sc, 5f, layer(name(Registry.effect(id)!!.nameRes), th.head, 0.1f, W, y = 0.3f, inA = "slide_up", stroke = 0.07f, upper = true))
        }
        header(d0, 30f, 3, s("$fxCount efectos", "$fxCount effects"))
        punch(d0 + 0.5f)

        // ---------------- E · Filtros (6 × 4), sin otras capas de ajuste encima para que el filtro mande
        val flCount = Registry.filters.size
        val flPick = FILTERS.filter { Registry.filter(it) != null }.shuffled(rnd).take(6)
        val e0 = cursor.toFloat()
        for ((i, id) in flPick.withIndex()) {
            val sc = scene(4, fill = g(40f + i * 25f, 0xFFFF5252, 0xFFFFD740, 0xFF00E676, 0xFF2979FF), media = 1, plain = true)
            if (!hasMedia()) for (k in 0 until 5) shape(listOf("circle", "heart", "star", "diamond", "hexagon")[k], acc[(k + i) % acc.size], 0.24f, -0.3f + k * 0.15f, -0.18f + (k % 2) * 0.22f, sc + k * 0.2f, 3.6f - k * 0.2f, "pop", "float")
            adjust(sc, 4f, emptyList(), filter = id)
            text(sc, 4f, layer(name(Registry.filter(id)!!.nameRes), "playfair_bold", 0.12f, W, y = 0.3f, inA = "blur", inMs = 700, stroke = 0.06f))
        }
        header(e0, 24f, 4, s("$flCount filtros", "$flCount filters"))

        // ---------------- F · Textos animados (4 × 4)
        val f0 = cursor.toFloat()
        val words = if (es) listOf("MAGIA", "ENERGÍA", "ESTILO", "DIVERSIÓN") else listOf("MAGIC", "ENERGY", "STYLE", "FUN")
        val tAnims = TEXT_ANIMS.shuffled(rnd).take(4)
        for ((i, id) in tAnims.withIndex()) {
            val sc = scene(4, media = 1)
            val def = Anims.all.firstOrNull { it.id == id && it.kind == AnimKind.IN }
            text(sc, 4f,
                layer(words[i], listOf(th.head, "abril_fatface", "bangers", "monoton")[i], 0.2f, acc[i % acc.size], inA = id, inMs = 1100, loop = if (i % 2 == 0) "wave" else null, stroke = 0.05f, shadow = 0.15f),
                layer(def?.let { name(it.nameRes) } ?: id, "space_mono_bold", 0.04f, W, y = 0.22f, inA = "fade", delayMs = 900, bg = DIM, upper = true))
            shape(listOf("sparkle", "star4", "burst", "ring")[i], acc[0], 0.18f, if (i % 2 == 0) -0.32f else 0.32f, -0.3f, sc + 0.4f, 3.4f, "spin", "rotate")
        }
        header(f0, 16f, 5, s("Textos animados", "Animated text"))
        punch(f0 + 0.3f)
        ambient(f0, 16f)

        // ---------------- G · Stickers y emojis (8): aquí sí hay emojis, pocos y bien repartidos
        val gs = scene(8, media = 1)
        text(gs, 8f, layer(s("Stickers\ny emojis", "Stickers\n& emojis"), "bangers", 0.15f, W, y = 0f, inA = "letter_pop", inMs = 900, stroke = 0.08f, loop = "wave", rot = -4f))
        val spots = listOf(-0.3f to -0.3f, 0.3f to -0.26f, -0.32f to 0.3f, 0.3f to 0.32f, 0f to -0.18f, 0f to 0.22f)
        for ((i, e) in EMOJIS.shuffled(rnd).take(spots.size).withIndex()) emoji(e, 0.17f, spots[i].first, spots[i].second, gs + 0.5f + i * 0.9f, 5f)
        header(gs, 8f, 6, s("Stickers y emojis", "Stickers & emojis"))
        punch(gs + 0.3f)
        ambient(gs, 8f)

        // ---------------- H · Formas (8)
        val shCount = Shapes.all.size
        val hs = scene(8)
        deco(hs, 8f)
        for ((i, id) in Shapes.all.shuffled(rnd).take(12).map { it.id }.withIndex()) shape(id, acc[i % acc.size], 0.2f, -0.3f + (i % 3) * 0.3f, -0.3f + (i / 3) * 0.2f, hs + 0.3f + i * 0.4f, 6f, STICKER_IN.pick(), if (i % 3 == 0) "rotate" else "pulse")
        header(hs, 8f, 7, s("$shCount formas", "$shCount shapes"))
        text(hs, 8f, layer(s("Cambia color y tamaño", "Change color and size"), th.sub, 0.05f, W, y = 0.38f, inA = "slide_up", delayMs = 600, bg = DIM))
        punch(hs + 0.3f)
        ambient(hs, 8f)

        // ---------------- I · Capas (10)
        val ls = scene(10)
        deco(ls, 10f)
        shape("round_frame", acc[1], 0.7f, 0f, 0f, ls, 10f, "zoom_in", "pulse")
        shape("hexagon_ring", acc[0], 0.5f, 0f, 0f, ls + 0.4f, 9.6f, "spin", "rotate")
        text(ls, 10f,
            layer(s("Capas de ajuste", "Adjustment layers"), th.head, 0.09f, W, y = -0.1f, inA = "slide_left", stroke = 0.05f),
            layer(s("y de efectos", "and effects layers"), th.head, 0.09f, acc[2], y = 0.02f, inA = "slide_right", delayMs = 400, stroke = 0.05f),
            layer(s("sobre todo el proyecto", "over the whole project"), "caveat", 0.075f, W, y = 0.16f, inA = "letter_fade", delayMs = 700))
        header(ls, 10f, 8, s("Capas", "Layers"))
        adjust(ls, 10f, listOf("prism", "rainbow"))

        // ---------------- J · Asistente (12)
        val js = scene(12, media = 1)
        val moodCount = AutoEdit.moods.size
        val ringShapes = listOf("heart", "bolt", "star", "sun", "moon", "leaf", "music_note", "flower", "rocket", "gem", "cloud", "crown")
        for ((i, id) in ringShapes.withIndex()) {
            val ang = i * 2.0 * Math.PI / ringShapes.size
            shape(id, acc[i % acc.size], 0.13f, (0.4 * cos(ang)).toFloat(), (0.3 * sin(ang)).toFloat() + 0.02f, js + 0.5f + i * 0.5f, 6f, "pop", STICKER_LOOP.pick())
        }
        text(js, 12f,
            layer(s("Asistente\nde vídeo", "Video\nassistant"), th.head, 0.12f, W, y = -0.08f, inA = "slide_up", stroke = 0.06f, shadow = 0.15f),
            layer(s("$moodCount estilos", "$moodCount styles"), "righteous", 0.1f, acc[2], y = 0.16f, inA = "letter_pop", inMs = 800, delayMs = 800, loop = "pulse"),
            layer(s("cortes al ritmo de la música", "cuts on the beat of the music"), th.sub, 0.04f, W, y = 0.3f, inA = "fade", delayMs = 1200, upper = true, spacing = 0.1f))
        header(js, 12f, 9, s("Asistente", "Assistant"))
        punch(js + 0.3f)
        ambient(js, 12f)

        // ---------------- K · Y mucho más (3 × 7)
        val more = listOf(
            Triple("eye", s("IA en tu móvil", "AI on your phone"), s("Subtítulos · Quitar fondo · Seguimiento", "Subtitles · Cutout · Tracking")),
            Triple("music_note", s("Audio pro", "Pro audio"), s("Ecualizador · Voces · Voz en off", "Equalizer · Voices · Voice-over")),
            Triple("paper_plane", s("Exporta", "Export"), s("Vídeo hasta 4K · GIF · Audio", "Video up to 4K · GIF · Audio")),
        )
        val k0 = cursor.toFloat()
        for ((idx, card) in more.withIndex()) {
            val sc = scene(7, media = 1)
            shape(card.first, acc[idx % acc.size], 0.36f, 0f, -0.2f, sc + 0.2f, 6.6f, "elastic", "pulse")
            text(sc, 7f,
                layer(card.second, th.head, 0.1f, W, y = 0.02f, inA = "letter_zoom", inMs = 700, stroke = 0.06f),
                layer(card.third, th.sub, 0.04f, acc[2], y = 0.15f, inA = "slide_up", delayMs = 300, bg = DIM))
        }
        header(k0, 21f, 10, s("Y mucho más", "And much more"))
        ambient(k0, 21f)

        // ---------------- L · Cierre (8)
        val z = scene(8, fill = th.fills[1 % th.fills.size])
        deco(z, 8f)
        shape("burst", acc[0], 1.0f, 0f, -0.05f, z, 8f, "zoom_out", "rotate")
        shape("heart", W, 0.22f, -0.32f, -0.34f, z + 1f, 7f, "pop", "heartbeat")
        shape("heart", acc[2], 0.17f, 0.34f, 0.3f, z + 1.5f, 6.5f, "pop", "heartbeat")
        text(z, 8f,
            layer(s("¡Ahora te toca!", "Your turn!"), "pacifico", 0.13f, W, y = -0.08f, inA = "letter_fade", inMs = 1000, stroke = 0.05f, shadow = 0.15f),
            layer("VIXA", th.head, 0.2f, grad = listOf(acc[0], acc[2]), y = 0.1f, inA = "zoom_blur", inMs = 700, delayMs = 1000, loop = "beat", stroke = 0.04f),
            layer("by DSK", th.sub, 0.055f, W, y = 0.22f, inA = "slide_up", delayMs = 1600, spacing = 0.3f, upper = true))
        adjust(z, 8f, listOf("sparkles", "lens_flare"))

        // --- Pista principal. Cada transición dura ~0,85 s (nunca 0,5 s): se aprecia sin comerse la escena, aunque la canción sea rápida.
        val (w, h) = sizeFor(9, 16, 720)
        val trUs = min(900_000L, 4 * period * 45 / 100) / 2 * 2
        fun td(i: Int): Long = if (i <= 0 || i >= scenes.size || scenes[i].tr == null) 0L else trUs
        val canvasAspect = w.toFloat() / h
        val main = scenes.mapIndexed { i, sc ->
            val tr = sc.tr?.let { Transition(it, td(i)) }
            val dur = sc.beats * period + td(i) / 2 + td(i + 1) / 2
            val m = sc.media
            if (m == null) {
                Clip(kind = ClipKind.COLOR, durationUs = dur, fill = sc.fill, animLoop = AnimRef(KEN[kenIdx++ % KEN.size]), transitionIn = tr)
            } else {
                val ratio = m.width.toFloat().coerceAtLeast(1f) / m.height.coerceAtLeast(1)
                val fit = if (abs(ln(ratio / canvasAspect)) < 0.3f) FitMode.FILL else FitMode.FIT
                var c = Importer.clipFor(m, dur)
                if (!m.isImage) {
                    val room = (m.durationUs - dur).coerceAtLeast(0L)
                    val start = if (room > 0L) rnd.nextLong(room) else 0L
                    c = c.copy(srcInUs = start, srcOutUs = start + min(dur, m.durationUs), speed = 1f, durationUs = dur, muted = true)
                }
                // Un tono común (contraste, viñeta) da unidad a todas las fotos; en los filtros no se toca para que se vea el filtro.
                c.copy(
                    transform = Transform(fit = fit), transitionIn = tr,
                    color = if (sc.plain) ColorAdjust() else ColorAdjust(contrast = 0.1f, saturation = 0.1f, vignette = 0.4f, exposure = -0.05f),
                    animLoop = if (m.isImage) AnimRef(KEN[kenIdx++ % KEN.size]) else null,
                )
            }
        }
        val now = System.currentTimeMillis()
        var p = Project(id = newId(), name = s("Demo de Vixa", "Vixa demo"), createdAt = now, modifiedAt = now, width = w, height = h, fps = 30, background = CanvasBg(BgType.BLUR), demo = true)
        p = p.insertMain(main)
        val total = p.mainTrack.clips.maxOfOrNull { it.endUs } ?: (cursor * period)
        for (c in adjusts.sortedBy { it.startUs }) p = p.addToTrack(c)
        for (c in stickers.sortedBy { it.startUs }) p = p.addToTrack(c)
        for (c in texts.sortedBy { it.startUs }) p = p.addToTrack(c)

        // --- Música desde un pulso (saltando una intro larga) y repetida en pulsos enteros si no llega
        val song = music
        if (song != null) {
            var from = phase
            val skip = AutoEdit.introSkipUs(song.path)
            if (skip > 0L) {
                val cand = phase + ((skip - phase) / period - 2).coerceAtLeast(0L) * period
                if (song.durationUs - cand >= min(total, 20_000_000L)) from = cand
            }
            val skipped = from != phase
            val avail = (song.durationUs - from).coerceAtLeast(1_000_000L)
            var at = 0L
            var copies = 0
            while (at < total && copies < 20) {
                val remaining = total - at
                val last = avail >= remaining
                val len = if (last) remaining else max(period, (avail / period) * period)
                if (len <= 0L) break
                p = p.addToTrack(Importer.clipFor(song, 0L, asAudio = true).copy(
                    startUs = at, srcInUs = from, srcOutUs = from + min(len, avail), durationUs = len,
                    fadeInUs = if (copies == 0) (if (skipped) 900_000L else 400_000L) else 300_000L,
                    fadeOutUs = if (last) min(2_500_000L, len / 2) else 300_000L,
                    label = song.title,
                ))
                at += len
                copies++
            }
        }
        Result(p, song?.title ?: "")
    }
}
