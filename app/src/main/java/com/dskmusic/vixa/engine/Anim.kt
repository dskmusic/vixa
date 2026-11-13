package com.dskmusic.vixa.engine

import com.dskmusic.vixa.R
import com.dskmusic.vixa.model.AnimRef
import com.dskmusic.vixa.model.Easing
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Estado animado de un elemento (clip, capa de texto o sticker). Los animadores lo modifican en función
 * del progreso. dx/dy van en fracción del lienzo; reveal* recortan el elemento (0..1 por cada lado).
 */
class AnimState {
    var dx = 0f
    var dy = 0f
    var scale = 1f
    /** Estirado por eje, encima de [scale] (volteos, gelatina…). */
    var sx = 1f
    var sy = 1f
    var rotation = 0f
    var alpha = 1f
    var revealL = 0f
    var revealT = 0f
    var revealR = 0f
    var revealB = 0f
    var blur = 0f
    /** Texto: fracción de caracteres visibles (1 = todos) y modo por carácter. */
    var chars = 1f
    var charMode = CHAR_NONE
    var charPhase = 0f

    fun reset() {
        dx = 0f; dy = 0f; scale = 1f; sx = 1f; sy = 1f; rotation = 0f; alpha = 1f
        revealL = 0f; revealT = 0f; revealR = 0f; revealB = 0f; blur = 0f
        chars = 1f; charMode = CHAR_NONE; charPhase = 0f
    }

    companion object {
        const val CHAR_NONE = 0
        const val CHAR_TYPE = 1
        const val CHAR_FADE = 2
        const val CHAR_POP = 3
        const val CHAR_RISE = 4
        const val CHAR_WAVE = 5
        const val CHAR_JITTER = 6
        const val CHAR_DROP = 7
        const val CHAR_SPIN = 8
        const val CHAR_ZOOM = 9
        const val CHAR_SLIDE = 10
        const val CHAR_RANDOM = 11
        const val CHAR_SCRAMBLE = 12
        const val CHAR_BOUNCE = 13
        const val CHAR_PULSE = 14
        const val CHAR_SWAY = 15
    }
}

/** Estado de un carácter en una animación por letras. dx/dy van en múltiplos del tamaño de letra; rot en grados. */
class CharFx {
    var a = 1f
    var dx = 0f
    var dy = 0f
    var sc = 1f
    var rot = 0f
    var hidden = false
}

private fun rnd(seed: Int): Float = (((seed * 1103515245 + 12345) ushr 16) and 0xFF) / 255f

/** Calcula en [o] cómo se dibuja el carácter [index] de [total]. Lo usan el render del vídeo y las vistas previas. */
fun AnimState.charFx(index: Int, total: Int, o: CharFx) {
    o.a = 1f; o.dx = 0f; o.dy = 0f; o.sc = 1f; o.rot = 0f; o.hidden = false
    val pos = chars * total
    // Presencia de este carácter (0..1) con una pequeña rampa para suavizar.
    val k = (pos - index).coerceIn(0f, 1f)
    when (charMode) {
        AnimState.CHAR_TYPE -> o.hidden = index >= ceil(pos.toDouble()).toInt() && chars < 1f
        AnimState.CHAR_FADE -> o.a = k
        AnimState.CHAR_POP -> { o.a = if (k > 0f) 1f else 0f; o.sc = if (k >= 1f) 1f else 0.4f + 0.9f * k }
        AnimState.CHAR_RISE -> { o.a = k; o.dy = (1f - k) * 0.6f }
        AnimState.CHAR_WAVE -> o.dy = sin(charPhase * 6f + index * 0.6f) * 0.12f
        AnimState.CHAR_JITTER -> o.dy = (rnd(index * 37 + (charPhase * 14f).toInt() * 101) - 0.5f) * 0.1f
        AnimState.CHAR_DROP -> { o.a = if (k > 0f) 1f else 0f; o.dy = -(1f - Easing.BOUNCE_OUT.ease(k)) * 1.2f }
        AnimState.CHAR_SPIN -> { o.a = k; o.rot = (1f - k) * -180f; o.sc = 0.3f + 0.7f * k }
        AnimState.CHAR_ZOOM -> { o.a = k; o.sc = 2.4f - 1.4f * k }
        AnimState.CHAR_SLIDE -> { o.a = k; o.dx = (1f - k) * 0.9f }
        // Orden al azar: cada letra tiene su propio umbral de aparición.
        AnimState.CHAR_RANDOM -> o.a = ((chars - rnd(index * 53 + 7) * 0.7f) / 0.3f).coerceIn(0f, 1f)
        // Las letras tiemblan unas posiciones por delante del cursor hasta asentarse.
        AnimState.CHAR_SCRAMBLE -> {
            val kw = ((pos - index + 3f) / 4f).coerceIn(0f, 1f)
            if (kw <= 0f) o.hidden = true
            else if (kw < 1f) {
                val seed = index * 37 + (chars * 40f).toInt() * 101
                o.dx = (rnd(seed) - 0.5f) * 0.5f * (1f - kw)
                o.dy = (rnd(seed + 17) - 0.5f) * 0.7f * (1f - kw)
                o.a = 0.35f + 0.65f * kw
            }
        }
        AnimState.CHAR_BOUNCE -> o.dy = -abs(sin(charPhase * 4f - index * 0.5f)) * 0.18f
        AnimState.CHAR_PULSE -> o.sc = 1f + 0.18f * sin(charPhase * 5f - index * 0.7f)
        AnimState.CHAR_SWAY -> o.rot = sin(charPhase * 4f + index * 0.8f) * 10f
    }
}

