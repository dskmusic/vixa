package com.dskmusic.vixa.engine

import com.dskmusic.vixa.R
import com.dskmusic.vixa.model.AnimRef
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.Layer
import com.dskmusic.vixa.model.LayerKind
import com.dskmusic.vixa.model.TextSpec
import com.dskmusic.vixa.model.Transform
import com.dskmusic.vixa.model.newId

/**
 * Plantillas de texto animadas, definidas como datos: capas (textos y formas) con sus animaciones.
 * Al aplicarlas se convierten en un clip de texto normal, totalmente editable.
 */
class TextTemplate(
    val id: String,
    val catRes: Int,
    val durationMs: Int = 3000,
    /** Posición del clip en el lienzo (desplazamiento del centro, fracción). */
    val x: Float = 0f,
    val y: Float = 0f,
    val clipLoop: AnimRef? = null,
    val layers: (es: Boolean) -> List<Layer>,
) {
    fun toClip(es: Boolean, startUs: Long): Clip = Clip(
        kind = ClipKind.TEXT, startUs = startUs, durationUs = durationMs * 1000L,
        transform = Transform(x = x, y = y), animLoop = clipLoop,
        text = TextSpec(layers = layers(es).map { it.copy(id = newId()) }, templateId = id),
    )
}

object Templates {
    private const val W = 0xFFFFFFFF.toInt()
    private const val K = 0xFF000000.toInt()
    private const val YEL = 0xFFFFD60A.toInt()
    private const val RED = 0xFFFF3B30.toInt()
    private const val BLU = 0xFF0A84FF.toInt()
    private const val CYA = 0xFF5CE1E6.toInt()
    private const val PUR = 0xFF9D7BFF.toInt()
    private const val PNK = 0xFFFF4F9A.toInt()
    private const val GRN = 0xFF30D158.toInt()
    private const val ORG = 0xFFFF9F0A.toInt()
    private const val GLD = 0xFFE7C873.toInt()
    private const val DIM = 0xB3000000.toInt()

    private fun a(id: String, ms: Int = 500) = AnimRef(id, ms * 1000L)
    private fun s(es: Boolean, spanish: String, english: String) = if (es) spanish else english

    private fun t(
        text: String, font: String, size: Float, color: Int = W, x: Float = 0f, y: Float = 0f,
        inA: AnimRef? = a("fade"), outA: AnimRef? = a("fade", 350), loop: AnimRef? = null, delay: Int = 0,
        stroke: Float = 0f, strokeColor: Int = K, shadow: Float = 0f, bg: Int = 0, align: Int = 1,
        spacing: Float = 0f, upper: Boolean = false, gradient: List<Int> = emptyList(), italic: Boolean = false,
        maxW: Float = 0.9f, lineSp: Float = 1.1f, dynamic: String = "", bgRadius: Float = 0.2f, bgPad: Float = 0.3f,
        rot: Float = 0f,
    ) = Layer(
        kind = LayerKind.TEXT, text = text, fontId = font, size = size, color = color, x = x, y = y,
        animIn = inA, animOut = outA, animLoop = loop, delayUs = delay * 1000L, strokeWidth = stroke,
        strokeColor = strokeColor, shadowRadius = shadow, bgColor = bg, align = align, letterSpacing = spacing,
        upper = upper, gradient = gradient, italic = italic, maxWidth = maxW, lineSpacing = lineSp,
        dynamic = dynamic, bgRadius = bgRadius, bgPadding = bgPad, rotation = rot,
    )

    private fun box(
        w: Float, h: Float, color: Int, x: Float = 0f, y: Float = 0f, inA: AnimRef? = a("wipe_right"),
        outA: AnimRef? = a("fade", 350), delay: Int = 0, radius: Float = 0f, oval: Boolean = false,
        gradient: List<Int> = emptyList(), rot: Float = 0f, loop: AnimRef? = null,
    ) = Layer(
        kind = if (oval) LayerKind.OVAL else LayerKind.RECT, w = w, h = h, color = color, x = x, y = y,
        animIn = inA, animOut = outA, animLoop = loop, delayUs = delay * 1000L, cornerRadius = radius,
        gradient = gradient, rotation = rot,
    )

