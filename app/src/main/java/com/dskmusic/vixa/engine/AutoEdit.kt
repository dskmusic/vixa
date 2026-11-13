package com.dskmusic.vixa.engine

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Cottage
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Nightlife
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.R
import com.dskmusic.vixa.ai.AiTasks
import com.dskmusic.vixa.ai.Models
import com.dskmusic.vixa.ai.Speech
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.data.online.ApiConfig
import com.dskmusic.vixa.data.online.OnlineType
import com.dskmusic.vixa.data.online.Query
import com.dskmusic.vixa.media.Analysis
import com.dskmusic.vixa.media.Importer
import com.dskmusic.vixa.media.MediaType
import com.dskmusic.vixa.media.Waveforms
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
import com.dskmusic.vixa.model.SpeedPoint
import com.dskmusic.vixa.model.TextSpec
import com.dskmusic.vixa.model.segmentSpeeds
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.Transform
import com.dskmusic.vixa.model.Transition
import com.dskmusic.vixa.model.addToTrack
import com.dskmusic.vixa.model.insertMain
import com.dskmusic.vixa.model.newId
import com.dskmusic.vixa.model.updateClip
import com.dskmusic.vixa.ui.editor.sizeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Asistente de vídeo: genera un proyecto normal (que se abre en la línea de tiempo y se puede retocar) a partir de unos
 * medios, un "mood" y una música. Los cortes caen en los tiempos de la música: el ritmo se analiza en el dispositivo
 * (cuadrícula de pulsos ajustada a los golpes detectados) y, sin música o sin ritmo claro, se usa el tempo del mood.
 */
object AutoEdit {

    /**
     * Receta de un mood. Cada montaje elige al azar entre las variantes (look, transiciones, capa de ambiente, título…),
     * así dos vídeos del mismo mood no salen iguales, y el montaje cambia por secciones: inicio y final suaves,
     * desarrollo con las transiciones del mood y clímax con las más intensas.
     */
    class Mood(
        val id: String,
        val nameRes: Int,
        val descRes: Int,
        val icon: ImageVector,
        /** Color plano del icono (ARGB). */
        val color: Long,
        /** Tempo de referencia (pulsos por minuto): el de la cuadrícula si no hay música y el que se prefiere al analizarla. */
        val bpm: Int,
        /** Pulsos que dura una foto y un vídeo, y mínimo de pulsos de cualquier plano. */
        val photoBeats: Int,
        val videoBeats: Int,
        val minBeats: Int,
        /** Transiciones: normales, de acento (al abrir una frase de 8 pulsos), del clímax y suaves (inicio y final). */
        val transitions: List<String>,
        val accents: List<String>,
        val climax: List<String>,
        val soft: List<String>,
        val transitionMs: Int,
        val kenBurns: List<String>,
        /** Looks posibles (filtros): cada montaje usa uno. */
        val filters: List<String>,
        val filterIntensity: Float,
        /** Corrección de color propia del mood, encima del filtro. */
        val grade: ColorAdjust,
        val titles: List<String>,
        val outros: List<String>,
        /** Etiquetas de Jamendo (música instrumental) y velocidades admitidas. */
        val tags: List<String>,
        val speeds: List<String>,
        /** Efectos en todos los planos, en los que abren una frase y en los del clímax. */
        val effects: List<Pair<String, Float>> = emptyList(),
        val accentFx: List<Pair<String, Float>> = emptyList(),
        val climaxFx: List<Pair<String, Float>> = emptyList(),
        /** Efectos a ritmo (efecto, pico, cada cuántos pulsos): su cantidad se anima con fotogramas clave en cada pulso. */
        val beatFx: List<Triple<String, Float, Int>> = emptyList(),
        /** Capas de ambiente sobre todo el vídeo (capa de ajuste): cada montaje elige una de las variantes. */
        val overlays: List<List<Pair<String, Float>>> = emptyList(),
        /** Barras de cine que entran al empezar (0 = sin barras). */
        val letterbox: Float = 0f,
        /** Vídeos sin su sonido: velocidad base (cámara lenta) y curvas de velocidad para los planos de acento. */
        val videoSpeed: Float = 1f,
        val ramps: List<List<Float>> = emptyList(),
        /** En el clímax los planos duran la mitad. */
        val fastClimax: Boolean = false,
    )

    private fun fx(id: String, amount: Float) = id to amount

    /**
     * Capas de ambiente con los efectos de las últimas tandas, por estilo: se suman a las de cada receta (el asistente elige una de
     * todas) y solo llevan efectos coherentes con el carácter del estilo. Para que un efecto nuevo lo use el asistente basta
     * añadirlo aquí en los estilos a los que le va bien (un id que no exista se ignora al montar).
     */
    private val moodOverlays: Map<String, List<List<Pair<String, Float>>>> = mapOf(
        "calm" to listOf(listOf(fx("dust_motes", 0.35f)), listOf(fx("water_caustics", 0.3f)), listOf(fx("moon_rays", 0.3f))),
        "melancholic" to listOf(listOf(fx("rain_glass", 0.4f)), listOf(fx("moon_rays", 0.35f)), listOf(fx("dust_motes", 0.3f)), listOf(fx("blizzard", 0.25f))),
        "happy" to listOf(listOf(fx("butterflies", 0.4f)), listOf(fx("magic_dust", 0.4f)), listOf(fx("cross_stars", 0.3f))),
        "dynamic" to listOf(listOf(fx("laser_beams", 0.35f)), listOf(fx("speed_lines", 0.4f)), listOf(fx("lens_dirt", 0.3f))),
        "epic" to listOf(listOf(fx("meteors", 0.4f)), listOf(fx("moon_rays", 0.4f), fx("film_burn", 0.4f)), listOf(fx("smoke", 0.35f)), listOf(fx("lens_dirt", 0.3f))),
        "romantic" to listOf(listOf(fx("petals", 0.4f)), listOf(fx("magic_dust", 0.4f)), listOf(fx("fireflies", 0.4f)), listOf(fx("candle_flicker", 0.3f))),
        "nostalgic" to listOf(listOf(fx("polaroid_frame", 0.5f)), listOf(fx("film_burn", 0.5f)), listOf(fx("lens_dirt", 0.3f), fx("dust", 0.4f)), listOf(fx("rec_timecode", 0.8f))),
        "fun" to listOf(listOf(fx("magic_dust", 0.4f)), listOf(fx("coin_rain", 0.4f)), listOf(fx("butterflies", 0.35f)), listOf(fx("pixel_border", 0.6f))),
        "elegant" to listOf(listOf(fx("cross_stars", 0.4f)), listOf(fx("magic_dust", 0.25f)), listOf(fx("lens_dirt", 0.2f))),
        "adventure" to listOf(listOf(fx("wind_streaks", 0.4f)), listOf(fx("heat_mirage", 0.3f)), listOf(fx("dust_motes", 0.4f)), listOf(fx("sandstorm", 0.3f))),
        "mystery" to listOf(listOf(fx("smoke", 0.4f)), listOf(fx("moon_rays", 0.4f)), listOf(fx("lightning", 0.3f)), listOf(fx("flashlight", 0.7f))),
        "summer" to listOf(listOf(fx("water_caustics", 0.4f)), listOf(fx("heat_mirage", 0.3f)), listOf(fx("butterflies", 0.35f))),
        "sport" to listOf(listOf(fx("speed_lines", 0.4f)), listOf(fx("laser_beams", 0.3f)), listOf(fx("lens_dirt", 0.2f))),
        "party" to listOf(listOf(fx("laser_beams", 0.5f)), listOf(fx("disco_lights", 0.6f)), listOf(fx("neon_sign", 0.4f)), listOf(fx("sparkler", 0.5f))),
        "futuristic" to listOf(listOf(fx("laser_beams", 0.4f)), listOf(fx("drone_hud", 0.7f)), listOf(fx("radar", 0.5f)), listOf(fx("lcd_lines", 0.4f))),
        "nature" to listOf(listOf(fx("butterflies", 0.4f)), listOf(fx("dust_motes", 0.4f)), listOf(fx("wind_streaks", 0.3f)), listOf(fx("petals", 0.3f))),
        "urban" to listOf(listOf(fx("neon_sign", 0.4f)), listOf(fx("lens_dirt", 0.3f)), listOf(fx("rec_timecode", 0.8f))),
        "cozy" to listOf(listOf(fx("candle_flicker", 0.4f)), listOf(fx("dust_motes", 0.4f)), listOf(fx("fireflies", 0.3f))),
        "retro80" to listOf(listOf(fx("neon_sign", 0.5f)), listOf(fx("lcd_lines", 0.4f)), listOf(fx("rec_timecode", 0.8f)), listOf(fx("crt_on", 0.5f))),
        "wedding" to listOf(listOf(fx("petals", 0.4f)), listOf(fx("magic_dust", 0.4f)), listOf(fx("cross_stars", 0.35f)), listOf(fx("fireflies", 0.3f))),
        "birthday" to listOf(listOf(fx("fireworks", 0.5f)), listOf(fx("magic_dust", 0.4f)), listOf(fx("sparkler", 0.5f))),
        "horror" to listOf(listOf(fx("smoke", 0.5f)), listOf(fx("moon_rays", 0.3f)), listOf(fx("lightning", 0.4f)), listOf(fx("lens_dirt", 0.4f))),
        "gaming" to listOf(listOf(fx("pixel_border", 0.5f)), listOf(fx("lcd_lines", 0.4f)), listOf(fx("laser_beams", 0.4f)), listOf(fx("channel_switch", 0.4f))),
        "fashion" to listOf(listOf(fx("cross_stars", 0.4f)), listOf(fx("polaroid_frame", 0.5f)), listOf(fx("lens_dirt", 0.25f)), listOf(fx("story_frame", 0.5f))),
        "lofi" to listOf(listOf(fx("rec_timecode", 0.8f)), listOf(fx("polaroid_frame", 0.6f)), listOf(fx("dust_motes", 0.4f)), listOf(fx("lcd_lines", 0.3f))),
        "christmas" to listOf(listOf(fx("blizzard", 0.4f)), listOf(fx("magic_dust", 0.4f)), listOf(fx("sparkler", 0.4f)), listOf(fx("candle_flicker", 0.3f))),
        "baby" to listOf(listOf(fx("butterflies", 0.4f)), listOf(fx("petals", 0.3f)), listOf(fx("magic_dust", 0.35f))),
    )

    private fun beat(id: String, peak: Float, every: Int) = Triple(id, peak, every)