enum class AnimKind { IN, OUT, LOOP }

/**
 * Animación como dato. [f] recibe k: en IN/OUT es la "presencia" (0 = fuera, 1 = en su sitio);
 * en LOOP es el tiempo en segundos (o el progreso 0..1 del clip si [overClip]).
 */
class AnimDef(
    val id: String,
    val nameRes: Int,
    val kind: AnimKind,
    val textOnly: Boolean = false,
    val overClip: Boolean = false,
    val f: (s: AnimState, k: Float) -> Unit,
)

object Anims {
    private fun eo(k: Float) = Easing.CUBIC_OUT.ease(k)

    /** Animaciones de entrada; las de salida son las mismas con la presencia invertida. */
    private val base: List<Triple<String, Int, (AnimState, Float) -> Unit>> = listOf(
        Triple("fade", R.string.anim_fade) { s, k -> s.alpha *= k },
        Triple("slide_left", R.string.anim_slide_left) { s, k -> s.dx += (1f - eo(k)) * 0.6f; s.alpha *= (k * 3f).coerceAtMost(1f) },
        Triple("slide_right", R.string.anim_slide_right) { s, k -> s.dx -= (1f - eo(k)) * 0.6f; s.alpha *= (k * 3f).coerceAtMost(1f) },
        Triple("slide_up", R.string.anim_slide_up) { s, k -> s.dy += (1f - eo(k)) * 0.4f; s.alpha *= (k * 3f).coerceAtMost(1f) },
        Triple("slide_down", R.string.anim_slide_down) { s, k -> s.dy -= (1f - eo(k)) * 0.4f; s.alpha *= (k * 3f).coerceAtMost(1f) },
        Triple("zoom_in", R.string.anim_zoom_in) { s, k -> s.scale *= 0.3f + 0.7f * eo(k); s.alpha *= (k * 2f).coerceAtMost(1f) },
        Triple("zoom_out", R.string.anim_zoom_out) { s, k -> s.scale *= 2.2f - 1.2f * eo(k); s.alpha *= (k * 2f).coerceAtMost(1f) },
        Triple("pop", R.string.anim_pop) { s, k -> s.scale *= Easing.BACK_OUT.ease(k).coerceAtLeast(0.01f); s.alpha *= (k * 4f).coerceAtMost(1f) },
        Triple("bounce", R.string.anim_bounce) { s, k -> s.dy -= (1f - Easing.BOUNCE_OUT.ease(k)) * 0.5f; s.alpha *= (k * 5f).coerceAtMost(1f) },
        Triple("elastic", R.string.anim_elastic) { s, k -> s.scale *= Easing.ELASTIC_OUT.ease(k).coerceAtLeast(0.01f); s.alpha *= (k * 4f).coerceAtMost(1f) },
        Triple("spin", R.string.anim_spin) { s, k -> s.rotation += (1f - eo(k)) * -360f; s.scale *= 0.2f + 0.8f * eo(k); s.alpha *= (k * 2f).coerceAtMost(1f) },
        Triple("swing", R.string.anim_swing) { s, k -> s.rotation += (1f - k) * 25f * sin(k * 12f); s.alpha *= (k * 3f).coerceAtMost(1f) },
        Triple("drop", R.string.anim_drop) { s, k -> s.dy -= (1f - Easing.CUBIC_IN.ease(k)) * 0.8f },
        Triple("wipe_right", R.string.anim_wipe_right) { s, k -> s.revealR = maxOf(s.revealR, 1f - eo(k)) },
        Triple("wipe_left", R.string.anim_wipe_left) { s, k -> s.revealL = maxOf(s.revealL, 1f - eo(k)) },
        Triple("wipe_up", R.string.anim_wipe_up) { s, k -> s.revealT = maxOf(s.revealT, 1f - eo(k)) },
        Triple("wipe_down", R.string.anim_wipe_down) { s, k -> s.revealB = maxOf(s.revealB, 1f - eo(k)) },
        Triple("curtain", R.string.anim_curtain) { s, k -> val r = (1f - eo(k)) * 0.5f; s.revealL = maxOf(s.revealL, r); s.revealR = maxOf(s.revealR, r) },
        Triple("blur", R.string.anim_blur) { s, k -> s.blur = maxOf(s.blur, (1f - k) * 0.06f); s.alpha *= k },
        Triple("flicker", R.string.anim_flicker) { s, k -> s.alpha *= if (k >= 1f) 1f else if (sin(k * 60f) > 0.2f - k) 1f else 0.15f },
        // --- Segunda tanda
        Triple("flip_h", R.string.anim_flip_h) { s, k -> s.sx *= Easing.BACK_OUT.ease(k).coerceAtLeast(0.01f); s.alpha *= (k * 4f).coerceAtMost(1f) },
        Triple("flip_v", R.string.anim_flip_v) { s, k -> s.sy *= Easing.BACK_OUT.ease(k).coerceAtLeast(0.01f); s.alpha *= (k * 4f).coerceAtMost(1f) },
        Triple("stretch", R.string.anim_stretch) { s, k -> s.sx *= 2.6f - 1.6f * eo(k); s.sy *= 0.6f + 0.4f * eo(k); s.alpha *= (k * 2.5f).coerceAtMost(1f) },
        Triple("squash", R.string.anim_squash) { s, k -> s.sy *= Easing.ELASTIC_OUT.ease(k).coerceAtLeast(0.01f); s.sx *= 1f + (1f - eo(k)) * 0.5f; s.alpha *= (k * 4f).coerceAtMost(1f) },
        Triple("rise", R.string.anim_rise) { s, k -> s.dy += (1f - eo(k)) * 0.07f; s.alpha *= k },
        Triple("fall", R.string.anim_fall) { s, k -> s.dy -= (1f - eo(k)) * 0.07f; s.alpha *= k },
        Triple("soft_zoom", R.string.anim_soft_zoom) { s, k -> s.scale *= 0.88f + 0.12f * eo(k); s.alpha *= k },
        Triple("zoom_blur", R.string.anim_zoom_blur) { s, k -> s.scale *= 1.7f - 0.7f * eo(k); s.blur = maxOf(s.blur, (1f - k) * 0.05f); s.alpha *= (k * 2f).coerceAtMost(1f) },
        Triple("slam", R.string.anim_slam) { s, k -> s.scale *= 1f + 2.4f * (1f - k * k * k); s.alpha *= (k * 3f).coerceAtMost(1f) },
        Triple("jelly", R.string.anim_jelly) { s, k -> val e = Easing.ELASTIC_OUT.ease(k).coerceIn(0.01f, 1.9f); s.sx *= e; s.sy *= 2f - e; s.alpha *= (k * 4f).coerceAtMost(1f) },
        Triple("roll_in", R.string.anim_roll_in) { s, k -> s.dx -= (1f - eo(k)) * 0.7f; s.rotation -= (1f - eo(k)) * 200f; s.alpha *= (k * 3f).coerceAtMost(1f) },
        Triple("spiral", R.string.anim_spiral) { s, k -> s.rotation += (1f - eo(k)) * 540f; s.scale *= eo(k).coerceAtLeast(0.01f); s.dx += sin((1f - k) * 6f) * (1f - k) * 0.2f; s.alpha *= (k * 3f).coerceAtMost(1f) },
        Triple("swoop", R.string.anim_swoop) { s, k -> s.dx -= (1f - eo(k)) * 0.5f; s.dy += (1f - eo(k)) * 0.35f; s.scale *= 0.4f + 0.6f * eo(k); s.alpha *= (k * 3f).coerceAtMost(1f) },
        Triple("tilt_in", R.string.anim_tilt_in) { s, k -> s.rotation -= (1f - eo(k)) * 18f; s.dy += (1f - eo(k)) * 0.14f; s.alpha *= (k * 2f).coerceAtMost(1f) },
        Triple("back_left", R.string.anim_back_left) { s, k -> s.dx += (1f - Easing.BACK_OUT.ease(k)) * 0.6f; s.alpha *= (k * 4f).coerceAtMost(1f) },
        Triple("back_right", R.string.anim_back_right) { s, k -> s.dx -= (1f - Easing.BACK_OUT.ease(k)) * 0.6f; s.alpha *= (k * 4f).coerceAtMost(1f) },
        Triple("back_up", R.string.anim_back_up) { s, k -> s.dy += (1f - Easing.BACK_OUT.ease(k)) * 0.4f; s.alpha *= (k * 4f).coerceAtMost(1f) },
        Triple("glitch_in", R.string.anim_glitch_in) { s, k ->
            if (k < 1f) { s.dx += sin(floor(k * 14f) * 91.7f) * 0.08f * (1f - k); s.alpha *= if (sin(k * 90f) > -0.3f) 1f else 0.2f }
        },
        Triple("box_reveal", R.string.anim_box_reveal) { s, k ->
            val r = (1f - eo(k)) * 0.5f
            s.revealL = maxOf(s.revealL, r); s.revealR = maxOf(s.revealR, r); s.revealT = maxOf(s.revealT, r); s.revealB = maxOf(s.revealB, r)
        },
        Triple("curtain_v", R.string.anim_curtain_v) { s, k -> val r = (1f - eo(k)) * 0.5f; s.revealT = maxOf(s.revealT, r); s.revealB = maxOf(s.revealB, r) },
    )

