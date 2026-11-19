package com.dskmusic.vixa.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Versión del formato de project.json. Súbela y añade un paso en ProjectStore.MIGRATIONS al cambiar el esquema. */
const val FORMAT_VERSION = 4

/** Microsegundos por segundo. Todos los tiempos del modelo son Long en microsegundos. */
const val US = 1_000_000L

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

@Serializable
enum class TrackType { MAIN, OVERLAY, AUDIO, TEXT, STICKER, ADJUST }

@Serializable
enum class ClipKind { VIDEO, IMAGE, COLOR, AUDIO, TEXT, STICKER, ADJUST }

@Serializable
enum class FitMode { FIT, FILL, STRETCH }

@Serializable
enum class BlendMode { NORMAL, SCREEN, ADD, MULTIPLY, LIGHTEN, DARKEN }

@Serializable
enum class MaskType { NONE, LINEAR, CIRCLE, RECT }

@Serializable
enum class ZoneType { NONE, BLUR, MOSAIC }

@Serializable
enum class BgType { COLOR, GRADIENT, BLUR }

@Serializable
enum class LayerKind { TEXT, RECT, OVAL, LINE }

@Serializable
enum class StickerType { EMOJI, SHAPE, IMAGE, ANIMATED }

@Serializable
enum class Easing {
    LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, CUBIC_IN, CUBIC_OUT, BACK_OUT, BOUNCE_OUT, ELASTIC_OUT, HOLD;

    fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> x
            EASE_IN -> x * x
            EASE_OUT -> 1f - (1f - x) * (1f - x)
            EASE_IN_OUT -> if (x < 0.5f) 2f * x * x else 1f - 2f * (1f - x) * (1f - x)
            CUBIC_IN -> x * x * x
            CUBIC_OUT -> 1f - (1f - x) * (1f - x) * (1f - x)
            BACK_OUT -> {
                val c1 = 1.70158f
                val c3 = c1 + 1f
                val y = x - 1f
                1f + c3 * y * y * y + c1 * y * y
            }
            BOUNCE_OUT -> {
                val n1 = 7.5625f
                val d1 = 2.75f
                when {
                    x < 1f / d1 -> n1 * x * x
                    x < 2f / d1 -> { val y = x - 1.5f / d1; n1 * y * y + 0.75f }
                    x < 2.5f / d1 -> { val y = x - 2.25f / d1; n1 * y * y + 0.9375f }
                    else -> { val y = x - 2.625f / d1; n1 * y * y + 0.984375f }
                }
            }
            ELASTIC_OUT -> if (x == 0f || x == 1f) x else {
                val c4 = (2.0 * Math.PI / 3.0).toFloat()
                (Math.pow(2.0, -10.0 * x).toFloat() * kotlin.math.sin((x * 10f - 0.75f) * c4)) + 1f
            }
            HOLD -> if (x >= 1f) 1f else 0f
        }
    }
}

/** Fotograma clave: [tUs] es relativo al inicio del clip en la línea de tiempo. */
@Serializable
data class Keyframe(val tUs: Long, val value: Float, val easing: Easing = Easing.EASE_IN_OUT)

/** Medio de origen. width/height ya tienen aplicada la rotación de los metadatos. */
@Serializable
data class MediaRef(
    val path: String,
    val durationUs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val hasVideo: Boolean = true,
    val hasAudio: Boolean = false,
    val fps: Float = 30f,
    val isHdr: Boolean = false,
    val isImage: Boolean = false,
    val origin: String = "",
    val title: String = "",
)

/** Punto de una curva de velocidad: [pos] 0..1 sobre el tramo de origen, [speed] en múltiplos (0,1..10). */
@Serializable
data class SpeedPoint(val pos: Float, val speed: Float)

@Serializable
data class Transform(
    /** Desplazamiento del centro en fracción del lienzo (0 = centrado, 0.5 = medio lienzo a la derecha/abajo). */
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    /** Estirado independiente por eje (se multiplica por [scale]); lo cambian los tiradores intermedios de la vista previa. */
    val sx: Float = 1f,
    val sy: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    /** Recorte del encuadre en fracciones del medio. */
    val cropL: Float = 0f,
    val cropT: Float = 0f,
    val cropR: Float = 0f,
    val cropB: Float = 0f,
    val fit: FitMode = FitMode.FIT,
    val blend: BlendMode = BlendMode.NORMAL,
)