    // Curvas de velocidad (5 puntos repartidos por el plano): golpe y cámara lenta, salida acelerada, vaivén y arranque lento.
    private val RAMP_HIT = listOf(2.2f, 2.2f, 0.5f, 0.6f, 1f)
    private val RAMP_OUT = listOf(0.6f, 0.7f, 1.5f, 2.5f, 2.5f)
    private val RAMP_WAVE = listOf(0.7f, 2f, 0.7f, 2f, 0.7f)
    private val RAMP_SLOW_IN = listOf(0.5f, 0.6f, 1f, 1.4f, 1.6f)

    val moods: List<Mood> = listOf(
        Mood("calm", R.string.mood_calm, R.string.mood_calm_d, Icons.Default.Spa, 0xFF4DB6AC, 70, 4, 8, 2,
            listOf("fade", "dissolve", "wipe_soft", "dream"), listOf("blur", "dream"), listOf("dream", "light_leak"), listOf("fade"), 900,
            listOf("kb_zoom_in", "kb_drift", "kb_pan_left", "kb_zoom_out", "kb_pan_right", "kb_arc"),
            listOf("airy", "zen", "soft_glow", "mint"), 0.7f, ColorAdjust(saturation = -0.05f, vignette = 0.12f, sharpen = 0.1f),
            listOf("title_calm", "title_serif", "title_nature"), listOf("outro_minimal", "outro_soft", "outro_thanks"),
            listOf("chillout", "ambient", "relaxing", "calm", "peaceful"), listOf("verylow", "low"),
            overlays = listOf(listOf(fx("sun_glow", 0.3f)), listOf(fx("bokeh", 0.3f)), emptyList()), videoSpeed = 0.8f),
        Mood("melancholic", R.string.mood_melancholic, R.string.mood_melancholic_d, Icons.Default.Cloud, 0xFF607D8B, 64, 4, 8, 2,
            listOf("dip_black", "fade", "dissolve", "wipe_soft"), listOf("dip_black", "blur"), listOf("blur", "ink"), listOf("fade", "dip_black"), 1000,
            listOf("kb_zoom_out", "kb_pan_up", "kb_pull", "kb_drift", "kb_zoom_in"),
            listOf("moody", "rainy", "gloomy", "bw_matte", "storm"), 0.8f, ColorAdjust(contrast = 0.1f, saturation = -0.15f, temperature = -0.1f, vignette = 0.25f),
            listOf("title_melancholy", "title_typewriter", "title_blur_in"), listOf("outro_soft", "outro_end"),
            listOf("sad", "melancholic", "emotional", "piano", "dark"), listOf("verylow", "low"),
            effects = listOf(fx("vignette_fx", 0.5f)),
            overlays = listOf(listOf(fx("rain", 0.3f)), listOf(fx("fog", 0.35f)), listOf(fx("dust", 0.3f)), emptyList()), videoSpeed = 0.75f),
        Mood("happy", R.string.mood_happy, R.string.mood_happy_d, Icons.Default.WbSunny, 0xFFFFB300, 112, 2, 4, 2,
            listOf("slide_left", "push_right", "wipe_right", "slide_up", "swap", "dots", "zigzag", "bounce"), listOf("zoom_in", "flash", "star", "punch"),
            listOf("punch", "bounce", "star", "swap"), listOf("fade", "wipe_soft"), 350,
            listOf("kb_zoom_in", "kb_diagonal", "kb_pan_right", "kb_pan_left", "kb_punch", "kb_arc"),
            listOf("sunny", "daylight", "fiesta", "honey"), 0.7f, ColorAdjust(saturation = 0.12f, temperature = 0.08f, vibrance = 0.15f, sharpen = 0.15f),
            listOf("title_happy", "title_swing", "title_cascade"), listOf("outro_see_you", "outro_wave", "outro_thanks_bold"),
            listOf("happy", "upbeat", "fun", "pop", "summer"), listOf("medium", "high"),
            accentFx = listOf(fx("light_leak", 0.4f)), beatFx = listOf(beat("zoom", 0.25f, 2)),
            overlays = listOf(listOf(fx("light_leak", 0.3f)), listOf(fx("sun_glow", 0.4f)), listOf(fx("bokeh", 0.35f)), emptyList()),
            ramps = listOf(RAMP_HIT), fastClimax = true),
        Mood("dynamic", R.string.mood_dynamic, R.string.mood_dynamic_d, Icons.Default.Bolt, 0xFFFF5722, 128, 2, 4, 1,
            listOf("whip", "whip_right", "push_left", "slide_up", "slices", "zoom_in", "split_v", "whip_up"), listOf("flash", "glitch", "punch", "zoom_blur"),
            listOf("punch", "shake", "zoom_blur", "strobe", "digital"), listOf("fade", "blur"), 220,
            listOf("kb_punch", "kb_push", "kb_zoom_out", "kb_diagonal", "kb_pull"),
            listOf("crisp", "punchy", "hdr", "teal_orange"), 0.8f, ColorAdjust(contrast = 0.15f, saturation = 0.12f, sharpen = 0.3f, vignette = 0.1f),
            listOf("title_dynamic", "title_shout", "title_slam"), listOf("outro_thanks_bold", "outro_stamp"),
            listOf("energetic", "dance", "electronic", "upbeat", "sport"), listOf("high", "veryhigh"),
            accentFx = listOf(fx("chromatic", 0.5f)), climaxFx = listOf(fx("handheld", 0.4f)),
            beatFx = listOf(beat("zoom", 0.35f, 1), beat("flash", 0.25f, 4), beat("chromatic", 0.6f, 2)),
            overlays = listOf(emptyList(), listOf(fx("letterbox", 0.25f))),
            ramps = listOf(RAMP_HIT, RAMP_WAVE, RAMP_OUT), fastClimax = true),
        Mood("epic", R.string.mood_epic, R.string.mood_epic_d, Icons.Default.Theaters, 0xFF8E24AA, 100, 4, 8, 2,
            listOf("dip_black", "blur", "fade", "zoom_blur", "wipe_soft"), listOf("zoom_in", "burn", "light_leak", "zoom_blur"),
            listOf("punch", "flash", "zoom_blur", "burn"), listOf("dip_black", "fade"), 550,
            listOf("kb_push", "kb_zoom_in", "kb_diagonal", "kb_pan_up", "kb_pull", "kb_rotate"),
            listOf("blockbuster", "teal_orange", "bronze", "blade", "dune"), 0.8f,
            ColorAdjust(contrast = 0.18f, saturation = -0.05f, shadows = -0.1f, vignette = 0.2f, sharpen = 0.2f),
            listOf("title_epic", "title_cinema", "title_slam"), listOf("outro_fin_cinema", "outro_end"),
            listOf("epic", "cinematic", "trailer", "powerful", "orchestral"), listOf("medium", "high"),
            climaxFx = listOf(fx("glow", 0.4f)), beatFx = listOf(beat("zoom", 0.3f, 4), beat("flash", 0.2f, 8)),
            overlays = listOf(listOf(fx("vignette_fx", 0.5f)), listOf(fx("vignette_fx", 0.4f), fx("light_rays", 0.35f)), listOf(fx("embers", 0.35f), fx("vignette_fx", 0.4f))),
            letterbox = 0.45f, videoSpeed = 0.85f, ramps = listOf(RAMP_HIT, RAMP_SLOW_IN)),
        Mood("romantic", R.string.mood_romantic, R.string.mood_romantic_d, Icons.Default.Favorite, 0xFFEC407A, 76, 4, 8, 2,
            listOf("dissolve", "fade", "dream", "wipe_soft", "heart"), listOf("dip_white", "heart", "light_leak"), listOf("dream", "light_leak", "heart"), listOf("fade", "dissolve"), 1000,
            listOf("kb_zoom_in", "kb_pan_left", "kb_zoom_out", "kb_drift", "kb_arc"),
            listOf("rose", "blush", "dream", "honey", "blossom"), 0.7f, ColorAdjust(saturation = 0.05f, temperature = 0.08f, fade = 0.06f, vignette = 0.15f),
            listOf("title_romantic", "quote_script", "title_serif"), listOf("outro_love", "outro_soft", "outro_end"),
            listOf("romantic", "love", "acoustic", "piano", "tender"), listOf("low", "medium"),
            effects = listOf(fx("dream_blur", 0.3f)),
            overlays = listOf(listOf(fx("bokeh", 0.4f)), listOf(fx("hearts", 0.3f)), listOf(fx("sun_glow", 0.35f)), listOf(fx("light_leak", 0.25f))), videoSpeed = 0.7f),
        Mood("nostalgic", R.string.mood_nostalgic, R.string.mood_nostalgic_d, Icons.Default.History, 0xFF8D6E63, 90, 4, 8, 2,
            listOf("fade", "dissolve", "dip_white", "light_leak", "wipe_soft"), listOf("dip_white", "light_leak", "burn"), listOf("light_leak", "flash"), listOf("fade"), 700,
            listOf("kb_pan_left", "kb_zoom_out", "kb_pan_right", "kb_drift", "kb_zoom_in"),
            listOf("vintage", "super8", "kodak", "sixties", "sepia_soft", "disposable"), 0.8f, ColorAdjust(saturation = -0.05f, temperature = 0.1f, fade = 0.08f, vignette = 0.15f),
            listOf("title_nostalgia", "title_retro", "title_typewriter"), listOf("outro_rewind", "outro_end", "outro_soft"),
            listOf("nostalgic", "retro", "vintage", "lofi", "memories"), listOf("low", "medium"),
            overlays = listOf(listOf(fx("super8", 0.6f), fx("dust", 0.4f)), listOf(fx("old_film", 0.5f)), listOf(fx("camcorder", 0.6f), fx("noise", 0.2f)),
                listOf(fx("film_strip", 0.3f), fx("dust", 0.4f)), listOf(fx("photo_frame", 0.3f))), videoSpeed = 0.9f),
        Mood("fun", R.string.mood_fun, R.string.mood_fun_d, Icons.Default.Celebration, 0xFFFF4081, 120, 2, 4, 2,
            listOf("pixelate", "wipe_diagonal", "circle_open", "checker", "roll", "dots", "bounce", "zigzag", "spin", "sunburst", "mosaic"), listOf("spin", "heart", "star", "bounce"),
            listOf("punch", "star", "spin", "strobe"), listOf("fade", "circle_open"), 300,
            listOf("kb_zoom_in", "kb_pan_right", "kb_diagonal", "kb_rotate", "kb_punch", "kb_rotate_rev"),
            listOf("candy", "fiesta", "pastel", "vivid"), 0.7f, ColorAdjust(saturation = 0.2f, contrast = 0.08f, vibrance = 0.2f),
            listOf("title_comic", "title_spin", "title_cascade", "title_elastic"), listOf("outro_see_you", "outro_wave", "outro_party"),
            listOf("funny", "quirky", "playful", "comedy", "party"), listOf("medium", "high"),
            beatFx = listOf(beat("zoom", 0.3f, 2)),
            overlays = listOf(listOf(fx("confetti", 0.4f)), listOf(fx("sparkles", 0.5f)), listOf(fx("bubbles", 0.4f)), emptyList()),
            ramps = listOf(RAMP_WAVE), fastClimax = true),
        Mood("elegant", R.string.mood_elegant, R.string.mood_elegant_d, Icons.Default.Diamond, 0xFF5C6BC0, 84, 4, 8, 2,
            listOf("dissolve", "fade", "dip_white", "wipe_soft", "split_v", "box_open"), listOf("blur", "dream", "eye"), listOf("dream", "split_h"), listOf("fade"), 900,
            listOf("kb_zoom_in", "kb_pan_left", "kb_zoom_out", "kb_drift", "kb_pull"),
            listOf("portra", "studio", "bw_soft", "matte", "bronze"), 0.8f, ColorAdjust(contrast = 0.08f, saturation = -0.08f, vignette = 0.15f, sharpen = 0.15f, highlights = -0.05f),
            listOf("title_elegant", "title_blur_in", "title_serif", "title_fashion"), listOf("outro_made_by", "outro_minimal", "outro_soft"),
            listOf("elegant", "sophisticated", "jazz", "lounge", "classy"), listOf("low", "medium"),
            effects = listOf(fx("vignette_fx", 0.3f)),
            overlays = listOf(emptyList(), listOf(fx("bokeh", 0.25f)), listOf(fx("lens_flare", 0.3f))), videoSpeed = 0.85f),
        Mood("adventure", R.string.mood_adventure, R.string.mood_adventure_d, Icons.Default.Explore, 0xFF43A047, 110, 2, 6, 2,
            listOf("push_left", "slide_left", "wipe_left", "push_up", "whip", "swap", "split_h", "slide_corner"), listOf("zoom_in", "whip", "zoom_blur", "light_leak"),
            listOf("punch", "whip_right", "zoom_blur"), listOf("fade", "wipe_soft"), 320,
            listOf("kb_zoom_in", "kb_pan_right", "kb_pan_left", "kb_diagonal", "kb_push", "kb_arc", "kb_pan_down"),
            listOf("golden_hour", "ektar", "daylight", "hdr", "western"), 0.8f, ColorAdjust(contrast = 0.1f, saturation = 0.1f, sharpen = 0.25f, vibrance = 0.1f),
            listOf("title_adventure", "intro_travel", "title_stack"), listOf("outro_stamp", "outro_see_you"),
            listOf("adventure", "travel", "uplifting", "acoustic", "inspiring"), listOf("medium", "high"),
            accentFx = listOf(fx("light_leak", 0.35f)), beatFx = listOf(beat("zoom", 0.22f, 4)),
            overlays = listOf(listOf(fx("lens_flare", 0.4f)), listOf(fx("sun_glow", 0.4f)), listOf(fx("light_rays", 0.3f)), emptyList()),
            ramps = listOf(RAMP_HIT, RAMP_OUT), fastClimax = true),
        Mood("mystery", R.string.mood_mystery, R.string.mood_mystery_d, Icons.Default.Nightlight, 0xFF37474F, 70, 4, 8, 2,
            listOf("dip_black", "fade", "blur", "ink", "static_tv", "wipe_soft"), listOf("glitch", "tv_off", "ink"), listOf("static_tv", "glitch", "strobe"), listOf("dip_black", "fade"), 800,
            listOf("kb_zoom_in", "kb_zoom_out", "kb_pan_up", "kb_push", "kb_drift"),
            listOf("thriller", "midnight", "noir_color", "horror", "bw_blue"), 0.8f, ColorAdjust(contrast = 0.12f, saturation = -0.15f, vignette = 0.3f, exposure = -0.06f),
            listOf("title_mystery", "intro_glitch", "title_typewriter"), listOf("outro_end", "outro_soft"),
            listOf("mysterious", "dark", "suspense", "ambient", "cinematic"), listOf("verylow", "low"),
            effects = listOf(fx("vignette_fx", 0.6f)), accentFx = listOf(fx("double_vision", 0.3f)),
            overlays = listOf(listOf(fx("fog", 0.4f)), listOf(fx("noise", 0.25f)), listOf(fx("dust", 0.3f), fx("spotlight", 0.4f))),
            letterbox = 0.3f, videoSpeed = 0.8f),
        Mood("summer", R.string.mood_summer, R.string.mood_summer_d, Icons.Default.BeachAccess, 0xFF00ACC1, 118, 2, 4, 2,
            listOf("slide_up", "wipe_right", "push_right", "circle_open", "swap", "ripple", "wave", "dots", "fan"), listOf("zoom_in", "flash", "light_leak", "ripple"),
            listOf("punch", "bounce", "light_leak"), listOf("fade", "wipe_soft"), 340,
            listOf("kb_zoom_in", "kb_pan_right", "kb_diagonal", "kb_arc", "kb_punch"),
            listOf("tropical", "aqua", "sunny", "ektar", "fiesta"), 0.75f, ColorAdjust(saturation = 0.15f, temperature = 0.08f, vibrance = 0.2f, exposure = 0.05f),
            listOf("title_summer", "title_elastic", "title_happy"), listOf("outro_see_you", "outro_wave"),
            listOf("summer", "tropical", "beach", "happy", "reggae"), listOf("medium", "high"),
            accentFx = listOf(fx("light_leak", 0.35f)), beatFx = listOf(beat("zoom", 0.25f, 2)),
            overlays = listOf(listOf(fx("sun_glow", 0.45f)), listOf(fx("lens_flare", 0.4f)), listOf(fx("bubbles", 0.3f)), emptyList()),
            ramps = listOf(RAMP_HIT), fastClimax = true),
        Mood("sport", R.string.mood_sport, R.string.mood_sport_d, Icons.Default.FitnessCenter, 0xFFE53935, 135, 2, 4, 1,
            listOf("whip", "push_left", "slide_left", "wipe_diagonal", "slices", "split_v", "whip_right", "whip_down"), listOf("flash", "zoom_in", "glitch", "punch"),
            listOf("punch", "shake", "strobe", "zoom_blur"), listOf("fade"), 200,
            listOf("kb_punch", "kb_push", "kb_zoom_out", "kb_pull"),
            listOf("vivid", "punchy", "hdr", "storm"), 0.8f, ColorAdjust(contrast = 0.18f, saturation = 0.08f, sharpen = 0.35f),
            listOf("title_sport", "title_drop", "title_slam"), listOf("outro_thanks_bold", "outro_stamp"),
            listOf("sport", "workout", "energetic", "rock", "motivational"), listOf("high", "veryhigh"),
            accentFx = listOf(fx("zoom_blur", 0.4f)), beatFx = listOf(beat("zoom", 0.4f, 1), beat("shake", 0.5f, 4), beat("flash", 0.25f, 8)),
            overlays = listOf(emptyList(), listOf(fx("letterbox", 0.2f))),
            ramps = listOf(RAMP_HIT, RAMP_WAVE, RAMP_OUT), fastClimax = true),
        Mood("party", R.string.mood_party, R.string.mood_party_d, Icons.Default.Nightlife, 0xFFD500F9, 124, 2, 4, 1,
            listOf("flash", "pixelate", "spin", "wipe_diagonal", "zoom_in", "strobe", "digital", "slices", "mosaic", "rings"), listOf("glitch", "flash", "strobe", "punch"),
            listOf("strobe", "punch", "shake", "digital"), listOf("fade", "flash"), 240,
            listOf("kb_punch", "kb_zoom_out", "kb_diagonal", "kb_rotate", "kb_push"),
            listOf("neon", "vaporwave", "fiesta", "synth_sunset"), 0.75f, ColorAdjust(contrast = 0.1f, saturation = 0.2f, vignette = 0.15f),
            listOf("title_party", "title_neon", "title_glitch"), listOf("outro_party", "outro_thanks_bold"),
            listOf("party", "club", "dance", "electronic", "house"), listOf("high", "veryhigh"),
            accentFx = listOf(fx("chromatic", 0.5f)), climaxFx = listOf(fx("prism", 0.4f)),
            beatFx = listOf(beat("zoom", 0.35f, 1), beat("flash", 0.35f, 2), beat("chromatic", 0.7f, 2)),
            overlays = listOf(listOf(fx("confetti", 0.35f)), listOf(fx("sparkles", 0.5f)), listOf(fx("bokeh", 0.5f)), emptyList()),
            ramps = listOf(RAMP_WAVE, RAMP_HIT), fastClimax = true),
        Mood("futuristic", R.string.mood_futuristic, R.string.mood_futuristic_d, Icons.Default.RocketLaunch, 0xFF00B0FF, 110, 2, 4, 1,
            listOf("wipe_right", "pixelate", "slide_left", "blur", "scan", "digital", "slices", "rain_bars", "box_open"), listOf("glitch", "zoom_in", "scan", "digital"),
            listOf("digital", "punch", "static_tv"), listOf("fade", "scan"), 280,
            listOf("kb_zoom_in", "kb_zoom_out", "kb_diagonal", "kb_push", "kb_drift"),
            listOf("cyberpunk", "scifi", "blade", "arctic", "teal_magenta"), 0.75f, ColorAdjust(contrast = 0.12f, saturation = 0.08f, sharpen = 0.25f),
            listOf("title_future", "title_gradient", "title_scramble"), listOf("outro_end", "outro_minimal"),
            listOf("futuristic", "electronic", "synth", "scifi", "technology"), listOf("medium", "high"),
            accentFx = listOf(fx("glitch", 0.4f)), climaxFx = listOf(fx("hologram", 0.35f)),
            beatFx = listOf(beat("chromatic", 0.6f, 2), beat("zoom", 0.2f, 4)),
            overlays = listOf(emptyList(), listOf(fx("code_rain", 0.25f)), listOf(fx("lens_flare", 0.35f))),
            ramps = listOf(RAMP_HIT), fastClimax = true),
        Mood("nature", R.string.mood_nature, R.string.mood_nature_d, Icons.Default.Eco, 0xFF7CB342, 78, 4, 8, 2,
            listOf("dissolve", "fade", "push_up", "slide_up", "wipe_soft", "dream", "ripple", "eye"), listOf("blur", "light_leak", "dream"), listOf("light_leak", "dream"), listOf("fade"), 850,
            listOf("kb_zoom_out", "kb_pan_left", "kb_pan_right", "kb_zoom_in", "kb_drift", "kb_arc", "kb_pan_down"),
            listOf("forest", "spring", "daylight", "autumn", "mint"), 0.7f, ColorAdjust(saturation = 0.1f, vibrance = 0.18f, sharpen = 0.18f),
            listOf("title_nature", "title_underline", "title_calm"), listOf("outro_thanks", "outro_minimal"),
            listOf("nature", "ambient", "acoustic", "relaxing", "peaceful"), listOf("low", "medium"),
            overlays = listOf(listOf(fx("light_rays", 0.35f)), listOf(fx("sun_glow", 0.35f)), listOf(fx("bokeh", 0.3f)), listOf(fx("fog", 0.25f))), videoSpeed = 0.85f),
        Mood("urban", R.string.mood_urban, R.string.mood_urban_d, Icons.Default.LocationCity, 0xFF546E7A, 95, 2, 4, 1,
            listOf("whip", "push_left", "slide_up", "wipe_diagonal", "slices", "split_h", "punch", "whip_down", "blinds_diag"), listOf("flash", "zoom_in", "glitch", "punch"),
            listOf("punch", "shake", "digital", "strobe"), listOf("fade", "dip_black"), 240,
            listOf("kb_zoom_in", "kb_pan_left", "kb_diagonal", "kb_punch", "kb_pull"),
            listOf("cross", "cinestill", "bw_contrast", "nineties", "bleach"), 0.7f, ColorAdjust(contrast = 0.15f, saturation = -0.05f, grain = 0.08f, sharpen = 0.3f, vignette = 0.15f),
            listOf("title_urban", "title_stack", "title_slam"), listOf("outro_thanks_bold", "outro_rewind"),
            listOf("hiphop", "urban", "beats", "trap", "lofi"), listOf("medium", "high"),
            effects = listOf(fx("handheld", 0.35f)), accentFx = listOf(fx("chromatic", 0.4f)),
            beatFx = listOf(beat("zoom", 0.3f, 2), beat("flash", 0.2f, 8)),
            overlays = listOf(listOf(fx("camcorder", 0.5f)), listOf(fx("noise", 0.2f)), emptyList(), listOf(fx("letterbox", 0.2f))),
            ramps = listOf(RAMP_HIT, RAMP_WAVE), fastClimax = true),
        Mood("cozy", R.string.mood_cozy, R.string.mood_cozy_d, Icons.Default.Cottage, 0xFFFB8C00, 88, 4, 8, 2,
            listOf("dissolve", "fade", "push_left", "wipe_soft", "dream", "light_leak"), listOf("dip_white", "light_leak"), listOf("dream", "light_leak"), listOf("fade"), 800,
            listOf("kb_zoom_in", "kb_pan_right", "kb_zoom_out", "kb_drift", "kb_arc"),
            listOf("golden_soft", "candlelight", "honey", "autumn", "portra"), 0.8f, ColorAdjust(saturation = 0.05f, temperature = 0.08f, vignette = 0.15f, vibrance = 0.1f),
            listOf("title_cozy", "title_clean", "title_serif"), listOf("outro_thanks", "outro_soft"),
            listOf("cozy", "warm", "family", "acoustic", "folk"), listOf("low", "medium"),
            effects = listOf(fx("dream_blur", 0.15f)),
            overlays = listOf(listOf(fx("bokeh", 0.35f)), listOf(fx("sun_glow", 0.35f)), listOf(fx("snow", 0.25f)), emptyList()), videoSpeed = 0.85f),
        Mood("retro80", R.string.mood_retro80, R.string.mood_retro80_d, Icons.Default.LiveTv, 0xFFAB47BC, 108, 2, 4, 2,
            listOf("wipe_right", "pixelate", "slide_left", "flash", "static_tv", "tv_off", "scan", "mosaic", "blinds_h", "slices"), listOf("glitch", "zoom_in", "tv_off", "static_tv"),
            listOf("digital", "strobe", "punch"), listOf("fade", "tv_off"), 280,
            listOf("kb_zoom_in", "kb_pan_right", "kb_diagonal", "kb_push"),
            listOf("eighties", "vaporwave", "synth_sunset", "retro_tv"), 0.8f, ColorAdjust(contrast = 0.1f, saturation = 0.12f, vignette = 0.2f),
            listOf("title_retro_wave", "title_neon", "title_pixel"), listOf("outro_rewind", "outro_game_over", "outro_end"),
            listOf("synthwave", "80s", "retro", "electronic", "synth"), listOf("medium", "high"),
            beatFx = listOf(beat("chromatic", 0.5f, 2), beat("zoom", 0.2f, 4)),
            overlays = listOf(listOf(fx("vhs", 0.5f)), listOf(fx("vhs", 0.4f), fx("noise", 0.15f)), listOf(fx("camcorder", 0.6f))),
            ramps = listOf(RAMP_WAVE), fastClimax = true),
        Mood("wedding", R.string.mood_wedding, R.string.mood_wedding_d, Icons.Default.LocalFlorist, 0xFFD4AF37, 80, 4, 8, 2,
            listOf("dissolve", "fade", "dream", "wipe_soft", "heart", "eye"), listOf("dip_white", "light_leak", "heart"), listOf("dream", "light_leak"), listOf("fade", "dip_white"), 1000,
            listOf("kb_zoom_in", "kb_pan_left", "kb_drift", "kb_zoom_out", "kb_arc"),
            listOf("soft_glow", "portra", "blush", "glow_skin", "bw_high_key"), 0.75f, ColorAdjust(vignette = 0.12f, highlights = -0.05f),
            listOf("title_wedding", "title_romantic", "title_elegant"), listOf("outro_love", "outro_soft"),
            listOf("wedding", "romantic", "love", "piano", "strings"), listOf("low", "medium"),
            effects = listOf(fx("dream_blur", 0.2f)),
            overlays = listOf(listOf(fx("bokeh", 0.45f)), listOf(fx("sparkles", 0.4f)), listOf(fx("light_rays", 0.3f)), listOf(fx("hearts", 0.2f))), videoSpeed = 0.7f),
        Mood("birthday", R.string.mood_birthday, R.string.mood_birthday_d, Icons.Default.Cake, 0xFFFF7043, 120, 2, 4, 2,
            listOf("dots", "bounce", "star", "circle_open", "swap", "zigzag", "sunburst", "slide_up", "roll"), listOf("star", "spin", "punch", "flash"),
            listOf("punch", "star", "bounce", "strobe"), listOf("fade"), 300,
            listOf("kb_zoom_in", "kb_punch", "kb_rotate", "kb_diagonal", "kb_pan_right"),
            listOf("fiesta", "candy", "vivid", "daylight"), 0.7f, ColorAdjust(saturation = 0.12f, vibrance = 0.15f),
            listOf("title_birthday", "title_comic", "title_cascade"), listOf("outro_party", "outro_wave", "outro_see_you"),
            listOf("happy", "party", "fun", "upbeat", "kids"), listOf("medium", "high"),
            beatFx = listOf(beat("zoom", 0.3f, 2)),
            overlays = listOf(listOf(fx("confetti", 0.5f)), listOf(fx("confetti", 0.35f), fx("sparkles", 0.4f)), listOf(fx("bubbles", 0.4f))),
            ramps = listOf(RAMP_WAVE), fastClimax = true),
        Mood("horror", R.string.mood_horror, R.string.mood_horror_d, Icons.Default.BugReport, 0xFF9B1B1F, 66, 4, 8, 1,
            listOf("dip_black", "static_tv", "ink", "tv_off", "glitch", "blur", "strobe"), listOf("static_tv", "glitch", "strobe", "shake"),
            listOf("strobe", "shake", "digital", "static_tv"), listOf("dip_black"), 700,
            listOf("kb_push", "kb_zoom_in", "kb_drift", "kb_rotate", "kb_pull"),
            listOf("horror", "crimson", "bw_film", "midnight", "noir"), 0.85f, ColorAdjust(contrast = 0.1f, vignette = 0.3f),
            listOf("title_horror", "title_mystery", "intro_glitch"), listOf("outro_end", "outro_rewind"),
            listOf("horror", "dark", "suspense", "scary", "creepy"), listOf("verylow", "low"),
            effects = listOf(fx("vignette_fx", 0.7f)), accentFx = listOf(fx("double_vision", 0.5f)), climaxFx = listOf(fx("glitch", 0.5f)),
            beatFx = listOf(beat("flash", 0.3f, 8), beat("shake", 0.4f, 4)),
            overlays = listOf(listOf(fx("noise", 0.35f), fx("dust", 0.4f)), listOf(fx("fog", 0.5f)), listOf(fx("old_film", 0.5f)), listOf(fx("vhs", 0.4f))),
            letterbox = 0.3f, videoSpeed = 0.8f),
        Mood("gaming", R.string.mood_gaming, R.string.mood_gaming_d, Icons.Default.SportsEsports, 0xFF7C4DFF, 128, 2, 4, 1,
            listOf("pixelate", "digital", "scan", "slices", "mosaic", "rain_bars", "whip", "checker", "box_open"), listOf("glitch", "punch", "digital", "zoom_blur"),
            listOf("punch", "digital", "strobe", "shake"), listOf("fade", "pixelate"), 240,
            listOf("kb_punch", "kb_push", "kb_zoom_out", "kb_diagonal"),
            listOf("neon", "cyberpunk", "vivid", "teal_magenta"), 0.75f, ColorAdjust(contrast = 0.12f, saturation = 0.12f, sharpen = 0.3f),
            listOf("title_arcade", "title_pixel", "title_glitch", "title_scramble"), listOf("outro_game_over", "outro_thanks_bold"),
            listOf("gaming", "electronic", "dubstep", "energetic", "chiptune"), listOf("high", "veryhigh"),
            accentFx = listOf(fx("chromatic", 0.5f)), climaxFx = listOf(fx("glitch_blocks", 0.4f)),
            beatFx = listOf(beat("zoom", 0.35f, 1), beat("chromatic", 0.6f, 2), beat("flash", 0.2f, 4)),
            overlays = listOf(emptyList(), listOf(fx("code_rain", 0.2f)), listOf(fx("vhs", 0.25f))),
            ramps = listOf(RAMP_HIT, RAMP_WAVE), fastClimax = true),
        Mood("fashion", R.string.mood_fashion, R.string.mood_fashion_d, Icons.Default.Checkroom, 0xFF9E9E9E, 118, 2, 4, 1,
            listOf("punch", "split_v", "split_h", "slices", "flash", "wipe_soft", "blinds_v", "box_open", "whip"), listOf("strobe", "flash", "punch", "zoom_blur"),
            listOf("strobe", "punch", "split_v"), listOf("fade", "dip_white"), 260,
            listOf("kb_push", "kb_pull", "kb_pan_up", "kb_pan_down", "kb_punch"),
            listOf("bw_contrast", "studio", "silver", "matte", "cinestill", "teal_magenta"), 0.85f, ColorAdjust(contrast = 0.1f, sharpen = 0.3f),
            listOf("title_fashion", "title_elegant", "title_outline"), listOf("outro_minimal", "outro_made_by"),
            listOf("fashion", "house", "deep", "lounge", "electronic"), listOf("medium", "high"),
            beatFx = listOf(beat("zoom", 0.25f, 2), beat("flash", 0.3f, 4)),
            overlays = listOf(emptyList(), listOf(fx("letterbox", 0.25f)), listOf(fx("lens_flare", 0.3f))),
            ramps = listOf(RAMP_HIT, RAMP_SLOW_IN), fastClimax = true),
        Mood("lofi", R.string.mood_lofi, R.string.mood_lofi_d, Icons.Default.Headphones, 0xFF9575CD, 82, 4, 8, 2,
            listOf("fade", "dissolve", "light_leak", "wipe_soft", "static_tv", "tv_off", "dip_black"), listOf("light_leak", "tv_off"), listOf("light_leak", "static_tv"), listOf("fade"), 800,
            listOf("kb_drift", "kb_zoom_in", "kb_pan_left", "kb_pan_right", "kb_zoom_out", "kb_arc"),
            listOf("nineties", "instant", "dusty_rose", "lavender", "faded", "agfa"), 0.8f, ColorAdjust(fade = 0.05f, vignette = 0.15f),
            listOf("title_lofi", "title_nostalgia", "title_typewriter"), listOf("outro_rewind", "outro_soft", "outro_minimal"),
            listOf("lofi", "chillhop", "chill", "beats", "relaxing"), listOf("low", "medium"),
            overlays = listOf(listOf(fx("camcorder", 0.6f), fx("noise", 0.2f)), listOf(fx("super8", 0.5f), fx("dust", 0.35f)), listOf(fx("vhs", 0.35f)),
                listOf(fx("photo_frame", 0.3f), fx("dust", 0.3f))), videoSpeed = 0.85f),
        Mood("christmas", R.string.mood_christmas, R.string.mood_christmas_d, Icons.Default.AcUnit, 0xFFC62828, 96, 4, 8, 2,
            listOf("dissolve", "fade", "star", "wipe_soft", "dream", "light_leak", "dots"), listOf("star", "dip_white", "light_leak"), listOf("star", "dream", "light_leak"), listOf("fade", "dip_white"), 800,
            listOf("kb_zoom_in", "kb_pan_left", "kb_zoom_out", "kb_drift", "kb_arc", "kb_pan_down"),
            listOf("candlelight", "golden_soft", "winter", "ember", "honey"), 0.75f, ColorAdjust(vignette = 0.15f, vibrance = 0.1f),
            listOf("title_xmas", "title_cozy", "title_serif"), listOf("outro_xmas", "outro_thanks", "outro_love"),
            listOf("christmas", "xmas", "holiday", "bells", "winter"), listOf("low", "medium"),
            overlays = listOf(listOf(fx("snow", 0.4f)), listOf(fx("snow", 0.3f), fx("sparkles", 0.4f)), listOf(fx("bokeh", 0.5f)), listOf(fx("sparkles", 0.5f), fx("sun_glow", 0.3f))),
            videoSpeed = 0.9f),
        Mood("baby", R.string.mood_baby, R.string.mood_baby_d, Icons.Default.ChildCare, 0xFFF48FB1, 84, 4, 8, 2,
            listOf("dissolve", "fade", "circle_open", "wipe_soft", "dream", "dots", "heart"), listOf("dip_white", "heart", "dream"), listOf("dream", "heart"), listOf("fade"), 900,
            listOf("kb_zoom_in", "kb_drift", "kb_pan_left", "kb_zoom_out", "kb_arc"),
            listOf("blossom", "soft_glow", "pastel", "glow_skin", "porcelain"), 0.75f, ColorAdjust(vignette = 0.1f),
            listOf("title_baby", "title_cozy", "title_romantic"), listOf("outro_love", "outro_soft", "outro_thanks"),
            listOf("lullaby", "children", "tender", "soft", "piano"), listOf("verylow", "low"),
            effects = listOf(fx("dream_blur", 0.15f)),
            overlays = listOf(listOf(fx("bubbles", 0.35f)), listOf(fx("bokeh", 0.35f)), listOf(fx("sparkles", 0.35f)), listOf(fx("hearts", 0.2f))), videoSpeed = 0.8f),
    )