    private val textBase: List<Triple<String, Int, (AnimState, Float) -> Unit>> = listOf(
        Triple("typewriter", R.string.anim_typewriter) { s, k -> s.chars = k; s.charMode = AnimState.CHAR_TYPE },
        Triple("letter_fade", R.string.anim_letter_fade) { s, k -> s.chars = k; s.charMode = AnimState.CHAR_FADE },
        Triple("letter_pop", R.string.anim_letter_pop) { s, k -> s.chars = k; s.charMode = AnimState.CHAR_POP },
        Triple("letter_rise", R.string.anim_letter_rise) { s, k -> s.chars = k; s.charMode = AnimState.CHAR_RISE },
        Triple("letter_drop", R.string.anim_letter_drop) { s, k -> s.chars = k; s.charMode = AnimState.CHAR_DROP },
        Triple("letter_spin", R.string.anim_letter_spin) { s, k -> s.chars = k; s.charMode = AnimState.CHAR_SPIN },
        Triple("letter_zoom", R.string.anim_letter_zoom) { s, k -> s.chars = k; s.charMode = AnimState.CHAR_ZOOM },
        Triple("letter_slide", R.string.anim_letter_slide) { s, k -> s.chars = k; s.charMode = AnimState.CHAR_SLIDE },
        Triple("letter_random", R.string.anim_letter_random) { s, k -> s.chars = k; s.charMode = AnimState.CHAR_RANDOM },
        Triple("letter_scramble", R.string.anim_letter_scramble) { s, k -> s.chars = k; s.charMode = AnimState.CHAR_SCRAMBLE },
    )