/** Corrección de color. Todos los valores van en -1..1 (0 = neutro) salvo los indicados. */
@Serializable
data class ColorAdjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val exposure: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val vibrance: Float = 0f,
    val fade: Float = 0f,
    val hue: Float = 0f,
    val lightness: Float = 0f,
    /** 0..1 */
    val sharpen: Float = 0f,
    /** 0..1 */
    val vignette: Float = 0f,
    /** 0..1 */
    val grain: Float = 0f,
    /** Curvas: 5 valores de salida (0..1) para entradas 0, .25, .5, .75 y 1. Vacía = identidad. */
    val curve: List<Float> = emptyList(),
    val curveR: List<Float> = emptyList(),
    val curveG: List<Float> = emptyList(),
    val curveB: List<Float> = emptyList(),
) {
    val hasCurves: Boolean get() = curve.size == 5 || curveR.size == 5 || curveG.size == 5 || curveB.size == 5

    fun isNeutral(): Boolean = this == ColorAdjust()
}

@Serializable
data class EffectInstance(
    val id: String = newId(),
    val defId: String,
    val params: Map<String, Float> = emptyMap(),
    val enabled: Boolean = true,
)

@Serializable
data class ChromaKey(
    val enabled: Boolean = false,
    val color: Int = 0xFF00FF00.toInt(),
    val similarity: Float = 0.35f,
    val smoothness: Float = 0.1f,
    val spill: Float = 0.5f,
)

/** Máscara en coordenadas del medio: centro 0..1, tamaño en fracción, giro en grados. */
@Serializable
data class Mask(
    val type: MaskType = MaskType.NONE,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val w: Float = 0.6f,
    val h: Float = 0.6f,
    val rotation: Float = 0f,
    val feather: Float = 0.05f,
    val invert: Boolean = false,
)

/** Zona desenfocada o en mosaico (p. ej. para tapar una cara o matrícula). */
@Serializable
data class Zone(
    val type: ZoneType = ZoneType.NONE,
    val ellipse: Boolean = false,
    val cx: Float = 0.5f,
    val cy: Float = 0.5f,
    val w: Float = 0.3f,
    val h: Float = 0.3f,
    val strength: Float = 0.5f,
)

/** Zonas por clip: la primera es [Clip.zone] (proyectos antiguos) y el resto [Clip.zones]. Tope por coste del shader. */
const val MAX_ZONES = 4

/** Prefijo de las claves animables de la zona [i]: "zone" para la primera (compatible), "zone1", "zone2"... para el resto. */
fun zoneKey(i: Int): String = if (i == 0) "zone" else "zone$i"

private val ZONE_KEY = Regex("""^zone(\d*)\.""")

/** Índice de zona de una clave animable ("zone.cx" -> 0, "zone2.w" -> 2) o -1 si no es de zona. */
fun zoneKeyIndex(key: String): Int = ZONE_KEY.find(key)?.groupValues?.get(1)?.let { if (it.isEmpty()) 0 else it.toInt() } ?: -1

val Clip.allZones: List<Zone> get() = listOf(zone) + zones

fun Clip.withZone(i: Int, z: Zone): Clip = if (i == 0) copy(zone = z) else copy(zones = zones.toMutableList().also { it[i - 1] = z })

/** Quita la zona [i] con sus keyframes; las siguientes bajan un puesto. Si era la única, queda una zona vacía. */
fun Clip.removeZone(i: Int): Clip {
    val all = allZones.toMutableList()
    all.removeAt(i)
    if (all.isEmpty()) all.add(Zone())
    val kfs = keyframes.filterKeys { zoneKeyIndex(it) != i }.mapKeys { (k, _) ->
        val j = zoneKeyIndex(k)
        if (j > i) zoneKey(j - 1) + "." + k.substringAfter('.') else k
    }
    return copy(zone = all[0], zones = all.drop(1), keyframes = kfs)
}

@Serializable
data class AnimRef(val id: String, val durationUs: Long = 500_000L)

@Serializable
data class Transition(val id: String, val durationUs: Long = 500_000L)