    fun mood(id: String): Mood? = moods.firstOrNull { it.id == id }

    enum class MusicMode { AUTO, OWN, NONE }

    /** Fundido de color: ninguno, al inicio, al final o en ambos. */
    object Fade {
        const val NONE = 0
        const val START = 1
        const val END = 2
        const val BOTH = 3
    }

    class Request(
        /** Medios en el orden en que se montan. */
        val media: List<String>,
        val title: String,
        val outro: String,
        /** Formato (por ejemplo 9 a 16; 0 a 0 = automático) y duración objetivo en segundos (0 = la natural). */
        val aspect: Pair<Int, Int>,
        val targetSec: Int,
        /** Id de un mood o vacío para "sorpréndeme". */
        val moodId: String,
        val music: MusicMode,
        val ownMusic: String,
        val keepAudio: Boolean,
        /** Con el sonido original de los vídeos: bajar la música mientras se oyen voces. */
        val duck: Boolean,
        val fade: Int,
        val fadeColor: Int,
        val shortSide: Int,
        val es: Boolean,
        /** Ritmo: 0,5 (más calmado, planos el doble de largos) a 2 (más rápido). */
        val pace: Float = 1f,
        /** Encuadre y zoom hacia las caras o el punto de interés de las fotos. */
        val smart: Boolean = true,
        /** Buscar el mejor tramo de los vídeos largos (nitidez, movimiento, sin cortes); si no, se usa el principio. */
        val bestSegments: Boolean = true,
        /** Subtítulos automáticos de los vídeos con voz (necesita los modelos de IA descargados). */
        val subtitles: Boolean = false,
        /** Para repetir el montaje sin volver a buscar la música: la ya elegida y sus golpes. */
        val reuseMusic: MediaRef? = null,
        val reuseBeats: List<Long>? = null,
    )

