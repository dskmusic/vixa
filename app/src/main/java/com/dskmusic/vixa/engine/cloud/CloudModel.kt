package com.dskmusic.vixa.engine.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable enum class RotMode { HORIZONTAL, VERTICAL, MIXED, RANDOM, ANGLES }
@Serializable enum class ColorMode { SINGLE, PALETTE, RANDOM, GRADIENT, BY_WEIGHT }
@Serializable enum class BgKind { NONE, SOLID, GRADIENT, IMAGE, PATTERN }
@Serializable enum class PatternKind { STRIPES, DOTS, GRID, CHECKER }
@Serializable enum class BorderStyle { SOLID, DOTTED, DASHED }
@Serializable enum class WordOrder { BY_WEIGHT, RANDOM }
@Serializable enum class ImgFit { CROP, FIT, STRETCH }

/** Palabra con su peso (importancia). */
data class Wd(val text: String, val weight: Float)

/** Zona donde no se escriben palabras (elipse o rectangulo, en fracciones del lienzo). */
@Serializable
data class Zone(
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val rx: Float = 0.15f,
    val ry: Float = 0.15f,
    val ellipse: Boolean = true,
)

@Serializable
data class TextStyleSpec(
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Espaciado entre letras, en em. */
    val letterSpacing: Float = 0f,
    val shadow: Boolean = false,
    val shadowColor: Int = 0x99000000.toInt(),
    /** Los tamanos de sombra, contorno y brillo son % del tamano del texto. */
    val shadowBlur: Float = 6f,
    val shadowDx: Float = 4f,
    val shadowDy: Float = 4f,
    val outline: Boolean = false,
    val outlineColor: Int = 0xFF000000.toInt(),
    val outlineWidth: Float = 6f,
    val glow: Boolean = false,
    val glowColor: Int = 0xFFFFFFFF.toInt(),
    val glowRadius: Float = 25f,
    val gradient: Boolean = false,
    val gradColor2: Int = 0xFFFFFFFF.toInt(),
    val gradAngle: Float = 90f,
)

@Serializable
data class BackgroundSpec(
    val kind: BgKind = BgKind.NONE,
    val color: Int = 0xFFFFFFFF.toInt(),
    val color2: Int = 0xFF1E1E2E.toInt(),
    val gradColors: List<Int> = listOf(0xFF42A5F5.toInt(), 0xFF7E57C2.toInt()),
    val radial: Boolean = false,
    val angle: Float = 90f,
    val imagePath: String? = null,
    val fit: ImgFit = ImgFit.CROP,
    /** % del lado menor. */
    val imgBlur: Float = 0f,
    val pattern: PatternKind = PatternKind.STRIPES,
    /** % del lado menor. */
    val patternSize: Float = 6f,
    val opacity: Float = 1f,
)

@Serializable
data class ShapeFx(
    val scale: Float = 0.92f,
    val rotation: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val posX: Float = 0f,
    val posY: Float = 0f,
    /** Esquinas redondeadas, 0..50. */
    val corner: Float = 0f,
    /** Suavizado, erosion y dilatacion de la mascara: % del lado menor. */
    val smooth: Float = 0f,
    val erode: Float = 0f,
    val dilate: Float = 0f,
    val borderOn: Boolean = false,
    val borderWidth: Float = 1.2f,
    val borderColor: Int = 0xFF000000.toInt(),
    val borderColor2: Int = 0xFFFFFFFF.toInt(),
    val borderGradient: Boolean = false,
    val borderStyle: BorderStyle = BorderStyle.SOLID,
    val borderOpacity: Float = 1f,
    val doubleBorder: Boolean = false,
    val shadowOn: Boolean = false,
    val shadowColor: Int = 0x88000000.toInt(),
    val shadowBlur: Float = 2f,
    val shadowDx: Float = 1.5f,
    val shadowDy: Float = 1.5f,
    val glowOn: Boolean = false,
    val glowColor: Int = 0xFFFFEB3B.toInt(),
    val glowRadius: Float = 3f,
    val bevelOn: Boolean = false,
    val bevelSize: Float = 0.8f,
    val bevelStrength: Float = 0.6f,
    val blur: Float = 0f,
    val opacity: Float = 1f,
)