@Serializable
data class AudioFx(
    /** Ecualizador de 3 bandas, -1..1 (±12 dB). */
    val eqLow: Float = 0f,
    val eqMid: Float = 0f,
    val eqHigh: Float = 0f,
    /** Cambio de tono en semitonos (-12..12). */
    val pitch: Float = 0f,
    /** Id de un preajuste de voz del registro (o vacío). */
    val voice: String = "",
    /** Puerta de ruido 0..1 (0 = desactivada). */
    val denoise: Float = 0f,
    /** Ganancia de normalización calculada (1 = sin cambios). */
    val normalizeGain: Float = 1f,
    /** Este clip baja de volumen automáticamente cuando hay voz. */
    val duck: Boolean = false,
    /** Este clip cuenta como voz para la atenuación automática. */
    val isVoice: Boolean = false,
) {
    fun isNeutral(): Boolean = this == AudioFx()
}

/** Capa de un clip de texto: un cuadro de texto o una forma. Posición relativa al centro del clip. */
@Serializable
data class Layer(
    val id: String = newId(),
    val kind: LayerKind = LayerKind.TEXT,
    val text: String = "",
    val fontId: String = "poppins_bold",
    /** Tamaño de letra en fracción del lado menor del lienzo. */
    val size: Float = 0.07f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val gradient: List<Int> = emptyList(),
    val gradientAngle: Float = 0f,
    val strokeColor: Int = 0xFF000000.toInt(),
    /** Grosor del contorno en fracción del tamaño de letra (0 = sin contorno). */
    val strokeWidth: Float = 0f,
    val shadowColor: Int = 0x99000000.toInt(),
    /** Radio de sombra en fracción del tamaño de letra (0 = sin sombra). */
    val shadowRadius: Float = 0f,
    val shadowDx: Float = 0f,
    val shadowDy: Float = 0.06f,
    val bgColor: Int = 0,
    val bgPadding: Float = 0.3f,
    val bgRadius: Float = 0.2f,
    /** 0 = izquierda, 1 = centro, 2 = derecha. */
    val align: Int = 1,
    val lineSpacing: Float = 1.1f,
    val letterSpacing: Float = 0f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val upper: Boolean = false,
    /** Desplazamiento respecto al centro del clip, en fracción del lienzo. */
    val x: Float = 0f,
    val y: Float = 0f,
    /** Solo formas: tamaño en fracción del lienzo. */
    val w: Float = 0.3f,
    val h: Float = 0.01f,
    val rotation: Float = 0f,
    val cornerRadius: Float = 0f,
    /** Ancho máximo del cuadro de texto en fracción del lienzo. */
    val maxWidth: Float = 0.9f,
    val animIn: AnimRef? = null,
    val animOut: AnimRef? = null,
    val animLoop: AnimRef? = null,
    /** Retardo de entrada de esta capa dentro del clip. */
    val delayUs: Long = 0,
    /** Texto dinámico: "countdown" muestra los segundos restantes del clip. */
    val dynamic: String = "",
)

@Serializable
data class TextSpec(
    val layers: List<Layer> = listOf(Layer(text = "Texto")),
    val templateId: String = "",
    val subtitle: Boolean = false,
    /** Subtítulos automáticos: inicio de cada palabra (separadas por espacios) relativo al clip, para el karaoke. */
    val wordsUs: List<Long> = emptyList(),
)

@Serializable
data class StickerSpec(
    val type: StickerType = StickerType.EMOJI,
    /** Emoji, id de forma vectorial o ruta de archivo. */
    val value: String = "",
    val color: Int = 0xFFFFFFFF.toInt(),
    /** Tamaño base en fracción del lado menor del lienzo. */
    val size: Float = 0.25f,
)

@Serializable
/** Relleno de un clip de color: de 1 a 4 colores ARGB repartidos por igual (el alfa da transparencia), lineal con [angle] o [radial]. */
data class ColorFill(val colors: List<Int> = listOf(0xFF000000.toInt()), val angle: Float = 90f, val radial: Boolean = false)

@Serializable
data class CanvasBg(
    val type: BgType = BgType.COLOR,
    val colors: List<Int> = listOf(0xFF000000.toInt()),
    val angle: Float = 90f,
    val blur: Float = 0.6f,
)

@Serializable
data class Marker(val id: String = newId(), val timeUs: Long, val label: String = "", val color: Int = 0xFFFFC107.toInt())

@Serializable
data class Watermark(
    val enabled: Boolean = false,
    val imagePath: String = "",
    val text: String = "",
    /** Centro en fracción del lienzo (0..1). */
    val x: Float = 0.85f,
    val y: Float = 0.92f,
    val scale: Float = 0.15f,
    val opacity: Float = 0.7f,
)