    class Result(val project: Project, val mood: Mood, val bpm: Int, val musicTitle: String, val warnings: List<Int>, val music: MediaRef?, val beats: List<Long>)

    /** Cuadrícula de pulsos: el pulso k cae en phaseUs + k * periodUs. */
    class Grid(val periodUs: Long, val phaseUs: Long, val confident: Boolean)

    // ------------------------------------------------------------------------------------------------
    // Ritmo
    // ------------------------------------------------------------------------------------------------

    /**
     * Ajusta una cuadrícula regular a los golpes detectados ([beatsUs], µs de la canción). Se barre el tempo (50 a 200
     * pulsos por minuto) midiendo cuánto se concentran los golpes en la misma fase del pulso, con preferencia por el tempo
     * cercano a [targetBpm] (así se evita el doble o la mitad del tempo real). Después se afina el periodo con una recta
     * de mínimos cuadrados sobre los golpes que encajan, para que no se acumule desfase en un minuto de música.
     * Devuelve null si los golpes no son lo bastante regulares.
     */
    fun fitGrid(beatsUs: List<Long>, targetBpm: Float): Grid? {
        if (beatsUs.size < 8) return null
        var bestScore = -1.0
        var bestC = 0.0
        var bestR = 0.0
        var bestAng = 0.0
        var bpm = 50.0
        while (bpm <= 200.0) {
            val c = 60e6 / bpm
            var re = 0.0
            var im = 0.0
            for (t in beatsUs) {
                val a = 2 * PI * (t.toDouble() % c) / c
                re += cos(a); im += sin(a)
            }
            val r = hypot(re, im) / beatsUs.size
            val octaves = ln(bpm / targetBpm) / ln(2.0)
            val score = r * Math.exp(-0.5 * (octaves / 0.45).pow(2))
            if (score > bestScore) { bestScore = score; bestC = c; bestR = r; bestAng = atan2(im, re) }
            bpm += 0.25
        }
        if (bestR < 0.3) return null
        var c = bestC
        var phase = bestAng / (2 * PI) * c
        repeat(2) {
            var n = 0
            var sk = 0.0; var st = 0.0; var skk = 0.0; var skt = 0.0
            for (t in beatsUs) {
                val k = Math.round((t - phase) / c).toDouble()
                if (abs(t - (phase + k * c)) < 0.15 * c) { n++; sk += k; st += t; skk += k * k; skt += k * t }
            }
            val den = n * skk - sk * sk
            if (n >= 6 && den != 0.0) {
                c = (n * skt - sk * st) / den
                phase = (st - c * sk) / n
            }
        }
        if (c < 250_000 || c > 1_500_000) return null
        val period = c.toLong()
        val ph = ((phase.toLong() % period) + period) % period
        return Grid(period, ph, true)
    }