@Serializable
data class WordArtConfig(
    /** Ancho / alto del lienzo. */
    val aspect: Float = 1f,
    /** "geo:xx", "ph:xx", "glyph:A", "text" o "img:<id>". */
    val shapeId: String = "geo:heart",
    val shapeText: String = "PIXA",
    val invertMask: Boolean = false,
    /** false = palabras dentro de la forma; true = palabras fuera, forma libre. */
    val inverted: Boolean = false,
    val wordsText: String = "",
    val mainWord: String = "",
    /** Tamano de las palabras, % del lado menor. */
    val minSize: Float = 2.4f,
    val maxSize: Float = 9f,
    val density: Float = 70f,
    val spacing: Float = 0.4f,
    val margin: Float = 1f,
    val order: WordOrder = WordOrder.BY_WEIGHT,
    val repeatWords: Boolean = true,
    val rotMode: RotMode = RotMode.MIXED,
    val rotAngles: String = "0,90,-45,45",
    val colorMode: ColorMode = ColorMode.PALETTE,
    val palette: List<Int> = PALETTES[0].second,
    val color1: Int = 0xFFE91E63.toInt(),
    val color2: Int = 0xFF3F51B5.toInt(),
    val gradAngle: Float = 45f,
    /** 0 = fuente fija, 1 = aleatoria del grupo. */
    val fontMode: Int = 0,
    val fontPool: Int = 0,
    val seed: Long = 1L,
    val zones: List<Zone> = emptyList(),
    val textStyle: TextStyleSpec = TextStyleSpec(),
    /** Fondo de todo el lienzo, detras de la forma y de las palabras. Transparente por defecto. */
    val bgCanvas: BackgroundSpec = BackgroundSpec(),
    val bgWords: BackgroundSpec = BackgroundSpec(),
    val bgOther: BackgroundSpec = BackgroundSpec(),
    val fx: ShapeFx = ShapeFx(),
) {
    companion object {
        val PALETTES: List<Pair<String, List<Int>>> = listOf(
            "vivid" to listOf(0xFFE91E63, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50, 0xFF2196F3, 0xFF9C27B0).map { it.toInt() },
            "love" to listOf(0xFFD81B60, 0xFFE91E63, 0xFFF06292, 0xFFFF5252, 0xFFC2185B, 0xFFFF8A80).map { it.toInt() },
            "ocean" to listOf(0xFF0D47A1, 0xFF1976D2, 0xFF29B6F6, 0xFF4DD0E1, 0xFF00ACC1, 0xFF80DEEA).map { it.toInt() },
            "forest" to listOf(0xFF1B5E20, 0xFF388E3C, 0xFF66BB6A, 0xFF9CCC65, 0xFF827717, 0xFFC0CA33).map { it.toInt() },
            "sunset" to listOf(0xFFFF6F00, 0xFFFF8F00, 0xFFFFB300, 0xFFE64A19, 0xFFD84315, 0xFFAD1457).map { it.toInt() },
            "gold" to listOf(0xFFFFD54F, 0xFFFFC107, 0xFFFFB300, 0xFFFFE082, 0xFFFFCA28, 0xFFFFF8E1).map { it.toInt() },
            "pastel" to listOf(0xFFF8BBD0, 0xFFB3E5FC, 0xFFC8E6C9, 0xFFFFF9C4, 0xFFD1C4E9, 0xFFFFCCBC).map { it.toInt() },
            "mono" to listOf(0xFF000000, 0xFF424242, 0xFF757575, 0xFF212121, 0xFF616161).map { it.toInt() },
            "neon" to listOf(0xFF00E5FF, 0xFFFF1744, 0xFF76FF03, 0xFFFFEA00, 0xFFD500F9, 0xFFFF9100).map { it.toInt() },
        )
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    }

    fun parsedWords(): List<Wd> = parseWords(wordsText)

    /** Campos que cambian la colocacion de las palabras; el resto solo cambia el dibujo. */
    fun layoutKey(extra: Any?): List<Any?> = listOf(
        aspect, shapeId, shapeText, invertMask, inverted, wordsText, mainWord, minSize, maxSize,
        density, spacing, margin, order, repeatWords, rotMode, rotAngles, fontMode, fontPool, seed, zones,
        fx.scale, fx.rotation, fx.flipH, fx.flipV, fx.posX, fx.posY, fx.corner, fx.smooth, fx.erode, fx.dilate,
        textStyle.bold, textStyle.italic, textStyle.letterSpacing, textStyle.outline, textStyle.outlineWidth,
        extra,
    )
}

/**
 * Una palabra por linea, con peso opcional tras ":" ("amor:5"). Tambien se aceptan
 * comas y puntos y coma como separador, para poder pegar una lista de golpe.
 */
fun parseWords(raw: String): List<Wd> {
    val out = ArrayList<Wd>()
    raw.split('\n', ';', ',').forEach { part ->
        val line = part.trim()
        if (line.isEmpty()) return@forEach
        var text = line
        var weight = 1f
        val idx = line.lastIndexOf(':')
        if (idx > 0) {
            line.substring(idx + 1).trim().toFloatOrNull()?.let {
                weight = it.coerceIn(0.1f, 100f)
                text = line.substring(0, idx).trim()
            }
        }
        if (text.isNotEmpty()) out += Wd(text, weight)
    }
    return out
}

/** Atribuciones exigidas por las licencias de las colecciones de formas. */
object WordArtCredits {
    val lines = listOf(
        "Phosphor Icons © Phosphor Icons — MIT License (phosphoricons.com)",
        "Tabler Icons © Paweł Kuna — MIT License (tabler.io/icons)",
        "Lucide © Lucide Contributors — ISC License (lucide.dev)",
        "Material Symbols © Google — Apache License 2.0 (fonts.google.com/icons)",
    )
}