    private val loops: List<AnimDef> = listOf(
        AnimDef("pulse", R.string.anim_pulse, AnimKind.LOOP) { s, t -> s.scale *= 1f + 0.06f * sin(t * 2f * PI.toFloat()) },
        AnimDef("float", R.string.anim_float, AnimKind.LOOP) { s, t -> s.dy += 0.015f * sin(t * 2f) },
        AnimDef("shake", R.string.anim_shake, AnimKind.LOOP) { s, t -> s.dx += 0.008f * sin(t * 40f); s.dy += 0.006f * sin(t * 53f) },
        AnimDef("wobble", R.string.anim_wobble, AnimKind.LOOP) { s, t -> s.rotation += 5f * sin(t * 4f) },
        AnimDef("rotate", R.string.anim_rotate, AnimKind.LOOP) { s, t -> s.rotation += t * 90f },
        AnimDef("blink", R.string.anim_blink, AnimKind.LOOP) { s, t -> s.alpha *= if (t % 1f < 0.6f) 1f else 0.15f },
        AnimDef("breathe", R.string.anim_breathe, AnimKind.LOOP) { s, t -> s.alpha *= 0.65f + 0.35f * abs(sin(t * 1.5f)) },
        AnimDef("heartbeat", R.string.anim_heartbeat, AnimKind.LOOP) { s, t ->
            val p = t % 1f
            s.scale *= 1f + 0.12f * (if (p < 0.15f) p / 0.15f else if (p < 0.3f) 1f - (p - 0.15f) / 0.15f else if (p < 0.45f) (p - 0.3f) / 0.15f * 0.6f else if (p < 0.6f) 0.6f * (1f - (p - 0.45f) / 0.15f) else 0f)
        },
        AnimDef("wave", R.string.anim_wave, AnimKind.LOOP, textOnly = true) { s, t -> s.charMode = AnimState.CHAR_WAVE; s.charPhase = t },
        AnimDef("jitter", R.string.anim_jitter, AnimKind.LOOP, textOnly = true) { s, t -> s.charMode = AnimState.CHAR_JITTER; s.charPhase = t },
        AnimDef("letter_bounce", R.string.anim_letter_bounce, AnimKind.LOOP, textOnly = true) { s, t -> s.charMode = AnimState.CHAR_BOUNCE; s.charPhase = t },
        AnimDef("letter_pulse", R.string.anim_letter_pulse, AnimKind.LOOP, textOnly = true) { s, t -> s.charMode = AnimState.CHAR_PULSE; s.charPhase = t },
        AnimDef("letter_sway", R.string.anim_letter_sway, AnimKind.LOOP, textOnly = true) { s, t -> s.charMode = AnimState.CHAR_SWAY; s.charPhase = t },
        AnimDef("sway", R.string.anim_sway, AnimKind.LOOP) { s, t -> s.rotation += 3f * sin(t * 1.5f); s.dx += 0.01f * sin(t * 0.9f) },
        AnimDef("hop", R.string.anim_hop, AnimKind.LOOP) { s, t -> s.dy -= abs(sin(t * 4f)) * 0.03f },
        AnimDef("drift", R.string.anim_drift, AnimKind.LOOP) { s, t -> s.dx += 0.02f * sin(t * 0.7f); s.dy += 0.015f * cos(t * 0.9f) },
        AnimDef("breathe_zoom", R.string.anim_breathe_zoom, AnimKind.LOOP) { s, t -> s.scale *= 1.03f + 0.03f * sin(t * 1.2f) },
        AnimDef("beat", R.string.anim_beat, AnimKind.LOOP) { s, t -> s.scale *= 1f + 0.09f * exp(-((t * 2f) % 1f) * 6f) },
        AnimDef("jelly_loop", R.string.anim_jelly_loop, AnimKind.LOOP) { s, t -> val w = 0.05f * sin(t * 6f); s.sx *= 1f + w; s.sy *= 1f - w },
        AnimDef("tada", R.string.anim_tada, AnimKind.LOOP) { s, t ->
            val p = t % 2f
            if (p < 0.6f) { val e = sin(p / 0.6f * PI.toFloat()); s.scale *= 1f + 0.12f * e; s.rotation += 6f * e * sin(p * 40f) }
        },
        AnimDef("glitch_loop", R.string.anim_glitch_loop, AnimKind.LOOP) { s, t ->
            val h = abs(sin(floor(t * 10f) * 91.7f))
            if (h > 0.8f) { s.dx += (h - 0.9f) * 0.2f; s.alpha *= 0.7f }
        },
        AnimDef("neon_flicker", R.string.anim_neon_flicker, AnimKind.LOOP) { s, t -> s.alpha *= if (abs(sin(floor(t * 16f) * 37.3f)) > 0.88f) 0.35f else 1f },
        // Ken Burns y créditos: recorren todo el clip.
        AnimDef("kb_zoom_in", R.string.anim_kb_zoom_in, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1f + 0.25f * p },
        AnimDef("kb_zoom_out", R.string.anim_kb_zoom_out, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1.25f - 0.25f * p },
        AnimDef("kb_pan_left", R.string.anim_kb_pan_left, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1.2f; s.dx += 0.08f - 0.16f * p },
        AnimDef("kb_pan_right", R.string.anim_kb_pan_right, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1.2f; s.dx += -0.08f + 0.16f * p },
        AnimDef("kb_pan_up", R.string.anim_kb_pan_up, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1.2f; s.dy += 0.08f - 0.16f * p },
        AnimDef("kb_diagonal", R.string.anim_kb_diagonal, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1.1f + 0.2f * p; s.dx += 0.05f - 0.1f * p; s.dy += 0.04f - 0.08f * p },
        AnimDef("kb_pan_down", R.string.anim_kb_pan_down, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1.2f; s.dy += -0.08f + 0.16f * p },
        AnimDef("kb_push", R.string.anim_kb_push, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1f + 0.45f * p },
        AnimDef("kb_pull", R.string.anim_kb_pull, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1.45f - 0.45f * p },
        AnimDef("kb_punch", R.string.anim_kb_punch, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1f + 0.25f * Easing.CUBIC_OUT.ease(p) },
        AnimDef("kb_diagonal_rev", R.string.anim_kb_diagonal_rev, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1.3f - 0.2f * p; s.dx += -0.05f + 0.1f * p; s.dy += 0.04f - 0.08f * p },
        AnimDef("kb_rotate", R.string.anim_kb_rotate, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1.15f + 0.15f * p; s.rotation += -2.5f + 5f * p },
        AnimDef("kb_rotate_rev", R.string.anim_kb_rotate_rev, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1.3f - 0.15f * p; s.rotation += 2.5f - 5f * p },
        AnimDef("kb_arc", R.string.anim_kb_arc, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1.25f; s.dx += -0.08f + 0.16f * p; s.dy += 0.04f * sin(p * PI.toFloat()) - 0.02f },
        AnimDef("kb_drift", R.string.anim_kb_drift, AnimKind.LOOP, overClip = true) { s, p -> s.scale *= 1.14f + 0.04f * sin(p * PI.toFloat()); s.dx += 0.03f * sin(p * 2f * PI.toFloat()); s.dy += 0.02f * cos(p * 2f * PI.toFloat()) },
        AnimDef("credits", R.string.anim_credits, AnimKind.LOOP, textOnly = true, overClip = true) { s, p -> s.dy += 1.1f - 2.2f * p },
        AnimDef("marquee", R.string.anim_marquee, AnimKind.LOOP, textOnly = true, overClip = true) { s, p -> s.dx += 1.2f - 2.4f * p },
    )