    /** Estilos de ocasión: solo se usan si el usuario los elige, nunca en "Sorpréndeme" ni al azar. */
    val occasionIds = setOf("wedding", "birthday", "christmas", "baby", "horror")
    private val occasion get() = occasionIds

    /** Estilo al azar para "Sorpréndeme" (sin los de ocasión). */
    fun randomMood(rnd: Random): Mood = moods.filter { it.id !in occasion }.let { it[rnd.nextInt(it.size)] }

    /** Mood más adecuado al tempo de una canción (con algo de azar entre los parecidos). */
    private fun suggestMood(bpm: Float, rnd: Random): Mood {
        val ids = when {
            bpm < 72 -> listOf("melancholic", "calm", "romantic", "mystery", "nature")
            bpm < 95 -> listOf("romantic", "nostalgic", "calm", "epic", "elegant", "nature", "cozy", "urban", "lofi")
            bpm < 118 -> listOf("happy", "epic", "nostalgic", "adventure", "summer", "futuristic", "retro80", "fashion")
            else -> listOf("dynamic", "fun", "happy", "sport", "party", "gaming")
        }
        return mood(ids[rnd.nextInt(ids.size)]) ?: moods[0]
    }

    // Secciones del montaje.
    private const val INTRO = 0
    private const val BUILD = 1
    private const val CLIMAX = 2
    private const val OUTRO = 3