    val all: List<TextTemplate> = buildList {
        // ---------------- Títulos ----------------
        val c1 = R.string.tcat_titles
        add(TextTemplate("title_clean", c1) { es -> listOf(t(s(es, "TU TÍTULO", "YOUR TITLE"), "montserrat_black", 0.11f, inA = a("zoom_in"), spacing = 0.05f)) })
        add(TextTemplate("title_underline", c1) { es -> listOf(
            t(s(es, "Título", "Title"), "poppins_bold", 0.1f, y = -0.02f, inA = a("slide_up")),
            box(0.3f, 0.006f, YEL, y = 0.045f, delay = 250)) })
        add(TextTemplate("title_cinema", c1, 4000) { es -> listOf(
            t(s(es, "UNA HISTORIA", "A STORY"), "bebas_neue", 0.05f, GLD, y = -0.07f, spacing = 0.4f, inA = a("fade", 900)),
            t(s(es, "EL COMIENZO", "THE BEGINNING"), "bebas_neue", 0.15f, y = 0.02f, spacing = 0.12f, inA = a("blur", 1200), delay = 300)) })
        add(TextTemplate("title_serif", c1) { es -> listOf(
            t(s(es, "Elegancia", "Elegance"), "playfair_bold", 0.11f, inA = a("letter_fade", 900), italic = true),
            t(s(es, "edición especial", "special edition"), "montserrat", 0.032f, y = 0.085f, spacing = 0.3f, upper = true, delay = 500)) })
        add(TextTemplate("title_neon", c1, clipLoop = null) { es -> listOf(
            t(s(es, "NEÓN", "NEON"), "monoton", 0.15f, PNK, inA = a("flicker", 900), shadow = 0.25f, loop = a("breathe")).copy(shadowColor = PNK, shadowDy = 0f)) })
        add(TextTemplate("title_gradient", c1) { es -> listOf(
            t(s(es, "DEGRADADO", "GRADIENT"), "anton", 0.14f, gradient = listOf(PUR, CYA), inA = a("letter_rise", 800))) })
        add(TextTemplate("title_box", c1) { es -> listOf(
            box(0.72f, 0.14f, W, inA = a("curtain", 450)),
            t(s(es, "DESTACADO", "FEATURED"), "archivo_black", 0.085f, K, delay = 250, inA = a("fade", 300))) })
        add(TextTemplate("title_stack", c1) { es -> listOf(
            t(s(es, "GRAN", "BIG"), "anton", 0.16f, y = -0.085f, inA = a("slide_left", 400)),
            t(s(es, "TITULAR", "HEADLINE"), "anton", 0.16f, YEL, y = 0.085f, inA = a("slide_right", 400), delay = 150)) })
        add(TextTemplate("title_typewriter", c1, 3500) { es -> listOf(
            t(s(es, "Érase una vez...", "Once upon a time..."), "space_mono", 0.06f, inA = a("typewriter", 1600))) })
        add(TextTemplate("title_comic", c1) { es -> listOf(
            t(s(es, "¡BOOM!", "BOOM!"), "bangers", 0.2f, YEL, inA = a("pop", 450), stroke = 0.1f, rot = -6f, loop = a("pulse"))) })
        add(TextTemplate("title_retro", c1) { es -> listOf(
            t(s(es, "Retro", "Retro"), "lobster", 0.17f, ORG, inA = a("bounce", 700), shadow = 0.02f).copy(shadowColor = 0xFF7A2E00.toInt(), shadowDx = 0.05f, shadowDy = 0.05f)) })
        add(TextTemplate("title_pixel", c1) { es -> listOf(
            t(s(es, "NIVEL 1", "LEVEL 1"), "press_start", 0.07f, GRN, inA = a("typewriter", 800), loop = a("blink"))) })

        // ---------------- Intros ----------------
        val c2 = R.string.tcat_intros
        add(TextTemplate("intro_channel", c2, 4000) { es -> listOf(
            box(0.012f, 0.16f, RED, x = -0.33f, inA = a("wipe_down", 350)),
            t(s(es, "MI CANAL", "MY CHANNEL"), "montserrat_black", 0.09f, x = -0.3f, y = -0.03f, align = 0, inA = a("slide_right", 450), delay = 200),
            t(s(es, "nuevo vídeo cada semana", "new video every week"), "poppins", 0.035f, x = -0.3f, y = 0.05f, align = 0, delay = 450)) })
        add(TextTemplate("intro_vlog", c2, 3500) { es -> listOf(
            t("VLOG", "bebas_neue", 0.22f, inA = a("zoom_out", 500), spacing = 0.2f),
            t(s(es, "episodio 01", "episode 01"), "caveat", 0.07f, YEL, y = 0.12f, delay = 400, inA = a("letter_fade", 500), rot = -4f)) })
        add(TextTemplate("intro_presents", c2, 4000) { es -> listOf(
            t(s(es, "PRESENTA", "PRESENTS"), "montserrat", 0.035f, y = 0.09f, spacing = 0.6f, delay = 600),
            t(s(es, "Tu Nombre", "Your Name"), "playfair_bold", 0.1f, inA = a("blur", 900))) })
        add(TextTemplate("intro_travel", c2, 4000) { es -> listOf(
            t(s(es, "viaje a", "trip to"), "dancing_script", 0.08f, y = -0.1f, inA = a("letter_fade", 600)),
            t(s(es, "ISLANDIA", "ICELAND"), "anton", 0.19f, y = 0.03f, spacing = 0.08f, inA = a("wipe_right", 700), delay = 300),
            t("2026", "space_mono", 0.04f, y = 0.15f, spacing = 0.5f, delay = 800)) })
        add(TextTemplate("intro_glitch", c2, 3000) { _ -> listOf(
            t("START", "press_start", 0.09f, CYA, x = -0.006f, inA = a("flicker", 600), loop = a("jitter")),
            t("START", "press_start", 0.09f, PNK, x = 0.006f, inA = a("flicker", 700), loop = a("jitter")),
            t("START", "press_start", 0.09f, inA = a("flicker", 500))) })
        add(TextTemplate("intro_minimal", c2, 3500) { es -> listOf(
            box(0.5f, 0.003f, W, y = -0.06f, inA = a("curtain", 600)),
            t(s(es, "CAPÍTULO UNO", "CHAPTER ONE"), "montserrat", 0.05f, spacing = 0.35f, delay = 300),
            box(0.5f, 0.003f, W, y = 0.06f, inA = a("curtain", 600), delay = 150)) })
        add(TextTemplate("intro_sport", c2, 3000) { es -> listOf(
            box(0.8f, 0.17f, RED, inA = a("slide_right", 300), rot = -4f),
            t(s(es, "DÍA DE PARTIDO", "GAME DAY"), "anton", 0.12f, inA = a("slide_left", 350), delay = 150, italic = true, rot = -4f)) })
        add(TextTemplate("intro_food", c2, 3500) { es -> listOf(
            t(s(es, "Receta", "Recipe"), "pacifico", 0.13f, ORG, y = -0.04f, inA = a("pop", 500)),
            t(s(es, "fácil y rápida", "quick & easy"), "poppins", 0.04f, y = 0.08f, delay = 400, spacing = 0.2f, upper = true)) })
        add(TextTemplate("intro_gaming", c2, 3000) { es -> listOf(
            t(s(es, "JUGADOR 1", "PLAYER 1"), "righteous", 0.11f, gradient = listOf(GRN, CYA), inA = a("elastic", 700), stroke = 0.06f),
            t(s(es, "LISTO", "READY"), "press_start", 0.035f, YEL, y = 0.11f, delay = 500, loop = a("blink"))) })

        // ---------------- Créditos ----------------
        val c3 = R.string.tcat_credits
        add(TextTemplate("credits_roll", c3, 10000) { es -> listOf(
            t(s(es, "DIRECCIÓN\nTu Nombre\n\nGUION\nTu Nombre\n\nMÚSICA\nArtista\n\nMONTAJE\nTu Nombre\n\nGRACIAS POR VER",
                "DIRECTED BY\nYour Name\n\nWRITTEN BY\nYour Name\n\nMUSIC\nArtist\n\nEDITING\nYour Name\n\nTHANKS FOR WATCHING"),
                "montserrat", 0.04f, inA = null, outA = null, loop = a("credits"), lineSp = 1.5f)) })
        add(TextTemplate("credits_roll_serif", c3, 10000) { es -> listOf(
            t(s(es, "Reparto\n\nPersona Uno\nPersona Dos\nPersona Tres\n\nProducción\n\nTu Estudio", "Cast\n\nPerson One\nPerson Two\nPerson Three\n\nProduction\n\nYour Studio"),
                "playfair", 0.045f, GLD, inA = null, outA = null, loop = a("credits"), lineSp = 1.5f)) })
        add(TextTemplate("credits_card", c3, 3500) { es -> listOf(
            t(s(es, "DIRIGIDO POR", "DIRECTED BY"), "montserrat", 0.03f, y = -0.05f, spacing = 0.5f),
            t(s(es, "Tu Nombre", "Your Name"), "playfair_bold", 0.085f, y = 0.03f, delay = 300, inA = a("letter_fade", 700))) })
        add(TextTemplate("credits_two_col", c3, 4000) { es -> listOf(
            t(s(es, "Cámara\nSonido\nEdición", "Camera\nSound\nEditing"), "poppins", 0.04f, 0xFFBBBBBB.toInt(), x = -0.03f, align = 2, lineSp = 1.6f, inA = a("slide_right", 500)),
            t(s(es, "Ana\nLuis\nMarta", "Anna\nLouis\nMartha"), "poppins_bold", 0.04f, x = 0.03f, align = 0, lineSp = 1.6f, inA = a("slide_left", 500), delay = 150)) })
        add(TextTemplate("credits_music", c3, 4000, y = 0.36f) { es -> listOf(
            t("♪", "poppins_bold", 0.06f, CYA, x = -0.4f, loop = a("wobble")),
            t(s(es, "Canción — Artista", "Song — Artist"), "poppins", 0.036f, x = -0.34f, align = 0, inA = a("wipe_right", 600), delay = 200)) })
        add(TextTemplate("credits_marquee", c3, 8000, y = 0.42f) { es -> listOf(
            box(1.2f, 0.07f, DIM, inA = a("fade", 300)),
            t(s(es, "Gracias a todos los que hicieron posible este vídeo  •  Suscríbete  •  Vixa", "Thanks to everyone who made this video possible  •  Subscribe  •  Vixa"),
                "poppins", 0.035f, inA = null, outA = null, loop = a("marquee"), maxW = 4f)) })

        // ---------------- Tercios inferiores ----------------
        val c4 = R.string.tcat_lower
        add(TextTemplate("lower_bar", c4, 4000, x = -0.12f, y = 0.33f) { es -> listOf(
            box(0.01f, 0.09f, YEL, x = -0.31f, inA = a("wipe_down", 300)),
            t(s(es, "Nombre Apellido", "First Last"), "poppins_bold", 0.048f, x = -0.28f, y = -0.02f, align = 0, inA = a("slide_right", 400), delay = 150),
            t(s(es, "Cargo o descripción", "Title or description"), "poppins", 0.03f, 0xFFDDDDDD.toInt(), x = -0.28f, y = 0.028f, align = 0, delay = 350)) })
        add(TextTemplate("lower_box", c4, 4000, x = -0.1f, y = 0.34f) { es -> listOf(
            box(0.62f, 0.07f, W, x = 0f, y = -0.02f, inA = a("wipe_right", 400)),
            t(s(es, "NOMBRE APELLIDO", "FIRST LAST"), "montserrat_black", 0.04f, K, x = -0.28f, y = -0.02f, align = 0, delay = 250),
            box(0.45f, 0.045f, RED, x = -0.085f, y = 0.04f, inA = a("wipe_right", 400), delay = 200),
            t(s(es, "Profesión", "Occupation"), "poppins", 0.026f, x = -0.28f, y = 0.04f, align = 0, delay = 450)) })
        add(TextTemplate("lower_news", c4, 5000, y = 0.36f) { es -> listOf(
            box(1f, 0.085f, 0xFFB00020.toInt(), inA = a("slide_up", 350)),
            box(0.24f, 0.085f, W, x = -0.38f, inA = a("slide_right", 350), delay = 150),
            t(s(es, "ÚLTIMA HORA", "BREAKING"), "archivo_black", 0.03f, 0xFFB00020.toInt(), x = -0.38f, delay = 350),
            t(s(es, "Titular de la noticia aquí", "News headline goes here"), "poppins_bold", 0.034f, x = -0.23f, align = 0, delay = 450, inA = a("wipe_right", 500))) })
        add(TextTemplate("lower_gradient", c4, 4000, x = -0.1f, y = 0.34f) { es -> listOf(
            box(0.66f, 0.1f, PUR, gradient = listOf(PUR, 0x009D7BFF), radius = 0.2f, inA = a("wipe_right", 450)),
            t(s(es, "Nombre Apellido", "First Last"), "poppins_bold", 0.042f, x = -0.3f, y = -0.016f, align = 0, delay = 200),
            t("@usuario", "poppins", 0.028f, x = -0.3f, y = 0.026f, align = 0, delay = 350)) })
        add(TextTemplate("lower_minimal", c4, 4000, x = -0.15f, y = 0.35f) { es -> listOf(
            t(s(es, "Nombre Apellido", "First Last"), "montserrat", 0.04f, x = -0.25f, y = -0.02f, align = 0, inA = a("letter_fade", 500), spacing = 0.1f),
            box(0.3f, 0.002f, W, x = -0.1f, y = 0.008f, delay = 200),
            t(s(es, "fotógrafa", "photographer"), "playfair", 0.03f, x = -0.25f, y = 0.035f, align = 0, delay = 400, italic = true)) })
        add(TextTemplate("lower_tag", c4, 4000, x = -0.12f, y = 0.34f) { es -> listOf(
            t(s(es, "Nombre Apellido", "First Last"), "poppins_bold", 0.04f, K, x = -0.28f, y = -0.02f, align = 0, bg = YEL, inA = a("wipe_right", 400), bgRadius = 0.1f),
            t(s(es, "Invitado especial", "Special guest"), "poppins", 0.028f, x = -0.28f, y = 0.045f, align = 0, bg = K, delay = 250, inA = a("wipe_right", 400), bgRadius = 0.1f)) })
        add(TextTemplate("lower_social", c4, 4000, x = -0.15f, y = 0.36f) { _ -> listOf(
            box(0.07f, 0.07f, PNK, x = -0.3f, oval = true, inA = a("pop", 400), gradient = listOf(ORG, PNK, PUR)).copy(h = 0f),
            t("@tu_usuario", "poppins_bold", 0.04f, x = -0.24f, align = 0, inA = a("slide_right", 400), delay = 200, shadow = 0.1f)) })
        add(TextTemplate("lower_right", c4, 4000, x = 0.12f, y = 0.34f) { es -> listOf(
            box(0.01f, 0.09f, CYA, x = 0.31f, inA = a("wipe_down", 300)),
            t(s(es, "Nombre Apellido", "First Last"), "poppins_bold", 0.048f, x = 0.28f, y = -0.02f, align = 2, inA = a("slide_left", 400), delay = 150),
            t(s(es, "Cargo o descripción", "Title or description"), "poppins", 0.03f, 0xFFDDDDDD.toInt(), x = 0.28f, y = 0.028f, align = 2, delay = 350)) })
        add(TextTemplate("lower_sport", c4, 4000, x = -0.1f, y = 0.34f) { es -> listOf(
            box(0.6f, 0.09f, BLU, inA = a("slide_right", 300), rot = 0f),
            box(0.1f, 0.09f, W, x = -0.25f, inA = a("slide_right", 300), delay = 100),
            t("10", "anton", 0.06f, BLU, x = -0.25f, delay = 300),
            t(s(es, "JUGADOR", "PLAYER"), "anton", 0.05f, x = -0.17f, align = 0, delay = 350, italic = true)) })
        add(TextTemplate("lower_elegant", c4, 4500, y = 0.34f) { es -> listOf(
            box(0.4f, 0.002f, GLD, y = -0.045f, inA = a("curtain", 500)),
            t(s(es, "Nombre Apellido", "First Last"), "playfair_bold", 0.045f, y = -0.012f, delay = 200),
            t(s(es, "CARGO", "TITLE"), "montserrat", 0.022f, GLD, y = 0.03f, spacing = 0.5f, delay = 400),
            box(0.4f, 0.002f, GLD, y = 0.055f, inA = a("curtain", 500), delay = 100)) })

        // ---------------- Rótulos de nombre y lugar ----------------
        val c5 = R.string.tcat_labels
        add(TextTemplate("label_place", c5, 3500, x = -0.2f, y = -0.36f) { es -> listOf(
            t("📍", "poppins", 0.04f, x = -0.2f, inA = a("bounce", 500)),
            t(s(es, "Madrid, España", "Madrid, Spain"), "poppins_bold", 0.04f, x = -0.16f, align = 0, delay = 200, inA = a("wipe_right", 400), shadow = 0.12f)) })
        add(TextTemplate("label_place_big", c5, 4000) { es -> listOf(
            t(s(es, "TOKIO", "TOKYO"), "anton", 0.2f, spacing = 0.15f, inA = a("letter_rise", 700)),
            t("35.6762° N, 139.6503° E", "space_mono", 0.028f, y = 0.13f, delay = 500, spacing = 0.1f)) })
        add(TextTemplate("label_date", c5, 3500, x = -0.2f, y = -0.38f) { es -> listOf(
            t(s(es, "12 DE MARZO, 2026", "MARCH 12, 2026"), "space_mono_bold", 0.03f, x = -0.2f, y = -0.015f, align = 0, inA = a("typewriter", 700)),
            t("08:45", "space_mono", 0.03f, 0xFFCCCCCC.toInt(), x = -0.2f, y = 0.025f, align = 0, delay = 500, inA = a("typewriter", 400))) })
        add(TextTemplate("label_name_tag", c5, 3500) { es -> listOf(
            t(s(es, "Hola, soy Alex", "Hi, I'm Alex"), "caveat", 0.08f, K, bg = W, inA = a("pop", 400), bgRadius = 0.5f, bgPad = 0.4f, rot = -3f)) })
        add(TextTemplate("label_pointer", c5, 3500) { es -> listOf(
            box(0.02f, 0.02f, W, x = -0.12f, y = 0.05f, oval = true, inA = a("pop", 300), loop = a("pulse")).copy(h = 0f),
            box(0.12f, 0.003f, W, x = -0.06f, y = 0.02f, rot = -30f, delay = 200),
            t(s(es, "Mira esto", "Look at this"), "poppins_bold", 0.04f, x = 0.0f, y = -0.02f, align = 0, delay = 400, shadow = 0.1f)) })
        add(TextTemplate("label_stamp", c5, 3000) { es -> listOf(
            t(s(es, "APROBADO", "APPROVED"), "archivo_black", 0.09f, RED, inA = a("zoom_out", 250), rot = -12f, bg = 0x22FF3B30, bgRadius = 0.1f, spacing = 0.1f)) })
        add(TextTemplate("label_price", c5, 3000) { _ -> listOf(
            box(0.26f, 0.26f, RED, oval = true, inA = a("pop", 400), loop = a("pulse")).copy(h = 0f),
            t("-50%", "anton", 0.09f, delay = 200, inA = a("pop", 300), rot = -8f)) })
        add(TextTemplate("label_hashtag", c5, 3000, y = -0.38f) { es -> listOf(
            t(s(es, "#tendencia", "#trending"), "poppins_bold", 0.045f, bg = BLU, inA = a("slide_down", 400), bgRadius = 0.5f, bgPad = 0.4f)) })
        add(TextTemplate("label_live", c5, 4000, x = -0.36f, y = -0.42f) { es -> listOf(
            t(s(es, "● EN DIRECTO", "● LIVE"), "poppins_bold", 0.03f, bg = RED, loop = a("breathe"), bgRadius = 0.2f)) })

        // ---------------- Citas ----------------
        val c6 = R.string.tcat_quotes
        add(TextTemplate("quote_classic", c6, 5000) { es -> listOf(
            t("“", "playfair_bold", 0.25f, GLD, y = -0.16f, inA = a("fade", 500)),
            t(s(es, "La creatividad es la inteligencia divirtiéndose.", "Creativity is intelligence having fun."), "playfair", 0.055f, italic = true, maxW = 0.8f, lineSp = 1.3f, delay = 300, inA = a("letter_fade", 1200)),
            t("— Albert Einstein", "montserrat", 0.03f, y = 0.16f, delay = 1400, spacing = 0.15f)) })
        add(TextTemplate("quote_bold", c6, 4500) { es -> listOf(
            t(s(es, "HAZLO\nCON PASIÓN\nO NO LO HAGAS", "DO IT\nWITH PASSION\nOR NOT AT ALL"), "anton", 0.1f, lineSp = 1.05f, inA = a("letter_pop", 1000))) })
        add(TextTemplate("quote_hand", c6, 4500) { es -> listOf(
            t(s(es, "los mejores momentos\nno se planean", "the best moments\nare unplanned"), "shadows", 0.075f, inA = a("typewriter", 1500), lineSp = 1.3f)) })
        add(TextTemplate("quote_box", c6, 4500) { es -> listOf(
            box(0.84f, 0.3f, DIM, radius = 0.08f, inA = a("fade", 400)),
            box(0.008f, 0.2f, CYA, x = -0.36f, delay = 200, inA = a("wipe_down", 400)),
            t(s(es, "Menos es más.", "Less is more."), "lora", 0.065f, x = -0.32f, align = 0, delay = 400, italic = true, maxW = 0.68f)) })
        add(TextTemplate("quote_script", c6, 4500) { es -> listOf(
            t(s(es, "Vive, ríe, ama", "Live, laugh, love"), "dancing_script", 0.12f, PNK, inA = a("letter_fade", 1200), shadow = 0.06f)) })
        add(TextTemplate("quote_mono", c6, 5000) { es -> listOf(
            t(s(es, "> el código es poesía_", "> code is poetry_"), "space_mono", 0.05f, GRN, inA = a("typewriter", 1400), maxW = 0.85f)) })
        add(TextTemplate("quote_duo", c6, 4500) { es -> listOf(
            t(s(es, "SUEÑA", "DREAM"), "montserrat_black", 0.13f, y = -0.06f, inA = a("slide_down", 450)),
            t(s(es, "en grande", "big"), "pacifico", 0.11f, YEL, y = 0.06f, delay = 300, inA = a("pop", 450), rot = -5f)) })
        add(TextTemplate("quote_frame", c6, 5000) { es -> listOf(
            box(0.8f, 0.004f, W, y = -0.12f, inA = a("curtain", 600)),
            t(s(es, "El viaje es el destino", "The journey is the destination"), "dm_serif", 0.06f, maxW = 0.75f, delay = 300, inA = a("blur", 800)),
            box(0.8f, 0.004f, W, y = 0.12f, inA = a("curtain", 600), delay = 150)) })

        // ---------------- Cuentas atrás ----------------
        val c7 = R.string.tcat_countdown
        add(TextTemplate("count_big", c7, 5000) { _ -> listOf(t("5", "anton", 0.4f, dynamic = "countdown", inA = null, outA = null, loop = a("heartbeat"))) })
        add(TextTemplate("count_ring", c7, 5000) { _ -> listOf(
            box(0.42f, 0.42f, 0x33FFFFFF, oval = true, inA = a("pop", 400)).copy(h = 0f),
            t("5", "montserrat_black", 0.25f, dynamic = "countdown", inA = null, outA = null)) })
        add(TextTemplate("count_digital", c7, 10000) { _ -> listOf(
            t("10", "press_start", 0.18f, RED, dynamic = "countdown", inA = null, outA = null, shadow = 0.2f).copy(shadowColor = RED, shadowDy = 0f)) })
        add(TextTemplate("count_film", c7, 5000) { _ -> listOf(
            box(0.5f, 0.5f, 0x22FFFFFF, oval = true, inA = null).copy(h = 0f),
            box(0.9f, 0.003f, 0x66FFFFFF, inA = null), box(0.003f, 0.9f, 0x66FFFFFF, inA = null).copy(w = 0.003f, h = 0.5f),
            t("5", "abril_fatface", 0.3f, dynamic = "countdown", inA = null, outA = null)) })
        add(TextTemplate("count_label", c7, 5000) { es -> listOf(
            t(s(es, "EMPIEZA EN", "STARTS IN"), "montserrat", 0.04f, y = -0.14f, spacing = 0.4f),
            t("5", "bebas_neue", 0.32f, YEL, y = 0.03f, dynamic = "countdown", inA = null, outA = null)) })
        add(TextTemplate("count_corner", c7, 10000, x = 0.38f, y = -0.42f) { _ -> listOf(
            t("10", "space_mono_bold", 0.06f, bg = DIM, dynamic = "countdown", inA = null, outA = null, bgRadius = 0.2f)) })

        // ---------------- Llamadas a la acción ----------------
        val c8 = R.string.tcat_cta
        add(TextTemplate("cta_subscribe", c8, 4000, y = 0.3f) { es -> listOf(
            t(s(es, "SUSCRÍBETE", "SUBSCRIBE"), "poppins_bold", 0.05f, bg = RED, inA = a("pop", 450), loop = a("pulse"), bgRadius = 0.25f, bgPad = 0.45f)) })
        add(TextTemplate("cta_like", c8, 4000, y = 0.3f) { es -> listOf(
            t("👍", "poppins", 0.09f, x = -0.2f, inA = a("bounce", 600), loop = a("wobble")),
            t(s(es, "¡Dale a me gusta!", "Smash that like!"), "poppins_bold", 0.045f, x = -0.12f, align = 0, delay = 300, inA = a("slide_right", 400), shadow = 0.1f)) })
        add(TextTemplate("cta_bell", c8, 4000, y = 0.3f) { es -> listOf(
            t("🔔", "poppins", 0.08f, x = -0.26f, loop = a("wobble"), inA = a("pop", 400)),
            t(s(es, "Activa la campanita", "Turn on notifications"), "poppins_bold", 0.04f, x = -0.19f, align = 0, bg = W, delay = 250, bgRadius = 0.3f).copy(color = K)) })
        add(TextTemplate("cta_swipe", c8, 3500, y = 0.36f) { es -> listOf(
            t("⌃", "poppins_bold", 0.08f, y = -0.06f, loop = a("float")),
            t(s(es, "DESLIZA", "SWIPE UP"), "montserrat_black", 0.04f, y = 0.01f, spacing = 0.3f)) })
        add(TextTemplate("cta_link", c8, 4000, y = 0.3f) { es -> listOf(
            t(s(es, "🔗 Enlace en la bio", "🔗 Link in bio"), "poppins_bold", 0.042f, K, bg = YEL, inA = a("slide_up", 400), bgRadius = 0.5f, bgPad = 0.45f)) })
        add(TextTemplate("cta_follow", c8, 4000, y = 0.3f) { es -> listOf(
            box(0.5f, 0.09f, W, radius = 0.5f, inA = a("zoom_in", 400), gradient = listOf(ORG, PNK, PUR)),
            t(s(es, "Sígueme  @usuario", "Follow  @user"), "poppins_bold", 0.036f, delay = 250)) })
        add(TextTemplate("cta_comment", c8, 4000, y = 0.28f) { es -> listOf(
            t(s(es, "¿Y tú qué opinas?\nTe leo en comentarios 👇", "What do you think?\nTell me in the comments 👇"), "poppins_bold", 0.04f, bg = DIM, inA = a("letter_fade", 900), lineSp = 1.3f, bgRadius = 0.15f)) })
        add(TextTemplate("cta_new", c8, 3000, x = 0.3f, y = -0.38f) { es -> listOf(
            t(s(es, "¡NUEVO!", "NEW!"), "bangers", 0.08f, YEL, inA = a("pop", 400), stroke = 0.1f, rot = 10f, loop = a("pulse"))) })
        add(TextTemplate("cta_watch_next", c8, 5000, y = -0.34f) { es -> listOf(
            t(s(es, "MIRA TAMBIÉN", "WATCH NEXT"), "montserrat_black", 0.05f, inA = a("slide_down", 400), spacing = 0.15f),
            box(0.2f, 0.005f, RED, y = 0.04f, delay = 250)) })

        // ---------------- Capítulos ----------------
        val c9 = R.string.tcat_chapters
        add(TextTemplate("chapter_number", c9, 3500) { es -> listOf(
            t("01", "anton", 0.26f, 0x44FFFFFF, y = -0.02f, inA = a("zoom_out", 500)),
            t(s(es, "EL PRINCIPIO", "THE BEGINNING"), "montserrat_black", 0.055f, y = 0.02f, delay = 300, inA = a("wipe_right", 500), spacing = 0.2f)) })
        add(TextTemplate("chapter_part", c9, 3500) { es -> listOf(
            t(s(es, "PARTE I", "PART I"), "playfair", 0.04f, GLD, y = -0.07f, spacing = 0.5f),
            box(0.1f, 0.003f, GLD, y = -0.03f, inA = a("curtain", 400), delay = 200),
            t(s(es, "El despertar", "The Awakening"), "playfair_bold", 0.085f, y = 0.045f, delay = 400, inA = a("blur", 700), italic = true)) })
        add(TextTemplate("chapter_step", c9, 3000, x = -0.15f, y = -0.3f) { es -> listOf(
            box(0.11f, 0.11f, YEL, x = -0.26f, oval = true, inA = a("pop", 350)).copy(h = 0f),
            t("1", "poppins_bold", 0.06f, K, x = -0.26f, delay = 150),
            t(s(es, "Primer paso", "Step one"), "poppins_bold", 0.05f, x = -0.18f, align = 0, delay = 250, inA = a("slide_right", 400), shadow = 0.1f)) })
        add(TextTemplate("chapter_day", c9, 3000) { es -> listOf(
            t(s(es, "DÍA 1", "DAY 1"), "bebas_neue", 0.22f, inA = a("letter_pop", 500), spacing = 0.1f),
            t(s(es, "la llegada", "arrival"), "caveat", 0.075f, YEL, y = 0.12f, delay = 400, rot = -4f)) })
        add(TextTemplate("chapter_bar", c9, 3500, y = -0.4f) { es -> listOf(
            box(1f, 0.08f, DIM, inA = a("slide_down", 300)),
            t(s(es, "2 · INGREDIENTES", "2 · INGREDIENTS"), "montserrat_black", 0.036f, delay = 200, spacing = 0.2f)) })
        add(TextTemplate("chapter_versus", c9, 3500) { _ -> listOf(
            t("A", "anton", 0.2f, BLU, x = -0.2f, inA = a("slide_right", 400)),
            t("VS", "bangers", 0.12f, YEL, delay = 300, inA = a("pop", 400), stroke = 0.1f),
            t("B", "anton", 0.2f, RED, x = 0.2f, inA = a("slide_left", 400))) })
        add(TextTemplate("chapter_tip", c9, 3500, y = -0.3f) { es -> listOf(
            t(s(es, "💡 CONSEJO Nº 3", "💡 TIP #3"), "poppins_bold", 0.045f, K, bg = W, inA = a("drop", 400), bgRadius = 0.2f, bgPad = 0.4f)) })

        // ---------------- Subtítulos con estilo ----------------
        val c10 = R.string.tcat_subs
        add(TextTemplate("sub_classic", c10, 3000, y = 0.36f) { es -> listOf(
            t(s(es, "Subtítulo de ejemplo", "Sample subtitle"), "poppins_bold", 0.045f, stroke = 0.12f, shadow = 0.1f, inA = null, outA = null)) })
        add(TextTemplate("sub_box", c10, 3000, y = 0.36f) { es -> listOf(
            t(s(es, "Subtítulo de ejemplo", "Sample subtitle"), "poppins", 0.042f, bg = DIM, inA = null, outA = null, bgRadius = 0.15f)) })
        add(TextTemplate("sub_yellow", c10, 3000, y = 0.36f) { es -> listOf(
            t(s(es, "Subtítulo de ejemplo", "Sample subtitle"), "archivo_black", 0.046f, YEL, stroke = 0.14f, inA = null, outA = null)) })
        add(TextTemplate("sub_karaoke", c10, 3000, y = 0.3f) { es -> listOf(
            t(s(es, "PALABRA A PALABRA", "WORD BY WORD"), "montserrat_black", 0.06f, stroke = 0.12f, inA = a("letter_pop", 1200), outA = null)) })
        add(TextTemplate("sub_pop", c10, 2000, y = 0.2f) { es -> listOf(
            t(s(es, "¡GUAU!", "WOW!"), "bangers", 0.13f, YEL, stroke = 0.1f, inA = a("pop", 300), outA = a("zoom_in", 200), rot = -4f)) })
        add(TextTemplate("sub_typewriter", c10, 3000, y = 0.36f) { es -> listOf(
            t(s(es, "Escribiendo el subtítulo...", "Typing the subtitle..."), "space_mono", 0.036f, bg = K, inA = a("typewriter", 1200), outA = null, bgRadius = 0.05f)) })
        add(TextTemplate("sub_highlight", c10, 3000, y = 0.34f) { es -> listOf(
            t(s(es, "Texto resaltado", "Highlighted text"), "poppins_bold", 0.045f, K, bg = CYA, inA = a("wipe_right", 400), outA = null, bgRadius = 0.08f)) })
        add(TextTemplate("sub_neon", c10, 3000, y = 0.34f) { es -> listOf(
            t(s(es, "Luces de neón", "Neon lights"), "righteous", 0.055f, CYA, shadow = 0.3f, inA = a("flicker", 500), outA = null).copy(shadowColor = CYA, shadowDy = 0f)) })

        // ---------------- Cierres ----------------
        val c11 = R.string.tcat_outros
        add(TextTemplate("outro_thanks", c11, 4000) { es -> listOf(
            t(s(es, "¡Gracias por ver!", "Thanks for watching!"), "pacifico", 0.1f, inA = a("letter_fade", 900)),
            t(s(es, "nos vemos en el próximo vídeo", "see you in the next one"), "poppins", 0.034f, y = 0.1f, delay = 700)) })
        add(TextTemplate("outro_end", c11, 4000) { es -> listOf(
            t(s(es, "FIN", "THE END"), "playfair_bold", 0.16f, inA = a("blur", 1200), outA = a("fade", 900), spacing = 0.2f)) })
        add(TextTemplate("outro_social", c11, 5000) { es -> listOf(
            t(s(es, "SÍGUEME", "FOLLOW ME"), "montserrat_black", 0.07f, y = -0.12f, inA = a("slide_down", 400), spacing = 0.15f),
            t("📷  @instagram", "poppins", 0.04f, y = -0.02f, delay = 300, inA = a("slide_right", 400)),
            t("▶  /youtube", "poppins", 0.04f, y = 0.05f, delay = 450, inA = a("slide_right", 400)),
            t("♪  @tiktok", "poppins", 0.04f, y = 0.12f, delay = 600, inA = a("slide_right", 400))) })
        add(TextTemplate("outro_continued", c11, 3500, x = 0.18f, y = 0.36f) { es -> listOf(
            t(s(es, "CONTINUARÁ", "TO BE CONTINUED"), "bebas_neue", 0.06f, x = 0.02f, inA = a("slide_left", 500), spacing = 0.15f, italic = true),
            t("→", "poppins_bold", 0.06f, x = 0.24f, delay = 300, loop = a("float"))) })
        add(TextTemplate("outro_see_you", c11, 3500) { es -> listOf(
            t(s(es, "¡Hasta pronto!", "See you soon!"), "caveat", 0.14f, YEL, inA = a("pop", 500), rot = -5f, loop = a("wobble"))) })
        add(TextTemplate("outro_made_by", c11, 4000) { es -> listOf(
            t(s(es, "un vídeo de", "a video by"), "montserrat", 0.03f, y = -0.06f, spacing = 0.4f, upper = true),
            t(s(es, "Tu Nombre", "Your Name"), "abril_fatface", 0.1f, y = 0.02f, delay = 300, inA = a("letter_rise", 700)),
            box(0.16f, 0.004f, W, y = 0.1f, delay = 700, inA = a("curtain", 400))) })
        add(TextTemplate("outro_rate", c11, 4000) { es -> listOf(
            t("★★★★★", "poppins_bold", 0.09f, YEL, y = -0.05f, inA = a("letter_pop", 800)),
            t(s(es, "¿Te ha gustado?", "Did you like it?"), "poppins_bold", 0.045f, y = 0.06f, delay = 700)) })
        add(TextTemplate("outro_logo", c11, 4000) { es -> listOf(
            box(0.2f, 0.2f, PUR, oval = true, inA = a("spin", 700), gradient = listOf(PUR, CYA), y = -0.06f).copy(h = 0f),
            t(s(es, "TU MARCA", "YOUR BRAND"), "montserrat_black", 0.05f, y = 0.12f, delay = 500, spacing = 0.3f)) })

        // ---------------- Más recursos (títulos, intros y cierres) ----------------
        val cx1 = R.string.tcat_titles
        add(TextTemplate("title_drop", cx1) { es -> listOf(t(s(es, "CAE", "DROP"), "anton", 0.17f, inA = a("drop", 600), spacing = 0.06f)) })
        add(TextTemplate("title_elastic", cx1) { es -> listOf(t(s(es, "ELÁSTICO", "ELASTIC"), "poppins_bold", 0.11f, PNK, inA = a("elastic", 900))) })
        add(TextTemplate("title_swing", cx1) { es -> listOf(t(s(es, "Balanceo", "Swing"), "lobster", 0.14f, CYA, inA = a("swing", 900), loop = a("float"))) })
        add(TextTemplate("title_shout", cx1) { es -> listOf(t(s(es, "¡GRITA!", "SHOUT!"), "bangers", 0.2f, RED, inA = a("zoom_in", 350), stroke = 0.08f, strokeColor = W, loop = a("shake"))) })
        add(TextTemplate("title_outline", cx1) { es -> listOf(t(s(es, "CONTORNO", "OUTLINE"), "archivo_black", 0.12f, 0x00FFFFFF, inA = a("wipe_right", 700), stroke = 0.07f, strokeColor = W)) })
        add(TextTemplate("title_two_tone", cx1) { es -> listOf(
            t(s(es, "DOS", "TWO"), "montserrat_black", 0.12f, W, y = -0.07f, inA = a("slide_left", 400)),
            t(s(es, "TONOS", "TONES"), "montserrat_black", 0.12f, ORG, y = 0.07f, inA = a("slide_right", 400), delay = 200)) })
        add(TextTemplate("title_label", cx1) { es -> listOf(
            box(0.5f, 0.11f, BLU, radius = 0.5f, inA = a("zoom_in", 350)),
            t(s(es, "NUEVO", "NEW"), "poppins_bold", 0.065f, W, delay = 250, spacing = 0.2f)) })
        add(TextTemplate("title_sale", cx1) { es -> listOf(
            box(0.22f, 0.22f, RED, oval = true, inA = a("pop", 450)).copy(h = 0f),
            t("-50%", "anton", 0.1f, W, inA = a("pop", 450), delay = 150, rot = -8f)) })
        add(TextTemplate("title_blur_in", cx1) { es -> listOf(t(s(es, "Enfoque", "Focus"), "playfair_bold", 0.14f, inA = a("blur", 1100), loop = a("breathe"))) })
        add(TextTemplate("title_stripe", cx1) { es -> listOf(
            box(1f, 0.16f, DIM, inA = a("wipe_right", 500)),
            t(s(es, "BANDA DE TEXTO", "TEXT STRIPE"), "montserrat_black", 0.07f, W, delay = 250, spacing = 0.15f)) })
        val cx2 = R.string.tcat_intros
        add(TextTemplate("intro_podcast", cx2, 4000) { es -> listOf(
            t("🎙", "poppins_bold", 0.12f, y = -0.1f, inA = a("pop", 500), loop = a("heartbeat")),
            t("PODCAST", "bebas_neue", 0.17f, y = 0.04f, spacing = 0.2f, inA = a("letter_pop", 700), delay = 250),
            t(s(es, "episodio 01", "episode 01"), "poppins", 0.035f, y = 0.14f, delay = 700)) })
        add(TextTemplate("intro_arcade", cx2, 3500) { es -> listOf(
            t("PLAYER 1", "press_start", 0.06f, GRN, y = -0.05f, inA = a("typewriter", 700)),
            t(s(es, "PULSA START", "PRESS START"), "press_start", 0.035f, YEL, y = 0.05f, delay = 800, loop = a("blink"))) })
        add(TextTemplate("intro_wedding", cx2, 4500) { es -> listOf(
            t(s(es, "Ana & Luis", "Anna & Luke"), "dancing_script", 0.15f, GLD, inA = a("letter_fade", 1200)),
            t(s(es, "NOS CASAMOS", "WE ARE GETTING MARRIED"), "montserrat", 0.03f, y = 0.1f, spacing = 0.4f, delay = 900)) })
        add(TextTemplate("intro_news", cx2, 4000) { es -> listOf(
            box(0.9f, 0.07f, RED, y = 0.12f, inA = a("wipe_right", 400)),
            t(s(es, "ÚLTIMA HORA", "BREAKING NEWS"), "archivo_black", 0.045f, W, y = 0.12f, delay = 250, inA = a("slide_left", 300)),
            t(s(es, "Titular de la noticia aquí", "News headline here"), "poppins_bold", 0.05f, y = 0.2f, delay = 500, inA = a("typewriter", 1200))) })
        add(TextTemplate("intro_countdown", cx2, 4000) { _ -> listOf(
            t("3", "anton", 0.3f, inA = a("zoom_out", 300), outA = a("zoom_in", 200)),
            t("2", "anton", 0.3f, ORG, inA = a("zoom_out", 300), outA = a("zoom_in", 200), delay = 1000),
            t("1", "anton", 0.3f, RED, inA = a("zoom_out", 300), outA = a("zoom_in", 200), delay = 2000)) })
        val cx3 = R.string.tcat_outros
        add(TextTemplate("outro_thanks_bold", cx3, 4000) { es -> listOf(
            t(s(es, "¡GRACIAS!", "THANK YOU!"), "montserrat_black", 0.13f, inA = a("elastic", 900)),
            t(s(es, "por verlo hasta el final", "for watching till the end"), "caveat", 0.06f, YEL, y = 0.11f, delay = 700)) })
        add(TextTemplate("outro_subscribe", cx3, 4000) { es -> listOf(
            box(0.5f, 0.1f, RED, radius = 0.2f, inA = a("pop", 400), loop = a("pulse")),
            t(s(es, "SUSCRÍBETE", "SUBSCRIBE"), "poppins_bold", 0.055f, W, delay = 250)) })
        add(TextTemplate("outro_share", cx3, 4000) { es -> listOf(
            t(s(es, "COMPARTE", "SHARE"), "bebas_neue", 0.16f, CYA, y = -0.04f, inA = a("slide_up", 500), spacing = 0.15f),
            t(s(es, "si te ha gustado ❤", "if you liked it ❤"), "poppins", 0.045f, y = 0.07f, delay = 500, loop = a("heartbeat"))) })

        // ---------------- Segunda tanda: títulos con carácter (los usa también el asistente) ----------------
        add(TextTemplate("title_slam", cx1) { es -> listOf(t(s(es, "IMPACTO", "IMPACT"), "anton", 0.18f, inA = a("slam", 450), spacing = 0.04f, shadow = 0.08f)) })
        add(TextTemplate("title_scramble", cx1, 3500) { es -> listOf(t(s(es, "DESCIFRADO", "DECODED"), "space_mono_bold", 0.075f, GRN, inA = a("letter_scramble", 1100), spacing = 0.1f)) })
        add(TextTemplate("title_cascade", cx1) { es -> listOf(t(s(es, "Cascada", "Cascade"), "poppins_bold", 0.12f, inA = a("letter_drop", 900))) })
        add(TextTemplate("title_spin", cx1) { es -> listOf(t(s(es, "GIRO", "SPIN"), "righteous", 0.16f, gradient = listOf(ORG, PNK), inA = a("letter_spin", 900))) })
        add(TextTemplate("title_glitch", cx1) { _ -> listOf(
            t("GLITCH", "archivo_black", 0.14f, CYA, x = -0.008f, inA = a("glitch_in", 600), loop = a("glitch_loop")),
            t("GLITCH", "archivo_black", 0.14f, PNK, x = 0.008f, inA = a("glitch_in", 700), loop = a("glitch_loop")),
            t("GLITCH", "archivo_black", 0.1401f, inA = a("glitch_in", 500))) })
        add(TextTemplate("title_calm", cx1, 4000) { es -> listOf(
            t(s(es, "respira", "breathe"), "montserrat", 0.075f, spacing = 0.6f, inA = a("soft_zoom", 1400), outA = a("fade", 800), loop = a("breathe"))) })
        add(TextTemplate("title_melancholy", cx1, 4000) { es -> listOf(
            t(s(es, "lo que fuimos", "what we were"), "lora", 0.085f, 0xFFD5DCE4.toInt(), italic = true, inA = a("blur", 1400), outA = a("blur", 800))) })
        add(TextTemplate("title_happy", cx1) { es -> listOf(
            t(s(es, "¡Buenas vibras!", "Good vibes!"), "poppins_bold", 0.115f, YEL, inA = a("letter_drop", 800), loop = a("letter_bounce"), shadow = 0.08f)) })
        add(TextTemplate("title_dynamic", cx1) { es -> listOf(
            t(s(es, "VAMOS", "LET'S GO"), "montserrat_black", 0.17f, italic = true, inA = a("zoom_blur", 350), loop = a("beat"))) })
        add(TextTemplate("title_epic", cx1, 4500) { es -> listOf(
            box(0.5f, 0.003f, GLD, y = -0.085f, inA = a("curtain", 900)),
            t(s(es, "LA ÚLTIMA FRONTERA", "THE LAST FRONTIER"), "bebas_neue", 0.12f, spacing = 0.28f, inA = a("zoom_blur", 1300), outA = a("fade", 900), delay = 200),
            box(0.5f, 0.003f, GLD, y = 0.085f, inA = a("curtain", 900), delay = 150)) })
        add(TextTemplate("title_romantic", cx1, 4000) { es -> listOf(
            t(s(es, "Tú y yo", "You & me"), "dancing_script", 0.17f, PNK, inA = a("letter_fade", 1200), shadow = 0.06f),
            box(0.03f, 0.03f, PNK, y = 0.13f, oval = true, inA = a("pop", 500), delay = 900, loop = a("heartbeat")).copy(h = 0f)) })
        add(TextTemplate("title_nostalgia", cx1, 4000) { es -> listOf(
            t(s(es, "Verano del 98", "Summer of '98"), "caveat", 0.13f, inA = a("letter_random", 1200), rot = -3f, shadow = 0.06f),
            box(0.3f, 0.004f, ORG, y = 0.09f, inA = a("wipe_right", 600), delay = 700, rot = -3f)) })
        add(TextTemplate("title_elegant", cx1, 4000) { _ -> listOf(
            box(0.24f, 0.002f, GLD, y = -0.09f, inA = a("curtain", 700)),
            t("Atelier", "playfair", 0.14f, GLD, italic = true, inA = a("letter_fade", 1100)),
            box(0.24f, 0.002f, GLD, y = 0.09f, inA = a("curtain", 700), delay = 150)) })
        add(TextTemplate("title_adventure", cx1) { es -> listOf(
            t(s(es, "RUMBO AL NORTE", "HEADING NORTH"), "oswald", 0.115f, inA = a("back_up", 600), spacing = 0.12f, shadow = 0.08f),
            box(0.12f, 0.006f, ORG, y = 0.085f, inA = a("curtain", 400), delay = 400)) })
        add(TextTemplate("title_mystery", cx1, 4000) { es -> listOf(
            t(s(es, "EXPEDIENTE 13", "CASE FILE 13"), "space_mono_bold", 0.075f, inA = a("typewriter", 1300), outA = a("flicker", 500), loop = a("neon_flicker")),
            box(0.42f, 0.003f, RED, y = 0.06f, inA = a("wipe_right", 500), delay = 1200)) })
        add(TextTemplate("title_summer", cx1) { es -> listOf(
            t(s(es, "Verano", "Summer"), "lobster", 0.19f, gradient = listOf(YEL, ORG), inA = a("jelly", 800), loop = a("letter_sway"), rot = -6f, shadow = 0.05f)) })
        add(TextTemplate("title_sport", cx1) { es -> listOf(
            box(0.86f, 0.17f, RED, inA = a("back_right", 350), rot = -4f),
            t(s(es, "SIN LÍMITES", "NO LIMITS"), "anton", 0.125f, italic = true, inA = a("back_left", 400), delay = 120, rot = -4f)) })
        add(TextTemplate("title_party", cx1) { es -> listOf(
            t(s(es, "FIESTA", "PARTY"), "monoton", 0.16f, gradient = listOf(PNK, PUR, CYA), inA = a("flicker", 700), loop = a("beat"), shadow = 0.2f).copy(shadowColor = PNK, shadowDy = 0f)) })
        add(TextTemplate("title_future", cx1, 3500) { es -> listOf(
            box(0.62f, 0.002f, CYA, y = -0.06f, inA = a("curtain", 500)),
            t(s(es, "SISTEMA EN LÍNEA", "SYSTEM ONLINE"), "space_mono_bold", 0.055f, CYA, inA = a("letter_scramble", 1000), spacing = 0.12f, shadow = 0.25f, delay = 200).copy(shadowColor = CYA, shadowDy = 0f),
            box(0.62f, 0.002f, CYA, y = 0.06f, inA = a("curtain", 500), delay = 100)) })
        add(TextTemplate("title_nature", cx1, 4000) { es -> listOf(
            t(s(es, "Naturaleza", "Nature"), "dm_serif", 0.125f, 0xFFF2F5E8.toInt(), inA = a("rise", 1000), outA = a("fall", 700), loop = a("breathe_zoom"), shadow = 0.08f)) })
        add(TextTemplate("title_urban", cx1) { es -> listOf(
            t(s(es, "CALLE", "STREET"), "archivo_black", 0.15f, K, bg = W, inA = a("slam", 400), rot = -3f, bgRadius = 0.04f, bgPad = 0.25f)) })
        add(TextTemplate("title_cozy", cx1, 4000) { es -> listOf(
            t(s(es, "Días de manta", "Blanket days"), "shadows", 0.105f, 0xFFFFD9A8.toInt(), inA = a("letter_fade", 1100), loop = a("sway"), shadow = 0.08f)) })
        add(TextTemplate("title_retro_wave", cx1) { _ -> listOf(
            t("RETRO WAVE", "righteous", 0.125f, gradient = listOf(YEL, PNK), inA = a("stretch", 600), shadow = 0.03f).copy(gradientAngle = 90f, shadowColor = PUR, shadowDx = 0.05f, shadowDy = 0.05f),
            box(0.6f, 0.008f, PNK, y = 0.085f, gradient = listOf(PNK, CYA), inA = a("wipe_right", 500), delay = 400)) })
        add(TextTemplate("title_wedding", cx1, 4500) { es -> listOf(
            box(0.2f, 0.002f, GLD, y = -0.11f, inA = a("curtain", 800)),
            t(s(es, "Ana & Luis", "Anna & Luke"), "dancing_script", 0.16f, GLD, inA = a("letter_fade", 1300), outA = a("fade", 800)),
            box(0.2f, 0.002f, GLD, y = 0.11f, inA = a("curtain", 800), delay = 200)) })
        add(TextTemplate("title_birthday", cx1) { es -> listOf(
            t(s(es, "¡FELIZ CUMPLE!", "HAPPY BIRTHDAY!"), "bangers", 0.15f, YEL, stroke = 0.09f, inA = a("letter_pop", 800), loop = a("letter_bounce"), rot = -4f)) })
        add(TextTemplate("title_horror", cx1, 4000) { es -> listOf(
            t(s(es, "NO MIRES ATRÁS", "DON'T LOOK BACK"), "playfair_bold", 0.095f, 0xFFD0101C.toInt(), spacing = 0.14f, inA = a("flicker", 1200), outA = a("glitch_in", 500), loop = a("neon_flicker"), shadow = 0.2f).copy(shadowColor = 0xFF600008.toInt(), shadowDy = 0f)) })
        add(TextTemplate("title_arcade", cx1) { _ -> listOf(
            t("GAME ON", "press_start", 0.085f, gradient = listOf(CYA, PNK), inA = a("letter_zoom", 800), stroke = 0.05f, loop = a("letter_pulse"))) })
        add(TextTemplate("title_fashion", cx1, 4000) { es -> listOf(
            t(s(es, "ESTILO", "STYLE"), "playfair_bold", 0.19f, spacing = 0.28f, inA = a("curtain_v", 800), outA = a("curtain_v", 500)),
            box(0.5f, 0.002f, W, y = 0.12f, inA = a("curtain", 600), delay = 500)) })
        add(TextTemplate("title_lofi", cx1, 4000) { es -> listOf(
            t(s(es, "tardes lentas", "slow afternoons"), "caveat", 0.115f, 0xFFFFE9C9.toInt(), inA = a("letter_random", 1300), loop = a("drift"), shadow = 0.06f)) })
        add(TextTemplate("title_xmas", cx1, 4000) { es -> listOf(
            t(s(es, "Feliz Navidad", "Merry Christmas"), "lobster", 0.14f, 0xFFE8323C.toInt(), inA = a("letter_fade", 1100), stroke = 0.05f, strokeColor = W, loop = a("sway"), shadow = 0.06f)) })
        add(TextTemplate("title_baby", cx1, 4000) { es -> listOf(
            t(s(es, "Hola, mundo", "Hello, world"), "pacifico", 0.125f, 0xFFFFC1D6.toInt(), inA = a("pop", 600), loop = a("float"), shadow = 0.08f)) })

        // ---------------- Segunda tanda: cierres ----------------
        add(TextTemplate("outro_soft", cx3, 4000) { es -> listOf(
            t(s(es, "hasta siempre", "until next time"), "playfair", 0.09f, italic = true, inA = a("blur", 1200), outA = a("fade", 900))) })
        add(TextTemplate("outro_love", cx3, 4000) { es -> listOf(
            t(s(es, "con amor", "with love"), "dancing_script", 0.14f, PNK, inA = a("letter_fade", 1100), loop = a("heartbeat"))) })
        add(TextTemplate("outro_game_over", cx3, 4000) { _ -> listOf(
            t("GAME OVER", "press_start", 0.08f, RED, inA = a("letter_zoom", 800), loop = a("blink"))) })
        add(TextTemplate("outro_party", cx3) { es -> listOf(
            t(s(es, "¡QUÉ NOCHE!", "WHAT A NIGHT!"), "bangers", 0.15f, gradient = listOf(YEL, PNK), inA = a("letter_pop", 700), stroke = 0.08f, loop = a("beat"))) })
        add(TextTemplate("outro_minimal", cx3, 4000) { es -> listOf(
            t(s(es, "gracias", "thank you"), "montserrat", 0.06f, spacing = 0.7f, inA = a("soft_zoom", 1300), outA = a("fade", 900))) })
        add(TextTemplate("outro_stamp", cx3) { es -> listOf(
            t(s(es, "FIN DEL VIAJE", "END OF THE TRIP"), "archivo_black", 0.08f, K, bg = YEL, inA = a("slam", 400), rot = -6f, bgRadius = 0.06f)) })
        add(TextTemplate("outro_rewind", cx3, 3500) { es -> listOf(
            t(s(es, "◀◀ REBOBINAR", "◀◀ REWIND"), "space_mono_bold", 0.06f, inA = a("glitch_in", 500), loop = a("blink"))) })
        add(TextTemplate("outro_xmas", cx3, 4000) { es -> listOf(
            t(s(es, "Felices fiestas", "Happy holidays"), "lobster", 0.13f, GLD, inA = a("letter_fade", 1000), loop = a("sway"), shadow = 0.06f)) })
        add(TextTemplate("outro_fin_cinema", cx3, 4500) { es -> listOf(
            box(0.3f, 0.003f, GLD, y = -0.09f, inA = a("curtain", 900)),
            t(s(es, "FIN", "FIN"), "bebas_neue", 0.17f, spacing = 0.5f, inA = a("zoom_blur", 1200), outA = a("fade", 1000)),
            box(0.3f, 0.003f, GLD, y = 0.09f, inA = a("curtain", 900), delay = 150)) })
        add(TextTemplate("outro_wave", cx3) { es -> listOf(
            t(s(es, "¡Nos vemos!", "See ya!"), "poppins_bold", 0.12f, CYA, inA = a("letter_drop", 800), loop = a("wave"), shadow = 0.08f)) })
        // ---------------- Tendencia (estilo vídeos cortos) ----------------
        val cTrend = R.string.tcat_trend
        add(TextTemplate("trend_pov", cTrend, 3500, y = -0.3f) { es -> listOf(
            t(s(es, "POV: tu mejor día", "POV: your best day"), "poppins_bold", 0.06f, inA = a("pop", 400), stroke = 0.12f)) })
        add(TextTemplate("trend_caption", cTrend, 4000, y = 0.26f) { es -> listOf(
            t(s(es, "esto es lo que pasó", "here is what happened"), "montserrat_black", 0.048f, bg = DIM, inA = a("slide_up", 350), bgRadius = 0.3f, bgPad = 0.4f)) })
        add(TextTemplate("trend_wait", cTrend, 3500, y = -0.32f) { es -> listOf(
            t(s(es, "ESPERA AL FINAL 👀", "WAIT FOR THE END 👀"), "bangers", 0.085f, YEL, inA = a("pop", 400), stroke = 0.1f, loop = a("wobble"))) })
        add(TextTemplate("trend_storytime", cTrend, 3500) { es -> listOf(
            t("STORYTIME", "luckiest_guy", 0.13f, gradient = listOf(PNK, ORG), inA = a("slam", 450), stroke = 0.09f, rot = -4f)) })
        add(TextTemplate("trend_noway", cTrend, 3000) { es -> listOf(
            t(s(es, "¡NO PUEDE SER!", "NO WAY!!"), "anton", 0.12f, inA = a("glitch_in", 500), loop = a("glitch_loop"), shadow = 0.12f).copy(shadowColor = CYA, shadowDx = 0.02f, shadowDy = 0f)) })
        add(TextTemplate("trend_day", cTrend, 4000, y = -0.32f) { es -> listOf(
            t(s(es, "un día conmigo", "a day with me"), "permanent_marker", 0.075f, inA = a("typewriter", 1100), loop = a("float"), shadow = 0.05f)) })
        add(TextTemplate("trend_y2k", cTrend, 4000) { es -> listOf(
            t("y2k ✦", "righteous", 0.16f, gradient = listOf(CYA, PUR, PNK), inA = a("zoom_in", 500), loop = a("jelly_loop"), stroke = 0.05f, strokeColor = W)) })
        add(TextTemplate("trend_aesthetic", cTrend, 4500) { es -> listOf(
            t("✦", "montserrat", 0.05f, y = -0.07f, inA = a("fade", 700), loop = a("breathe")),
            t(s(es, "momentos bonitos", "pretty moments"), "montserrat", 0.04f, spacing = 0.6f, delay = 300, inA = a("soft_zoom", 1100))) })
        add(TextTemplate("trend_rate", cTrend, 3000, x = 0.2f, y = -0.34f) { _ -> listOf(
            t("10/10 ⭐", "luckiest_guy", 0.09f, YEL, inA = a("pop", 450), stroke = 0.1f, rot = 8f, loop = a("tada"))) })

        // ---------------- Cuquis ----------------
        val cCute = R.string.tcat_cute
        add(TextTemplate("cute_hello", cCute, 3500) { es -> listOf(
            t(s(es, "¡Hey!", "Heyyy!"), "chewy", 0.2f, PNK, inA = a("bounce", 600), stroke = 0.09f, strokeColor = W, loop = a("hop"))) })
        add(TextTemplate("cute_bff", cCute, 4000) { es -> listOf(
            t(s(es, "mejores amigos 🤝", "best friends 🤝"), "pacifico", 0.1f, gradient = listOf(PNK, PUR), inA = a("letter_pop", 800), loop = a("float"))) })
        add(TextTemplate("cute_sparkle", cCute, 3500) { es -> listOf(
            t(s(es, "✨ brillante ✨", "✨ sparkly ✨"), "chewy", 0.12f, YEL, inA = a("pop", 500), stroke = 0.08f, strokeColor = PUR, loop = a("breathe"))) })
        add(TextTemplate("cute_rainbow", cCute, 4000) { es -> listOf(
            t(s(es, "ARCOÍRIS", "RAINBOW"), "luckiest_guy", 0.15f, gradient = listOf(RED, ORG, YEL, GRN, BLU, PUR), inA = a("letter_drop", 800), stroke = 0.07f, strokeColor = W, loop = a("wave"))) })
        add(TextTemplate("cute_bubble", cCute, 4000, y = 0.28f) { es -> listOf(
            t(s(es, "me encanta 💗", "love it 💗"), "poppins_bold", 0.055f, 0xFF7A2E5C.toInt(), bg = 0xFFFFC1E3.toInt(), inA = a("pop", 450), loop = a("float"), bgRadius = 0.5f, bgPad = 0.5f)) })
        add(TextTemplate("cute_diary", cCute, 5000, y = -0.3f) { es -> listOf(
            t(s(es, "querido diario...", "dear diary..."), "caveat", 0.1f, inA = a("typewriter", 1400), shadow = 0.05f)) })
        add(TextTemplate("cute_birthday", cCute, 4000) { es -> listOf(
            t(s(es, "¡FELIZ CUMPLE!", "HAPPY BIRTHDAY!"), "luckiest_guy", 0.1f, gradient = listOf(PNK, YEL), inA = a("letter_bounce", 900), stroke = 0.08f, strokeColor = W, loop = a("beat"))) })
        add(TextTemplate("trend_fyp", cTrend, 3500, y = 0.3f) { es -> listOf(
            t(s(es, "para ti ✨", "for you ✨"), "poppins_bold", 0.05f, W, bg = PNK, inA = a("slide_up", 400), loop = a("pulse"), bgRadius = 0.5f, bgPad = 0.5f)) })
        add(TextTemplate("trend_plot", cTrend, 3000) { es -> listOf(
            t(s(es, "GIRO DE GUION", "PLOT TWIST"), "bangers", 0.12f, CYA, inA = a("slam", 400), stroke = 0.1f, loop = a("shake"), rot = -4f)) })
        add(TextTemplate("trend_fit", cTrend, 3500, y = -0.3f) { es -> listOf(
            t(s(es, "outfit del día ✔", "fit check ✔"), "montserrat_black", 0.06f, inA = a("letter_pop", 700), stroke = 0.1f)) })
        add(TextTemplate("trend_slay", cTrend, 3500) { _ -> listOf(
            t("SLAY 💅", "anton", 0.17f, gradient = listOf(PNK, PUR), inA = a("zoom_in", 450), loop = a("jelly_loop"), stroke = 0.04f, strokeColor = W)) })
        add(TextTemplate("trend_vibes", cTrend, 4000, y = 0.28f) { _ -> listOf(
            t("good vibes only", "caveat", 0.11f, 0xFFFFC1D6.toInt(), inA = a("letter_fade", 900), loop = a("float"), shadow = 0.06f)) })
        add(TextTemplate("trend_main", cTrend, 4000) { es -> listOf(
            t(s(es, "protagonista total", "main character energy"), "poppins_bold", 0.05f, K, bg = YEL, inA = a("slide_left", 400), bgRadius = 0.2f, bgPad = 0.4f, rot = -3f)) })
        add(TextTemplate("trend_cap", cTrend, 3000, x = 0.18f, y = -0.34f) { es -> listOf(
            t(s(es, "EN SERIO 🧢", "NO CAP 🧢"), "luckiest_guy", 0.08f, W, inA = a("pop", 400), stroke = 0.1f, rot = 8f, loop = a("wobble"))) })
        add(TextTemplate("trend_tbt", cTrend, 4000, y = -0.32f) { es -> listOf(
            t(s(es, "recuerdo del pasado", "throwback"), "space_mono_bold", 0.05f, inA = a("typewriter", 1200))) })
        add(TextTemplate("trend_wait2", cTrend, 3500) { es -> listOf(
            t(s(es, "espera...", "wait for it..."), "montserrat_black", 0.08f, inA = a("fade", 600), loop = a("blink"), spacing = 0.1f)) })
        add(TextTemplate("trend_rank", cTrend, 3500) { _ -> listOf(
            t("TOP 1", "anton", 0.2f, GLD, inA = a("bounce", 600), loop = a("tada"), stroke = 0.05f)) })
        add(TextTemplate("trend_grwm", cTrend, 4000, y = -0.3f) { es -> listOf(
            t("GRWM", "montserrat_black", 0.12f, inA = a("letter_rise", 700), spacing = 0.2f),
            t(s(es, "prepárate conmigo", "get ready with me"), "poppins", 0.035f, y = 0.07f, delay = 400, inA = a("fade", 500))) })
        add(TextTemplate("trend_asmr", cTrend, 3500) { _ -> listOf(
            t("ASMR 🎧", "righteous", 0.14f, CYA, inA = a("flicker", 700), shadow = 0.2f, loop = a("neon_flicker")).copy(shadowColor = CYA, shadowDy = 0f)) })
        add(TextTemplate("trend_nofilter", cTrend, 3500, y = 0.3f) { es -> listOf(
            t(s(es, "sin filtro", "no filter"), "lobster", 0.1f, inA = a("zoom_in", 500), shadow = 0.08f)) })
        add(TextTemplate("trend_lol", cTrend, 3000, y = -0.3f) { es -> listOf(
            t(s(es, "JAJAJA 😂", "LMAO 😂"), "bangers", 0.12f, YEL, inA = a("pop", 400), stroke = 0.1f, loop = a("wobble"))) })
        add(TextTemplate("trend_tuto", cTrend, 4000, y = 0.3f) { es -> listOf(
            t(s(es, "tutorial rápido", "quick tutorial"), "poppins_bold", 0.045f, bg = DIM, inA = a("slide_up", 400), bgRadius = 0.3f, bgPad = 0.4f)) })
        add(TextTemplate("cute_pastel", cCute, 4000) { es -> listOf(
            t(s(es, "sueños de colores", "pastel dreams"), "dancing_script", 0.12f, gradient = listOf(0xFFFFB6D5.toInt(), 0xFFB5E0FF.toInt()), inA = a("letter_fade", 1000), loop = a("float"), shadow = 0.05f)) })
        add(TextTemplate("cute_star", cCute, 3500) { es -> listOf(
            t(s(es, "⭐ estrella ⭐", "⭐ star ⭐"), "chewy", 0.11f, YEL, inA = a("pop", 450), stroke = 0.08f, strokeColor = ORG, loop = a("tada"))) })
        add(TextTemplate("cute_flower", cCute, 4000) { es -> listOf(
            t(s(es, "🌸 flores 🌸", "🌸 flowers 🌸"), "dancing_script", 0.14f, 0xFFFF9EC7.toInt(), inA = a("letter_fade", 900), loop = a("sway"), shadow = 0.06f)) })
        add(TextTemplate("cute_sleepy", cCute, 4000) { _ -> listOf(
            t("zzz... 💤", "chewy", 0.13f, 0xFFB9D4FF.toInt(), inA = a("fade", 700), loop = a("breathe"), stroke = 0.06f, strokeColor = PUR)) })
        add(TextTemplate("cute_love", cCute, 4000) { es -> listOf(
            t(s(es, "te quiero 💗", "love you 💗"), "pacifico", 0.11f, 0xFFFF5C8A.toInt(), inA = a("letter_pop", 800), loop = a("heartbeat"), shadow = 0.07f)) })
        add(TextTemplate("cute_party", cCute, 4000) { es -> listOf(
            t(s(es, "¡FIESTA! 🎈", "PARTY! 🎈"), "luckiest_guy", 0.13f, gradient = listOf(RED, ORG, YEL, GRN, BLU, PUR), inA = a("letter_bounce", 900), stroke = 0.07f, strokeColor = W, loop = a("beat"))) })
        add(TextTemplate("cute_magic", cCute, 4000) { es -> listOf(
            t(s(es, "✨ magia ✨", "✨ magic ✨"), "righteous", 0.14f, gradient = listOf(PUR, CYA), inA = a("letter_pop", 800), loop = a("tada"), stroke = 0.04f, strokeColor = W)) })
        add(TextTemplate("cute_sunny", cCute, 4000, y = -0.3f) { es -> listOf(
            t(s(es, "☀️ buen día", "☀️ good day"), "lobster", 0.11f, YEL, inA = a("slide_down", 500), loop = a("float"), shadow = 0.08f)) })
        add(TextTemplate("trend_glow", cTrend, 3500) { _ -> listOf(
            t("GLOW UP ✨", "luckiest_guy", 0.12f, gradient = listOf(PNK, YEL), inA = a("zoom_in", 450), stroke = 0.07f, strokeColor = W, loop = a("tada"))) })
        add(TextTemplate("trend_mood", cTrend, 3500, y = -0.3f) { es -> listOf(
            t(s(es, "mood: feliz 😊", "mood: happy 😊"), "poppins_bold", 0.05f, K, bg = W, inA = a("pop", 400), bgRadius = 0.5f, bgPad = 0.5f, loop = a("float"))) })
        add(TextTemplate("trend_secret", cTrend, 3000) { es -> listOf(
            t(s(es, "secreto 🤫", "secret 🤫"), "chewy", 0.13f, W, inA = a("pop", 400), stroke = 0.09f, loop = a("shake"))) })
        add(TextTemplate("trend_hot", cTrend, 3500) { _ -> listOf(
            t("HOT 🔥", "anton", 0.18f, gradient = listOf(RED, ORG, YEL), inA = a("flicker", 600), loop = a("neon_flicker"), stroke = 0.03f, strokeColor = W)) })
        add(TextTemplate("trend_chill", cTrend, 4000) { _ -> listOf(
            t("chill 😎", "lobster", 0.14f, CYA, inA = a("slide_up", 500), loop = a("float"), shadow = 0.08f)) })
        add(TextTemplate("trend_boss", cTrend, 3500) { _ -> listOf(
            t("BOSS MODE", "bebas_neue", 0.15f, GLD, inA = a("slide_up", 450), loop = a("pulse"), spacing = 0.15f, stroke = 0.04f)) })
        add(TextTemplate("trend_unbox", cTrend, 4000, y = -0.3f) { _ -> listOf(
            t("unboxing 📦", "montserrat_black", 0.06f, bg = DIM, inA = a("slide_down", 400), bgRadius = 0.3f, bgPad = 0.45f)) })
        add(TextTemplate("trend_lets", cTrend, 3000) { es -> listOf(
            t(s(es, "¡VAMOS!", "LET'S GO!"), "bangers", 0.15f, YEL, inA = a("slam", 400), stroke = 0.1f, loop = a("beat"))) })
        add(TextTemplate("trend_real", cTrend, 3000, x = -0.18f, y = -0.34f) { _ -> listOf(
            t("real 💯", "righteous", 0.09f, W, inA = a("pop", 400), stroke = 0.08f, rot = -8f, loop = a("tada"))) })
        add(TextTemplate("trend_day1", cTrend, 3500) { es -> listOf(
            t(s(es, "DÍA 1", "DAY 1"), "anton", 0.2f, inA = a("letter_pop", 600), spacing = 0.05f, shadow = 0.1f)) })
        add(TextTemplate("cute_holi2", cCute, 3500) { es -> listOf(
            t(s(es, "¡holaaa! 💕", "heyyy! 💕"), "chewy", 0.13f, PNK, inA = a("bounce", 600), stroke = 0.09f, strokeColor = W, loop = a("hop"))) })
        add(TextTemplate("cute_candy", cCute, 3500) { es -> listOf(
            t(s(es, "🍬 dulce", "🍬 candy"), "bangers", 0.15f, gradient = listOf(PNK, CYA), inA = a("pop", 400), stroke = 0.09f, strokeColor = W, loop = a("wobble"))) })
        add(TextTemplate("cute_rainbow2", cCute, 4000) { es -> listOf(
            t(s(es, "🌈 colores", "🌈 colors"), "pacifico", 0.11f, gradient = listOf(RED, ORG, YEL, GRN, BLU, PUR), inA = a("letter_pop", 800), loop = a("float"), shadow = 0.05f)) })
        add(TextTemplate("cute_pizza", cCute, 3500) { es -> listOf(
            t(s(es, "🍕 hora de pizza", "🍕 pizza time"), "luckiest_guy", 0.09f, ORG, inA = a("pop", 450), stroke = 0.08f, strokeColor = W, loop = a("wobble"))) })
        add(TextTemplate("cute_moon", cCute, 4000) { es -> listOf(
            t(s(es, "🌙 buenas noches", "🌙 good night"), "pacifico", 0.09f, 0xFFD6C8FF.toInt(), inA = a("fade", 800), loop = a("float"), shadow = 0.1f)) })
        add(TextTemplate("cute_butterfly", cCute, 4000) { es -> listOf(
            t(s(es, "🦋 mariposa", "🦋 butterfly"), "dancing_script", 0.15f, CYA, inA = a("letter_fade", 900), loop = a("sway"), shadow = 0.06f)) })
        add(TextTemplate("cute_cake", cCute, 3500) { es -> listOf(
            t(s(es, "🎂 ¡yupi!", "🎂 yay!"), "bangers", 0.15f, YEL, inA = a("pop", 400), stroke = 0.1f, strokeColor = PNK, loop = a("tada"))) })
        add(TextTemplate("cute_smile", cCute, 3500) { es -> listOf(
            t(s(es, "😊 sonríe", "😊 smile"), "chewy", 0.13f, YEL, inA = a("pop", 450), stroke = 0.08f, strokeColor = ORG, loop = a("breathe"))) })
    }

    fun get(id: String): TextTemplate? = all.firstOrNull { it.id == id }

    // "Hot" y "Divertidas" van primero: son las más nuevas.
    val categories: List<Int> get() = all.map { it.catRes }.distinct().sortedBy { if (it == R.string.tcat_trend || it == R.string.tcat_cute) 0 else 1 }
}