    val all: List<AnimDef> =
        base.map { AnimDef(it.first, it.second, AnimKind.IN, f = it.third) } +
            textBase.map { AnimDef(it.first, it.second, AnimKind.IN, textOnly = true, f = it.third) } +
            base.map { AnimDef(it.first, it.second, AnimKind.OUT, f = it.third) } +
            textBase.map { AnimDef(it.first, it.second, AnimKind.OUT, textOnly = true, f = it.third) } +
            loops

    fun list(kind: AnimKind, forText: Boolean): List<AnimDef> = all.filter { it.kind == kind && (forText || !it.textOnly) }

    private fun find(kind: AnimKind, id: String): AnimDef? = all.firstOrNull { it.kind == kind && it.id == id }

    /** Aplica entrada, salida y bucle a [s] para el instante local [localUs] de un elemento de duración [durUs]. */
    fun apply(s: AnimState, animIn: AnimRef?, animOut: AnimRef?, animLoop: AnimRef?, localUs: Long, durUs: Long) {
        if (animLoop != null) {
            val d = find(AnimKind.LOOP, animLoop.id)
            if (d != null) d.f(s, if (d.overClip) (localUs.toFloat() / durUs.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) else localUs / 1_000_000f)
        }
        if (animIn != null && animIn.durationUs > 0 && localUs < animIn.durationUs) {
            find(AnimKind.IN, animIn.id)?.f?.invoke(s, (localUs.toFloat() / animIn.durationUs.toFloat()).coerceIn(0f, 1f))
        }
        if (animOut != null && animOut.durationUs > 0 && localUs > durUs - animOut.durationUs) {
            find(AnimKind.OUT, animOut.id)?.f?.invoke(s, ((durUs - localUs).toFloat() / animOut.durationUs.toFloat()).coerceIn(0f, 1f))
        }
    }

    fun isAnimating(animIn: AnimRef?, animOut: AnimRef?, animLoop: AnimRef?, localUs: Long, durUs: Long): Boolean =
        animLoop != null || (animIn != null && localUs < animIn.durationUs) || (animOut != null && localUs > durUs - animOut.durationUs)
}