    /** Sección del plano [i] de [n]: inicio, desarrollo, clímax (del 55 al 85 %) y final. Con pocos planos no hay clímax. */
    private fun sectionOf(i: Int, n: Int): Int = when {
        n < 6 -> if (i == n - 1 && n > 2) OUTRO else BUILD
        i < max(1, n * 12 / 100) -> INTRO
        i >= n - max(1, n / 10) -> OUTRO
        i >= n * 55 / 100 && i < n * 85 / 100 -> CLIMAX
        else -> BUILD
    }

    /** Efecto con su parámetro principal (el primero de su definición) a [amount]. */
    private fun fxInst(id: String, amount: Float): EffectInstance {
        val key = Registry.effect(id)?.params?.firstOrNull()?.key
        return EffectInstance(defId = id, params = if (key != null) mapOf(key to amount) else emptyMap())
    }

    /**
     * Intro larga de la canción: instante (µs) en que la música "entra" de verdad, o 0 si arranca pronto. Se mide la
     * energía en ventanas de 2 s y se busca la primera vez que se sostiene cerca del nivel habitual de la canción.
     */
    internal fun introSkipUs(path: String): Long {
        val env = (try { Waveforms.loadBlocking(path) } catch (e: Throwable) { null }) ?: return 0L
        val win = Waveforms.RATE * 2
        val n = env.size / win
        if (n < 10) return 0L
        val e = FloatArray(n) { k ->
            var s = 0L
            for (q in k * win until (k + 1) * win) s += env[q].toInt() and 0xFF
            s.toFloat() / win
        }
        val ref = e.sortedArray()[n * 7 / 10]
        if (ref < 8f) return 0L
        val thr = ref * 0.6f
        for (k in 0 until n - 1) if (e[k] >= thr && e[k + 1] >= thr) {
            val t = k * 2_000_000L
            return if (t >= 6_000_000L && t <= n * 1_000_000L) t else 0L
        }
        return 0L
    }

    // ------------------------------------------------------------------------------------------------
    // Reparto de pulsos
    // ------------------------------------------------------------------------------------------------

    /**
     * Pulsos de cada plano. Parte de los naturales ([nat]) y, si hay [target] (en pulsos), recorta los planos más largos
     * (sin bajar de [min]) o alarga los más cortos (sin pasar de [cap]) hasta acercarse. Nunca elimina planos: si el objetivo
     * es menor que la suma de mínimos, el montaje queda algo más largo.
     */
    fun allocateBeats(nat: IntArray, min: IntArray, cap: IntArray, target: Int): IntArray {
        val b = nat.copyOf()
        if (target <= 0) return b
        var sum = b.sum()
        while (sum > target) {
            var j = -1
            for (i in b.indices) if (b[i] > min[i] && (j < 0 || b[i] > b[j])) j = i
            if (j < 0) break
            b[j]--; sum--
        }
        while (sum < target) {
            var j = -1
            for (i in b.indices) if (b[i] < cap[i] && (j < 0 || b[i] < b[j])) j = i
            if (j < 0) break
            b[j]++; sum++
        }
        return b
    }

    private class Shot(val ref: MediaRef, val isVideo: Boolean, val srcStart: Long, val nat: Int, val min: Int, val cap: Int)

    private fun ratio(r: MediaRef) = r.width.toFloat().coerceAtLeast(1f) / r.height.coerceAtLeast(1)