/**
 * Resultado de IA que sustituye al audio del clip sin tocar el medio original: [path] es un archivo de la
 * caché de IA y, si falta, se regenera con estos mismos datos.
 * [kind]: "denoise" (voz mejorada), "vocals" / "music" (separación) o "tts" (texto a voz; [text] es lo narrado).
 */
@Serializable
data class AiAudioRef(
    val kind: String,
    val model: String,
    /** Intensidad de la reducción de ruido (0..1) o velocidad de la voz sintetizada. */
    val amount: Float = 1f,
    val path: String = "",
    val text: String = "",
    val enabled: Boolean = true,
    /** Texto a voz: hablante dentro del modelo (los modelos multihablante traen varios). */
    val speaker: Int = 0,
)

/**
 * Fondo de personas sin croma. [path] es la máscara precalculada (caché de IA, se regenera si falta) y el resto son
 * ajustes que se leen en vivo: no hace falta repetir el análisis para cambiarlos.
 * [mode]: 0 = desactivado, 1 = fondo transparente (el clip se usa como superposición), 2 = fondo de color, 3 = fondo desenfocado.
 */
@Serializable
data class PersonCut(
    val mode: Int = 1,
    val color: Int = 0xFF000000.toInt(),
    /** Suavizado de los bordes 0..1. */
    val edge: Float = 0.5f,
    /** Intensidad del desenfoque del fondo 0..1. */
    val blur: Float = 0.6f,
    /** Grosor del contorno o resplandor alrededor de la persona 0..1 (0 = sin contorno). */
    val outline: Float = 0f,
    val outlineColor: Int = 0xFFFFFFFF.toInt(),
    val model: String = "",
    val path: String = "",
    val fromUs: Long = 0,
    val toUs: Long = 0,
)

/**
 * Seguimiento de un objeto de un clip de vídeo. Los datos (posición, tamaño y giro por muestra) están en [path], en
 * archivos propios de la app, y si faltan se recalculan con estos mismos parámetros: [fromUs]..[toUs] es el tramo del
 * medio, [startUs] el instante en que se marcó el objeto, ([u], [v]) su posición (0..1) y [size] su tamaño.
 */
@Serializable
data class ObjTrack(
    val id: String = newId(),
    val name: String = "",
    val path: String = "",
    val fromUs: Long = 0,
    val toUs: Long = 0,
    val startUs: Long = 0,
    val u: Float = 0.5f,
    val v: Float = 0.5f,
    val size: Float = 0.1f,
)

@Serializable
data class Clip(
    val id: String = newId(),
    val kind: ClipKind,
    /** Inicio en la línea de tiempo. En la pista principal lo recalcula [normalized]. */
    val startUs: Long = 0,
    /** Duración en la línea de tiempo. Para vídeo/audio la recalcula [normalized] a partir del tramo y la velocidad. */
    val durationUs: Long = 3 * US,
    val media: MediaRef? = null,
    /** Tramo usado del medio de origen (tiempo de origen). */
    val srcInUs: Long = 0,
    val srcOutUs: Long = 0,
    val speed: Float = 1f,
    val speedCurve: List<SpeedPoint> = emptyList(),
    val keepPitch: Boolean = true,
    /** Reproducción inversa: se usa el archivo prerenderizado en caché. */
    val reversed: Boolean = false,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
    /** El audio de este clip de vídeo se separó a una pista de audio. */
    val audioDetached: Boolean = false,
    val audioFx: AudioFx = AudioFx(),
    val transform: Transform = Transform(),
    val color: ColorAdjust = ColorAdjust(),
    val filterId: String = "",
    val filterIntensity: Float = 1f,
    /** LUT propia (.cube): ruta de archivo o "asset:luts/nombre.cube". */
    val lutPath: String = "",
    val effects: List<EffectInstance> = emptyList(),
    val chroma: ChromaKey = ChromaKey(),
    val mask: Mask = Mask(),
    val zone: Zone = Zone(),
    /** Zonas adicionales (2.ª en adelante); ver [allZones]. */
    val zones: List<Zone> = emptyList(),
    val keyframes: Map<String, List<Keyframe>> = emptyMap(),
    val animIn: AnimRef? = null,
    val animOut: AnimRef? = null,
    val animLoop: AnimRef? = null,
    /** Transición con el clip anterior (solo pista principal). */
    val transitionIn: Transition? = null,
    /** Fundido desde / a negro. */
    val blackInUs: Long = 0,
    val blackOutUs: Long = 0,
    val text: TextSpec? = null,
    val sticker: StickerSpec? = null,
    val fill: ColorFill? = null,
    val groupId: String = "",
    /** Hueco reemplazable al usar el proyecto como plantilla. */
    val slot: Boolean = false,
    /** Estabilización básica activada (usa el análisis en caché). */
    val stabilize: Boolean = false,
    val label: String = "",
    /** Clip negro de "hueco al inicio": al cambiar su duración o borrarlo se mueve todo lo demás (ver rippleGap). */
    val gap: Boolean = false,
    /** El hueco mueve también las pistas de audio. */
    val gapAudio: Boolean = true,
    /** Audio procesado con IA (reducción de ruido, separación, texto a voz). */
    val aiAudio: AiAudioRef? = null,
    /** Tramos con voz detectados por IA (pares inicio, fin relativos al clip): la música solo baja durante ellos. */
    val voiceRangesUs: List<Long> = emptyList(),
    /** Fondo de personas sin croma (IA). */
    val personCut: PersonCut? = null,
    /** Seguimientos de objetos analizados en este clip (se pueden insertar varios elementos con cada uno). */
    val objTracks: List<ObjTrack> = emptyList(),
) {
    val endUs: Long get() = startUs + durationUs
    val isVisual: Boolean get() = kind == ClipKind.VIDEO || kind == ClipKind.IMAGE || kind == ClipKind.COLOR
    val hasTimedMedia: Boolean get() = kind == ClipKind.VIDEO || kind == ClipKind.AUDIO
    val srcSpanUs: Long get() = (srcOutUs - srcInUs).coerceAtLeast(0)

    /** Indica si el clip aporta audio a la mezcla. */
    val audible: Boolean
        get() = when (kind) {
            ClipKind.AUDIO -> !muted
            ClipKind.VIDEO -> media?.hasAudio == true && !muted && !audioDetached && !reversed
            else -> false
        }
}