    /**
     * Transición de entrada de cada plano (índice 0 sin transición). Reglas:
     *  - inicio y final usan las suaves; el clímax, las intensas (siempre al abrir frase y una de cada tres veces en el resto);
     *  - al abrir una frase (cada 8 pulsos) se usa una transición de acento, más larga;
     *  - nunca se repite la anterior y se reparte el uso por igual entre las elegidas;
     *  - entre medios de formato muy distinto (vertical y horizontal) solo transiciones suaves;
     *  - nunca dura más de 0,45 pulsos, para que el corte siga cayendo en el tiempo.
     */
    private fun planTransitions(m: Mood, normal: List<String>, shots: List<Shot>, beatsPer: IntArray, secs: IntArray, period: Long, rnd: Random): Array<Transition?> {
        val out = arrayOfNulls<Transition>(shots.size)
        if (normal.isEmpty()) return out
        val soft = setOf("fade", "dissolve", "blur", "dip_black", "dip_white", "wipe_soft", "dream", "light_leak")
        val used = HashMap<String, Int>()
        var last = ""
        var b = 0
        val maxD = period * 45 / 100
        for (i in 1 until shots.size) {
            b += beatsPer[i - 1]
            val accent = b % 8 == 0 && m.accents.isNotEmpty()
            var pool = when {
                secs[i] == INTRO || secs[i] == OUTRO -> m.soft
                secs[i] == CLIMAX && (accent || rnd.nextInt(3) == 0) -> m.climax
                accent -> m.accents
                else -> normal
            }.ifEmpty { normal }
            if (abs(ln(ratio(shots[i - 1].ref) / ratio(shots[i].ref))) > 0.4f) pool = pool.filter { it in soft }.ifEmpty { (m.soft + normal).filter { it in soft } }.ifEmpty { listOf("fade") }
            val cand = pool.filter { it != last }.ifEmpty { pool }
            val least = cand.minOf { used[it] ?: 0 }
            val id = cand.filter { (used[it] ?: 0) == least }.let { it[rnd.nextInt(it.size)] }
            used[id] = (used[id] ?: 0) + 1
            last = id
            val base = min(m.transitionMs * 1000L, maxD)
            val dur = if (accent) min(base * 3 / 2, maxD) else base
            out[i] = Transition(id, dur / 2 * 2)
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------
    // Montaje
    // ------------------------------------------------------------------------------------------------

    suspend fun build(context: Context, container: AppContainer, req: Request, onStage: (stage: Int, progress: Float) -> Unit): Result = withContext(Dispatchers.Default) {
        val warnings = ArrayList<Int>()
        val rnd = Random(System.nanoTime())

        onStage(R.string.as_stage_media, 0f)
        val refs = req.media.mapNotNull { Importer.probe(context, it) }.filter { it.hasVideo || it.isImage }
        if (refs.isEmpty()) throw IllegalStateException("no media")

        // --- Música y mood. La canción propia se analiza antes: si el usuario no elige mood, el tempo lo decide.
        var chosen = mood(req.moodId)
        var music: MediaRef? = null
        var beats: List<Long> = emptyList()
        if (req.reuseMusic != null) {
            music = req.reuseMusic
            beats = req.reuseBeats ?: emptyList()
        } else if (req.music == MusicMode.OWN && req.ownMusic.isNotBlank()) {
            onStage(R.string.as_stage_rhythm, 0f)
            music = Importer.probe(context, req.ownMusic, MediaType.AUDIO)
            if (music == null) warnings.add(R.string.as_warn_music)
        } else if (req.music == MusicMode.AUTO) {
            chosen = chosen ?: randomMood(rnd)
            onStage(R.string.as_stage_music, 0f)
            music = try { fetchMusic(context, container, chosen, rnd) { onStage(R.string.as_stage_music, it) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { ErrorLog.log("AutoEdit/music", e); null }
            if (music == null) warnings.add(R.string.as_warn_music)
        }
        if (music != null && beats.isEmpty()) {
            onStage(R.string.as_stage_rhythm, 0.5f)
            beats = try { Analysis.beats(music.path) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { ErrorLog.log("AutoEdit/beats", e); emptyList() }
        }
        val m: Mood = chosen ?: run {
            // Sorpréndeme con canción propia: se elige según su tempo; sin ritmo claro, al azar.
            val g = fitGrid(beats, 105f)
            if (g != null) suggestMood(60e6f / g.periodUs, rnd) else randomMood(rnd)
        }

        var grid: Grid? = if (music != null) fitGrid(beats, m.bpm.toFloat()) else null
        if (music != null && grid == null) warnings.add(R.string.as_warn_rhythm)
        val fixed = grid == null
        if (grid == null) grid = Grid((60e6 / m.bpm).toLong(), 0L, false)
        val period = grid.periodUs
        // Con la cuadrícula fija la canción se alinea por su primer golpe, si se detectó.
        val phase = if (music != null && !fixed) grid.phaseUs else if (music != null) (beats.firstOrNull() ?: 0L) % period else 0L

        // --- Ritmo del estilo ajustado por el usuario: con más ritmo los planos duran menos pulsos.
        val pace = req.pace.coerceIn(0.5f, 2f)
        val photoB = max(1, (m.photoBeats / pace).roundToInt())
        val videoB = max(1, (m.videoBeats / pace).roundToInt())
        val minB = max(1, min(photoB, (m.minBeats / pace).roundToInt()))

        // --- Duración pedida y repetición de los medios si no bastan para llegar a ella
        val targetBeats = if (req.targetSec > 0) (req.targetSec * 1_000_000L / period).toInt() else 0
        val estNat = refs.sumOf { if (it.isImage || it.durationUs <= 0L) photoB else videoB }
        val cycles = if (targetBeats > estNat * 2) min(8, ceil(targetBeats / (estNat * 1.5)).toInt()) else 1
        val maxD = period * 45 / 100

        // --- Caras y puntos de interés de las fotos (encuadre y zoom inteligentes)
        val focus = HashMap<String, SmartFrame.Focus?>()
        if (req.smart) {
            val imgs = refs.filter { it.isImage }.distinctBy { it.path }
            for ((k, r) in imgs.withIndex()) {
                onStage(R.string.as_stage_frame, k / imgs.size.toFloat())
                focus[r.path] = SmartFrame.analyze(r.path)
            }
        }

        // --- Planos
        val shots = ArrayList<Shot>()
        val starts = HashMap<Int, Long>()
        var scenesBudget = 6
        val total0 = refs.size * cycles
        val secs = IntArray(total0) { sectionOf(it, total0) }
        // Duración natural según la sección: inicio y final respiran (planos más largos) y el clímax de los moods rápidos corta al doble.
        fun paced(beats: Int, sec: Int): Int = when {
            sec == CLIMAX && m.fastClimax -> max(minB, beats / 2)
            sec == INTRO || sec == OUTRO -> (beats * 3 + 1) / 2
            else -> beats
        }
        for (k in 0 until total0) {
            val idx = k % refs.size
            val cycle = k / refs.size
            val ref = refs[idx]
            onStage(R.string.as_stage_scenes, k / total0.toFloat())
            if (ref.isImage || ref.durationUs <= 0L) {
                val cap = max(minB, photoB * 3)
                // De vez en cuando un plano más largo da respiro al ritmo.
                val nat = paced(if (k % 4 == 3 && secs[k] != CLIMAX) photoB * 2 else photoB, secs[k]).coerceIn(minB, cap)
                shots.add(Shot(ref, false, 0L, nat, minB, cap))
                continue
            }
            val natLen = videoB * period + maxD
            var start = 0L
            if (ref.durationUs > natLen + 1_000_000L) {
                val first = starts.getOrPut(idx) {
                    if (req.bestSegments && scenesBudget > 0) { scenesBudget--; bestSegmentStart(ref, natLen, req.keepAudio || music == null) }
                    else min(ref.durationUs / 10, ref.durationUs - natLen).coerceAtLeast(0L)
                }
                // En una repetición se usa otro tramo del mismo vídeo.
                val room = ref.durationUs - natLen
                start = if (cycle == 0 || room <= natLen / 2) first else (first + cycle * natLen) % room
            }
            val cap = max(1, ((ref.durationUs - start - maxD) / period).toInt())
            shots.add(Shot(ref, true, start, paced(videoB, secs[k]).coerceIn(1, cap), min(minB, cap), cap))
        }
        val beatsPer = allocateBeats(IntArray(shots.size) { shots[it].nat }, IntArray(shots.size) { shots[it].min }, IntArray(shots.size) { shots[it].cap }, targetBeats)
        if (targetBeats > 0 && beatsPer.sum() < targetBeats * 9 / 10) warnings.add(R.string.as_warn_short)

        // --- Clips de la pista principal. El plano i ocupa de B_i a B_i + n_i pulsos; con transición de duración d_i en su
        // entrada, cada clip se extiende medio d hacia sus vecinos para que el centro de cada transición caiga en el pulso.
        onStage(R.string.as_stage_build, 0f)
        // Estilo de este montaje: cada vez se elige una variante distinta de la receta del mood.
        val normalTr = m.transitions.shuffled(rnd).take(5)
        val look = m.filters[rnd.nextInt(m.filters.size)]
        val kb = m.kenBurns.shuffled(rnd)
        val beatFx = m.beatFx.shuffled(rnd).take(2)
        val allOverlays = m.overlays + (moodOverlays[m.id] ?: emptyList())
        val overlay = if (allOverlays.isEmpty()) emptyList() else allOverlays[rnd.nextInt(allOverlays.size)]
        val trans = planTransitions(m, normalTr, shots, beatsPer, secs, period, rnd)
        fun td(i: Int): Long = if (i <= 0 || i >= shots.size) 0L else (trans[i]?.durationUs ?: 0L)
        // Formato automático: horizontal si la mayoría de los medios lo son; si no, vertical.
        val asp = if (req.aspect.first == 0) (if (refs.count { it.width >= it.height } * 2 > refs.size) 16 to 9 else 9 to 16) else req.aspect
        val (w, h) = sizeFor(asp.first, asp.second, req.shortSide)
        val canvasAspect = w.toFloat() / h
        val muteVideos = music != null && !req.keepAudio
        val clips = ArrayList<Clip>()
        var cursor = 0L
        var bAcc = 0
        for ((i, s) in shots.withIndex()) {
            val dur = beatsPer[i] * period + td(i) / 2 + td(i + 1) / 2
            // Si el medio casi tiene la forma del lienzo se rellena; si no, entra entero sobre el fondo desenfocado
            // (las fotos con caras que caben en el recorte se encuadran con SmartFrame).
            val fit = if (abs(ln(ratio(s.ref) / canvasAspect)) < 0.3f) FitMode.FILL else FitMode.FIT
            var c = Importer.clipFor(s.ref, dur)
            val sec = secs[i]
            val opensPhrase = i > 0 && bAcc % 8 == 0
            if (s.isVideo) {
                val avail = (s.ref.durationUs - s.srcStart).coerceAtLeast(100_000L)
                // Sin su sonido, los moods tranquilos van a cámara lenta; si falta metraje se ralentiza lo justo.
                var speed = if (muteVideos) m.videoSpeed else 1f
                if (avail < dur * speed) speed = (avail.toFloat() / dur).coerceIn(0.5f, 1f)
                val span = min(avail, (dur * speed).toLong())
                c = c.copy(srcInUs = s.srcStart, srcOutUs = s.srcStart + span, speed = speed, durationUs = dur, muted = muteVideos)
                // Rampa de velocidad en los planos de acento: se toma del origen justo lo que hace falta para que el plano dure lo mismo.
                if (muteVideos && m.ramps.isNotEmpty() && (opensPhrase || sec == CLIMAX) && beatsPer[i] >= 2 && rnd.nextInt(3) != 0) {
                    val ramp = m.ramps[rnd.nextInt(m.ramps.size)]
                    val probe = c.copy(speed = 1f, speedCurve = ramp.mapIndexed { j, v -> SpeedPoint(j / (ramp.size - 1).toFloat(), v) })
                    val sp = probe.segmentSpeeds()
                    var inv = 0.0
                    for (x in sp) inv += 1.0 / x
                    val rspan = (dur * (sp.size / inv)).toLong()
                    if (rspan <= avail) c = probe.copy(srcOutUs = s.srcStart + rspan)
                }
            }
            val sm = if (req.smart && !s.isVideo) SmartFrame.plan(focus[s.ref.path], ratio(s.ref), canvasAspect, dur, i % 2 == 0) else null
            val fxs = ArrayList<EffectInstance>()
            val kfs = HashMap<String, List<Keyframe>>(sm?.keyframes ?: emptyMap())
            for ((id, amount) in m.effects) fxs.add(fxInst(id, amount))
            if (opensPhrase) for ((id, amount) in m.accentFx) fxs.add(fxInst(id, amount))
            if (sec == CLIMAX) for ((id, amount) in m.climaxFx) fxs.add(fxInst(id, amount))
            // Efectos a ritmo: un golpe (subida rápida y caída) en cada pulso que toca, con fotogramas clave editables.
            if (sec == BUILD || sec == CLIMAX) for ((id, peak, every) in beatFx) {
                val inst = fxInst(id, 0f)
                val key = inst.params.keys.firstOrNull() ?: continue
                val ev = if (sec == CLIMAX) max(1, every / 2) else every
                val list = ArrayList<Keyframe>()
                list.add(Keyframe(0L, 0f, Easing.HOLD))
                for (b in 0 until beatsPer[i]) {
                    if ((bAcc + b) % ev != 0) continue
                    // El primer pulso del plano ya lo marca la transición.
                    if (b == 0 && td(i) > 0L) continue
                    val t = td(i) / 2 + b * period
                    if (t >= 60_000L) list.add(Keyframe(t - 50_000L, 0f, Easing.HOLD))
                    list.add(Keyframe(t, if (sec == CLIMAX) peak * 1.25f else peak, Easing.EASE_OUT))
                    list.add(Keyframe(min(t + period * 6 / 10, dur), 0f, Easing.EASE_OUT))
                }
                if (list.size > 1) { fxs.add(inst); kfs["fx:" + inst.id + ":" + key] = list }
            }
            c = c.copy(
                durationUs = dur,
                transform = sm?.transform ?: Transform(fit = fit),
                keyframes = kfs,
                filterId = look,
                filterIntensity = m.filterIntensity,
                color = m.grade,
                effects = fxs,
                animLoop = if (!s.isVideo && sm == null && kb.isNotEmpty()) AnimRef(kb[i % kb.size]) else null,
                transitionIn = trans[i],
            )
            clips.add(c)
            cursor += beatsPer[i] * period
            bAcc += beatsPer[i]
        }

        val now = System.currentTimeMillis()
        val name = req.title.trim().ifBlank { context.getString(R.string.as_project_name, context.getString(m.nameRes)) }
        var p = Project(id = newId(), name = name, createdAt = now, modifiedAt = now, width = w, height = h, fps = 30, background = CanvasBg(BgType.BLUR))
        p = p.insertMain(clips)
        val total = p.mainTrack.clips.maxOfOrNull { it.endUs } ?: cursor

        // --- Fundido de color al inicio y/o al final (clip de color con opacidad animada, editable en el editor)
        val fadeUs = min(1_000_000L, total / 6).coerceAtLeast(300_000L)
        if (req.fade == Fade.START || req.fade == Fade.BOTH) p = p.addToTrack(fadeClip(req.fadeColor, true, 0L, fadeUs))
        if (req.fade == Fade.END || req.fade == Fade.BOTH) p = p.addToTrack(fadeClip(req.fadeColor, false, (total - fadeUs).coerceAtLeast(0L), fadeUs))

        // --- Capa de ambiente del mood sobre todo el vídeo (capa de ajuste, editable): partículas, grano, marcos, barras de cine…
        val amb = ArrayList<EffectInstance>()
        val ambKf = HashMap<String, List<Keyframe>>()
        for ((id, amount) in overlay) {
            val inst = fxInst(id, amount)
            val key = inst.params.keys.firstOrNull()
            // La capa de ambiente se compone encima de los fundidos de color: su cantidad sube y baja con ellos.
            if (key != null && req.fade != Fade.NONE) {
                val k = ArrayList<Keyframe>()
                val atStart = req.fade == Fade.START || req.fade == Fade.BOTH
                val atEnd = req.fade == Fade.END || req.fade == Fade.BOTH
                k.add(Keyframe(0L, if (atStart) 0f else amount, Easing.EASE_OUT))
                if (atStart) k.add(Keyframe(fadeUs, amount, Easing.EASE_OUT))
                if (atEnd) { k.add(Keyframe((total - fadeUs).coerceAtLeast(fadeUs), amount, Easing.EASE_IN)); k.add(Keyframe(total, 0f, Easing.EASE_IN)) }
                ambKf["fx:" + inst.id + ":" + key] = k
            }
            amb.add(inst)
        }
        if (m.letterbox > 0f) {
            val bars = fxInst("letterbox", m.letterbox)
            ambKf["fx:" + bars.id + ":size"] = listOf(Keyframe(0L, 0f, Easing.EASE_OUT), Keyframe(min(1_500_000L, total / 4), m.letterbox, Easing.EASE_OUT))
            amb.add(bars)
        }
        if (amb.isNotEmpty()) p = p.addToTrack(Clip(kind = ClipKind.ADJUST, startUs = 0L, durationUs = total, effects = amb, keyframes = ambKf))

        // --- Título al inicio y mensaje final al cierre (plantilla al azar entre las del mood)
        val titleMax = min(4_000_000L, total * 2 / 5)
        if (req.title.isNotBlank() && titleMax >= 1_200_000L) textClip(m.titles[rnd.nextInt(m.titles.size)], req.title.trim(), 0L, titleMax, req.es)?.let { p = p.addToTrack(it) }
        val outroMax = min(4_000_000L, total * 3 / 10)
        if (req.outro.isNotBlank() && outroMax >= 1_200_000L) {
            val c = textClip(m.outros[rnd.nextInt(m.outros.size)], req.outro.trim(), 0L, outroMax, req.es)
            if (c != null) p = p.addToTrack(c.copy(startUs = (total - c.durationUs).coerceAtLeast(0L)))
        }

        // --- Música: empieza en un pulso y, si la canción es más corta que el montaje, se repite en pulsos enteros.
        if (music != null) {
            // Intro larga: se salta hasta un par de pulsos antes de que entre la música (en pulsos enteros, para no perder
            // la cuadrícula) y se entra con un fundido algo más largo.
            var from = phase
            val skip = introSkipUs(music.path)
            if (skip > 0L) {
                val cand = phase + ((skip - phase) / period - 2).coerceAtLeast(0L) * period
                if (music.durationUs - cand >= min(total, 20_000_000L)) from = cand
            }
            val skipped = from != phase
            val avail = (music.durationUs - from).coerceAtLeast(1_000_000L)
            var at = 0L
            var copies = 0
            while (at < total && copies < 60) {
                val remaining = total - at
                val last = avail >= remaining
                val len = if (last) remaining else max(period, (avail / period) * period)
                if (len <= 0L) break
                val base = Importer.clipFor(music, 0L, asAudio = true)
                p = p.addToTrack(base.copy(
                    startUs = at, srcInUs = from, srcOutUs = from + min(len, avail), durationUs = len,
                    fadeInUs = if (copies == 0) (if (skipped) 900_000L else 400_000L) else 300_000L,
                    fadeOutUs = if (last) min(2_500_000L, len / 2) else 300_000L,
                    volume = if (req.keepAudio) 0.6f else 1f,
                    label = music.title,
                ))
                at += len
                copies++
            }
        }

        // --- Con el sonido original: la música baja sola cuando se oyen voces en los vídeos
        if (music != null && req.keepAudio && req.duck) {
            onStage(R.string.as_stage_voice, 0f)
            val vids = p.mainTrack.clips.filter { it.kind == ClipKind.VIDEO && it.media?.hasAudio == true }.take(12)
            var any = false
            for ((j, v) in vids.withIndex()) {
                onStage(R.string.as_stage_voice, j / vids.size.toFloat())
                val ranges = voiceRanges(container, v.media!!.path, v.srcInUs, v.srcOutUs)
                if (ranges.isEmpty()) continue
                val flat = ranges.flatMap { (a, b) -> listOf(((a - v.srcInUs) / v.speed).toLong().coerceAtLeast(0L), ((b - v.srcInUs) / v.speed).toLong()) }
                p = p.updateClip(v.id) { it.copy(voiceRangesUs = flat, audioFx = it.audioFx.copy(isVoice = true)) }
                any = true
            }
            if (any) p = p.copy(autoDuck = true, duckAmount = 0.25f)
        }
        // --- Subtítulos automáticos de los vídeos con voz (solo si se oyen: sin música o con el sonido original)
        if (req.subtitles && (music == null || req.keepAudio)) {
            val quality = container.settings.value.aiSubQuality
            if (!container.models.isReady(Models.VAD) || !container.models.isReady(Models.whisper(quality))) warnings.add(R.string.as_warn_subs_model)
            else {
                val vids = p.mainTrack.clips.filter { it.kind == ClipKind.VIDEO && it.media?.hasAudio == true }.take(8)
                val subs = ArrayList<Clip>()
                for ((j, v) in vids.withIndex()) {
                    onStage(R.string.as_stage_subs, j / vids.size.toFloat())
                    try {
                        val lines = AiTasks.transcribe(container.models, CompositionBuilder.audioPath(v, v.media!!.path), v.srcInUs, v.srcOutUs, quality, "", false) { onStage(R.string.as_stage_subs, (j + it) / vids.size) }
                        for (sub in Speech.subtitles(lines, 28, 2)) {
                            val a = (v.startUs + ((sub.startUs - v.srcInUs) / v.speed).toLong()).coerceAtLeast(v.startUs)
                            val b = min(v.startUs + ((sub.endUs - v.srcInUs) / v.speed).toLong(), v.endUs)
                            if (b - a < 200_000L) continue
                            subs.add(Clip(kind = ClipKind.TEXT, startUs = a, durationUs = b - a, text = TextSpec(listOf(Layer(text = sub.text)), subtitle = true, wordsUs = sub.wordsUs.map { (it / v.speed).toLong() })))
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        ErrorLog.log("AutoEdit/subs", e)
                    }
                }
                for (c in subs) p = p.addToTrack(c, TrackType.TEXT)
                if (subs.isEmpty()) warnings.add(R.string.as_warn_subs_none)
            }
        }
        val bpm = (60e6 / period).roundToInt()
        Result(p, m, bpm, music?.title ?: "", warnings, music, beats)
    }

    /** Tramos con voz de un tramo de vídeo: con el modelo de voz (Silero VAD) si está instalado; si no, por energía. */
    private suspend fun voiceRanges(container: AppContainer, path: String, fromUs: Long, toUs: Long): List<Pair<Long, Long>> {
        try {
            if (container.models.isReady(Models.VAD)) return Speech.speechRanges(container.models, path, fromUs, toUs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            ErrorLog.log("AutoEdit/vad", e)
        }
        return try { Analysis.nonSilent(path, fromUs, toUs, 0.6f) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { emptyList() }
    }

    /** Fundido: clip de color plano cuya opacidad baja (al inicio) o sube (al final). */
    private fun fadeClip(color: Int, atStart: Boolean, startUs: Long, durUs: Long): Clip {
        val kf = if (atStart) listOf(Keyframe(0L, 1f, Easing.EASE_OUT), Keyframe(durUs, 0f, Easing.EASE_OUT))
        else listOf(Keyframe(0L, 0f, Easing.EASE_IN), Keyframe(durUs, 1f, Easing.EASE_IN))
        return Clip(kind = ClipKind.COLOR, startUs = startUs, durationUs = durUs, fill = ColorFill(listOf(color)), keyframes = mapOf("opacity" to kf))
    }

    /** Clip de texto de una plantilla con el texto del usuario: se conserva la capa principal (la mayor) y las formas. */
    private fun textClip(templateId: String, text: String, startUs: Long, maxUs: Long, es: Boolean): Clip? {
        val t = Templates.get(templateId) ?: return null
        val c = t.toClip(es, startUs)
        val spec = c.text ?: return null
        val main = spec.layers.filter { it.kind == LayerKind.TEXT }.maxByOrNull { it.size } ?: return null
        // Se conservan también las copias de la capa principal (los títulos glitch/neón duplican el texto con otro color).
        val layers = spec.layers.filter { it.kind != LayerKind.TEXT || it.text == main.text }.map { if (it.kind == LayerKind.TEXT) it.copy(text = text) else it }
        return c.copy(durationUs = min(c.durationUs, maxUs), text = spec.copy(layers = layers))
    }

    /** Busca en Jamendo música instrumental del mood (primero con velocidad, luego sin ella) y la descarga. */
    internal suspend fun fetchMusic(context: Context, container: AppContainer, m: Mood, rnd: Random, progress: (Float) -> Unit): MediaRef? {
        val tags = m.tags.joinToString(" ")
        for (speed in listOf(m.speeds.joinToString(" "), "")) {
            val res = container.online.search(Query(OnlineType.MUSIC, duration = 2, tags = tags, instrumental = true, speed = speed), setOf(ApiConfig.JAMENDO))
            val pool = res.items.filter { it.durationSec >= 60f }.ifEmpty { res.items }
            if (pool.isEmpty()) continue
            val item = pool.take(12).shuffled(rnd).first()
            val file = container.online.download(item, progress) ?: continue
            val ref = Importer.probe(context, file.path, MediaType.AUDIO) ?: continue
            return ref.copy(title = listOf(item.title, item.author).filter { it.isNotBlank() }.joinToString(" — "))
        }
        return null
    }

    /**
     * Inicio del mejor tramo de un vídeo largo. Se muestrea cada 0,75 s (los primeros 75 s) y cada ventana puntúa por:
     * nitidez (descarta lo desenfocado), algo de movimiento (ni plano estático ni sacudida), ausencia de cortes de escena
     * dentro del tramo y, si se oye el sonido original, que haya audio. Gana la ventana con más puntuación.
     */
    private suspend fun bestSegmentStart(ref: MediaRef, lenUs: Long, useAudio: Boolean): Long {
        val limit = min(ref.durationUs, 75_000_000L)
        val step = 750_000L
        val f = Analysis.frameFeatures(ref.path, 0L, limit, step)
        if (f.size < 3) return 0L
        val maxSharp = f.maxOf { it.sharp }.coerceAtLeast(1f)
        val env = if (useAudio && ref.hasAudio) try { Waveforms.loadBlocking(ref.path) } catch (e: Throwable) { null } else null
        val win = max(2, (lenUs / step).toInt())
        var best = -1e9f
        var bestT = 0L
        for (i in 0..(f.size - win).coerceAtLeast(0)) {
            var sh = 0f; var mo = 0f; var cuts = 0; var n = 0
            for (k in i until min(f.size, i + win)) {
                sh += f[k].sharp / maxSharp
                mo += f[k].motion
                if (f[k].motion > 30f) cuts++
                n++
            }
            sh /= n; mo /= n
            var score = sh + (if (mo < 1.5f) 0.1f else if (mo < 14f) 0.4f else 0.1f) - cuts * 0.6f
            if (env != null && env.isNotEmpty()) {
                val a = (f[i].tUs * Waveforms.RATE / 1_000_000L).toInt().coerceIn(0, env.size - 1)
                val b = ((f[i].tUs + win * step) * Waveforms.RATE / 1_000_000L).toInt().coerceIn(a + 1, env.size)
                var e = 0L
                for (q in a until b) e += env[q].toInt() and 0xFF
                score += 0.25f * (e.toFloat() / (b - a) / 255f)
            }
            if (score > best) { best = score; bestT = f[i].tUs }
        }
        return bestT.coerceIn(0L, (ref.durationUs - lenUs).coerceAtLeast(0L))
    }
}