@Serializable
data class Track(
    val id: String = newId(),
    val type: TrackType,
    val name: String = "",
    val clips: List<Clip> = emptyList(),
    val muted: Boolean = false,
    val locked: Boolean = false,
    val hidden: Boolean = false,
) {
    val endUs: Long get() = clips.maxOfOrNull { it.endUs } ?: 0L
}

@Serializable
data class Project(
    val formatVersion: Int = FORMAT_VERSION,
    val id: String = newId(),
    val name: String = "",
    val createdAt: Long = 0,
    val modifiedAt: Long = 0,
    val width: Int = 1080,
    val height: Int = 1920,
    val fps: Int = 30,
    val background: CanvasBg = CanvasBg(),
    val tracks: List<Track> = listOf(Track(type = TrackType.MAIN)),
    val markers: List<Marker> = emptyList(),
    val watermark: Watermark = Watermark(),
    /** Estilo común de los subtítulos. */
    val subtitleStyle: Layer = Layer(
        size = 0.045f, y = 0.36f, strokeWidth = 0.12f, shadowRadius = 0.1f, fontId = "poppins_bold",
    ),
    /** Instante usado como portada. */
    val coverUs: Long = 0,
    /** Atenuación automática de la música cuando hay voz. */
    val autoDuck: Boolean = false,
    val duckAmount: Float = 0.25f,
    /** El proyecto es una plantilla con huecos reemplazables. */
    val isTemplate: Boolean = false,
    /** Color de la palabra que se está diciendo en los subtítulos con tiempos por palabra (0 = sin karaoke). */
    val karaokeColor: Int = 0,
    /** Proyecto de demostración: al abrirlo el editor avisa de que puede haber cortes en la reproducción y aconseja exportarlo. */
    val demo: Boolean = false,
    /** Fundido desde / a negro de todo el vídeo, por encima de todas las capas (0 = sin fundido). */
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
) {
    val durationUs: Long get() = tracks.maxOfOrNull { it.endUs } ?: 0L
    val mainTrack: Track get() = tracks.firstOrNull { it.type == TrackType.MAIN } ?: Track(type = TrackType.MAIN)
    val aspect: Float get() = if (height == 0) 1f else width.toFloat() / height.toFloat()
    val frameUs: Long get() = US / fps.coerceAtLeast(1)

    fun allClips(): List<Clip> = tracks.flatMap { it.clips }
    fun clip(id: String): Clip? {
        for (t in tracks) for (c in t.clips) if (c.id == id) return c
        return null
    }
    fun trackOf(clipId: String): Track? = tracks.firstOrNull { t -> t.clips.any { it.id == clipId } }
    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }
}
