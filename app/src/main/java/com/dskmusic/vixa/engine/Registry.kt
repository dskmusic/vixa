package com.dskmusic.vixa.engine

import com.dskmusic.vixa.R
import com.dskmusic.vixa.model.ColorAdjust
import com.dskmusic.vixa.model.SpeedPoint

/**
 * Registros de datos: transiciones, efectos, filtros, preajustes de velocidad y de voz.
 * Añadir un elemento nuevo es añadir una entrada a la lista correspondiente.
 */

class ParamDef(val key: String, val nameRes: Int, val def: Float, val min: Float = 0f, val max: Float = 1f)

/** Efecto GL: [body] es el cuerpo GLSL de `vec4 fx(vec2 uv)`; los parámetros llegan en uP0.xyzw y uP1.xyzw por orden. */
class FxDef(val id: String, val nameRes: Int, val catRes: Int, val params: List<ParamDef>, val body: String)

/** Transición: [body] es el cuerpo GLSL de `void tr(float p, vec2 uv, float role)` (role 1 = clip entrante). */
class TransitionDef(val id: String, val nameRes: Int, val catRes: Int, val body: String)

/** Filtro: preajuste de parámetros del shader de color (+ LUT opcional de assets). */
class FilterPreset(
    val id: String,
    val nameRes: Int,
    val catRes: Int,
    val adj: ColorAdjust,
    val mono: Float = 0f,
    /** Tinte de sombras / luces: ARGB, el alfa es la cantidad. */
    val tintS: Int = 0,
    val tintH: Int = 0,
    val lut: String = "",
)

class SpeedPreset(val id: String, val nameRes: Int, val points: List<SpeedPoint>)

class VoicePreset(val id: String, val nameRes: Int, val pitch: Float, val robot: Float = 0f, val echo: Float = 0f, val tremolo: Float = 0f)

object Registry {

    // -----------------------------------------------------------------------------------------
    // Transiciones
    // -----------------------------------------------------------------------------------------

    private const val XFADE = "tA = mix(1.0 - p, p, role);"
    private const val EASE = "float e = p * p * (3.0 - 2.0 * p);"

    private fun cover(expr: String) = "float c = $expr;\n  tA = mix(1.0 - c, c, role);"

    private fun slide(dx: String, dy: String, edge: String) = """
  $EASE
  if (role > 0.5) { tOfs = vec2($dx, $dy) * (1.0 - e); tA = 1.0; }
  else { tA = $edge; }"""

    private fun push(dx: String, dy: String) = """
  $EASE
  if (role > 0.5) tOfs = vec2($dx, $dy) * (1.0 - e);
  else tOfs = vec2($dx, $dy) * (-e);
  tA = 1.0;"""

    private fun whip(dx: String, dy: String) = """
  $EASE
  if (role > 0.5) tOfs = vec2($dx, $dy) * (1.0 - e);
  else tOfs = vec2($dx, $dy) * (-e);
  tBlur = sin(p * 3.14159265) * 0.09;
  tA = 1.0;"""

    private fun dip(color: String) = """
  float c = step(0.5, p);
  tA = mix(1.0 - c, c, role);
  float k = role > 0.5 ? 1.0 - smoothstep(0.5, 1.0, p) : smoothstep(0.0, 0.5, p);
  tTint = vec4($color, k);"""

    val transitions: List<TransitionDef> = listOf(
        TransitionDef("fade", R.string.tr_fade, R.string.cat_basic, XFADE),
        TransitionDef("dissolve", R.string.tr_dissolve, R.string.cat_basic,
            cover("smoothstep(0.0, 0.12, p * 1.12 - hash(floor(uv * uSize.zw / 4.0)))")),
        TransitionDef("dip_black", R.string.tr_dip_black, R.string.cat_basic, dip("0.0, 0.0, 0.0")),
        TransitionDef("dip_white", R.string.tr_dip_white, R.string.cat_basic, dip("1.0, 1.0, 1.0")),
        TransitionDef("flash", R.string.tr_flash, R.string.cat_basic,
            "$XFADE\n  tTint = vec4(1.0, 1.0, 1.0, sin(p * 3.14159265) * 0.9);"),
        TransitionDef("blur", R.string.tr_blur, R.string.cat_basic,
            "$XFADE\n  tBlur = sin(p * 3.14159265) * 0.07;"),

        TransitionDef("slide_left", R.string.tr_slide_left, R.string.cat_slide, slide("1.0", "0.0", "step(uv.x, 1.0 - e)")),
        TransitionDef("slide_right", R.string.tr_slide_right, R.string.cat_slide, slide("-1.0", "0.0", "step(e, uv.x)")),
        TransitionDef("slide_up", R.string.tr_slide_up, R.string.cat_slide, slide("0.0", "1.0", "step(uv.y, 1.0 - e)")),
        TransitionDef("slide_down", R.string.tr_slide_down, R.string.cat_slide, slide("0.0", "-1.0", "step(e, uv.y)")),
        TransitionDef("push_left", R.string.tr_push_left, R.string.cat_slide, push("1.0", "0.0")),
        TransitionDef("push_right", R.string.tr_push_right, R.string.cat_slide, push("-1.0", "0.0")),
        TransitionDef("push_up", R.string.tr_push_up, R.string.cat_slide, push("0.0", "1.0")),
        TransitionDef("push_down", R.string.tr_push_down, R.string.cat_slide, push("0.0", "-1.0")),

        TransitionDef("wipe_left", R.string.tr_wipe_left, R.string.cat_wipe,
            cover("smoothstep(-0.02, 0.02, uv.x - (1.0 - p * 1.04) - 0.02)")),
        TransitionDef("wipe_right", R.string.tr_wipe_right, R.string.cat_wipe,
            cover("smoothstep(-0.02, 0.02, p * 1.04 - 0.02 - uv.x)")),
        TransitionDef("wipe_up", R.string.tr_wipe_up, R.string.cat_wipe,
            cover("smoothstep(-0.02, 0.02, uv.y - (1.0 - p * 1.04) - 0.02)")),
        TransitionDef("wipe_down", R.string.tr_wipe_down, R.string.cat_wipe,
            cover("smoothstep(-0.02, 0.02, p * 1.04 - 0.02 - uv.y)")),
        TransitionDef("wipe_diagonal", R.string.tr_wipe_diagonal, R.string.cat_wipe,
            cover("smoothstep(-0.03, 0.03, p * 1.06 - 0.03 - (uv.x + uv.y) * 0.5)")),
        TransitionDef("barn_doors", R.string.tr_barn_doors, R.string.cat_wipe,
            cover("1.0 - smoothstep(-0.01, 0.01, abs(uv.x - 0.5) - p * 0.52)")),
        TransitionDef("clock", R.string.tr_clock, R.string.cat_wipe,
            cover("step(atan(uv.x - 0.5, 0.5 - uv.y) / 6.2831853 + 0.5, p)")),
        TransitionDef("blinds_h", R.string.tr_blinds_h, R.string.cat_wipe, cover("step(fract(uv.y * 8.0), p)")),
        TransitionDef("blinds_v", R.string.tr_blinds_v, R.string.cat_wipe, cover("step(fract(uv.x * 8.0), p)")),
        TransitionDef("stripes", R.string.tr_stripes, R.string.cat_wipe,
            cover("smoothstep(0.0, 0.3, p * 1.3 - hash(vec2(floor(uv.x * 18.0), 3.0)))")),
        TransitionDef("checker", R.string.tr_checker, R.string.cat_wipe, """
  vec2 g = floor(uv * vec2(8.0, 8.0 * uSize.w / uSize.z));
  float n = mod(g.x + g.y, 2.0) * 0.5;
  ${cover("step(n + fract(uv.x * 8.0) * 0.5, p)")}"""),

        TransitionDef("circle_open", R.string.tr_circle_open, R.string.cat_shape, """
  float d = length((uv - 0.5) * vec2(uSize.z / uSize.w, 1.0));
  float r = p * (0.55 + 0.5 * uSize.z / uSize.w);
  ${cover("1.0 - smoothstep(r - 0.01, r + 0.01, d)")}"""),
        TransitionDef("circle_close", R.string.tr_circle_close, R.string.cat_shape, """
  float d = length((uv - 0.5) * vec2(uSize.z / uSize.w, 1.0));
  float r = (1.0 - p) * (0.55 + 0.5 * uSize.z / uSize.w);
  ${cover("smoothstep(r - 0.01, r + 0.01, d)")}"""),
        TransitionDef("diamond", R.string.tr_diamond, R.string.cat_shape,
            cover("1.0 - smoothstep(-0.01, 0.01, abs(uv.x - 0.5) + abs(uv.y - 0.5) - p * 1.02)")),
        TransitionDef("heart", R.string.tr_heart, R.string.cat_shape, """
  vec2 h = (uv - vec2(0.5, 0.46)) * vec2(uSize.z / uSize.w, 1.0) / max(p * 1.6, 0.001);
  h.y = -h.y;
  float a2 = h.x * h.x + h.y * h.y - 0.25;
  ${cover("1.0 - step(0.0, a2 * a2 * a2 - h.x * h.x * h.y * h.y * h.y * 4.0)")}"""),

        TransitionDef("zoom_in", R.string.tr_zoom_in, R.string.cat_motion, """
  $EASE
  if (role > 0.5) { tZoom = mix(0.35, 1.0, e); tA = p; }
  else { tZoom = 1.0 + e * 0.7; tA = 1.0 - p; }"""),
        TransitionDef("zoom_out", R.string.tr_zoom_out, R.string.cat_motion, """
  $EASE
  if (role > 0.5) { tZoom = mix(1.9, 1.0, e); tA = p; }
  else { tZoom = mix(1.0, 0.45, e); tA = 1.0 - p; }"""),
        TransitionDef("spin", R.string.tr_spin, R.string.cat_motion, """
  $EASE
  if (role > 0.5) { tRot = (1.0 - e) * -3.14159265; tZoom = mix(0.25, 1.0, e); tA = p; }
  else { tRot = e * 3.14159265; tZoom = mix(1.0, 0.25, e); tA = 1.0 - p; }"""),
        TransitionDef("roll", R.string.tr_roll, R.string.cat_motion, """
  $EASE
  if (role > 0.5) { tRot = (1.0 - e) * 1.2; tOfs = vec2(1.0 - e, 0.0); tA = 1.0; }
  else { tA = step(uv.x, 1.0 - e); }"""),
        TransitionDef("whip", R.string.tr_whip, R.string.cat_motion, """
  $EASE
  if (role > 0.5) tOfs = vec2(1.0 - e, 0.0);
  else tOfs = vec2(-e, 0.0);
  tBlur = sin(p * 3.14159265) * 0.09;
  tA = 1.0;"""),

        TransitionDef("glitch", R.string.tr_glitch, R.string.cat_fx, """
  float b = floor(uv.y * 24.0);
  float n = hash(vec2(b, floor(p * 12.0)));
  float amp = sin(p * 3.14159265);
  tOfs = vec2((n - 0.5) * 0.28 * amp, 0.0);
  ${cover("step(hash(vec2(b * 1.7, floor(p * 9.0))), p)")}"""),
        TransitionDef("pixelate", R.string.tr_pixelate, R.string.cat_fx,
            "$XFADE\n  tPix = sin(p * 3.14159265);"),
        TransitionDef("wave", R.string.tr_wave, R.string.cat_fx, """
  float amp = sin(p * 3.14159265) * 0.05;
  tOfs = vec2(sin(uv.y * 30.0 + p * 20.0) * amp, 0.0);
  $XFADE"""),
        TransitionDef("burn", R.string.tr_burn, R.string.cat_fx, """
  float n = noise(uv * vec2(6.0 * uSize.z / uSize.w, 6.0));
  float c = smoothstep(0.0, 0.08, p * 1.2 - n);
  tA = mix(1.0 - c, c, role);
  float edge = smoothstep(0.0, 0.08, p * 1.2 - n + 0.07) - c;
  tTint = vec4(1.0, 0.55, 0.1, clamp(edge, 0.0, 1.0));"""),

        // ---------------- Segunda tanda ----------------
        // Regla: la capa de arriba es la que aplica tA, así que la cobertura del clip entrante (c) y la del saliente
        // (1 - c) deben ser complementarias. Los movimientos (tOfs, tZoom, tRot) pueden depender del píxel.
        TransitionDef("dream", R.string.tr_dream, R.string.cat_basic, """
  float k = sin(p * 3.14159265);
  tBlur = k * 0.09;
  tZoom = 1.0 + k * 0.08;
  tTint = vec4(1.0, 0.96, 0.9, k * 0.35);
  $XFADE"""),
        TransitionDef("light_leak", R.string.tr_light_leak, R.string.cat_basic, """
  float k = sin(p * 3.14159265);
  float n = noise(vec2(uv.x * 2.0 + p * 3.0, uv.y * 1.5 - p * 2.0));
  tTint = vec4(1.0, 0.55 + 0.35 * n, 0.15 + 0.2 * n, clamp(k * (0.35 + 0.9 * n), 0.0, 0.95));
  $XFADE"""),
        TransitionDef("strobe", R.string.tr_strobe, R.string.cat_basic, """
  float c = p > 0.8 ? 1.0 : step(0.15, p) * step(0.5, fract(p * 5.0));
  tA = mix(1.0 - c, c, role);
  tTint = vec4(1.0, 1.0, 1.0, step(0.8, fract(p * 5.0 + 0.4)) * 0.6 * sin(p * 3.14159265));"""),

        TransitionDef("slide_corner", R.string.tr_slide_corner, R.string.cat_slide, """
  $EASE
  if (role > 0.5) { tOfs = vec2(1.0 - e, 1.0 - e); tA = 1.0; }
  else { tA = 1.0 - step(1.0 - e, uv.x) * step(1.0 - e, uv.y); }"""),
        TransitionDef("swap", R.string.tr_swap, R.string.cat_slide, """
  $EASE
  tZoom = 1.0 - 0.22 * sin(p * 3.14159265);
  if (role > 0.5) tOfs = vec2(1.0 - e, 0.0);
  else tOfs = vec2(-e, 0.0);
  tA = 1.0;"""),
        TransitionDef("split_v", R.string.tr_split_v, R.string.cat_slide, """
  $EASE
  float vis = step(e * 0.5, abs(uv.x - 0.5));
  if (role < 0.5) tOfs = vec2((uv.x < 0.5 ? -0.5 : 0.5) * e, 0.0);
  else tZoom = mix(1.15, 1.0, e);
  tA = mix(vis, 1.0 - vis, role);"""),
        TransitionDef("split_h", R.string.tr_split_h, R.string.cat_slide, """
  $EASE
  float vis = step(e * 0.5, abs(uv.y - 0.5));
  if (role < 0.5) tOfs = vec2(0.0, (uv.y < 0.5 ? -0.5 : 0.5) * e);
  else tZoom = mix(1.15, 1.0, e);
  tA = mix(vis, 1.0 - vis, role);"""),
        TransitionDef("slices", R.string.tr_slices, R.string.cat_slide, """
  $EASE
  float dir = mod(floor(uv.y * 8.0), 2.0) < 0.5 ? 1.0 : -1.0;
  if (role > 0.5) { tOfs = vec2(dir * (1.0 - e), 0.0); tA = 1.0; }
  else { tA = dir > 0.0 ? step(uv.x, 1.0 - e) : step(e, uv.x); }"""),
        TransitionDef("rain_bars", R.string.tr_rain_bars, R.string.cat_slide, """
  float h = hash(vec2(floor(uv.x * 12.0), 5.0));
  float e = clamp(p * 1.5 - h * 0.5, 0.0, 1.0);
  e = e * e * (3.0 - 2.0 * e);
  if (role > 0.5) { tOfs = vec2(0.0, e - 1.0); tA = 1.0; }
  else { tA = step(e, uv.y); }"""),

        TransitionDef("wipe_soft", R.string.tr_wipe_soft, R.string.cat_wipe, cover("smoothstep(0.0, 0.4, p * 1.4 - uv.x)")),
        TransitionDef("fan", R.string.tr_fan, R.string.cat_wipe,
            cover("step(abs(atan(uv.x - 0.5, 0.5 - uv.y)) / 3.14159265, p * 1.002 - 0.001)")),
        TransitionDef("sunburst", R.string.tr_sunburst, R.string.cat_wipe,
            cover("step(fract((atan(uv.x - 0.5, 0.5 - uv.y) / 6.2831853 + 0.5) * 12.0), p)")),
        TransitionDef("blinds_diag", R.string.tr_blinds_diag, R.string.cat_wipe,
            cover("step(fract((uv.x + uv.y * uSize.w / uSize.z) * 5.0), p)")),
        TransitionDef("zigzag", R.string.tr_zigzag, R.string.cat_wipe,
            cover("step(uv.x + abs(fract(uv.y * 7.0) - 0.5) * 0.14, p * 1.08)")),
        TransitionDef("mosaic", R.string.tr_mosaic, R.string.cat_wipe, """
  vec2 g = floor(uv * vec2(10.0, 10.0 * uSize.w / uSize.z));
  ${cover("smoothstep(0.0, 0.12, p * 1.12 - hash(g))")}"""),
        TransitionDef("rings", R.string.tr_rings, R.string.cat_wipe, """
  float d = length((uv - 0.5) * vec2(uSize.z / uSize.w, 1.0));
  ${cover("step(fract(d * 6.0), p)")}"""),
        TransitionDef("spiral", R.string.tr_spiral, R.string.cat_wipe, """
  float d = length((uv - 0.5) * vec2(uSize.z / uSize.w, 1.0));
  float a = atan(uv.x - 0.5, 0.5 - uv.y) / 6.2831853 + 0.5;
  ${cover("step(fract(a + d * 3.0), p)")}"""),
        TransitionDef("corners", R.string.tr_corners, R.string.cat_wipe,
            cover("smoothstep(-0.01, 0.01, abs(uv.x - 0.5) + abs(uv.y - 0.5) - (1.0 - p) * 1.02)")),

        TransitionDef("star", R.string.tr_star, R.string.cat_shape, """
  vec2 q = (uv - 0.5) * vec2(uSize.z / uSize.w, 1.0);
  float a = atan(q.x, -q.y);
  float r = p * (0.55 + 0.5 * uSize.z / uSize.w) / 0.42 * (0.42 + 0.58 * (1.0 - abs(sin(a * 2.5))));
  ${cover("1.0 - smoothstep(r - 0.01, r + 0.01, length(q))")}"""),
        TransitionDef("box_open", R.string.tr_box_open, R.string.cat_shape,
            cover("1.0 - step(p * 0.51, max(abs(uv.x - 0.5), abs(uv.y - 0.5)))")),
        TransitionDef("cross", R.string.tr_cross, R.string.cat_shape,
            cover("1.0 - step(p * 0.51, min(abs(uv.x - 0.5), abs(uv.y - 0.5)))")),
        TransitionDef("dots", R.string.tr_dots, R.string.cat_shape, """
  vec2 g = uv * vec2(5.0 * uSize.z / uSize.w, 5.0);
  float h = hash(floor(g));
  ${cover("1.0 - smoothstep(-0.02, 0.02, length(fract(g) - 0.5) - (p * 1.6 - h * 0.6) * 0.75)")}"""),
        TransitionDef("eye", R.string.tr_eye, R.string.cat_shape, """
  float x2 = abs(uv.x - 0.5) * 2.0;
  ${cover("1.0 - step(p * 1.3 * (1.0 - 0.6 * x2 * x2), abs(uv.y - 0.5))")}"""),

        TransitionDef("whip_right", R.string.tr_whip_right, R.string.cat_motion, whip("-1.0", "0.0")),
        TransitionDef("whip_up", R.string.tr_whip_up, R.string.cat_motion, whip("0.0", "1.0")),
        TransitionDef("whip_down", R.string.tr_whip_down, R.string.cat_motion, whip("0.0", "-1.0")),
        TransitionDef("zoom_blur", R.string.tr_zoom_blur, R.string.cat_motion, """
  $EASE
  tBlur = sin(p * 3.14159265) * 0.07;
  if (role > 0.5) { tZoom = mix(0.55, 1.0, e); tA = smoothstep(0.3, 0.7, p); }
  else { tZoom = 1.0 + e * 1.4; tA = 1.0 - smoothstep(0.3, 0.7, p); }"""),
        TransitionDef("punch", R.string.tr_punch, R.string.cat_motion, """
  float c = step(0.5, p);
  tA = mix(1.0 - c, c, role);
  float y = role > 0.5 ? clamp((1.0 - p) * 2.0, 0.0, 1.0) : clamp(p * 2.0, 0.0, 1.0);
  tZoom = 1.0 + (role > 0.5 ? 0.35 : 0.12) * y * y;"""),
        TransitionDef("twist", R.string.tr_twist, R.string.cat_motion, """
  $EASE
  if (role > 0.5) { tRot = (1.0 - e) * -0.6; tZoom = 1.0 + (1.0 - e) * 0.8; }
  else { tRot = e * 0.6; tZoom = 1.0 + e * 0.8; }
  tBlur = sin(p * 3.14159265) * 0.04;
  $XFADE"""),
        TransitionDef("swirl", R.string.tr_swirl, R.string.cat_motion, """
  float d = length((uv - 0.5) * vec2(uSize.z / uSize.w, 1.0));
  float k = role > 0.5 ? 1.0 - p : p;
  tRot = k * k * (1.0 - smoothstep(0.0, 0.9, d)) * 7.0;
  tZoom = 1.0 + k * 0.3;
  $XFADE"""),
        TransitionDef("bounce", R.string.tr_bounce, R.string.cat_motion, """
  float b = p < 0.3636 ? 7.5625 * p * p
          : (p < 0.7273 ? 7.5625 * (p - 0.5455) * (p - 0.5455) + 0.75
          : (p < 0.9091 ? 7.5625 * (p - 0.8182) * (p - 0.8182) + 0.9375
          : 7.5625 * (p - 0.9545) * (p - 0.9545) + 0.984375));
  if (role > 0.5) { tOfs = vec2(0.0, b - 1.0); tA = 1.0; }
  else { tA = step(b, uv.y); }"""),
        TransitionDef("stretch", R.string.tr_stretch, R.string.cat_motion, """
  float k = role > 0.5 ? 1.0 - p : p;
  tOfs = vec2((uv.x - 0.5) * k * k * 0.92, 0.0);
  $XFADE"""),
        TransitionDef("shake", R.string.tr_shake, R.string.cat_motion, """
  float k = sin(p * 3.14159265);
  float t = floor(p * 14.0);
  tZoom = 1.0 + 0.14 * k;
  tOfs = (vec2(hash(vec2(t, 1.0)), hash(vec2(t, 2.0))) - 0.5) * 0.1 * k;
  ${cover("step(0.5, p)")}"""),
        TransitionDef("bulge", R.string.tr_bulge, R.string.cat_motion, """
  float d = length((uv - 0.5) * vec2(uSize.z / uSize.w, 1.0));
  tZoom = 1.0 + sin(p * 3.14159265) * 1.4 * max(0.0, 1.0 - d * 1.7);
  $XFADE"""),

        TransitionDef("ripple", R.string.tr_ripple, R.string.cat_fx, """
  vec2 q = (uv - 0.5) * vec2(uSize.z / uSize.w, 1.0);
  float d = length(q);
  tOfs = q / (d + 0.0001) * sin(d * 34.0 - p * 16.0) * 0.03 * sin(p * 3.14159265);
  $XFADE"""),
        TransitionDef("digital", R.string.tr_digital, R.string.cat_fx, """
  float k = sin(p * 3.14159265);
  float t = floor(p * 10.0);
  vec2 g = floor(uv * vec2(5.0, 9.0));
  float n = hash(g + t);
  tOfs = vec2((hash(g + t + 5.0) - 0.5) * 0.3 * k * step(0.55, n), 0.0);
  tTint = vec4(n > 0.8 ? vec3(0.0, 1.0, 0.9) : vec3(1.0, 0.0, 0.7), k * 0.45 * step(0.7, hash(g * 1.3 + t)));
  ${cover("step(hash(g * 2.1), p)")}"""),
        TransitionDef("tv_off", R.string.tr_tv_off, R.string.cat_fx, """
  float a = p < 0.5 ? 1.0 - p * 2.0 : (p - 0.5) * 2.0;
  float vis = step(abs(uv.y - 0.5), max(a * a, 0.006) * 0.5);
  tTint = mix(vec4(0.0, 0.0, 0.0, 1.0), vec4(1.0, 1.0, 1.0, (1.0 - a) * 0.85), vis);
  ${cover("step(0.5, p)")}"""),
        TransitionDef("static_tv", R.string.tr_static_tv, R.string.cat_fx, """
  float k = sin(p * 3.14159265);
  float n = hash(floor(uv * uSize.zw / 3.0) + floor(p * 30.0) * 7.0);
  tTint = vec4(vec3(n), clamp(k * 1.3, 0.0, 0.9));
  tOfs = vec2((hash(vec2(floor(uv.y * 40.0), floor(p * 20.0))) - 0.5) * 0.04 * k, 0.0);
  ${cover("step(0.5, p)")}"""),
        TransitionDef("ink", R.string.tr_ink, R.string.cat_fx, """
  float asp = uSize.z / uSize.w;
  float d = length((uv - 0.5) * vec2(asp, 1.0));
  float n = noise(uv * vec2(4.0 * asp, 4.0)) * 0.4 + noise(uv * vec2(11.0 * asp, 11.0)) * 0.2;
  float v = p * (1.2 + 0.6 * asp) - d - n;
  float c = smoothstep(0.0, 0.05, v);
  tA = mix(1.0 - c, c, role);
  tTint = vec4(0.0, 0.0, 0.0, (smoothstep(-0.06, 0.0, v) - c) * 0.85);"""),
        TransitionDef("shatter", R.string.tr_shatter, R.string.cat_fx, """
  vec2 g = floor(uv * vec2(6.0 * uSize.z / uSize.w, 6.0) + vec2(uv.y * 2.0, uv.x * 2.0));
  float h = hash(g);
  float k = sin(p * 3.14159265);
  tOfs = (vec2(h, hash(g + 7.0)) - 0.5) * 0.22 * k;
  tRot = (h - 0.5) * 0.5 * k;
  ${cover("smoothstep(0.0, 0.15, p * 1.15 - h)")}"""),
        TransitionDef("scan", R.string.tr_scan, R.string.cat_fx, """
  float y = p * 1.06 - 0.03;
  tTint = vec4(0.4, 1.0, 0.9, (1.0 - smoothstep(0.0, 0.03, abs(uv.y - y))) * 0.9);
  ${cover("step(uv.y, y)")}"""),

        // ---------------- Tercera tanda de transiciones ----------------
        TransitionDef("film_roll", R.string.tr_film_roll, R.string.cat_slide, """
  $EASE
  if (role > 0.5) tOfs = vec2(0.0, 1.0 - e); else tOfs = vec2(0.0, -e);
  tBlur = sin(p * 3.14159265) * 0.05;
  float perf = step(0.5, fract(uv.y * 10.0 + e * 10.0)) * (step(uv.x, 0.05) + step(0.95, uv.x));
  tTint = vec4(0.0, 0.0, 0.0, perf * sin(p * 3.14159265) * 0.9);
  tA = 1.0;"""),
        TransitionDef("window_zoom", R.string.tr_window_zoom, R.string.cat_shape, """
  $EASE
  ${cover("1.0 - step(p * 0.51, max(abs(uv.x - 0.5), abs(uv.y - 0.5)))")}
  if (role > 0.5) tZoom = mix(0.7, 1.0, e);"""),
        TransitionDef("iris_zoom", R.string.tr_iris_zoom, R.string.cat_shape, """
  $EASE
  float d = length((uv - 0.5) * vec2(uSize.z / uSize.w, 1.0));
  float r = p * (0.55 + 0.5 * uSize.z / uSize.w);
  ${cover("1.0 - smoothstep(r - 0.01, r + 0.01, d)")}
  tBlur = sin(p * 3.14159265) * 0.03;
  if (role > 0.5) tZoom = mix(1.35, 1.0, e);"""),
        TransitionDef("drip", R.string.tr_drip, R.string.cat_wipe, """
  float h = hash(vec2(floor(uv.x * 14.0), 1.0));
  ${cover("step(uv.y, p * 1.35 - h * 0.35)")}"""),
        TransitionDef("curtain_wave", R.string.tr_curtain_wave, R.string.cat_wipe,
            cover("1.0 - smoothstep(-0.02, 0.02, abs(uv.x - 0.5) + sin(uv.y * 12.0) * 0.03 - p * 0.55)")),
        TransitionDef("cloud_dissolve", R.string.tr_cloud_dissolve, R.string.cat_basic, """
  float n = noise(uv * vec2(5.0 * uSize.z / uSize.w, 5.0));
  ${cover("smoothstep(0.0, 0.15, p * 1.15 - n)")}"""),
    )

    fun transition(id: String): TransitionDef? = transitions.firstOrNull { it.id == id }

    // -----------------------------------------------------------------------------------------
    // Efectos
    // -----------------------------------------------------------------------------------------

    // Los parámetros de cantidad arrancan más altos: el efecto se nota de entrada y el usuario lo baja con el slider.
    private fun p(key: String, nameRes: Int, def: Float) = ParamDef(key, nameRes, if (key == "amount" || key == "strength" || key == "intensity") minOf(1f, def * 1.6f) else def)

    /** Cuerpo GLSL de un efecto de estrellas fugaces / meteoritos: [count] rayas con ancho, caída de la cola y color dados. */
    private fun streaks(count: Int, width: String, decay: String, col: String, speedMul: String) = """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  vec2 dir = normalize(vec2(-0.6, -1.0));
  vec3 acc = vec3(0.0);
  for (int i = 0; i < $count; i++) {
    float fi = float(i);
    float t = uTime * (0.25 + uP0.y * 0.6) * $speedMul + fi * 0.33;
    float cyc = floor(t);
    float ph = fract(t);
    float live = step(1.0 - uP0.x * 0.7, hash(vec2(cyc, fi + 5.0)));
    vec2 head = vec2((0.1 + 0.9 * hash(vec2(cyc, fi))) * asp, 1.1) + dir * ph * 1.6;
    vec2 d = uv * vec2(asp, 1.0) - head;
    float along = -dot(d, dir);
    float perp = abs(d.x * dir.y - d.y * dir.x);
    float trail = step(0.0, along) * exp(-along * $decay) * (1.0 - smoothstep(0.0, $width, perp));
    float glow = 1.0 / (1.0 + dot(d, d) * 4000.0);
    acc += (trail + glow) * live * (1.0 - ph * ph);
  }
  c.rgb += $col * acc * 1.4;
  return c;"""

    /**
     * Dígito de siete segmentos (para el cronómetro y la cuenta atrás): suma 1 a [onVar] donde se dibuja el valor de [value]
     * (se toma su resto entre 10). Su esquina inferior izquierda está en ([ox], [oy]) y mide [sc] de alto (fracción de la pantalla).
     */
    private fun seg(value: String, ox: String, oy: String, sc: String, onVar: String) = """{
    vec2 q = (uv - vec2($ox, $oy)) * vec2(asp, 1.0) / $sc;
    float d = mod(floor($value), 10.0);
    float code = d < 0.5 ? 126.0 : (d < 1.5 ? 48.0 : (d < 2.5 ? 109.0 : (d < 3.5 ? 121.0 : (d < 4.5 ? 51.0 : (d < 5.5 ? 91.0 : (d < 6.5 ? 95.0 : (d < 7.5 ? 112.0 : (d < 8.5 ? 127.0 : 123.0))))))));
    float on = mod(floor(code / 64.0), 2.0) * step(0.1, q.x) * step(q.x, 0.5) * step(0.88, q.y) * step(q.y, 1.0)
             + mod(floor(code / 32.0), 2.0) * step(0.48, q.x) * step(q.x, 0.6) * step(0.5, q.y) * step(q.y, 0.94)
             + mod(floor(code / 16.0), 2.0) * step(0.48, q.x) * step(q.x, 0.6) * step(0.06, q.y) * step(q.y, 0.5)
             + mod(floor(code / 8.0), 2.0) * step(0.1, q.x) * step(q.x, 0.5) * step(0.0, q.y) * step(q.y, 0.12)
             + mod(floor(code / 4.0), 2.0) * step(0.0, q.x) * step(q.x, 0.12) * step(0.06, q.y) * step(q.y, 0.5)
             + mod(floor(code / 2.0), 2.0) * step(0.0, q.x) * step(q.x, 0.12) * step(0.5, q.y) * step(q.y, 0.94)
             + mod(floor(code), 2.0) * step(0.1, q.x) * step(q.x, 0.5) * step(0.44, q.y) * step(q.y, 0.56);
    $onVar += on;
  }"""

    /** Cuerpo GLSL de un efecto de elementos que caen (pétalos, hojas): [shape] da la máscara 0..1 y [colors] el color; ambos usan q y h. */
    private fun faller(shape: String, colors: String, dir: String = "+") = """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    float sc = 6.0 + fi * 4.0;
    vec2 u = uv * vec2(sc * asp, sc);
    u.y $dir= uTime * (0.6 + uP0.y * 2.0) * (1.0 + fi * 0.3);
    u.x += sin(uTime * 0.9 + fi * 2.0 + u.y * 0.5) * 0.6;
    float h = hash(floor(u) + fi * 13.0);
    vec2 q = fract(u) - 0.5 - (vec2(h, fract(h * 37.0)) - 0.5) * 0.4;
    float a = uTime * (1.0 + h * 2.0) + h * 20.0;
    q = vec2(q.x * cos(a) - q.y * sin(a), q.x * sin(a) + q.y * cos(a));
    float m = $shape;
    vec3 col = $colors;
    c.rgb = mix(c.rgb, col, m * 0.92 * step(1.0 - uP0.x * 0.6, h));
  }
  return c;"""

    /** Una de las cuatro regiones del filtro Kuwahara (óleo): media y varianza de 4 muestras hacia (sx, sy). */
    private fun kuw(n: Int, sx: String, sy: String) = """
  vec3 a$n = T(uv).rgb;
  vec3 b$n = T(uv + px * vec2($sx, 0.0)).rgb;
  vec3 c$n = T(uv + px * vec2(0.0, $sy)).rgb;
  vec3 d$n = T(uv + px * vec2($sx, $sy)).rgb;
  vec3 m$n = (a$n + b$n + c$n + d$n) * 0.25;
  float v$n = dot(a$n - m$n, a$n - m$n) + dot(b$n - m$n, b$n - m$n) + dot(c$n - m$n, c$n - m$n) + dot(d$n - m$n, d$n - m$n);"""

    /** Expresión GLSL: color RGB saturado del tono [x] (0..1). */
    private fun hue(x: String) = "clamp(abs(mod($x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0)"

    private const val DISK_BLUR = """
  float asp = uRes.x / uRes.y;
  for (int i = 0; i < 16; i++) {
    float a = float(i) * 2.39996323;
    float d = sqrt((float(i) + 0.5) / 16.0) * r;
    acc += T(uv + vec2(cos(a), sin(a) * asp) * d);
  }"""

    val effects: List<FxDef> = listOf(
        FxDef("glitch", R.string.fx_glitch, R.string.cat_retro,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.5f)), """
  float t = floor(uTime * (4.0 + uP0.y * 20.0));
  float b = floor(uv.y * 18.0);
  float n = hash(vec2(b, t));
  float on = step(1.0 - uP0.x * 0.6, hash(vec2(t, 3.0)) * 0.5 + n * 0.5);
  float sh = (n - 0.5) * 0.12 * uP0.x * on;
  vec4 c = T(uv + vec2(sh, 0.0));
  c.r = T(uv + vec2(sh + 0.012 * uP0.x, 0.0)).r;
  c.b = T(uv + vec2(sh - 0.012 * uP0.x, 0.0)).b;
  return c;"""),
        FxDef("vhs", R.string.fx_vhs, R.string.cat_retro,
            listOf(p("amount", R.string.p_amount, 0.6f), p("noise", R.string.p_noise, 0.4f)), """
  float w = sin(uv.y * 80.0 + uTime * 6.0) * 0.0015 * uP0.x;
  float jump = step(0.985, hash(vec2(floor(uTime * 12.0), floor(uv.y * 30.0)))) * 0.02 * uP0.x;
  vec2 u = uv + vec2(w + jump, 0.0);
  vec4 c = T(u);
  c.r = T(u + vec2(0.004 * uP0.x, 0.0)).r;
  c.b = T(u - vec2(0.004 * uP0.x, 0.0)).b;
  float scan = 0.92 + 0.08 * sin(uv.y * uRes.y * 1.5);
  c.rgb *= mix(1.0, scan, uP0.x);
  c.rgb += (hash(uv * uRes + uTime * 60.0) - 0.5) * 0.18 * uP0.y;
  c.rgb = mix(c.rgb, vec3(luma(c.rgb)), 0.15 * uP0.x) * vec3(1.02, 1.0, 1.06);
  return c;"""),
        FxDef("vignette_fx", R.string.fx_vignette_fx, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec4 c = T(uv);
  float d = distance(uv, vec2(0.5));
  c.rgb *= 1.0 - smoothstep(0.25, 0.85, d) * uP0.x;
  return c;"""),
        FxDef("flash_pulse", R.string.fx_flash_pulse, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec4 c = T(uv);
  float f = step(0.88, hash(vec2(floor(uTime * 8.0), 1.0)));
  c.rgb += vec3(0.45) * uP0.x * f;
  return c;"""),
        FxDef("dream_blur", R.string.fx_dream_blur, R.string.cat_blur,
            listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec4 c = T(uv);
  vec4 s = vec4(0.0);
  for (int i = 0; i < 8; i++) {
    float a = float(i) * 0.785398;
    s += T(uv + vec2(cos(a), sin(a)) * 0.012 * uP0.x);
  }
  s /= 8.0;
  return mix(c, max(c, s * 1.12), 0.7);"""),
        FxDef("rgb_shift_v", R.string.fx_rgb_shift_v, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec4 c = T(uv);
  c.g = T(uv + vec2(0.0, 0.007 * uP0.x)).g;
  c.b = T(uv - vec2(0.0, 0.007 * uP0.x)).b;
  return c;"""),
        FxDef("heat_haze", R.string.fx_heat_haze, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec2 u = uv + vec2(sin(uv.y * 40.0 + uTime * 4.0), cos(uv.x * 30.0 + uTime * 3.0)) * 0.004 * uP0.x;
  return T(u);"""),
        FxDef("vertical_roll", R.string.fx_vertical_roll, R.string.cat_retro,
            listOf(p("amount", R.string.p_amount, 0.6f)), """
  float s = fract(uTime * uP0.x * 0.35);
  return T(vec2(uv.x, fract(uv.y + s)));"""),
        FxDef("pop_art", R.string.fx_pop_art, R.string.cat_stylize,
            listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec4 c = T(uv);
  float l = luma(c.rgb);
  vec3 col = vec3(1.0, 0.9, 0.2);
  if (l < 0.33) col = vec3(0.1, 0.1, 0.45);
  else if (l < 0.66) col = vec3(0.95, 0.2, 0.45);
  return vec4(mix(c.rgb, col, uP0.x), c.a);"""),
        FxDef("hue_cycle", R.string.fx_hue_cycle, R.string.cat_stylize,
            listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec4 c = T(uv);
  float a = uTime * 1.5 * uP0.x;
  float cs = cos(a);
  float sn = sin(a);
  vec3 k = vec3(0.57735);
  vec3 r = c.rgb * cs + cross(k, c.rgb) * sn + k * dot(k, c.rgb) * (1.0 - cs);
  return vec4(r, c.a);"""),
        FxDef("old_film", R.string.fx_old_film, R.string.cat_retro,
            listOf(p("amount", R.string.p_amount, 0.7f), p("scratches", R.string.p_scratches, 0.5f)), """
  vec4 c = T(uv + vec2(0.0, (hash(vec2(floor(uTime * 14.0), 5.0)) - 0.5) * 0.004 * uP0.x));
  float y = luma(c.rgb);
  c.rgb = mix(c.rgb, vec3(y * 1.07, y * 0.92, y * 0.72), uP0.x);
  c.rgb *= mix(1.0, 0.9 + 0.1 * hash(vec2(floor(uTime * 18.0), 9.0)), uP0.x);
  float sx = hash(vec2(floor(uTime * 9.0), 2.0));
  float scr = step(abs(uv.x - sx), 0.0012) * step(0.6, hash(vec2(floor(uTime * 9.0), 7.0)));
  c.rgb += scr * 0.35 * uP0.y;
  c.rgb += (hash(uv * uRes + uTime * 40.0) - 0.5) * 0.12 * uP0.x;
  c.rgb *= 1.0 - 0.6 * uP0.x * smoothstep(0.4, 0.85, distance(uv, vec2(0.5)));
  return c;"""),
        FxDef("crt", R.string.fx_crt, R.string.cat_retro,
            listOf(p("curve", R.string.p_curve, 0.5f), p("scan", R.string.p_scanlines, 0.5f)), """
  vec2 u = uv - 0.5;
  float r2 = dot(u, u);
  u *= 1.0 + r2 * uP0.x * 0.5;
  u += 0.5;
  if (u.x < 0.0 || u.x > 1.0 || u.y < 0.0 || u.y > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);
  vec4 c = T(u);
  c.rgb *= 1.0 - uP0.y * 0.35 * (0.5 + 0.5 * sin(u.y * uRes.y * 3.14159265));
  c.rgb *= 1.0 - smoothstep(0.3, 0.75, sqrt(r2)) * 0.5;
  return c;"""),
        FxDef("noise", R.string.fx_noise, R.string.cat_retro,
            listOf(p("amount", R.string.p_amount, 0.4f), p("color", R.string.p_color, 0.2f)), """
  vec4 c = T(uv);
  vec2 s = uv * uRes + uTime * 50.0;
  vec3 n = vec3(hash(s), hash(s + 13.0), hash(s + 29.0)) - 0.5;
  n = mix(vec3(n.r), n, uP0.y);
  c.rgb += n * uP0.x * 0.6;
  return c;"""),

        FxDef("blur", R.string.fx_blur, R.string.cat_blur, listOf(p("radius", R.string.p_radius, 0.4f)), """
  vec4 acc = T(uv);
  float r = uP0.x * 0.045;$DISK_BLUR
  return acc / 17.0;"""),
        FxDef("zoom_blur", R.string.fx_zoom_blur, R.string.cat_blur, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec4 acc = vec4(0.0);
  for (int i = 0; i < 16; i++) {
    float s = 1.0 - uP0.x * 0.25 * float(i) / 16.0;
    acc += T(vec2(0.5) + (uv - 0.5) * s);
  }
  return acc / 16.0;"""),
        FxDef("motion_blur", R.string.fx_motion_blur, R.string.cat_blur,
            listOf(p("amount", R.string.p_amount, 0.5f), p("angle", R.string.p_angle, 0f)), """
  float ang = uP0.y * 6.2831853;
  vec2 dir = vec2(cos(ang), sin(ang)) * uP0.x * 0.06;
  vec4 acc = vec4(0.0);
  for (int i = 0; i < 16; i++) acc += T(uv + dir * (float(i) / 15.0 - 0.5));
  return acc / 16.0;"""),
        FxDef("tilt_shift", R.string.fx_tilt_shift, R.string.cat_blur,
            listOf(p("amount", R.string.p_amount, 0.6f), p("position", R.string.p_position, 0.5f), p("width", R.string.p_width, 0.4f)), """
  float dd = abs(uv.y - (1.0 - uP0.y));
  float r = smoothstep(uP0.z * 0.4, uP0.z * 0.4 + 0.25, dd) * uP0.x * 0.03;
  vec4 acc = T(uv);$DISK_BLUR
  return acc / 17.0;"""),
        FxDef("glow", R.string.fx_glow, R.string.cat_blur,
            listOf(p("amount", R.string.p_amount, 0.6f), p("radius", R.string.p_radius, 0.5f)), """
  vec4 c = T(uv);
  vec4 acc = vec4(0.0);
  float r = 0.01 + uP0.y * 0.04;$DISK_BLUR
  vec3 g = max(acc.rgb / 16.0 - 0.35, 0.0) * 1.5;
  c.rgb = 1.0 - (1.0 - c.rgb) * (1.0 - g * uP0.x);
  return c;"""),

        FxDef("pixelate", R.string.fx_pixelate, R.string.cat_stylize, listOf(p("size", R.string.p_size, 0.4f)), """
  float n = mix(200.0, 12.0, uP0.x);
  vec2 g = vec2(n, n * uRes.y / uRes.x);
  return T((floor(uv * g) + 0.5) / g);"""),
        FxDef("posterize", R.string.fx_posterize, R.string.cat_stylize, listOf(p("levels", R.string.p_levels, 0.6f)), """
  vec4 c = T(uv);
  float n = floor(mix(16.0, 2.0, uP0.x));
  c.rgb = floor(c.rgb * n + 0.5) / n;
  return c;"""),
        FxDef("sketch", R.string.fx_sketch, R.string.cat_stylize,
            listOf(p("amount", R.string.p_amount, 0.5f), p("color", R.string.p_color, 0f)), """
  vec2 px = 1.0 / uRes;
  float tl = luma(T(uv + px * vec2(-1.0, 1.0)).rgb);
  float tc = luma(T(uv + px * vec2(0.0, 1.0)).rgb);
  float tr = luma(T(uv + px * vec2(1.0, 1.0)).rgb);
  float ml = luma(T(uv + px * vec2(-1.0, 0.0)).rgb);
  float mr = luma(T(uv + px * vec2(1.0, 0.0)).rgb);
  float bl = luma(T(uv + px * vec2(-1.0, -1.0)).rgb);
  float bc = luma(T(uv + px * vec2(0.0, -1.0)).rgb);
  float br = luma(T(uv + px * vec2(1.0, -1.0)).rgb);
  float gx = -tl - 2.0 * ml - bl + tr + 2.0 * mr + br;
  float gy = -tl - 2.0 * tc - tr + bl + 2.0 * bc + br;
  float e = clamp(length(vec2(gx, gy)) * (1.0 + uP0.x * 4.0), 0.0, 1.0);
  vec4 c = T(uv);
  return vec4(mix(vec3(1.0 - e), c.rgb * (1.0 - e), uP0.y), c.a);"""),
        FxDef("emboss", R.string.fx_emboss, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec2 px = 1.0 / uRes;
  vec4 c = T(uv);
  vec3 d = T(uv + px).rgb - T(uv - px).rgb;
  float g = luma(d) * (1.0 + uP0.x * 4.0) + 0.5;
  return vec4(mix(vec3(g), c.rgb + d * (1.0 + uP0.x * 3.0), 0.35), c.a);"""),
        FxDef("halftone", R.string.fx_halftone, R.string.cat_stylize, listOf(p("size", R.string.p_size, 0.4f)), """
  float n = mix(140.0, 30.0, uP0.x);
  vec2 g = vec2(n, n * uRes.y / uRes.x);
  vec4 c = T((floor(uv * g) + 0.5) / g);
  float d = length(fract(uv * g) - 0.5);
  float r = sqrt(1.0 - luma(c.rgb)) * 0.55;
  float k = 1.0 - smoothstep(r - 0.05, r + 0.05, d);
  return vec4(mix(vec3(1.0), c.rgb * 0.25, k), c.a);"""),
        FxDef("invert", R.string.fx_invert, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 1f)), """
  vec4 c = T(uv);
  c.rgb = mix(c.rgb, 1.0 - c.rgb, uP0.x);
  return c;"""),
        FxDef("thermal", R.string.fx_thermal, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 1f)), """
  vec4 c = T(uv);
  float y = luma(c.rgb);
  vec3 t = y < 0.33 ? mix(vec3(0.0, 0.0, 0.5), vec3(0.8, 0.0, 0.8), y / 0.33)
         : (y < 0.66 ? mix(vec3(0.8, 0.0, 0.8), vec3(1.0, 0.5, 0.0), (y - 0.33) / 0.33)
         : mix(vec3(1.0, 0.5, 0.0), vec3(1.0, 1.0, 0.7), (y - 0.66) / 0.34));
  return vec4(mix(c.rgb, t, uP0.x), c.a);"""),
        FxDef("night_vision", R.string.fx_night_vision, R.string.cat_stylize,
            listOf(p("amount", R.string.p_amount, 1f), p("noise", R.string.p_noise, 0.5f)), """
  vec4 c = T(uv);
  float y = luma(c.rgb) * 1.6;
  float n = hash(uv * uRes + uTime * 30.0) * 0.25;
  float sc = 0.85 + 0.15 * sin(uv.y * uRes.y * 2.0);
  vec3 g = vec3(0.1, 1.0, 0.2) * (y + n * uP0.y) * sc;
  g *= 1.0 - smoothstep(0.35, 0.75, distance(uv, vec2(0.5)));
  return vec4(mix(c.rgb, g, uP0.x), c.a);"""),
        FxDef("duotone", R.string.fx_duotone, R.string.cat_stylize,
            listOf(p("hue_a", R.string.p_color_a, 0.65f), p("hue_b", R.string.p_color_b, 0.1f), p("amount", R.string.p_amount, 1f)), """
  vec4 c = T(uv);
  float y = luma(c.rgb);
  vec3 a = clamp(abs(mod(uP0.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0) * 0.4;
  vec3 b = clamp(abs(mod(uP0.y * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  b = mix(vec3(1.0), b, 0.6);
  return vec4(mix(c.rgb, mix(a, b, y), uP0.z), c.a);"""),
        FxDef("color_pop", R.string.fx_color_pop, R.string.cat_stylize,
            listOf(p("hue", R.string.p_color, 0f), p("range", R.string.p_range, 0.3f)), """
  vec4 c = T(uv);
  float mx = max(c.r, max(c.g, c.b));
  float mn = min(c.r, min(c.g, c.b));
  float d = mx - mn;
  float h = 0.0;
  if (d > 0.0001) {
    if (mx == c.r) h = mod((c.g - c.b) / d, 6.0);
    else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
    else h = (c.r - c.g) / d + 4.0;
  }
  h /= 6.0;
  float dh = abs(h - uP0.x);
  dh = min(dh, 1.0 - dh);
  float k = (1.0 - smoothstep(uP0.y * 0.25, uP0.y * 0.25 + 0.08, dh)) * step(0.08, d);
  return vec4(mix(vec3(luma(c.rgb)), c.rgb, k), c.a);"""),

        FxDef("chromatic", R.string.fx_chromatic, R.string.cat_distort, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec2 d = (uv - 0.5) * uP0.x * 0.06;
  vec4 c = T(uv);
  c.r = T(uv + d).r;
  c.b = T(uv - d).b;
  return c;"""),
        FxDef("rgb_wave", R.string.fx_rgb_wave, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.5f), p("freq", R.string.p_frequency, 0.3f)), """
  float w = sin(uv.y * (10.0 + uP0.y * 60.0) + uTime * 5.0) * 0.02 * uP0.x;
  vec4 c = T(uv);
  c.r = T(uv + vec2(w, 0.0)).r;
  c.b = T(uv - vec2(w, 0.0)).b;
  return c;"""),
        FxDef("mirror_h", R.string.fx_mirror_h, R.string.cat_distort, listOf(p("side", R.string.p_side, 0f)), """
  vec2 u = uv;
  if (uP0.x < 0.5) { if (u.x > 0.5) u.x = 1.0 - u.x; }
  else { if (u.x < 0.5) u.x = 1.0 - u.x; }
  return T(u);"""),
        FxDef("mirror_v", R.string.fx_mirror_v, R.string.cat_distort, listOf(p("side", R.string.p_side, 0f)), """
  vec2 u = uv;
  if (uP0.x < 0.5) { if (u.y < 0.5) u.y = 1.0 - u.y; }
  else { if (u.y > 0.5) u.y = 1.0 - u.y; }
  return T(u);"""),
        FxDef("mirror_quad", R.string.fx_mirror_quad, R.string.cat_distort, emptyList(), """
  return T(0.5 - abs(uv - 0.5));"""),
        FxDef("kaleidoscope", R.string.fx_kaleidoscope, R.string.cat_distort,
            listOf(p("segments", R.string.p_segments, 0.4f), p("speed", R.string.p_speed, 0.2f)), """
  float asp = uRes.x / uRes.y;
  vec2 c0 = uv - 0.5;
  c0.x *= asp;
  float r = length(c0);
  float a = atan(c0.y, c0.x) + uTime * uP0.y * 2.0;
  float seg = 6.2831853 / floor(3.0 + uP0.x * 9.0);
  a = mod(a, seg);
  a = abs(a - seg * 0.5);
  vec2 u = vec2(cos(a), sin(a)) * r;
  u.x /= asp;
  return T(u + 0.5);"""),
        FxDef("fisheye", R.string.fx_fisheye, R.string.cat_distort, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec2 u = uv - 0.5;
  u *= 1.0 - uP0.x * 0.9 * (0.5 - dot(u, u));
  return T(u + 0.5);"""),
        FxDef("swirl", R.string.fx_swirl, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.5f), p("radius", R.string.p_radius, 0.5f)), """
  float asp = uRes.x / uRes.y;
  vec2 u = uv - 0.5;
  u.x *= asp;
  float a = 1.0 - smoothstep(0.0, 0.2 + uP0.y * 0.6, length(u));
  a = a * a * uP0.x * 6.0;
  float cs = cos(a);
  float sn = sin(a);
  u = vec2(u.x * cs - u.y * sn, u.x * sn + u.y * cs);
  u.x /= asp;
  return T(u + 0.5);"""),
        FxDef("wave", R.string.fx_wave, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.4f), p("freq", R.string.p_frequency, 0.3f), p("speed", R.string.p_speed, 0.3f)), """
  float f = 5.0 + uP0.y * 40.0;
  float t = uTime * (1.0 + uP0.z * 8.0);
  vec2 u = uv;
  u.x += sin(uv.y * f + t) * 0.03 * uP0.x;
  u.y += cos(uv.x * f + t) * 0.03 * uP0.x;
  return T(u);"""),
        FxDef("tile", R.string.fx_tile, R.string.cat_distort, listOf(p("count", R.string.p_size, 0.3f)), """
  float n = floor(1.0 + uP0.x * 5.0 + 0.5);
  return T(fract(uv * n));"""),

        FxDef("shake", R.string.fx_shake, R.string.cat_motion,
            listOf(p("amount", R.string.p_amount, 0.4f), p("speed", R.string.p_speed, 0.5f)), """
  float t = floor(uTime * (8.0 + uP0.y * 30.0));
  vec2 o = (vec2(hash(vec2(t, 1.0)), hash(vec2(t, 2.0))) - 0.5) * 0.06 * uP0.x;
  return T((uv - 0.5) / (1.0 + 0.06 * uP0.x) + 0.5 + o);"""),
        FxDef("zoom_pulse", R.string.fx_zoom_pulse, R.string.cat_motion,
            listOf(p("amount", R.string.p_amount, 0.4f), p("speed", R.string.p_speed, 0.4f)), """
  float z = 1.0 + uP0.x * 0.25 * (0.5 + 0.5 * sin(uTime * (1.0 + uP0.y * 10.0)));
  return T((uv - 0.5) / z + 0.5);"""),
        FxDef("strobe", R.string.fx_strobe, R.string.cat_motion,
            listOf(p("speed", R.string.p_speed, 0.4f), p("amount", R.string.p_amount, 0.6f)), """
  vec4 c = T(uv);
  float f = step(0.5, fract(uTime * (2.0 + uP0.x * 14.0)));
  c.rgb = mix(c.rgb, vec3(1.0), f * uP0.y);
  return c;"""),

        FxDef("light_leak", R.string.fx_light_leak, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float t = uTime * (0.2 + uP0.y);
  float g = smoothstep(0.2, 1.0, noise(vec2(uv.x * 1.5 + t, uv.y * 0.8 - t * 0.5)));
  vec3 leak = mix(vec3(1.0, 0.45, 0.1), vec3(1.0, 0.85, 0.3), noise(uv * 2.0 + t)) * g;
  c.rgb = 1.0 - (1.0 - c.rgb) * (1.0 - leak * uP0.x);
  return c;"""),
        FxDef("sparkles", R.string.fx_sparkles, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.8f), p("density", R.string.p_size, 0.4f)), """
  vec4 c = T(uv);
  vec2 g = uv * vec2(14.0 * uRes.x / uRes.y, 14.0);
  vec2 id = floor(g);
  vec2 f = fract(g) - 0.5;
  float h = hash(id);
  float tw = 0.5 + 0.5 * sin(uTime * (2.0 + h * 6.0) + h * 40.0);
  float star = max(0.0, 1.0 - abs(f.x) * 14.0) * max(0.0, 1.0 - abs(f.y) * 2.5)
             + max(0.0, 1.0 - abs(f.y) * 14.0) * max(0.0, 1.0 - abs(f.x) * 2.5);
  float s = step(1.0 - uP0.y * 0.5, h) * star * tw * step(0.55, luma(c.rgb) + 0.35);
  c.rgb += s * uP0.x * vec3(1.0, 0.95, 0.8);
  return c;"""),
        FxDef("snow", R.string.fx_snow, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float s = 0.0;
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    float sc = 12.0 + fi * 9.0;
    vec2 u = uv * vec2(sc * uRes.x / uRes.y, sc);
    u.y += uTime * (0.6 + uP0.y * 2.0) * (1.0 + fi * 0.4);
    u.x += sin(uTime * 0.7 + fi) * 0.5;
    vec2 id = floor(u);
    vec2 f = fract(u) - 0.5;
    float h = hash(id + fi * 17.0);
    vec2 o = (vec2(h, fract(h * 37.0)) - 0.5) * 0.6;
    s += step(1.0 - uP0.x * 0.6, h) * (1.0 - smoothstep(0.03, 0.13, length(f - o)));
  }
  c.rgb += s * 0.85;
  return c;"""),
        FxDef("rain", R.string.fx_rain, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.4f)), """
  vec4 c = T(uv);
  float s = 0.0;
  for (int i = 0; i < 2; i++) {
    float fi = float(i);
    vec2 u = uv * vec2(60.0 + fi * 40.0, 4.0 + fi * 2.0);
    u.y += uTime * (6.0 + uP0.y * 14.0) * (1.0 + fi * 0.3);
    u.x += u.y * 0.02;
    vec2 id = floor(u);
    vec2 f = fract(u);
    float h = hash(id + fi * 7.0);
    s += step(1.0 - uP0.x * 0.35, h) * (1.0 - smoothstep(0.0, 0.12, abs(f.x - 0.5)))
       * smoothstep(0.0, 0.3, f.y) * (1.0 - smoothstep(0.6, 1.0, f.y));
  }
  c.rgb = mix(c.rgb, vec3(0.8, 0.85, 0.95), clamp(s, 0.0, 1.0) * 0.45);
  return c;"""),
        FxDef("letterbox", R.string.fx_letterbox, R.string.cat_light, listOf(p("size", R.string.p_size, 0.4f)), """
  vec4 c = T(uv);
  float b = uP0.x * 0.25;
  c.rgb *= step(b, uv.y) * step(uv.y, 1.0 - b);
  return c;"""),

        // ---------------- Segunda tanda ----------------
        // Luz y clima
        FxDef("bokeh", R.string.fx_bokeh, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  vec3 acc = vec3(0.0);
  for (int i = 0; i < 2; i++) {
    float fi = float(i);
    float sc = 4.0 + fi * 3.0;
    vec2 u = uv * vec2(sc * asp, sc) + vec2(uTime * 0.05 * (1.0 + fi), uTime * (0.08 + uP0.y * 0.3) * (1.0 + fi * 0.5));
    vec2 id = floor(u);
    vec2 f = fract(u) - 0.5;
    float h = hash(id + fi * 11.0);
    vec2 o = (vec2(h, fract(h * 41.0)) - 0.5) * 0.4;
    float r = 0.12 + 0.12 * fract(h * 17.0);
    float d = length(f - o);
    float b = (1.0 - smoothstep(r * 0.85, r, d)) * (0.6 + 0.4 * smoothstep(r * 0.5, r, d));
    b *= step(0.45, h) * (0.5 + 0.5 * sin(uTime * (0.8 + h * 2.0) + h * 30.0));
    acc += b * mix(vec3(1.0, 0.8, 0.5), vec3(0.6, 0.8, 1.0), fract(h * 7.0));
  }
  c.rgb = 1.0 - (1.0 - c.rgb) * (1.0 - clamp(acc * uP0.x * 0.55, 0.0, 1.0));
  return c;"""),
        FxDef("light_rays", R.string.fx_light_rays, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float t = uTime * (0.1 + uP0.y * 0.5);
  vec2 d = uv - vec2(0.5 + 0.25 * sin(t), 1.15);
  float a = atan(d.x, -d.y);
  float r = noise(vec2(a * 7.0, t)) * noise(vec2(a * 17.0 + 3.0, t * 0.7));
  r = smoothstep(0.12, 0.55, r) * (1.0 - smoothstep(0.2, 1.35, length(d)));
  c.rgb += vec3(1.0, 0.92, 0.75) * r * uP0.x * 0.9;
  return c;"""),
        FxDef("lens_flare", R.string.fx_lens_flare, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("position", R.string.p_position, 0.3f)), """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  vec2 lp = vec2(0.15 + 0.7 * uP0.y, 0.74);
  vec2 d = (uv - lp) * vec2(asp, 1.0);
  float core = 0.012 / (dot(d, d) + 0.012);
  float streak = 0.006 / (abs(d.y) + 0.006) * (1.0 - smoothstep(0.0, 0.7, abs(d.x)));
  vec2 g1 = (uv - (1.0 - lp)) * vec2(asp, 1.0);
  vec2 g2 = (uv - mix(lp, 1.0 - lp, 0.65)) * vec2(asp, 1.0);
  float ghost = (1.0 - smoothstep(0.05, 0.09, length(g1))) * 0.22 + (1.0 - smoothstep(0.02, 0.04, length(g2))) * 0.3;
  vec3 f = vec3(1.0, 0.86, 0.6) * core * 0.9 + vec3(0.6, 0.75, 1.0) * streak * 0.35 + vec3(0.5, 1.0, 0.75) * ghost;
  c.rgb = 1.0 - (1.0 - c.rgb) * (1.0 - clamp(f * uP0.x, 0.0, 1.0));
  return c;"""),
        FxDef("sun_glow", R.string.fx_sun_glow, R.string.cat_light, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec4 c = T(uv);
  float g = 1.0 - smoothstep(0.0, 1.1, distance(uv, vec2(0.85, 0.95)));
  g *= 0.85 + 0.15 * sin(uTime * 0.8);
  c.rgb = 1.0 - (1.0 - c.rgb) * (1.0 - vec3(1.0, 0.7, 0.35) * g * uP0.x * 0.8);
  return c;"""),
        FxDef("spotlight", R.string.fx_spotlight, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.5f), p("radius", R.string.p_radius, 0.4f)), """
  vec4 c = T(uv);
  float d = length((uv - 0.5) * vec2(uRes.x / uRes.y, 1.0));
  float r = 0.12 + uP0.y * 0.5;
  c.rgb *= 1.0 - smoothstep(r, r + 0.28, d) * uP0.x;
  return c;"""),
        FxDef("fog", R.string.fx_fog, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float t = uTime * (0.3 + uP0.y * 1.5);
  float n = noise(uv * vec2(3.0, 2.0) + vec2(t * 0.08, 0.0)) * 0.6 + noise(uv * vec2(7.0, 5.0) - vec2(t * 0.12, t * 0.03)) * 0.4;
  float f = smoothstep(0.3, 0.9, n) * (1.0 - uv.y * 0.6);
  c.rgb = mix(c.rgb, vec3(0.85, 0.88, 0.92), f * uP0.x * 0.8);
  return c;"""),
        FxDef("gradient_overlay", R.string.fx_gradient_overlay, R.string.cat_light,
            listOf(p("hue_a", R.string.p_color_a, 0.85f), p("hue_b", R.string.p_color_b, 0.55f), p("amount", R.string.p_amount, 0.4f)), """
  vec4 c = T(uv);
  vec3 a = ${hue("uP0.x")};
  vec3 b = ${hue("uP0.y")};
  vec3 g = mix(a, b, uv.y) * 0.6;
  c.rgb = mix(c.rgb, 1.0 - (1.0 - c.rgb) * (1.0 - g), uP0.z);
  return c;"""),
        FxDef("flash", R.string.fx_flash, R.string.cat_light, listOf(p("amount", R.string.p_amount, 0.3f)), """
  vec4 c = T(uv);
  c.rgb += vec3(0.7) * uP0.x;
  return c;"""),
        FxDef("confetti", R.string.fx_confetti, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.4f)), """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    float sc = 9.0 + fi * 5.0;
    vec2 u = uv * vec2(sc * asp, sc);
    u.y += uTime * (1.0 + uP0.y * 3.0) * (1.0 + fi * 0.3);
    u.x += sin(uTime * 1.3 + fi * 2.0) * 0.4;
    float h = hash(floor(u) + fi * 13.0);
    vec2 q = fract(u) - 0.5 - (vec2(h, fract(h * 37.0)) - 0.5) * 0.5;
    float a = uTime * (2.0 + h * 4.0) + h * 20.0;
    q = vec2(q.x * cos(a) - q.y * sin(a), q.x * sin(a) + q.y * cos(a));
    float m = step(abs(q.x), 0.12) * step(abs(q.y), 0.05 * (0.3 + abs(cos(a * 1.7)))) * step(1.0 - uP0.x * 0.7, h);
    c.rgb = mix(c.rgb, ${hue("fract(h * 91.0)")}, m);
  }
  return c;"""),
        FxDef("hearts", R.string.fx_hearts, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  for (int i = 0; i < 2; i++) {
    float fi = float(i);
    float sc = 5.0 + fi * 3.0;
    vec2 u = uv * vec2(sc * asp, sc);
    u.y -= uTime * (0.3 + uP0.y * 1.2) * (1.0 + fi * 0.4);
    u.x += sin(uTime * 0.8 + fi * 3.0) * 0.3;
    float h = hash(floor(u) + fi * 19.0);
    vec2 q = (fract(u) - 0.5 - (vec2(h, fract(h * 37.0)) - 0.5) * 0.4) / (0.13 + 0.07 * fract(h * 11.0));
    q *= 1.0 + 0.08 * sin(uTime * 5.0 + h * 30.0);
    q.y += 0.25;
    float a2 = q.x * q.x + q.y * q.y - 1.0;
    float m = step(a2 * a2 * a2 - q.x * q.x * q.y * q.y * q.y, 0.0) * step(1.0 - uP0.x * 0.6, h);
    c.rgb = mix(c.rgb, mix(vec3(1.0, 0.25, 0.45), vec3(1.0, 0.55, 0.7), fract(h * 5.0)), m * 0.9);
  }
  return c;"""),
        FxDef("bubbles", R.string.fx_bubbles, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  for (int i = 0; i < 2; i++) {
    float fi = float(i);
    float sc = 6.0 + fi * 4.0;
    vec2 u = uv * vec2(sc * asp, sc);
    u.y -= uTime * (0.3 + uP0.y * 1.4) * (1.0 + fi * 0.4);
    u.x += sin(uTime * 1.2 + fi * 2.0) * 0.25;
    float h = hash(floor(u) + fi * 29.0);
    vec2 q = fract(u) - 0.5 - (vec2(h, fract(h * 37.0)) - 0.5) * 0.4;
    float r = 0.1 + 0.12 * fract(h * 13.0);
    float d = length(q);
    float ring = smoothstep(r - 0.03, r, d) * (1.0 - smoothstep(r, r + 0.012, d));
    float hl = 1.0 - smoothstep(0.0, r * 0.3, length(q - vec2(-r * 0.4, r * 0.4)));
    float body = (1.0 - smoothstep(r - 0.01, r, d)) * 0.06;
    c.rgb += (ring * 0.55 + hl * 0.5 + body) * step(1.0 - uP0.x * 0.6, h) * vec3(0.85, 0.95, 1.0);
  }
  return c;"""),
        FxDef("embers", R.string.fx_embers, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.4f)), """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  vec3 acc = vec3(0.0);
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    float sc = 9.0 + fi * 7.0;
    vec2 u = uv * vec2(sc * asp, sc);
    u.y -= uTime * (0.5 + uP0.y * 2.0) * (1.0 + fi * 0.35);
    u.x += sin(uTime * 0.9 + fi * 2.0) * 0.35;
    float h = hash(floor(u) + fi * 23.0);
    vec2 q = fract(u) - 0.5 - (vec2(h, fract(h * 37.0)) - 0.5) * 0.6;
    float g = 1.0 - smoothstep(0.0, 0.14 - fi * 0.03, length(q));
    g *= step(1.0 - uP0.x * 0.55, h) * (0.6 + 0.4 * sin(uTime * (3.0 + h * 8.0) + h * 50.0));
    acc += g * g * mix(vec3(1.0, 0.35, 0.05), vec3(1.0, 0.8, 0.3), fract(h * 9.0));
  }
  c.rgb += acc * 1.4;
  return c;"""),
        FxDef("dust", R.string.fx_dust, R.string.cat_retro, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec4 c = T(uv);
  float t = floor(uTime * 12.0);
  vec2 g = uv * vec2(34.0 * uRes.x / uRes.y, 34.0);
  float h = hash(floor(g) + t * 3.1);
  float sp = step(1.0 - 0.03 * uP0.x, h) * (1.0 - smoothstep(0.1, 0.45, length(fract(g) - 0.5)));
  c.rgb = mix(c.rgb, vec3(fract(h * 77.0) > 0.5 ? 0.92 : 0.06), sp * 0.8);
  float hx = hash(vec2(t, 4.0)) + sin(uv.y * 9.0 + t) * 0.01;
  float hair = step(0.75, hash(vec2(t, 8.0))) * (1.0 - smoothstep(0.0, 0.0018, abs(uv.x - hx))) * step(abs(uv.y - hash(vec2(t, 6.0))), 0.12);
  c.rgb = mix(c.rgb, vec3(0.08), hair * uP0.x * 0.8);
  return c;"""),

        // Marcos y retro
        FxDef("photo_frame", R.string.fx_photo_frame, R.string.cat_retro, listOf(p("size", R.string.p_size, 0.4f)), """
  vec4 c = T(uv);
  float b = 0.02 + uP0.x * 0.08;
  float bx = b * uRes.y / uRes.x;
  float inside = step(bx, uv.x) * step(uv.x, 1.0 - bx) * step(b * 2.2, uv.y) * step(uv.y, 1.0 - b);
  return vec4(mix(vec3(0.97, 0.96, 0.93), c.rgb, inside), c.a);"""),
        FxDef("film_strip", R.string.fx_film_strip, R.string.cat_retro, listOf(p("size", R.string.p_size, 0.4f)), """
  vec4 c = T(uv);
  float bx = 0.03 + uP0.x * 0.1;
  float side = step(uv.x, bx) + step(1.0 - bx, uv.x);
  float hole = step(abs(fract(uv.y * 9.0) - 0.5), 0.24) * step(abs(abs(uv.x - 0.5) - (0.5 - bx * 0.5)), bx * 0.28);
  vec3 col = mix(c.rgb, vec3(0.03), clamp(side, 0.0, 1.0));
  return vec4(mix(col, vec3(0.88), hole), c.a);"""),
        FxDef("super8", R.string.fx_super8, R.string.cat_retro, listOf(p("amount", R.string.p_amount, 0.6f)), """
  float tk = floor(uTime * 16.0);
  vec4 c = T(uv + vec2(0.0, (hash(vec2(tk, 3.0)) - 0.5) * 0.006 * uP0.x));
  c.rgb *= mix(1.0, 0.88 + 0.12 * hash(vec2(tk, 8.0)), uP0.x);
  vec2 q = abs(uv - 0.5) * 2.0;
  c.rgb *= 1.0 - smoothstep(0.86, 1.0, max(q.x, q.y)) * uP0.x;
  c.rgb *= 1.0 - smoothstep(1.15, 1.35, length(q)) * uP0.x;
  c.rgb = mix(c.rgb, c.rgb * vec3(1.08, 1.0, 0.86), uP0.x * 0.7);
  return c;"""),
        FxDef("camcorder", R.string.fx_camcorder, R.string.cat_retro, listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  c.rgb *= 1.0 - 0.07 * uP0.x * (0.5 + 0.5 * sin(uv.y * uRes.y * 1.6));
  vec2 q = abs(uv - 0.5);
  float tx = 0.004 / asp;
  float lx = 0.07 / asp;
  float fr = step(abs(q.x - 0.44), tx) * step(0.39, q.y) * step(q.y, 0.464)
           + step(abs(q.y - 0.46), 0.004) * step(0.44 - lx, q.x) * step(q.x, 0.44 + tx);
  float rec = 1.0 - smoothstep(0.016, 0.02, length((uv - vec2(0.06 + 0.06 / asp, 0.9)) * vec2(asp, 1.0)));
  rec *= step(fract(uTime * 0.8), 0.6);
  c.rgb = mix(c.rgb, vec3(1.0), clamp(fr, 0.0, 1.0) * 0.85 * uP0.x);
  c.rgb = mix(c.rgb, vec3(1.0, 0.1, 0.1), rec * uP0.x);
  return c;"""),
        FxDef("glitch_blocks", R.string.fx_glitch_blocks, R.string.cat_retro,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.4f)), """
  float t = floor(uTime * (6.0 + uP0.y * 20.0));
  vec2 g = floor(uv * vec2(6.0, 10.0));
  float h = hash(g + t);
  float on = step(1.0 - uP0.x * 0.35, h);
  vec4 c = T(uv + vec2((hash(g + t + 3.0) - 0.5) * 0.15 * on, 0.0));
  c.rgb = mix(c.rgb, c.gbr, on * step(0.5, fract(h * 13.0)));
  return c;"""),
        FxDef("retro_game", R.string.fx_retro_game, R.string.cat_retro, listOf(p("size", R.string.p_size, 0.4f)), """
  float n = mix(160.0, 36.0, uP0.x);
  vec2 g = vec2(n, n * uRes.y / uRes.x);
  vec4 c = T((floor(uv * g) + 0.5) / g);
  c.rgb = floor(c.rgb * 4.0 + 0.5) / 4.0;
  return c;"""),
        FxDef("hologram", R.string.fx_hologram, R.string.cat_retro, listOf(p("amount", R.string.p_amount, 0.6f)), """
  float j = step(0.9, hash(vec2(floor(uTime * 10.0), floor(uv.y * 40.0)))) * 0.02 * uP0.x;
  vec4 c = T(uv + vec2(j, 0.0));
  vec3 h = vec3(0.2, 0.9, 1.0) * (luma(c.rgb) * 1.3 + 0.1);
  h *= 0.75 + 0.25 * sin(uv.y * uRes.y * 1.2 + uTime * 8.0);
  h *= 0.9 + 0.1 * sin(uTime * 30.0);
  h += vec3(0.3, 0.6, 0.7) * (1.0 - smoothstep(0.0, 0.04, abs(fract(uv.y + uTime * 0.3) - 0.5)));
  return vec4(mix(c.rgb, h, uP0.x), c.a);"""),
        FxDef("code_rain", R.string.fx_code_rain, R.string.cat_retro,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.4f)), """
  vec4 c = T(uv);
  float cols = 46.0 * uRes.x / uRes.y;
  float cx = floor(uv.x * cols);
  float h = hash(vec2(cx, 1.0));
  float row = floor(uv.y * 46.0);
  float y = fract(row / 46.0 + uTime * (0.15 + uP0.y * 0.6) * (0.5 + h) + h * 10.0);
  float gl = step(0.35, hash(vec2(cx, row + floor(uTime * 8.0) * 3.0)));
  vec2 f = fract(vec2(uv.x * cols, uv.y * 46.0));
  float cell = step(0.15, f.x) * step(f.x, 0.85) * step(0.1, f.y) * step(f.y, 0.9) * gl * step(0.3, h);
  float l = luma(c.rgb);
  vec3 base = mix(c.rgb, vec3(0.0, l * 0.8, l * 0.25), uP0.x * 0.7);
  base += (vec3(0.25, 1.0, 0.45) * pow(y, 5.0) + vec3(0.8, 1.0, 0.85) * step(0.97, y)) * cell * uP0.x;
  return vec4(base, c.a);"""),

        // Desenfoque
        FxDef("radial_blur", R.string.fx_radial_blur, R.string.cat_blur, listOf(p("amount", R.string.p_amount, 0.4f)), """
  float asp = uRes.x / uRes.y;
  vec2 u = uv - 0.5;
  u.x *= asp;
  vec4 acc = vec4(0.0);
  for (int i = 0; i < 12; i++) {
    float a = (float(i) / 11.0 - 0.5) * uP0.x * 0.35;
    float cs = cos(a);
    float sn = sin(a);
    vec2 r = vec2(u.x * cs - u.y * sn, u.x * sn + u.y * cs);
    r.x /= asp;
    acc += T(r + 0.5);
  }
  return acc / 12.0;"""),
        FxDef("lens_blur", R.string.fx_lens_blur, R.string.cat_blur, listOf(p("amount", R.string.p_amount, 0.5f)), """
  float r = smoothstep(0.15, 0.7, distance(uv, vec2(0.5))) * uP0.x * 0.035;
  vec4 acc = T(uv);$DISK_BLUR
  return acc / 17.0;"""),
        FxDef("frosted", R.string.fx_frosted, R.string.cat_blur, listOf(p("amount", R.string.p_amount, 0.4f)), """
  vec2 o = (vec2(hash(uv * uRes), hash(uv * uRes + 17.0)) - 0.5) * 0.045 * uP0.x;
  return T(uv + o);"""),
        FxDef("double_vision", R.string.fx_double_vision, R.string.cat_blur, listOf(p("amount", R.string.p_amount, 0.4f)), """
  vec2 o = vec2(0.035 * uP0.x * (0.6 + 0.4 * sin(uTime * 2.0)), 0.0);
  return mix(T(uv), (T(uv + o) + T(uv - o)) * 0.5, 0.6);"""),
        FxDef("echo", R.string.fx_echo, R.string.cat_blur, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec4 acc = T(uv);
  float w = 1.0;
  for (int i = 1; i < 5; i++) {
    float k = 0.6 / float(i);
    acc += T((uv - 0.5) / (1.0 + 0.1 * float(i) * uP0.x) + 0.5) * k;
    w += k;
  }
  return acc / w;"""),

        // Estilizar
        FxDef("comic", R.string.fx_comic, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec2 px = 1.2 / uRes;
  vec4 c = T(uv);
  float gx = luma(T(uv + vec2(px.x, 0.0)).rgb) - luma(T(uv - vec2(px.x, 0.0)).rgb);
  float gy = luma(T(uv + vec2(0.0, px.y)).rgb) - luma(T(uv - vec2(0.0, px.y)).rgb);
  float e = smoothstep(0.08, 0.25, length(vec2(gx, gy)) * (1.0 + uP0.x * 3.0));
  vec3 q = floor(c.rgb * 4.0 + 0.5) / 4.0;
  q = mix(vec3(luma(q)), q, 1.4);
  vec2 g = vec2(90.0 * uRes.x / uRes.y, 90.0);
  float dots = step(length(fract(uv * g) - 0.5), (1.0 - luma(c.rgb)) * 0.45) * 0.18;
  return vec4(clamp(q - dots, 0.0, 1.0) * (1.0 - e), c.a);"""),
        FxDef("watercolor", R.string.fx_watercolor, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec4 c = T(uv);
  vec3 acc = c.rgb;
  float r = 0.004 + 0.008 * uP0.x;
  for (int i = 0; i < 8; i++) {
    float a = float(i) * 0.785398;
    acc += T(uv + vec2(cos(a), sin(a) * uRes.x / uRes.y) * r).rgb;
  }
  acc /= 9.0;
  vec3 col = mix(acc, floor(acc * 7.0 + 0.5) / 7.0, 0.6);
  col = mix(vec3(luma(col)), col, 1.25);
  col *= 0.92 + 0.08 * noise(uv * uRes / 3.0);
  col = mix(col, vec3(1.0), 0.07);
  return vec4(mix(c.rgb, col, clamp(uP0.x * 2.0, 0.0, 1.0)), c.a);"""),
        FxDef("blueprint", R.string.fx_blueprint, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 1f)), """
  vec2 px = 1.5 / uRes;
  vec4 c = T(uv);
  float gx = luma(T(uv + vec2(px.x, 0.0)).rgb) - luma(T(uv - vec2(px.x, 0.0)).rgb);
  float gy = luma(T(uv + vec2(0.0, px.y)).rgb) - luma(T(uv - vec2(0.0, px.y)).rgb);
  float e = clamp(length(vec2(gx, gy)) * 7.0, 0.0, 1.0);
  vec2 g = fract(uv * vec2(16.0 * uRes.x / uRes.y, 16.0));
  float grid = max(step(0.96, g.x), step(0.96, g.y));
  vec3 col = vec3(0.05, 0.22, 0.52) + luma(c.rgb) * 0.12 + e * vec3(0.9, 0.95, 1.0) + grid * 0.07;
  return vec4(mix(c.rgb, col, uP0.x), c.a);"""),
        FxDef("neon_edges", R.string.fx_neon_edges, R.string.cat_stylize,
            listOf(p("amount", R.string.p_amount, 0.6f), p("hue", R.string.p_color, 0.5f)), """
  vec2 px = 1.5 / uRes;
  vec4 c = T(uv);
  float gx = luma(T(uv + vec2(px.x, 0.0)).rgb) - luma(T(uv - vec2(px.x, 0.0)).rgb);
  float gy = luma(T(uv + vec2(0.0, px.y)).rgb) - luma(T(uv - vec2(0.0, px.y)).rgb);
  float e = clamp(length(vec2(gx, gy)) * 6.0, 0.0, 1.0);
  vec3 col = mix(${hue("uP0.y")}, vec3(1.0), e * 0.4);
  return vec4(mix(c.rgb, c.rgb * 0.22 + col * e * 1.7, uP0.x), c.a);"""),
        FxDef("infrared", R.string.fx_infrared, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 1f)), """
  vec4 c = T(uv);
  vec3 ir = vec3(c.g * 1.2 + c.r * 0.2, c.r * 0.6 + c.b * 0.3, c.b * 0.8 + c.r * 0.2);
  return vec4(mix(c.rgb, ir, uP0.x), c.a);"""),
        FxDef("solarize", R.string.fx_solarize, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 1f)), """
  vec4 c = T(uv);
  return vec4(mix(c.rgb, 1.0 - abs(2.0 * c.rgb - 1.0), uP0.x), c.a);"""),
        FxDef("stained_glass", R.string.fx_stained_glass, R.string.cat_stylize, listOf(p("size", R.string.p_size, 0.4f)), """
  float n = mix(40.0, 8.0, uP0.x);
  vec2 sc = vec2(n * uRes.x / uRes.y, n);
  vec2 id = floor(uv * sc);
  vec2 f = fract(uv * sc);
  float md = 9.0;
  float md2 = 9.0;
  vec2 best = vec2(0.0);
  for (int j = -1; j <= 1; j++) {
    for (int i = -1; i <= 1; i++) {
      vec2 o = vec2(float(i), float(j));
      vec2 pt = o + vec2(hash(id + o), hash(id + o + 31.0));
      float d = length(pt - f);
      if (d < md) { md2 = md; md = d; best = id + pt; }
      else if (d < md2) { md2 = d; }
    }
  }
  vec4 c = T(best / sc);
  c.rgb *= smoothstep(0.0, 0.08, md2 - md);
  return c;"""),

        // Distorsión
        FxDef("droste", R.string.fx_droste, R.string.cat_distort,
            listOf(p("size", R.string.p_size, 0.4f), p("speed", R.string.p_speed, 0.3f)), """
  float k = mix(0.9, 0.55, uP0.x);
  float ph = fract(uTime * uP0.y * 0.8);
  vec2 u = (uv - 0.5) * pow(k, ph);
  float m = max(max(abs(u.x), abs(u.y)) * 2.0, 0.0005);
  float lvl = min(floor(log(m) / log(k)), 8.0);
  vec4 c = T(u / pow(k, lvl) + 0.5);
  c.rgb *= 1.0 - 0.06 * max(lvl - ph, 0.0);
  return c;"""),
        FxDef("prism", R.string.fx_prism, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.3f)), """
  float a = uTime * (0.3 + uP0.y * 2.0);
  float r = 0.004 + 0.022 * uP0.x;
  float asp = uRes.x / uRes.y;
  vec4 c = T(uv);
  c.r = T(uv + vec2(cos(a), sin(a) * asp) * r).r;
  c.g = T(uv + vec2(cos(a + 2.0944), sin(a + 2.0944) * asp) * r).g;
  c.b = T(uv + vec2(cos(a + 4.1888), sin(a + 4.1888) * asp) * r).b;
  return c;"""),
        FxDef("ripple", R.string.fx_ripple, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.4f), p("freq", R.string.p_frequency, 0.4f), p("speed", R.string.p_speed, 0.4f)), """
  float asp = uRes.x / uRes.y;
  vec2 u = (uv - 0.5) * vec2(asp, 1.0);
  float d = length(u);
  float w = sin(d * (15.0 + uP0.y * 60.0) - uTime * (2.0 + uP0.z * 10.0)) * 0.012 * uP0.x;
  return T(uv + u / (d + 0.0001) * w / vec2(asp, 1.0));"""),
        FxDef("pinch", R.string.fx_pinch, R.string.cat_distort, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec2 u = uv - 0.5;
  u *= 1.0 + uP0.x * 1.4 * max(0.0, 0.5 - dot(u, u) * 2.0);
  return T(u + 0.5);"""),
        FxDef("glass_blocks", R.string.fx_glass_blocks, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.5f), p("size", R.string.p_size, 0.4f)), """
  float n = mix(30.0, 6.0, uP0.y);
  vec2 g = vec2(n * uRes.x / uRes.y, n);
  vec2 f = fract(uv * g) - 0.5;
  vec4 c = T(uv + f / g * uP0.x * 1.6);
  c.rgb *= 1.0 - 0.25 * smoothstep(0.4, 0.5, max(abs(f.x), abs(f.y)));
  return c;"""),
        FxDef("split_screen", R.string.fx_split_screen, R.string.cat_distort, listOf(p("count", R.string.p_size, 0.3f)), """
  float n = floor(2.0 + uP0.x * 3.4);
  float f = fract(uv.x * n);
  vec4 c = T(vec2(0.5 + (f - 0.5) / n, uv.y));
  c.rgb *= step(0.012, f) * step(f, 0.988);
  return c;"""),

        // Movimiento
        FxDef("zoom", R.string.fx_zoom, R.string.cat_motion, listOf(p("amount", R.string.p_amount, 0.2f)), """
  return T((uv - 0.5) / (1.0 + uP0.x * 0.5) + 0.5);"""),
        FxDef("beat_zoom", R.string.fx_beat_zoom, R.string.cat_motion,
            listOf(p("amount", R.string.p_amount, 0.4f), p("speed", R.string.p_speed, 0.4f)), """
  float k = exp(-fract(uTime * (0.5 + uP0.y * 3.5)) * 6.0);
  return T((uv - 0.5) / (1.0 + uP0.x * 0.25 * k) + 0.5);"""),
        FxDef("rgb_pulse", R.string.fx_rgb_pulse, R.string.cat_motion,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.4f)), """
  float k = exp(-fract(uTime * (0.5 + uP0.y * 3.5)) * 5.0);
  vec2 d = (uv - 0.5) * uP0.x * 0.1 * k;
  vec4 c = T(uv);
  c.r = T(uv + d).r;
  c.b = T(uv - d).b;
  return c;"""),
        FxDef("handheld", R.string.fx_handheld, R.string.cat_motion,
            listOf(p("amount", R.string.p_amount, 0.4f), p("speed", R.string.p_speed, 0.4f)), """
  float t = uTime * (0.5 + uP0.y * 3.0);
  vec2 o = (vec2(noise(vec2(t * 1.3, 1.0)), noise(vec2(t * 1.1, 9.0))) - 0.5) * 0.045 * uP0.x;
  return T((uv - 0.5) / (1.0 + 0.05 * uP0.x) + 0.5 + o);"""),

        // ---------------- Tercera tanda (30) ----------------
        // Luz y clima
        FxDef("aurora", R.string.fx_aurora, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float t = uTime * (0.1 + uP0.y * 0.5);
  vec3 col = vec3(0.0);
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    float w = noise(vec2(uv.x * (2.0 + fi) + t * (1.0 + fi * 0.5), fi * 7.0));
    float yc = 0.68 + 0.12 * sin(uv.x * 3.0 + t * 2.0 + fi * 2.0) + (w - 0.5) * 0.25;
    float a = (1.0 - smoothstep(0.0, 0.18, abs(uv.y - yc))) * (0.5 + 0.5 * noise(vec2(uv.x * 12.0 + fi * 3.0, t * 3.0)));
    col += a * mix(vec3(0.1, 1.0, 0.5), vec3(0.6, 0.3, 1.0), clamp(fi / 2.0 + w * 0.3, 0.0, 1.0));
  }
  c.rgb = 1.0 - (1.0 - c.rgb) * (1.0 - clamp(col * uP0.x * 0.9, 0.0, 1.0));
  return c;"""),
        FxDef("starfield", R.string.fx_starfield, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.4f)), """
  vec4 c = T(uv);
  vec2 q = (uv - 0.5) * vec2(uRes.x / uRes.y, 1.0);
  float r = length(q);
  float a = atan(q.y, q.x) / 6.2831853 + 0.5;
  float s = 0.0;
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    float cells = 30.0 + fi * 25.0;
    float id = floor(a * cells);
    float h = hash(vec2(id, fi));
    float prog = fract(uTime * (0.15 + uP0.y * 0.8) * (0.5 + h) + h * 7.0);
    float rr = prog * prog;
    float f = abs(fract(a * cells) - 0.5);
    float line = (1.0 - smoothstep(0.0, 0.12 + 0.2 * prog, f)) * (1.0 - smoothstep(0.0, 0.02 + 0.14 * prog, abs(r - rr)));
    s += line * step(1.0 - uP0.x * 0.5, h);
  }
  c.rgb *= 1.0 - 0.3 * uP0.x;
  c.rgb += s * vec3(0.8, 0.9, 1.0) * 1.3;
  return c;"""),
        FxDef("lightning", R.string.fx_lightning, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float tt = uTime * (1.0 + uP0.y * 3.0);
  float t = floor(tt);
  float ph = fract(tt);
  float on = step(1.0 - uP0.x * 0.4, hash(vec2(t, 1.0)));
  float flash = on * exp(-ph * 9.0) * (0.6 + 0.4 * sin(ph * 60.0));
  float x0 = 0.2 + 0.6 * hash(vec2(t, 2.0));
  float x = x0 + (noise(vec2(uv.y * 8.0, t)) - 0.5) * 0.2 + (uv.y - 0.5) * (hash(vec2(t, 3.0)) - 0.5) * 0.4;
  float bolt = (1.0 - smoothstep(0.0, 0.008 + 0.012 * flash, abs(uv.x - x))) * smoothstep(0.1, 0.9, uv.y) * on * (1.0 - ph * 0.9);
  c.rgb += vec3(0.6, 0.7, 1.0) * flash * 0.7 + vec3(0.85, 0.92, 1.0) * bolt * 1.4;
  return c;"""),
        FxDef("fireflies", R.string.fx_fireflies, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  vec3 acc = vec3(0.0);
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    float sc = 5.0 + fi * 3.0;
    vec2 u = uv * vec2(sc * asp, sc);
    float h = hash(floor(u) + fi * 31.0);
    float t = uTime * (0.3 + uP0.y);
    vec2 o = vec2(sin(t * (0.4 + h) + h * 20.0), cos(t * (0.5 + h) + h * 30.0)) * 0.3;
    vec2 q = fract(u) - 0.5 - o;
    float tw = 0.5 + 0.5 * sin(uTime * (1.5 + h * 3.0) + h * 40.0);
    acc += exp(-dot(q, q) * 90.0) * tw * step(1.0 - uP0.x * 0.6, h) * vec3(1.0, 0.9, 0.4);
  }
  c.rgb += acc * 1.3;
  return c;"""),
        FxDef("disco_lights", R.string.fx_disco_lights, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.4f)), """
  vec4 c = T(uv);
  float t = uTime * (0.4 + uP0.y * 2.0);
  float asp = uRes.x / uRes.y;
  vec3 add = vec3(0.0);
  for (int i = 0; i < 4; i++) {
    float fi = float(i);
    vec2 p0 = vec2(0.5 + 0.45 * sin(t * (0.7 + fi * 0.3) + fi * 1.7), 0.5 + 0.35 * cos(t * (0.5 + fi * 0.27) + fi * 2.3));
    float g = 1.0 - smoothstep(0.0, 0.32, length((uv - p0) * vec2(asp, 1.0)));
    add += g * g * ${hue("fract(fi * 0.27 + t * 0.1)")};
  }
  c.rgb = c.rgb * (1.0 - 0.4 * uP0.x) + add * uP0.x * 0.8;
  return c;"""),
        FxDef("fireworks", R.string.fx_fireworks, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  vec3 acc = vec3(0.0);
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    float t = uTime * (0.35 + uP0.y * 0.8) + fi * 0.37;
    float cyc = floor(t);
    float ph = fract(t);
    vec2 ctr = vec2(0.2 + 0.6 * hash(vec2(cyc, fi)), 0.35 + 0.45 * hash(vec2(cyc, fi + 9.0)));
    vec2 d = (uv - ctr) * vec2(asp, 1.0);
    float r = length(d);
    float rays = 0.5 + 0.5 * cos(atan(d.y, d.x) * 12.0 + hash(vec2(cyc, fi)) * 6.0);
    float shell = (1.0 - smoothstep(0.0, 0.012 + 0.02 * ph, abs(r - ph * 0.35))) * (0.4 + 0.6 * rays);
    float core = (1.0 - smoothstep(0.0, 0.04, r)) * (1.0 - smoothstep(0.0, 0.15, ph));
    acc += (shell * (1.0 - ph) * (1.0 - ph) + core) * ${hue("fract(hash(vec2(cyc, fi + 3.0)))")};
  }
  c.rgb += acc * uP0.x * 1.6;
  return c;"""),
        FxDef("petals", R.string.fx_petals, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.3f)),
            faller("(1.0 - smoothstep(0.5, 1.0, length(q * vec2(5.0, 9.0))))", "mix(vec3(1.0, 0.78, 0.86), vec3(1.0, 0.5, 0.7), fract(h * 7.0))")),
        FxDef("autumn_leaves", R.string.fx_autumn_leaves, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.3f)),
            faller("(1.0 - smoothstep(0.5, 1.0, length(q * vec2(4.0, 7.0))))", "mix(vec3(0.9, 0.3, 0.05), vec3(1.0, 0.75, 0.1), fract(h * 5.0)) * (0.8 + 0.2 * smoothstep(0.0, 0.03, abs(q.y)))")),
        FxDef("rain_glass", R.string.fx_rain_glass, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.3f)), """
  float asp = uRes.x / uRes.y;
  vec2 g = uv * vec2(7.0 * asp, 7.0);
  float h = hash(floor(g));
  vec2 f = fract(g) - 0.5 - (vec2(h, fract(h * 17.0)) - 0.5) * 0.5;
  float slide = fract(uTime * (0.05 + uP0.y * 0.25) * (0.5 + h) + h * 10.0);
  f.y += (slide - 0.5) * 0.5;
  float drop = (1.0 - smoothstep(0.1, 0.2, length(f * vec2(1.0, 0.8)))) * step(1.0 - uP0.x * 0.6, h);
  vec4 c = T(uv - f * drop * 0.06 / vec2(asp, 1.0));
  c.rgb += drop * 0.07;
  c.rgb = mix(c.rgb, c.rgb * 0.9 + 0.03, uP0.x * 0.4);
  return c;"""),

        // Estilizar
        FxDef("oil_paint", R.string.fx_oil_paint, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec2 px = (1.0 / uRes) * (1.5 + uP0.x * 4.0);
  vec4 src = T(uv);
  ${kuw(0, "-1.0", "-1.0")}
  ${kuw(1, "1.0", "-1.0")}
  ${kuw(2, "-1.0", "1.0")}
  ${kuw(3, "1.0", "1.0")}
  vec3 best = m0;
  float bv = v0;
  if (v1 < bv) { bv = v1; best = m1; }
  if (v2 < bv) { bv = v2; best = m2; }
  if (v3 < bv) { bv = v3; best = m3; }
  best = mix(best, best * best * (3.0 - 2.0 * best), 0.4);
  return vec4(mix(src.rgb, best, clamp(uP0.x * 2.5, 0.0, 1.0)), src.a);"""),
        FxDef("color_pencil", R.string.fx_color_pencil, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec2 px = 1.2 / uRes;
  vec4 c = T(uv);
  float gx = luma(T(uv + vec2(px.x, 0.0)).rgb) - luma(T(uv - vec2(px.x, 0.0)).rgb);
  float gy = luma(T(uv + vec2(0.0, px.y)).rgb) - luma(T(uv - vec2(0.0, px.y)).rgb);
  float e = clamp(length(vec2(gx, gy)) * 5.0, 0.0, 1.0);
  float hatch = 0.5 + 0.5 * sin((uv.x + uv.y) * uRes.y * 0.9 + noise(uv * uRes / 4.0) * 6.0);
  vec3 col = mix(vec3(0.98, 0.97, 0.92), c.rgb, 0.55 + 0.3 * hatch);
  col *= 1.0 - e * 0.8;
  return vec4(mix(c.rgb, col, clamp(uP0.x * 1.6, 0.0, 1.0)), c.a);"""),
        FxDef("paper_cut", R.string.fx_paper_cut, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec4 c = T(uv);
  float lv = floor(luma(c.rgb) * 4.0 + 0.5) / 4.0;
  vec3 q = floor(c.rgb * 4.0 + 0.5) / 4.0;
  float ln = floor(luma(T(uv + vec2(0.006, -0.006)).rgb) * 4.0 + 0.5) / 4.0;
  q *= 1.0 - step(0.01, ln - lv) * 0.4;
  return vec4(mix(c.rgb, q, clamp(uP0.x * 2.0, 0.0, 1.0)), c.a);"""),
        FxDef("crosshatch", R.string.fx_crosshatch, R.string.cat_stylize,
            listOf(p("amount", R.string.p_amount, 0.7f), p("size", R.string.p_size, 0.4f)), """
  vec4 c = T(uv);
  float l = luma(c.rgb);
  vec2 p = uv * uRes;
  float sp = mix(8.0, 3.0, uP0.y);
  float ink = smoothstep(0.38, 0.5, abs(fract((p.x + p.y) / sp) - 0.5)) * step(l, 0.75)
            + smoothstep(0.38, 0.5, abs(fract((p.x - p.y) / sp) - 0.5)) * step(l, 0.5)
            + smoothstep(0.38, 0.5, abs(fract(p.x / sp) - 0.5)) * step(l, 0.3)
            + smoothstep(0.38, 0.5, abs(fract(p.y / sp) - 0.5)) * step(l, 0.18);
  vec3 col = vec3(0.96, 0.94, 0.88) * (1.0 - clamp(ink, 0.0, 1.0) * 0.85);
  return vec4(mix(c.rgb, col, clamp(uP0.x * 1.4, 0.0, 1.0)), c.a);"""),
        FxDef("anaglyph", R.string.fx_anaglyph, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec2 o = vec2(0.012 * uP0.x * (1.0 + 0.3 * sin(uTime * 2.0)), 0.0);
  vec4 c = T(uv);
  c.r = T(uv - o).r;
  c.g = T(uv + o).g;
  c.b = T(uv + o).b;
  return c;"""),
        FxDef("xray", R.string.fx_xray, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 1f)), """
  vec2 px = 1.5 / uRes;
  vec4 c = T(uv);
  float x = 1.0 - luma(c.rgb);
  float gx = luma(T(uv + vec2(px.x, 0.0)).rgb) - luma(T(uv - vec2(px.x, 0.0)).rgb);
  float gy = luma(T(uv + vec2(0.0, px.y)).rgb) - luma(T(uv - vec2(0.0, px.y)).rgb);
  float e = clamp(length(vec2(gx, gy)) * 5.0, 0.0, 1.0);
  vec3 col = vec3(0.1, 0.35, 0.55) * x + vec3(0.6, 0.9, 1.0) * pow(x, 3.0) + e * vec3(0.3, 0.7, 1.0);
  col += vec3(0.1, 0.3, 0.4) * (1.0 - smoothstep(0.0, 0.05, abs(fract(uTime * 0.3) - uv.y)));
  return vec4(mix(c.rgb, col, uP0.x), c.a);"""),
        FxDef("ascii", R.string.fx_ascii, R.string.cat_stylize, listOf(p("size", R.string.p_size, 0.4f)), """
  float cw = mix(6.0, 16.0, uP0.x);
  vec2 cell = vec2(cw, cw * 2.0) / uRes;
  vec2 id = floor(uv / cell);
  vec2 f = fract(uv / cell);
  vec4 c = T((id + 0.5) * cell);
  float l = luma(c.rgb);
  float g = 0.0;
  if (l > 0.8) g = step(0.15, f.x) * step(f.x, 0.85) * step(0.1, f.y) * step(f.y, 0.9);
  else if (l > 0.6) g = max(step(abs(f.x - 0.5), 0.12) * step(0.15, f.y) * step(f.y, 0.85), step(abs(f.y - 0.5), 0.07) * step(0.2, f.x) * step(f.x, 0.8));
  else if (l > 0.4) g = step(abs(f.x - 0.5), 0.12) * (step(abs(f.y - 0.3), 0.07) + step(abs(f.y - 0.7), 0.07));
  else if (l > 0.2) g = step(abs(f.x - 0.5), 0.12) * step(abs(f.y - 0.75), 0.08);
  return vec4(c.rgb * g * 1.4 + vec3(0.0, 0.03, 0.0), c.a);"""),
        FxDef("rainbow", R.string.fx_rainbow, R.string.cat_stylize,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float h = fract(uv.x * 0.6 + uv.y * 0.4 + uTime * (0.1 + uP0.y * 0.4));
  vec3 rb = ${hue("h")};
  c.rgb = mix(c.rgb, c.rgb * (0.4 + rb * 1.1), uP0.x);
  return c;"""),

        // Distorsión
        FxDef("liquid", R.string.fx_liquid, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.3f)), """
  float t = uTime * (0.3 + uP0.y);
  vec2 u = uv;
  u.y += (sin(uv.x * 6.0 + t * 2.0) * 0.02 + sin(uv.x * 17.0 - t * 3.0) * 0.008) * uP0.x;
  u.x += (cos(uv.y * 7.0 + t * 2.3) * 0.02 + cos(uv.y * 19.0 + t * 2.7) * 0.006) * uP0.x;
  vec4 c = T(u);
  float h = luma(T(u + vec2(0.003)).rgb) - luma(T(u - vec2(0.003)).rgb);
  c.rgb += h * 1.5 * uP0.x;
  return c;"""),
        FxDef("tunnel", R.string.fx_tunnel, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec2 q = (uv - 0.5) * vec2(uRes.x / uRes.y, 1.0);
  float r = length(q) + 0.0001;
  vec2 u = fract(vec2(0.35 / r + uTime * (0.2 + uP0.y), atan(q.y, q.x) / 3.14159265));
  vec4 c = T(u);
  c.rgb *= smoothstep(0.0, 0.25, r);
  return mix(T(uv), c, clamp(uP0.x * 1.5, 0.0, 1.0));"""),
        FxDef("paper_fold", R.string.fx_paper_fold, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.5f), p("size", R.string.p_size, 0.3f)), """
  float n = floor(3.0 + uP0.y * 3.0);
  float f = uv.x * n;
  float i = floor(f);
  float k = fract(f);
  float dir = mod(i, 2.0) < 0.5 ? 1.0 : -1.0;
  vec4 c = T(vec2(uv.x, uv.y + (k - 0.5) * dir * 0.08 * uP0.x));
  c.rgb *= 1.0 - uP0.x * 0.35 + uP0.x * 0.5 * (dir > 0.0 ? k : 1.0 - k);
  c.rgb *= 1.0 - (1.0 - smoothstep(0.0, 0.03, k)) * 0.35;
  return c;"""),

        // Movimiento
        FxDef("speed_lines", R.string.fx_speed_lines, R.string.cat_motion, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec4 c = T(uv);
  vec2 q = (uv - 0.5) * vec2(uRes.x / uRes.y, 1.0);
  float r = length(q);
  float a = atan(q.y, q.x);
  float h = hash(vec2(floor(a * 40.0), floor(uTime * 12.0)));
  float f = abs(fract(a * 40.0) - 0.5) * 2.0;
  float line = step(1.0 - uP0.x * 0.45, h) * smoothstep(0.18, 0.5, r) * (1.0 - smoothstep(0.0, 0.35, f));
  c.rgb = mix(c.rgb, vec3(1.0), line * 0.85);
  return c;"""),
        FxDef("dolly_zoom", R.string.fx_dolly_zoom, R.string.cat_motion,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.3f)), """
  float k = sin(uTime * (0.4 + uP0.y * 1.5)) * uP0.x * 0.35;
  vec2 q = uv - 0.5;
  float r = length(q * vec2(uRes.x / uRes.y, 1.0));
  return T(q / (1.0 + k * (0.45 - r)) + 0.5);"""),
        FxDef("color_trail", R.string.fx_color_trail, R.string.cat_motion, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec2 dir = vec2(cos(uTime * 0.8), sin(uTime * 0.8)) * 0.03 * uP0.x;
  vec4 c = T(uv);
  vec3 acc = c.rgb;
  for (int i = 1; i < 6; i++) {
    float fi = float(i);
    vec3 g = T(uv - dir * fi).rgb;
    vec3 tint = ${hue("fract(fi * 0.12 + uTime * 0.1)")};
    acc = max(acc, mix(g, g * tint * 1.4, 0.7) * (1.0 - fi * 0.17));
  }
  return vec4(acc, c.a);"""),
        FxDef("pixel_stretch", R.string.fx_pixel_stretch, R.string.cat_motion,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.4f)), """
  float t = floor(uTime * (4.0 + uP0.y * 12.0));
  float band = floor(uv.y * 14.0);
  float on = step(1.0 - uP0.x * 0.4, hash(vec2(band, t)));
  float xs = hash(vec2(band, t + 5.0));
  return T(vec2(mix(uv.x, min(uv.x, xs), on), uv.y));"""),

        // Retro, marcos y pantallas
        FxDef("drone_hud", R.string.fx_drone_hud, R.string.cat_retro, listOf(p("amount", R.string.p_amount, 0.8f)), """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  vec2 q = abs(uv - 0.5);
  float tx = 0.0035 / asp;
  float ty = 0.0035;
  float corners = step(abs(q.x - 0.44), tx) * step(0.36, q.y) * step(q.y, 0.46) + step(abs(q.y - 0.46), ty) * step(0.44 - 0.07 / asp, q.x) * step(q.x, 0.44 + tx);
  float ring = 1.0 - smoothstep(0.0, 0.003, abs(length((uv - 0.5) * vec2(asp, 1.0)) - 0.06));
  float tick = step(q.x, tx) * step(0.012, q.y) * step(q.y, 0.03) + step(q.y, ty) * step(0.012 / asp, q.x) * step(q.x, 0.03 / asp);
  float ticks = step(fract(uv.y * 20.0), 0.12) * step(uv.x, 0.045) * step(0.3, uv.y) * step(uv.y, 0.7);
  float hud = clamp(corners + ring * 0.8 + tick + ticks, 0.0, 1.0);
  float rec = (1.0 - smoothstep(0.012, 0.015, length((uv - vec2(0.12 / asp, 0.9)) * vec2(asp, 1.0)))) * step(fract(uTime * 0.8), 0.6);
  c.rgb = mix(c.rgb, vec3(0.6, 1.0, 0.8), hud * 0.9 * uP0.x);
  c.rgb = mix(c.rgb, vec3(1.0, 0.1, 0.1), rec * uP0.x);
  return c;"""),
        FxDef("gameboy", R.string.fx_gameboy, R.string.cat_retro, listOf(p("size", R.string.p_size, 0.4f)), """
  float n = mix(160.0, 60.0, uP0.x);
  vec2 g = vec2(n, n * uRes.y / uRes.x);
  vec2 id = floor(uv * g);
  vec4 c = T((id + 0.5) / g);
  float d = (mod(id.x, 2.0) + 2.0 * mod(id.y, 2.0)) / 4.0 - 0.375;
  float q = floor(clamp(luma(c.rgb) + d * 0.25, 0.0, 1.0) * 3.999) / 3.0;
  vec3 p = q < 0.2 ? vec3(0.06, 0.22, 0.06) : (q < 0.5 ? vec3(0.19, 0.38, 0.19) : (q < 0.8 ? vec3(0.55, 0.67, 0.06) : vec3(0.61, 0.74, 0.06)));
  p *= 0.85 + 0.15 * step(0.08, fract(uv.x * g.x)) * step(0.08, fract(uv.y * g.y));
  return vec4(p, c.a);"""),
        FxDef("scanner_beam", R.string.fx_scanner_beam, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float x = fract(uTime * (0.15 + uP0.y * 0.6));
  float d = abs(uv.x - x);
  float beam = (1.0 - smoothstep(0.0, 0.012, d)) + (1.0 - smoothstep(0.0, 0.18, d)) * 0.35;
  c.rgb = mix(c.rgb, vec3(luma(c.rgb)) * vec3(0.7, 1.0, 1.1), step(uv.x, x) * 0.25 * uP0.x);
  c.rgb += vec3(0.35, 0.9, 1.0) * beam * uP0.x;
  return c;"""),
        FxDef("radar", R.string.fx_radar, R.string.cat_retro,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  vec2 q = (uv - 0.5) * vec2(uRes.x / uRes.y, 1.0);
  float r = length(q);
  float sw = fract(atan(q.y, q.x) / 6.2831853 - uTime * (0.2 + uP0.y * 0.6));
  float trail = pow(sw, 8.0);
  float rings = smoothstep(0.47, 0.5, abs(fract(r * 5.0) - 0.5));
  float cr = 1.0 - smoothstep(0.0, 0.003, min(abs(q.x), abs(q.y)));
  c.rgb = mix(c.rgb, c.rgb * vec3(0.5, 0.9, 0.6), 0.4 * uP0.x);
  c.rgb += vec3(0.2, 1.0, 0.5) * (trail * 0.6 + rings * 0.25 + cr * 0.25) * uP0.x;
  return c;"""),
        FxDef("pulse_vignette", R.string.fx_pulse_vignette, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float ph = fract(uTime * (0.8 + uP0.y * 1.5));
  float beat = exp(-ph * 8.0) + 0.6 * exp(-abs(ph - 0.3) * 20.0);
  float d = smoothstep(0.25, 0.85, distance(uv, vec2(0.5)));
  c.rgb = mix(c.rgb, c.rgb * 0.5 + vec3(1.0, 0.1, 0.15) * 0.5, clamp(d * uP0.x * (0.4 + 0.6 * beat) * 1.5, 0.0, 1.0));
  return c;"""),
        FxDef("blinds_shadow", R.string.fx_blinds_shadow, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("size", R.string.p_size, 0.4f)), """
  vec4 c = T(uv);
  float k = (uv.y + uv.x * 0.25) * mix(8.0, 20.0, uP0.y) + sin(uTime * 0.3) * 0.5;
  float s = smoothstep(0.35, 0.65, abs(fract(k) - 0.5) * 2.0);
  c.rgb *= 1.0 - s * 0.6 * uP0.x;
  c.rgb += vec3(0.1, 0.07, 0.02) * (1.0 - s) * uP0.x;
  return c;"""),

        // ---------------- Cuarta tanda (56) ----------------
        // Luz
        FxDef("water_caustics", R.string.fx_water_caustics, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  vec2 q = uv * vec2(uRes.x / uRes.y, 1.0) * 6.0;
  float t = uTime * (0.3 + uP0.y);
  float v = 0.0;
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    q += vec2(sin(q.y * 1.3 + t + fi * 2.0), cos(q.x * 1.1 - t * 0.8 + fi)) * 0.6;
    v += abs(sin(q.x * 1.7) * sin(q.y * 1.5));
  }
  float caus = pow(1.0 - clamp(v / 3.0, 0.0, 1.0), 4.0) * 3.0;
  c.rgb += vec3(0.5, 0.9, 1.0) * clamp(caus, 0.0, 1.0) * uP0.x * 0.7;
  return c;"""),
        FxDef("moon_rays", R.string.fx_moon_rays, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.2f)), """
  vec4 c = T(uv);
  float t = uTime * (0.05 + uP0.y * 0.3);
  vec2 d = uv - vec2(0.15, 1.1);
  float r = smoothstep(0.3, 0.7, noise(vec2(atan(d.x, -d.y) * 9.0, t))) * (1.0 - smoothstep(0.2, 1.4, length(d)));
  c.rgb = c.rgb * (1.0 - 0.15 * uP0.x) * vec3(0.9, 0.95, 1.1) + vec3(0.55, 0.7, 1.0) * r * uP0.x * 0.8;
  return c;"""),
        FxDef("neon_sign", R.string.fx_neon_sign, R.string.cat_light, listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec4 c = T(uv);
  float f = step(0.15, hash(vec2(floor(uTime * 14.0), 1.0)));
  float pulse = 0.7 + 0.3 * sin(uTime * 3.0);
  vec3 neon = mix(vec3(1.0, 0.1, 0.6), vec3(0.1, 0.8, 1.0), clamp(uv.x + 0.2 * sin(uTime * 0.5), 0.0, 1.0));
  vec3 glow = neon * pow(luma(c.rgb), 2.0) * 1.6;
  c.rgb = mix(c.rgb, c.rgb * 0.6 + glow, uP0.x * (0.6 + 0.4 * f) * pulse);
  float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
  c.rgb += neon * (1.0 - smoothstep(0.0, 0.25, edge)) * 0.25 * uP0.x * f;
  return c;"""),
        FxDef("laser_beams", R.string.fx_laser_beams, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.4f)), """
  vec4 c = T(uv);
  vec2 q = (uv - vec2(0.5, 0.0)) * vec2(uRes.x / uRes.y, 1.0);
  float ang = atan(q.x, q.y);
  vec3 acc = vec3(0.0);
  for (int i = 0; i < 5; i++) {
    float fi = float(i);
    float d = abs(ang - sin(uTime * (0.6 + uP0.y * 1.5) + fi * 1.3) * 0.9);
    float beam = (1.0 - smoothstep(0.0, 0.012, d)) + (1.0 - smoothstep(0.0, 0.08, d)) * 0.25;
    acc += beam * ${hue("fract(fi * 0.2 + 0.4)")};
  }
  c.rgb += acc * uP0.x;
  return c;"""),
        FxDef("flashlight", R.string.fx_flashlight, R.string.cat_light, listOf(p("amount", R.string.p_amount, 0.7f)), """
  vec4 c = T(uv);
  vec2 ctr = vec2(0.5 + 0.25 * sin(uTime * 0.7), 0.5 + 0.2 * cos(uTime * 0.9));
  float l = 1.0 - smoothstep(0.1, 0.55, length((uv - ctr) * vec2(uRes.x / uRes.y, 1.0)));
  c.rgb = c.rgb * mix(1.0, 0.25 + 1.3 * l, uP0.x) + vec3(1.0, 0.8, 0.5) * l * l * uP0.x * 0.25;
  return c;"""),
        FxDef("candle_flicker", R.string.fx_candle_flicker, R.string.cat_light, listOf(p("amount", R.string.p_amount, 0.7f)), """
  vec4 c = T(uv);
  float fl = 0.85 + 0.15 * noise(vec2(uTime * 9.0, 1.0)) + 0.1 * sin(uTime * 23.0);
  c.rgb *= mix(1.0, fl, uP0.x);
  c.rgb = mix(c.rgb, c.rgb * vec3(1.15, 0.95, 0.7), 0.6 * uP0.x);
  c.rgb *= 1.0 - 0.5 * uP0.x * smoothstep(0.3, 0.9, distance(uv, vec2(0.5, 0.45 + 0.02 * sin(uTime * 5.0))));
  return c;"""),
        FxDef("cross_stars", R.string.fx_cross_stars, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("size", R.string.p_size, 0.4f)), """
  vec4 c = T(uv);
  vec3 acc = vec3(0.0);
  float asp = uRes.x / uRes.y;
  for (int i = 1; i < 9; i++) {
    float fi = float(i);
    float w = 1.0 - fi / 9.0;
    vec2 dx = vec2(fi * 0.012 * (0.5 + uP0.y), 0.0);
    vec2 dy = vec2(0.0, fi * 0.012 * (0.5 + uP0.y) * asp);
    acc += w * (max(luma(T(uv + dx).rgb) - 0.8, 0.0) + max(luma(T(uv - dx).rgb) - 0.8, 0.0) + max(luma(T(uv + dy).rgb) - 0.8, 0.0) + max(luma(T(uv - dy).rgb) - 0.8, 0.0));
  }
  c.rgb += acc * vec3(1.0, 0.95, 0.85) * uP0.x * 1.5;
  return c;"""),
        FxDef("lens_dirt", R.string.fx_lens_dirt, R.string.cat_light, listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec4 c = T(uv);
  float dirt = smoothstep(0.35, 0.9, noise(uv * 7.0) * noise(uv * 17.0 + 3.0) + noise(uv * 3.0 + 9.0) * 0.6);
  float b = (luma(T(uv + vec2(0.03, 0.0)).rgb) + luma(T(uv - vec2(0.03, 0.0)).rgb) + luma(T(uv + vec2(0.0, 0.05)).rgb) + luma(T(uv - vec2(0.0, 0.05)).rgb)) * 0.25;
  c.rgb += vec3(1.0, 0.95, 0.85) * dirt * smoothstep(0.4, 0.9, b) * uP0.x * 0.9;
  return c;"""),
        FxDef("film_burn", R.string.fx_film_burn, R.string.cat_light, listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec4 c = T(uv);
  float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
  float n = noise(uv * 6.0 + uTime * 0.7) * 0.6 + noise(uv * 14.0 - uTime) * 0.4;
  float burn = 1.0 - smoothstep(0.0, 0.25 + 0.15 * sin(uTime * 1.3), edge + (n - 0.5) * 0.3);
  c.rgb = mix(c.rgb, mix(vec3(1.0, 0.35, 0.05), vec3(1.0, 0.85, 0.3), burn * burn), burn * uP0.x * 0.9);
  c.rgb += burn * burn * vec3(1.0, 0.6, 0.2) * uP0.x * 0.5;
  return c;"""),

        // Clima y partículas
        FxDef("blizzard", R.string.fx_blizzard, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.4f)), """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  float s = 0.0;
  for (int i = 0; i < 4; i++) {
    float fi = float(i);
    float sc = 10.0 + fi * 8.0;
    vec2 u = uv * vec2(sc * asp, sc);
    u.y += uTime * (1.5 + uP0.y * 3.0) * (1.0 + fi * 0.4);
    u.x += uTime * (1.0 + uP0.y * 2.0) * (1.0 + fi * 0.3) + sin(u.y * 0.5) * 0.4;
    float h = hash(floor(u) + fi * 17.0);
    vec2 o = (vec2(h, fract(h * 37.0)) - 0.5) * 0.6;
    s += step(1.0 - uP0.x * 0.7, h) * (1.0 - smoothstep(0.03, 0.14, length(fract(u) - 0.5 - o)));
  }
  c.rgb = mix(c.rgb, vec3(0.85, 0.9, 1.0), uP0.x * 0.25) + s * 0.9;
  return c;"""),
        FxDef("meteors", R.string.fx_meteors, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)),
            streaks(3, "0.012", "5.0", "vec3(1.0, 0.75, 0.4)", "1.0")),
        FxDef("shooting_stars", R.string.fx_shooting_stars, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.5f)),
            streaks(4, "0.004", "9.0", "vec3(0.9, 0.95, 1.0)", "1.6")),
        FxDef("magic_dust", R.string.fx_magic_dust, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)),
            faller("(exp(-dot(q, q) * 90.0) * (0.6 + 0.4 * sin(uTime * 6.0 + h * 40.0)))", hue("fract(h * 9.0)"), "-")),
        FxDef("smoke", R.string.fx_smoke, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float t = uTime * (0.1 + uP0.y * 0.4);
  vec2 p = uv * vec2(3.0, 2.0) + vec2(0.0, -t);
  float n = noise(p) * 0.5 + noise(p * 2.0 + 3.0 + vec2(t, 0.0)) * 0.3 + noise(p * 4.0 - t) * 0.2;
  float s = smoothstep(0.4, 0.8, n) * (0.4 + 0.6 * (1.0 - uv.y));
  c.rgb = mix(c.rgb, vec3(0.7, 0.72, 0.75), s * uP0.x * 0.8);
  return c;"""),
        FxDef("sparkler", R.string.fx_sparkler, R.string.cat_light, listOf(p("amount", R.string.p_amount, 0.7f)), """
  vec4 c = T(uv);
  vec2 ctr = vec2(0.5 + 0.3 * sin(uTime * 0.8), 0.45 + 0.2 * cos(uTime * 1.1));
  vec2 d = (uv - ctr) * vec2(uRes.x / uRes.y, 1.0);
  float r = length(d);
  float ray = pow(0.5 + 0.5 * cos(atan(d.y, d.x) * 18.0 + floor(uTime * 18.0) * 7.0), 10.0);
  float spark = ray * exp(-r * 9.0) + (1.0 - smoothstep(0.0, 0.025, r)) * 2.0;
  c.rgb += vec3(1.0, 0.8, 0.4) * spark * uP0.x;
  return c;"""),
        FxDef("butterflies", R.string.fx_butterflies, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.2f)),
            faller("(1.0 - smoothstep(0.5, 1.0, length(q * vec2(3.0 + 3.0 * abs(sin(uTime * 12.0 + h * 30.0)), 5.0))))", "mix(vec3(1.0, 0.6, 0.1), vec3(0.3, 0.7, 1.0), fract(h * 3.0))", "-")),
        FxDef("coin_rain", R.string.fx_coin_rain, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.4f)),
            faller("(1.0 - smoothstep(0.5, 0.56, length(q * vec2(4.5, 4.5 * abs(cos(uTime * 3.0 + h * 20.0)) + 0.5))))", "mix(vec3(1.0, 0.8, 0.2), vec3(1.0, 0.95, 0.6), fract(h * 5.0))")),
        FxDef("heat_mirage", R.string.fx_heat_mirage, R.string.cat_distort, listOf(p("amount", R.string.p_amount, 0.6f)), """
  float band = 1.0 - smoothstep(0.0, 0.5, abs(uv.y - 0.35));
  vec2 u = uv + vec2(sin(uv.y * 80.0 + uTime * 5.0) * 0.006, cos(uv.x * 30.0 + uTime * 3.0) * 0.002) * uP0.x * band;
  vec4 c = T(u);
  c.rgb = mix(c.rgb, c.rgb * vec3(1.1, 1.0, 0.9) + 0.04, band * uP0.x * 0.5);
  return c;"""),
        FxDef("sandstorm", R.string.fx_sandstorm, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.4f)), """
  vec4 c = T(uv);
  float t = uTime * (0.8 + uP0.y * 2.0);
  float n = noise(vec2(uv.x * 4.0 - t * 2.0, uv.y * 40.0)) * noise(vec2(uv.x * 9.0 - t * 3.0, uv.y * 14.0 + 5.0));
  c.rgb = mix(c.rgb, vec3(0.85, 0.65, 0.4), clamp(smoothstep(0.1, 0.6, n) * uP0.x * 1.2 + uP0.x * 0.15, 0.0, 0.85));
  return c;"""),
        FxDef("wind_streaks", R.string.fx_wind_streaks, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.4f)), """
  vec4 c = T(uv);
  float t = uTime * (0.5 + uP0.y * 2.0);
  float row = floor(uv.y * 40.0);
  float h = hash(vec2(row, 3.0));
  float x = fract(uv.x * (0.5 + h) - t * (0.5 + h) + h * 9.0);
  float streak = smoothstep(0.0, 0.02, x) * (1.0 - smoothstep(0.02, 0.25, x)) * step(1.0 - uP0.x * 0.35, hash(vec2(row, floor(t * 0.5 + h * 7.0))));
  c.rgb += streak * (1.0 - smoothstep(0.0, 0.25, abs(fract(uv.y * 40.0) - 0.5))) * 0.6;
  return c;"""),
        FxDef("dust_motes", R.string.fx_dust_motes, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.2f)), """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  vec3 acc = vec3(0.0);
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    float sc = 4.0 + fi * 3.0;
    vec2 u = uv * vec2(sc * asp, sc);
    u.y += uTime * (0.03 + uP0.y * 0.2) * (1.0 + fi * 0.5);
    float h = hash(floor(u) + fi * 21.0);
    vec2 q = fract(u) - 0.5 - (vec2(h, fract(h * 41.0)) - 0.5) * 0.5 - vec2(sin(uTime * 0.3 + h * 20.0), cos(uTime * 0.25 + h * 30.0)) * 0.08;
    acc += exp(-dot(q, q) * 40.0) * (0.4 + 0.6 * noise(vec2(uTime * 0.5 + h * 9.0, fi))) * step(1.0 - uP0.x * 0.6, h) * vec3(1.0, 0.95, 0.8);
  }
  c.rgb += acc * 0.9;
  return c;"""),

        // Estilizar
        FxDef("pixel_art", R.string.fx_pixel_art, R.string.cat_stylize, listOf(p("size", R.string.p_size, 0.4f)), """
  float n = mix(220.0, 70.0, uP0.x);
  vec2 g = vec2(n, n * uRes.y / uRes.x);
  vec4 c = T((floor(uv * g) + 0.5) / g);
  c.rgb = floor(c.rgb * 3.0 + 0.5) / 3.0;
  c.rgb = mix(vec3(luma(c.rgb)), c.rgb, 1.3);
  return c;"""),
        FxDef("cel_shading", R.string.fx_cel_shading, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec2 px = 1.3 / uRes;
  vec4 c = T(uv);
  float gx = luma(T(uv + vec2(px.x, 0.0)).rgb) - luma(T(uv - vec2(px.x, 0.0)).rgb);
  float gy = luma(T(uv + vec2(0.0, px.y)).rgb) - luma(T(uv - vec2(0.0, px.y)).rgb);
  float e = step(0.18, length(vec2(gx, gy)));
  vec3 q = floor(c.rgb * 5.0 + 0.5) / 5.0;
  q = mix(vec3(luma(q)), q, 1.25) * (1.0 - e * 0.9);
  return vec4(mix(c.rgb, q, clamp(uP0.x * 1.6, 0.0, 1.0)), c.a);"""),
        FxDef("pointillism", R.string.fx_pointillism, R.string.cat_stylize, listOf(p("size", R.string.p_size, 0.4f)), """
  float n = mix(110.0, 40.0, uP0.x);
  vec2 p = vec2(uv.x * uRes.x / uRes.y, uv.y) * n;
  p = vec2(p.x * 0.9397 - p.y * 0.342, p.x * 0.342 + p.y * 0.9397);
  vec2 id = floor(p);
  vec2 f = fract(p) - 0.5;
  vec2 cu = vec2(id.x * 0.9397 + id.y * 0.342, -id.x * 0.342 + id.y * 0.9397) / n;
  vec4 c = T(vec2(cu.x * uRes.y / uRes.x, cu.y) + 0.5 / n);
  float dp = 1.0 - smoothstep(0.38, 0.5, length(f));
  return vec4(mix(vec3(0.96, 0.93, 0.85), c.rgb, dp), c.a);"""),
        FxDef("ink_wash", R.string.fx_ink_wash, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 0.8f)), """
  vec4 c = T(uv);
  float l = luma(c.rgb);
  for (int i = 0; i < 8; i++) {
    float a = float(i) * 0.785398;
    l += luma(T(uv + vec2(cos(a), sin(a) * uRes.x / uRes.y) * 0.01).rgb);
  }
  l /= 9.0;
  float ink = pow(1.0 - smoothstep(0.2, 0.9, l), 1.5);
  vec3 paper = vec3(0.95, 0.93, 0.88) * (0.95 + 0.05 * noise(uv * uRes / 3.0));
  return vec4(mix(c.rgb, paper * (1.0 - ink * 0.9), clamp(uP0.x * 1.3, 0.0, 1.0)), c.a);"""),
        FxDef("cyanotype", R.string.fx_cyanotype, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 1f)), """
  vec4 c = T(uv);
  float l = pow(luma(c.rgb), 0.9);
  vec3 col = mix(vec3(0.02, 0.1, 0.25), vec3(0.85, 0.95, 1.0), l);
  return vec4(mix(c.rgb, col, uP0.x), c.a);"""),
        FxDef("risograph", R.string.fx_risograph, R.string.cat_stylize,
            listOf(p("amount", R.string.p_amount, 0.8f), p("size", R.string.p_size, 0.4f)), """
  vec4 c = T(uv);
  vec2 p = uv * uRes;
  float s = mix(5.0, 12.0, uP0.y);
  vec2 p1 = vec2(p.x * 0.966 - p.y * 0.259, p.x * 0.259 + p.y * 0.966) / s;
  vec2 p2 = vec2(p.x * 0.707 + p.y * 0.707, -p.x * 0.707 + p.y * 0.707) / s;
  float t1 = sqrt(clamp(1.0 - c.g, 0.0, 1.0)) * 0.5;
  float t2 = sqrt(clamp(1.0 - c.r, 0.0, 1.0)) * 0.5;
  float dot1 = 1.0 - smoothstep(t1 - 0.06, t1 + 0.06, length(fract(p1) - 0.5));
  float dot2 = 1.0 - smoothstep(t2 - 0.06, t2 + 0.06, length(fract(p2) - 0.5));
  vec3 col = vec3(0.96, 0.94, 0.88);
  col = mix(col, vec3(1.0, 0.35, 0.6), dot1 * 0.9);
  col *= mix(vec3(1.0), vec3(0.25, 0.55, 1.0), dot2 * 0.9);
  return vec4(mix(c.rgb, col, clamp(uP0.x * 1.3, 0.0, 1.0)), c.a);"""),
        FxDef("linocut", R.string.fx_linocut, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 0.5f)), """
  vec4 c = T(uv);
  float lines = 0.5 + 0.5 * sin((uv.x * 0.3 + uv.y) * uRes.y * 0.35);
  float ink = step(luma(c.rgb) + (lines - 0.5) * 0.35, mix(0.35, 0.55, uP0.x));
  return vec4(mix(vec3(0.97, 0.93, 0.85), vec3(0.08, 0.07, 0.1), ink), c.a);"""),
        FxDef("roman_mosaic", R.string.fx_roman_mosaic, R.string.cat_stylize, listOf(p("size", R.string.p_size, 0.4f)), """
  float n = mix(70.0, 25.0, uP0.x);
  vec2 g = vec2(n, n * uRes.y / uRes.x);
  vec2 id = floor(uv * g);
  vec2 f = fract(uv * g);
  vec4 c = T((id + 0.5) / g);
  float tile = step(0.1, f.x) * step(f.x, 0.9) * step(0.1, f.y) * step(f.y, 0.9);
  return vec4(mix(vec3(0.12, 0.1, 0.09), c.rgb * (0.85 + 0.3 * hash(id)), tile), c.a);"""),
        FxDef("lego", R.string.fx_lego, R.string.cat_stylize, listOf(p("size", R.string.p_size, 0.4f)), """
  float n = mix(70.0, 24.0, uP0.x);
  vec2 g = vec2(n, n * uRes.y / uRes.x);
  vec2 id = floor(uv * g);
  vec2 f = fract(uv * g);
  vec4 c = T((id + 0.5) / g);
  vec3 col = floor(c.rgb * 4.0 + 0.5) / 4.0;
  float stud = 1.0 - smoothstep(0.28, 0.33, length(f - 0.5));
  float hl = (1.0 - smoothstep(0.0, 0.15, length(f - vec2(0.4, 0.62)))) * stud;
  col = col * (0.8 + 0.3 * stud) + hl * 0.25;
  col *= 0.8 + 0.2 * step(0.06, f.x) * step(0.06, f.y);
  return vec4(col, c.a);"""),
        FxDef("knit", R.string.fx_knit, R.string.cat_stylize, listOf(p("size", R.string.p_size, 0.4f)), """
  float n = mix(80.0, 28.0, uP0.x);
  vec2 g = vec2(n, n * uRes.y / uRes.x);
  vec2 f = fract(uv * g);
  vec4 c = T((floor(uv * g) + 0.5) / g);
  float s = 0.7 + 0.3 * (1.0 - smoothstep(0.0, 0.5, abs(f.y - abs(f.x - 0.5) * 1.5 - 0.15)));
  return vec4(c.rgb * s, c.a);"""),
        FxDef("double_exposure", R.string.fx_double_exposure, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec4 a = T(uv);
  vec4 b = T((uv - 0.5) * 1.6 * vec2(-1.0, 1.0) + 0.5 + vec2(0.05 * sin(uTime * 0.5), 0.0));
  vec3 col = 1.0 - (1.0 - a.rgb) * (1.0 - b.rgb * vec3(0.9, 0.8, 1.0));
  return vec4(mix(a.rgb, col, uP0.x), a.a);"""),
        FxDef("fire_silhouette", R.string.fx_fire_silhouette, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 0.8f)), """
  vec4 c = T(uv + vec2(sin(uv.y * 30.0 + uTime * 6.0), 0.0) * 0.004);
  float l = clamp(luma(c.rgb) + (noise(uv * 12.0 + vec2(0.0, -uTime * 2.0)) - 0.5) * 0.2, 0.0, 1.0);
  vec3 f = l < 0.33 ? mix(vec3(0.05, 0.0, 0.0), vec3(0.8, 0.1, 0.0), l / 0.33)
         : (l < 0.66 ? mix(vec3(0.8, 0.1, 0.0), vec3(1.0, 0.6, 0.0), (l - 0.33) / 0.33)
         : mix(vec3(1.0, 0.6, 0.0), vec3(1.0, 0.95, 0.6), (l - 0.66) / 0.34));
  return vec4(mix(c.rgb, f, uP0.x), c.a);"""),
        FxDef("comic_panels", R.string.fx_comic_panels, R.string.cat_stylize, listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec2 g = floor(uv * 2.0);
  vec2 f = fract(uv * 2.0);
  vec4 c = T((f - 0.5) * (0.9 + 0.15 * hash(g)) + 0.5);
  vec3 tint = mix(vec3(1.0), ${hue("fract(hash(g) * 3.0)")}, 0.25 * uP0.x);
  float panel = step(0.04, f.x) * step(f.x, 0.96) * step(0.04, f.y) * step(f.y, 0.96);
  return vec4(mix(vec3(1.0), c.rgb * tint, panel), c.a);"""),

        // Distorsión
        FxDef("flag_wave", R.string.fx_flag_wave, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.4f)), """
  float ph = uv.x * 8.0 - uTime * 4.0 * (0.5 + uP0.y);
  vec4 c = T(vec2(uv.x, uv.y + sin(ph) * 0.04 * uP0.x * uv.x));
  c.rgb *= 1.0 + 0.2 * cos(ph) * uP0.x * uv.x;
  return c;"""),
        FxDef("page_curl", R.string.fx_page_curl, R.string.cat_distort, listOf(p("amount", R.string.p_amount, 0.6f)), """
  float fold = 1.0 - uP0.x * 0.55 * (0.5 + 0.5 * sin(uTime));
  vec4 c = T(uv);
  if (uv.x > fold) {
    float ux = 2.0 * fold - uv.x;
    c = ux < 0.0 ? vec4(0.05, 0.05, 0.06, 1.0) : T(vec2(ux, uv.y));
    c.rgb = mix(c.rgb, vec3(0.92), 0.35) * 0.85;
  }
  c.rgb *= 1.0 - 0.35 * exp(-abs(uv.x - fold) * 25.0) * step(uv.x, fold);
  return c;"""),
        FxDef("tiny_planet", R.string.fx_tiny_planet, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.8f), p("speed", R.string.p_speed, 0.2f)), """
  vec2 q = (uv - 0.5) * vec2(uRes.x / uRes.y, 1.0);
  vec2 u = vec2(fract((atan(q.y, q.x) + uTime * uP0.y * 0.5) / 6.2831853 + 0.5), 1.0 - clamp(length(q) * 1.6, 0.0, 1.0));
  return mix(T(uv), T(u), clamp(uP0.x * 1.5, 0.0, 1.0));"""),
        FxDef("drop_ripples", R.string.fx_drop_ripples, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  float asp = uRes.x / uRes.y;
  vec2 off = vec2(0.0);
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    float t = uTime * (0.3 + uP0.y) + fi * 0.37;
    float cyc = floor(t);
    float ph = fract(t);
    vec2 dv = (uv - vec2(0.2 + 0.6 * hash(vec2(cyc, fi)), 0.2 + 0.6 * hash(vec2(cyc, fi + 7.0)))) * vec2(asp, 1.0);
    float d = length(dv) - ph * 0.5;
    off += normalize(dv + 0.0001) * sin(d * 70.0) * exp(-abs(d) * 12.0) * (1.0 - ph) / vec2(asp, 1.0);
  }
  return T(uv + off * 0.012 * uP0.x * 3.0);"""),
        FxDef("rolling_shutter", R.string.fx_rolling_shutter, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.4f)), """
  float w = sin(uTime * (3.0 + uP0.y * 8.0) + uv.y * 7.0) * 0.02 * uP0.x * uv.y;
  return T(vec2(uv.x + w, uv.y));"""),
        FxDef("ice_refraction", R.string.fx_ice_refraction, R.string.cat_distort, listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec2 o = vec2(noise(uv * 14.0 + 5.0) - 0.5, noise(uv * 14.0) - 0.5) * 0.04 * uP0.x;
  vec4 c = T(uv + o);
  c.rgb = mix(c.rgb, c.rgb * vec3(0.8, 0.95, 1.1) + 0.05, 0.4 * uP0.x);
  c.rgb += step(0.8, noise(uv * 30.0)) * 0.1 * uP0.x;
  return c;"""),
        FxDef("pixel_melt", R.string.fx_pixel_melt, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  float h = hash(vec2(floor(uv.x * 60.0), 1.0));
  float melt = (sin(uTime * (0.4 + uP0.y) + h * 6.0) * 0.5 + 0.5) * uP0.x * 0.3 * h;
  return T(vec2(uv.x, uv.y + melt));"""),
        FxDef("lake_reflection", R.string.fx_lake_reflection, R.string.cat_distort, listOf(p("amount", R.string.p_amount, 0.6f)), """
  float hor = 0.45;
  vec2 u = uv;
  if (uv.y < hor) {
    u.y = 2.0 * hor - uv.y + sin(uv.y * 80.0 + uTime * 3.0) * 0.004 * uP0.x;
    u.x += sin(uv.y * 60.0 + uTime * 2.0) * 0.003 * uP0.x;
  }
  vec4 c = T(u);
  if (uv.y < hor) c.rgb *= 0.75;
  c.rgb += (1.0 - smoothstep(0.0, 0.006, abs(uv.y - hor))) * 0.25;
  return c;"""),
        FxDef("triangle_kaleido", R.string.fx_triangle_kaleido, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.2f)), """
  float asp = uRes.x / uRes.y;
  vec2 p = (uv - 0.5) * vec2(asp, 1.0) * 2.0;
  float a = 1.0471975 + uTime * uP0.y * 0.2;
  for (int i = 0; i < 4; i++) {
    p = abs(p) - 0.3;
    p = vec2(p.x * cos(a) + p.y * sin(a), -p.x * sin(a) + p.y * cos(a));
  }
  return mix(T(uv), T(p * 0.5 / vec2(asp, 1.0) + 0.5), clamp(uP0.x * 1.5, 0.0, 1.0));"""),

        // Movimiento
        FxDef("strobe_trail", R.string.fx_strobe_trail, R.string.cat_motion,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  vec3 acc = c.rgb;
  float ang = uTime * 0.7;
  for (int i = 1; i < 5; i++) {
    float fi = float(i);
    vec3 g = T(uv - vec2(cos(ang), sin(ang)) * 0.06 * fi * uP0.x).rgb;
    acc = mix(acc, g, 0.45 * step(fi / 5.0, fract(uTime * (0.8 + uP0.y * 2.0))));
  }
  return vec4(acc, c.a);"""),
        FxDef("whip_blur", R.string.fx_whip_blur, R.string.cat_blur,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.4f)), """
  vec2 dir = vec2(0.12 * uP0.x * abs(sin(uTime * (1.0 + uP0.y * 3.0))), 0.0);
  vec4 acc = vec4(0.0);
  for (int i = 0; i < 16; i++) acc += T(uv + dir * (float(i) / 15.0 - 0.5));
  return acc / 16.0;"""),
        FxDef("shutter_flicker", R.string.fx_shutter_flicker, R.string.cat_motion,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.4f)), """
  vec4 c = T(uv);
  float f = step(0.5, fract(uTime * (6.0 + uP0.y * 10.0)));
  float band = 1.0 - smoothstep(0.0, 0.12, abs(fract(uv.y - uTime * 0.4) - 0.5) - 0.2);
  c.rgb *= 1.0 - f * 0.45 * uP0.x - band * 0.3 * uP0.x;
  return c;"""),
        FxDef("spin_burst", R.string.fx_spin_burst, R.string.cat_blur,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.4f)), """
  float asp = uRes.x / uRes.y;
  vec2 q = (uv - 0.5) * vec2(asp, 1.0);
  float k = exp(-fract(uTime * (0.5 + uP0.y * 2.0)) * 4.0) * uP0.x * 0.5 + uP0.x * 0.05;
  vec4 acc = vec4(0.0);
  for (int i = 0; i < 12; i++) {
    float a = (float(i) / 11.0 - 0.5) * k;
    vec2 r = vec2(q.x * cos(a) - q.y * sin(a), q.x * sin(a) + q.y * cos(a));
    acc += T(vec2(r.x / asp, r.y) + 0.5);
  }
  return acc / 12.0;"""),

        // Retro, marcos y pantallas
        FxDef("polaroid_frame", R.string.fx_polaroid_frame, R.string.cat_retro, listOf(p("amount", R.string.p_amount, 0.5f)), """
  float bx = 0.04 + uP0.x * 0.06;
  float bot = bx * 3.2;
  vec2 u = vec2((uv.x - bx) / (1.0 - 2.0 * bx), (uv.y - bot) / (1.0 - bx - bot));
  float inside = step(0.0, u.x) * step(u.x, 1.0) * step(0.0, u.y) * step(u.y, 1.0);
  vec3 paper = vec3(0.96, 0.95, 0.92) * (0.97 + 0.03 * noise(uv * uRes / 2.0));
  vec3 photo = T(u).rgb * (1.0 - 0.15 * smoothstep(0.4, 1.0, distance(u, vec2(0.5))));
  return vec4(mix(paper, photo, inside), 1.0);"""),
        FxDef("story_frame", R.string.fx_story_frame, R.string.cat_retro, listOf(p("amount", R.string.p_amount, 0.5f)), """
  float k = 1.0 - 0.15 * uP0.x;
  vec2 q = (uv - 0.5) / k + 0.5;
  vec2 d = abs(q - 0.5) - 0.44;
  float sd = length(max(d, 0.0)) - 0.06;
  float m = 1.0 - smoothstep(0.0, 0.004, sd);
  vec3 bg = (T((uv - 0.5) * 1.3 + 0.5).rgb + T((uv - 0.5) * 1.3 + 0.5 + vec2(0.03, 0.0)).rgb + T((uv - 0.5) * 1.3 + 0.5 - vec2(0.0, 0.03)).rgb) / 3.0 * 0.45;
  vec3 ring = ${hue("fract(uv.y * 0.5 + uTime * 0.1)")} * (1.0 - smoothstep(0.0, 0.01, abs(sd)));
  return vec4(mix(bg, T(q).rgb, m) + ring * uP0.x * 0.8, 1.0);"""),
        FxDef("channel_switch", R.string.fx_channel_switch, R.string.cat_retro,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  float ph = fract(uTime * (0.3 + uP0.y));
  float on = (1.0 - smoothstep(0.0, 0.15, ph)) * uP0.x;
  vec4 c = T(vec2(uv.x + (hash(vec2(floor(uv.y * 60.0), floor(uTime * 30.0))) - 0.5) * 0.15 * on, uv.y));
  c.rgb = mix(c.rgb, vec3(hash(uv * uRes + uTime * 50.0)), on * 0.8);
  return c;"""),
        FxDef("crt_on", R.string.fx_crt_on, R.string.cat_retro,
            listOf(p("amount", R.string.p_amount, 0.8f), p("speed", R.string.p_speed, 0.3f)), """
  float k = clamp(fract(uTime * (0.15 + uP0.y * 0.4)) / 0.25, 0.0, 1.0);
  float h = max(mix(0.004, 1.0, k * k), 0.004);
  float vis = 1.0 - smoothstep(h * 0.5 - 0.003, h * 0.5 + 0.003, abs(uv.y - 0.5));
  vec4 c = T(vec2(uv.x, (uv.y - 0.5) / h + 0.5));
  c.rgb = mix(vec3(0.0), c.rgb * (1.0 + (1.0 - k) * 2.0) + (1.0 - k) * 0.5, vis);
  return mix(T(uv), c, uP0.x);"""),
        FxDef("cassette_vu", R.string.fx_cassette_vu, R.string.cat_retro,
            listOf(p("amount", R.string.p_amount, 0.8f), p("speed", R.string.p_speed, 0.4f)), """
  vec4 c = T(uv);
  float id = floor(uv.x * 24.0);
  float h = 0.05 + 0.12 * noise(vec2(id * 0.7, uTime * 6.0 * (0.5 + uP0.y)));
  float inBar = step(uv.y, h) * step(0.15, fract(uv.x * 24.0)) * step(fract(uv.x * 24.0), 0.85);
  c.rgb = mix(c.rgb, mix(vec3(0.2, 1.0, 0.4), vec3(1.0, 0.2, 0.1), smoothstep(0.08, 0.17, uv.y)), inBar * uP0.x);
  return c;"""),
        FxDef("lcd_lines", R.string.fx_lcd_lines, R.string.cat_retro,
            listOf(p("amount", R.string.p_amount, 0.6f), p("size", R.string.p_size, 0.3f)), """
  vec4 c = T(uv);
  vec2 p = uv * uRes / (1.0 + uP0.y * 3.0);
  float fx = fract(p.x / 3.0);
  vec3 mask = vec3(step(fx, 0.333), step(0.333, fx) * step(fx, 0.666), step(0.666, fx));
  vec3 col = c.rgb * (0.4 + 0.9 * mask) * (0.85 + 0.15 * step(0.2, fract(p.y / 3.0)));
  return vec4(mix(c.rgb, col, uP0.x), c.a);"""),
        FxDef("rec_timecode", R.string.fx_rec_timecode, R.string.cat_retro, listOf(p("amount", R.string.p_amount, 0.8f)), """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  float hud = 0.0;
  ${seg("mod(floor(uTime / 60.0), 6.0)", "0.64", "0.045", "0.04", "hud")}
  ${seg("floor(uTime / 60.0 / 10.0)", "0.585", "0.045", "0.04", "hud")}
  ${seg("mod(floor(uTime / 10.0), 6.0)", "0.80", "0.045", "0.04", "hud")}
  ${seg("floor(uTime)", "0.855", "0.045", "0.04", "hud")}
  hud += (1.0 - smoothstep(0.004, 0.006, length((uv - vec2(0.752, 0.062)) * vec2(asp, 1.0)))) + (1.0 - smoothstep(0.004, 0.006, length((uv - vec2(0.752, 0.086)) * vec2(asp, 1.0))));
  float rec = (1.0 - smoothstep(0.012, 0.015, length((uv - vec2(0.1 / asp + 0.04, 0.93)) * vec2(asp, 1.0)))) * step(fract(uTime * 0.8), 0.6);
  float play = step(abs(uv.y - 0.93), 0.02 - (uv.x - 0.78) * 0.4) * step(0.78, uv.x) * step(uv.x, 0.83);
  c.rgb = mix(c.rgb, vec3(1.0), clamp(hud + play, 0.0, 1.0) * 0.95 * uP0.x);
  c.rgb = mix(c.rgb, vec3(1.0, 0.1, 0.1), rec * uP0.x);
  return c;"""),
        FxDef("film_countdown", R.string.fx_film_countdown, R.string.cat_retro, listOf(p("amount", R.string.p_amount, 0.8f)), """
  vec4 c = T(uv);
  float asp = uRes.x / uRes.y;
  float hud = 0.0;
  ${seg("5.0 - mod(floor(uTime), 5.0)", "0.47", "0.4", "0.16", "hud")}
  vec2 q = (uv - 0.5) * vec2(asp, 1.0);
  float ring = 1.0 - smoothstep(0.0, 0.006, abs(length(q) - 0.3));
  float cr = (1.0 - smoothstep(0.0, 0.003, min(abs(q.x), abs(q.y)))) * step(length(q), 0.36);
  float sweep = 1.0 - smoothstep(0.0, 0.5, fract(atan(q.y, q.x) / 6.2831853 - uTime) * 2.0);
  vec3 film = vec3(luma(c.rgb)) * vec3(0.95, 0.9, 0.75) * (0.8 + 0.2 * hash(vec2(floor(uTime * 24.0), 3.0)));
  film = film * 0.55 + vec3(0.9, 0.85, 0.7) * clamp(hud + ring + cr * 0.5 + sweep * step(length(q), 0.3) * 0.12, 0.0, 1.0) * 0.8;
  film += (hash(uv * uRes + uTime * 40.0) - 0.5) * 0.12;
  film += step(abs(uv.x - hash(vec2(floor(uTime * 9.0), 2.0))), 0.0015) * 0.35;
  return vec4(mix(c.rgb, film, uP0.x), c.a);"""),
        FxDef("pixel_border", R.string.fx_pixel_border, R.string.cat_retro, listOf(p("amount", R.string.p_amount, 0.8f)), """
  vec4 c = T(uv);
  float cells = 40.0;
  float nx = floor(cells * uRes.x / uRes.y);
  vec2 id = floor(uv * vec2(nx, cells));
  float edge = min(min(id.x, nx - 1.0 - id.x), min(id.y, cells - 1.0 - id.y));
  vec3 col = mix(vec3(1.0), ${hue("fract(id.x * 0.07 + id.y * 0.05 + uTime * 0.3)")}, mod(id.x + id.y, 2.0));
  c.rgb = mix(c.rgb, col, step(edge, 1.0) * uP0.x);
  return c;"""),

        // ---------------- Quinta tanda (6) ----------------
        FxDef("whirlpool", R.string.fx_whirlpool, R.string.cat_distort,
            listOf(p("amount", R.string.p_amount, 0.5f), p("speed", R.string.p_speed, 0.3f)), """
  float asp = uRes.x / uRes.y;
  vec2 q = (uv - 0.5) * vec2(asp, 1.0);
  float a = (1.0 - smoothstep(0.0, 0.7, length(q))) * (1.5 + sin(uTime * (0.5 + uP0.y * 2.0))) * uP0.x * 3.0;
  float cs = cos(a);
  float sn = sin(a);
  vec2 u = vec2(q.x * cs - q.y * sn, q.x * sn + q.y * cs);
  return mix(T(u / vec2(asp, 1.0) + 0.5), T(u * 0.97 / vec2(asp, 1.0) + 0.5), 0.5);"""),
        FxDef("holo_foil", R.string.fx_holo_foil, R.string.cat_stylize,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float l = luma(c.rgb);
  vec3 rb = ${hue("fract(dot(uv, vec2(0.9, 0.6)) * 1.5 + l * 1.2 + uTime * (0.1 + uP0.y * 0.4))")};
  float mask = smoothstep(0.15, 0.7, l) * (0.4 + 0.6 * noise(uv * 40.0));
  c.rgb = mix(c.rgb, c.rgb * 0.5 + rb * 0.8, mask * uP0.x * 0.8);
  c.rgb += pow(max(0.0, sin(uv.x * 6.0 + uv.y * 4.0 - uTime)), 12.0) * uP0.x * 0.25;
  return c;"""),
        FxDef("frost", R.string.fx_frost, R.string.cat_light, listOf(p("amount", R.string.p_amount, 0.6f)), """
  vec4 c = T(uv);
  float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
  float n = noise(uv * 30.0) * 0.5 + noise(uv * 80.0) * 0.3 + noise(uv * 8.0) * 0.2;
  float f = 1.0 - smoothstep(0.0, 0.3 * uP0.x + 0.02, edge + (n - 0.5) * 0.2);
  c.rgb = mix(c.rgb, vec3(0.82, 0.92, 1.0) * (0.8 + 0.4 * n), clamp(f * 1.2, 0.0, 0.95));
  c.rgb += pow(n, 6.0) * f * 0.8;
  return c;"""),
        FxDef("color_vignette", R.string.fx_color_vignette, R.string.cat_light,
            listOf(p("amount", R.string.p_amount, 0.6f), p("speed", R.string.p_speed, 0.3f)), """
  vec4 c = T(uv);
  float d = smoothstep(0.2, 0.9, distance(uv, vec2(0.5)));
  vec3 tint = ${hue("fract(uTime * (0.05 + uP0.y * 0.3) + d * 0.3)")};
  c.rgb = mix(c.rgb, c.rgb * 0.4 + tint * d * 0.9, d * uP0.x);
  return c;"""),
        FxDef("gradient_map", R.string.fx_gradient_map, R.string.cat_stylize,
            listOf(p("hue_a", R.string.p_color_a, 0.7f), p("hue_b", R.string.p_color_b, 0.05f), p("amount", R.string.p_amount, 1f)), """
  vec4 c = T(uv);
  float l = luma(c.rgb);
  vec3 a = ${hue("uP0.x")} * 0.35;
  vec3 b = ${hue("uP0.y")};
  vec3 g = l < 0.5 ? mix(a, b, l * 2.0) : mix(b, vec3(1.0), (l - 0.5) * 2.0);
  return vec4(mix(c.rgb, g, uP0.z), c.a);"""),
        FxDef("led_screen", R.string.fx_led_screen, R.string.cat_retro, listOf(p("size", R.string.p_size, 0.4f)), """
  float n = mix(90.0, 28.0, uP0.x);
  vec2 g = vec2(n, n * uRes.y / uRes.x);
  vec2 id = floor(uv * g);
  vec4 c = T((id + 0.5) / g);
  float led = 1.0 - smoothstep(0.32, 0.45, length(fract(uv * g) - 0.5));
  return vec4(c.rgb * led * 1.5, c.a);"""),
    )

    fun effect(id: String): FxDef? = effects.firstOrNull { it.id == id }

    // -----------------------------------------------------------------------------------------
    // Filtros (preajustes del shader de color)
    // -----------------------------------------------------------------------------------------

    private fun adj(
        b: Float = 0f, c: Float = 0f, s: Float = 0f, e: Float = 0f, temp: Float = 0f, tint: Float = 0f,
        hi: Float = 0f, sh: Float = 0f, vib: Float = 0f, fade: Float = 0f, hue: Float = 0f,
        vig: Float = 0f, grain: Float = 0f, sharp: Float = 0f,
    ) = ColorAdjust(
        brightness = b, contrast = c, saturation = s, exposure = e, temperature = temp, tint = tint,
        highlights = hi, shadows = sh, vibrance = vib, fade = fade, hue = hue, vignette = vig, grain = grain, sharpen = sharp,
    )

    val filters: List<FilterPreset> = listOf(
        // Naturales
        FilterPreset("vivid", R.string.filter_vivid, R.string.cat_natural, adj(c = 0.18f, s = 0.3f, vib = 0.3f)),
        FilterPreset("clean", R.string.filter_clean, R.string.cat_natural, adj(e = 0.1f, c = 0.1f, sh = 0.2f, sharp = 0.25f)),
        FilterPreset("fresh", R.string.filter_fresh, R.string.cat_natural, adj(e = 0.08f, temp = -0.12f, s = 0.15f, hi = -0.1f)),
        FilterPreset("sunny", R.string.filter_sunny, R.string.cat_natural, adj(temp = 0.3f, e = 0.1f, s = 0.12f)),
        FilterPreset("crisp", R.string.filter_crisp, R.string.cat_natural, adj(c = 0.25f, sharp = 0.5f, vib = 0.15f)),
        FilterPreset("soft", R.string.filter_soft, R.string.cat_natural, adj(c = -0.18f, fade = 0.2f, e = 0.08f)),
        // Retrato
        FilterPreset("portrait", R.string.filter_portrait, R.string.cat_portrait, adj(temp = 0.1f, sh = 0.18f, c = -0.05f, vib = 0.1f)),
        FilterPreset("peach", R.string.filter_peach, R.string.cat_portrait, adj(temp = 0.15f, tint = 0.12f, fade = 0.1f), tintH = 0x18FFB090),
        FilterPreset("rose", R.string.filter_rose, R.string.cat_portrait, adj(tint = 0.2f, s = -0.05f, e = 0.06f), tintS = 0x14FF4080),
        FilterPreset("porcelain", R.string.filter_porcelain, R.string.cat_portrait, adj(e = 0.15f, s = -0.15f, c = -0.1f, hi = -0.15f)),
        FilterPreset("tan", R.string.filter_tan, R.string.cat_portrait, adj(temp = 0.25f, s = 0.1f, c = 0.1f, sh = -0.05f)),
        // Cine
        FilterPreset("teal_orange", R.string.filter_teal_orange, R.string.cat_cinema, adj(c = 0.1f), lut = "asset:luts/teal_orange.cube"),
        FilterPreset("blockbuster", R.string.filter_blockbuster, R.string.cat_cinema, adj(c = 0.22f, s = -0.1f, vig = 0.3f), tintS = 0x24007088, tintH = 0x18FF9040),
        FilterPreset("noir_color", R.string.filter_noir_color, R.string.cat_cinema, adj(c = 0.3f, s = -0.45f, vig = 0.45f, sh = -0.15f)),
        FilterPreset("matrix", R.string.filter_matrix, R.string.cat_cinema, adj(c = 0.15f), lut = "asset:luts/matrix.cube"),
        FilterPreset("bleach", R.string.filter_bleach, R.string.cat_cinema, adj(), lut = "asset:luts/bleach_bypass.cube"),
        FilterPreset("moonlight", R.string.filter_moonlight, R.string.cat_cinema, adj(temp = -0.4f, e = -0.2f, s = -0.2f, c = 0.15f), tintS = 0x24203070),
        FilterPreset("golden_hour", R.string.filter_golden_hour, R.string.cat_cinema, adj(temp = 0.4f, s = 0.15f, hi = -0.1f, vig = 0.2f), tintH = 0x1CFFB040),
        FilterPreset("desert", R.string.filter_desert, R.string.cat_cinema, adj(temp = 0.3f, c = 0.2f, s = -0.15f, fade = 0.1f)),
        // Película analógica
        FilterPreset("kodak", R.string.filter_kodak, R.string.cat_film, adj(grain = 0.15f), lut = "asset:luts/warm_film.cube"),
        FilterPreset("fuji", R.string.filter_fuji, R.string.cat_film, adj(temp = -0.08f, tint = -0.1f, c = 0.1f, s = 0.1f, grain = 0.12f), tintS = 0x14008060),
        FilterPreset("polaroid", R.string.filter_polaroid, R.string.cat_film, adj(fade = 0.3f, temp = 0.12f, c = -0.1f, vig = 0.25f, grain = 0.2f)),
        FilterPreset("super8", R.string.filter_super8, R.string.cat_film, adj(fade = 0.25f, temp = 0.25f, s = -0.1f, vig = 0.5f, grain = 0.45f)),
        FilterPreset("expired", R.string.filter_expired, R.string.cat_film, adj(fade = 0.35f, tint = 0.15f, grain = 0.3f), tintS = 0x20703090),
        FilterPreset("lomo", R.string.filter_lomo, R.string.cat_film, adj(c = 0.3f, s = 0.3f, vig = 0.65f)),
        FilterPreset("cross", R.string.filter_cross, R.string.cat_film, adj(c = 0.2f, s = 0.2f), tintS = 0x24004090, tintH = 0x20E0E040),
        // Vintage
        FilterPreset("sepia", R.string.filter_sepia, R.string.cat_vintage, adj(c = 0.05f), mono = 1f, tintS = 0x30402000, tintH = 0x30704010),
        FilterPreset("vintage", R.string.filter_vintage, R.string.cat_vintage, adj(fade = 0.25f, temp = 0.2f, s = -0.25f, vig = 0.35f)),
        FilterPreset("seventies", R.string.filter_seventies, R.string.cat_vintage, adj(temp = 0.3f, fade = 0.2f, s = 0.1f, c = -0.05f), tintS = 0x1C803000),
        FilterPreset("eighties", R.string.filter_eighties, R.string.cat_vintage, adj(s = 0.35f, c = 0.15f, tint = 0.2f), tintS = 0x244000A0, tintH = 0x18FF40C0),
        FilterPreset("faded", R.string.filter_faded, R.string.cat_vintage, adj(fade = 0.5f, s = -0.2f, c = -0.15f)),
        FilterPreset("pastel", R.string.filter_pastel, R.string.cat_vintage, adj(), lut = "asset:luts/pastel.cube"),
        // Blanco y negro
        FilterPreset("bw", R.string.filter_bw, R.string.cat_bw, adj(), mono = 1f),
        FilterPreset("bw_contrast", R.string.filter_bw_contrast, R.string.cat_bw, adj(c = 0.45f), mono = 1f),
        FilterPreset("bw_soft", R.string.filter_bw_soft, R.string.cat_bw, adj(c = -0.15f, fade = 0.25f), mono = 1f),
        FilterPreset("noir", R.string.filter_noir, R.string.cat_bw, adj(c = 0.5f, e = -0.1f, vig = 0.55f, grain = 0.25f), mono = 1f),
        FilterPreset("silver", R.string.filter_silver, R.string.cat_bw, adj(c = 0.15f, e = 0.1f), mono = 1f, tintH = 0x14A0B0D0),
        FilterPreset("newspaper", R.string.filter_newspaper, R.string.cat_bw, adj(c = 0.35f, grain = 0.5f, fade = 0.15f), mono = 1f),
        // Ambiente
        FilterPreset("cold", R.string.filter_cold, R.string.cat_mood, adj(temp = -0.45f, s = -0.1f, c = 0.1f)),
        FilterPreset("warm", R.string.filter_warm, R.string.cat_mood, adj(temp = 0.45f, s = 0.05f)),
        FilterPreset("night", R.string.filter_night, R.string.cat_mood, adj(), lut = "asset:luts/cool_night.cube"),
        FilterPreset("neon", R.string.filter_neon, R.string.cat_mood, adj(s = 0.5f, c = 0.25f, vib = 0.3f), tintS = 0x284000C0, tintH = 0x1800E0FF),
        FilterPreset("dream", R.string.filter_dream, R.string.cat_mood, adj(e = 0.15f, fade = 0.25f, s = 0.1f, tint = 0.1f), tintH = 0x18FFC0E0),
        FilterPreset("forest", R.string.filter_forest, R.string.cat_mood, adj(tint = -0.25f, s = 0.15f, c = 0.1f, sh = -0.05f), tintS = 0x18003020),
        FilterPreset("ocean", R.string.filter_ocean, R.string.cat_mood, adj(temp = -0.3f, tint = -0.1f, s = 0.2f, vib = 0.2f)),
        FilterPreset("sunset", R.string.filter_sunset, R.string.cat_mood, adj(temp = 0.35f, tint = 0.2f, s = 0.25f, c = 0.1f), tintS = 0x20601060, tintH = 0x1CFF8020),
        FilterPreset("foodie", R.string.filter_foodie, R.string.cat_mood, adj(s = 0.3f, temp = 0.12f, c = 0.12f, sharp = 0.3f, vib = 0.2f)),
        FilterPreset("gloomy", R.string.filter_gloomy, R.string.cat_mood, adj(s = -0.35f, e = -0.12f, c = 0.12f, temp = -0.12f, vig = 0.3f)),
        FilterPreset("golden_soft", R.string.filter_golden_soft, R.string.cat_natural, adj(temp = 0.2f, e = 0.1f, fade = 0.1f, s = 0.08f)),
        FilterPreset("airy", R.string.filter_airy, R.string.cat_natural, adj(e = 0.2f, c = -0.1f, fade = 0.12f, s = -0.05f)),
        FilterPreset("rich", R.string.filter_rich, R.string.cat_natural, adj(c = 0.22f, s = 0.22f, sh = 0.1f, vib = 0.2f)),
        FilterPreset("cyberpunk", R.string.filter_cyberpunk, R.string.cat_cinema, adj(c = 0.25f, s = 0.4f, tint = 0.15f), tintS = 0x300040C0, tintH = 0x20FF30B0),
        FilterPreset("thriller", R.string.filter_thriller, R.string.cat_cinema, adj(c = 0.3f, s = -0.35f, temp = -0.2f, vig = 0.45f, e = -0.1f), tintS = 0x18003040),
        FilterPreset("scifi", R.string.filter_scifi, R.string.cat_cinema, adj(c = 0.18f, s = -0.15f, temp = -0.3f, e = 0.05f), tintH = 0x1800C0FF),
        FilterPreset("kodachrome", R.string.filter_kodachrome, R.string.cat_film, adj(c = 0.25f, s = 0.3f, temp = 0.12f, grain = 0.12f), tintS = 0x14300000),
        FilterPreset("portra", R.string.filter_portra, R.string.cat_film, adj(c = -0.05f, s = -0.05f, temp = 0.1f, fade = 0.15f, grain = 0.1f), tintH = 0x14FFD0B0),
        FilterPreset("sunbleached", R.string.filter_sunbleached, R.string.cat_vintage, adj(fade = 0.35f, e = 0.15f, s = -0.3f, temp = 0.25f)),
        FilterPreset("retro_tv", R.string.filter_retro_tv, R.string.cat_vintage, adj(c = 0.2f, s = -0.2f, vig = 0.5f, grain = 0.4f, fade = 0.15f), tintS = 0x18003020),
        FilterPreset("autumn", R.string.filter_autumn, R.string.cat_mood, adj(temp = 0.35f, s = 0.2f, c = 0.1f, tint = 0.1f), tintS = 0x1C401000),
        FilterPreset("winter", R.string.filter_winter, R.string.cat_mood, adj(temp = -0.35f, e = 0.1f, s = -0.2f, c = 0.1f), tintH = 0x1480B0FF),
        FilterPreset("spring", R.string.filter_spring, R.string.cat_mood, adj(s = 0.25f, vib = 0.3f, e = 0.1f, tint = -0.1f, temp = 0.05f)),
        FilterPreset("candy", R.string.filter_candy, R.string.cat_mood, adj(s = 0.35f, e = 0.1f, fade = 0.1f, tint = 0.2f), tintH = 0x18FF80C0),
        FilterPreset("moody", R.string.filter_moody, R.string.cat_mood, adj(c = 0.2f, s = -0.3f, e = -0.15f, fade = 0.1f, vig = 0.4f), tintS = 0x20102030),
        FilterPreset("tropical", R.string.filter_tropical, R.string.cat_mood, adj(s = 0.4f, vib = 0.35f, temp = 0.1f, c = 0.12f), tintS = 0x14004030),
        // ---------------- Segunda tanda ----------------
        FilterPreset("daylight", R.string.filter_daylight, R.string.cat_natural, adj(e = 0.06f, c = 0.08f, vib = 0.2f, sharp = 0.2f, temp = 0.04f)),
        FilterPreset("matte", R.string.filter_matte, R.string.cat_natural, adj(fade = 0.22f, c = 0.1f, s = -0.08f)),
        FilterPreset("punchy", R.string.filter_punchy, R.string.cat_natural, adj(c = 0.3f, s = 0.18f, vib = 0.15f, sh = -0.08f, sharp = 0.3f)),
        FilterPreset("hdr", R.string.filter_hdr, R.string.cat_natural, adj(c = 0.15f, hi = -0.35f, sh = 0.4f, vib = 0.3f, sharp = 0.45f)),
        FilterPreset("soft_glow", R.string.filter_soft_glow, R.string.cat_natural, adj(e = 0.12f, c = -0.12f, fade = 0.15f, hi = 0.1f, temp = 0.06f)),
        FilterPreset("honey", R.string.filter_honey, R.string.cat_portrait, adj(temp = 0.22f, tint = 0.05f, s = 0.08f, fade = 0.06f), tintH = 0x16FFC060),
        FilterPreset("blush", R.string.filter_blush, R.string.cat_portrait, adj(tint = 0.15f, e = 0.08f, c = -0.06f, fade = 0.08f), tintH = 0x14FF90A0),
        FilterPreset("studio", R.string.filter_studio, R.string.cat_portrait, adj(c = 0.14f, s = -0.06f, sh = 0.12f, sharp = 0.3f, hi = -0.08f)),
        FilterPreset("glow_skin", R.string.filter_glow_skin, R.string.cat_portrait, adj(e = 0.1f, temp = 0.08f, c = -0.08f, vib = 0.12f, sh = 0.15f)),
        FilterPreset("teal_magenta", R.string.filter_teal_magenta, R.string.cat_cinema, adj(c = 0.18f, s = 0.05f), tintS = 0x26008080, tintH = 0x18FF50A0),
        FilterPreset("bronze", R.string.filter_bronze, R.string.cat_cinema, adj(c = 0.2f, s = -0.2f, temp = 0.2f, vig = 0.25f), tintS = 0x1C3A2000, tintH = 0x14FFC080),
        FilterPreset("western", R.string.filter_western, R.string.cat_cinema, adj(temp = 0.35f, c = 0.25f, s = -0.25f, fade = 0.08f, grain = 0.15f, vig = 0.3f)),
        FilterPreset("horror", R.string.filter_horror, R.string.cat_cinema, adj(c = 0.35f, s = -0.5f, e = -0.15f, tint = -0.15f, vig = 0.5f, grain = 0.2f), tintS = 0x18002010),
        FilterPreset("blade", R.string.filter_blade, R.string.cat_cinema, adj(c = 0.22f, s = 0.1f, temp = -0.15f, vig = 0.3f), tintS = 0x28003050, tintH = 0x1CFF7020),
        FilterPreset("dune", R.string.filter_dune, R.string.cat_cinema, adj(temp = 0.3f, s = -0.3f, c = 0.15f, fade = 0.15f, hi = -0.1f), tintH = 0x14FFD090),
        FilterPreset("arctic", R.string.filter_arctic, R.string.cat_cinema, adj(temp = -0.3f, s = -0.25f, c = 0.18f, e = 0.06f), tintS = 0x18102040, tintH = 0x10C0E0FF),
        FilterPreset("crimson", R.string.filter_crimson, R.string.cat_cinema, adj(c = 0.25f, s = -0.3f, vig = 0.35f), tintS = 0x22500010),
        FilterPreset("agfa", R.string.filter_agfa, R.string.cat_film, adj(c = 0.15f, s = 0.15f, temp = 0.05f, grain = 0.14f), tintS = 0x12500000, tintH = 0x10FFFFA0),
        FilterPreset("cinestill", R.string.filter_cinestill, R.string.cat_film, adj(c = 0.12f, temp = -0.12f, s = 0.1f, grain = 0.15f, fade = 0.06f), tintS = 0x18003060, tintH = 0x1CFF5030),
        FilterPreset("ektar", R.string.filter_ektar, R.string.cat_film, adj(c = 0.2f, s = 0.35f, vib = 0.2f, grain = 0.08f)),
        FilterPreset("instant", R.string.filter_instant, R.string.cat_film, adj(fade = 0.22f, c = 0.05f, temp = -0.06f, tint = -0.06f, vig = 0.2f, grain = 0.15f), tintS = 0x16004050),
        FilterPreset("disposable", R.string.filter_disposable, R.string.cat_film, adj(c = 0.22f, s = 0.2f, temp = 0.12f, vig = 0.4f, grain = 0.3f, e = 0.08f), tintH = 0x10FFF0C0),
        FilterPreset("sepia_soft", R.string.filter_sepia_soft, R.string.cat_vintage, adj(fade = 0.15f), mono = 0.75f, tintS = 0x24402000, tintH = 0x20704010),
        FilterPreset("sixties", R.string.filter_sixties, R.string.cat_vintage, adj(temp = 0.18f, s = 0.2f, fade = 0.18f, c = -0.05f, grain = 0.15f), tintH = 0x14FFE080),
        FilterPreset("nineties", R.string.filter_nineties, R.string.cat_vintage, adj(c = 0.1f, s = -0.12f, fade = 0.14f, temp = -0.05f, grain = 0.2f), tintS = 0x14203050),
        FilterPreset("rust", R.string.filter_rust, R.string.cat_vintage, adj(temp = 0.3f, tint = 0.1f, s = -0.15f, c = 0.15f, fade = 0.1f), tintS = 0x20602000),
        FilterPreset("dusty_rose", R.string.filter_dusty_rose, R.string.cat_vintage, adj(tint = 0.15f, fade = 0.25f, s = -0.2f, temp = 0.08f), tintH = 0x14FFB0B0),
        FilterPreset("bw_film", R.string.filter_bw_film, R.string.cat_bw, adj(c = 0.2f, grain = 0.35f, vig = 0.3f), mono = 1f),
        FilterPreset("bw_high_key", R.string.filter_bw_high_key, R.string.cat_bw, adj(e = 0.25f, c = 0.1f, fade = 0.1f), mono = 1f),
        FilterPreset("bw_blue", R.string.filter_bw_blue, R.string.cat_bw, adj(c = 0.2f), mono = 1f, tintS = 0x24102848, tintH = 0x10C0D8FF),
        FilterPreset("bw_matte", R.string.filter_bw_matte, R.string.cat_bw, adj(fade = 0.35f, c = 0.05f), mono = 1f),
        FilterPreset("vaporwave", R.string.filter_vaporwave, R.string.cat_mood, adj(s = 0.3f, tint = 0.25f, fade = 0.1f, c = 0.05f), tintS = 0x2A6000C0, tintH = 0x2000E0E0),
        FilterPreset("synth_sunset", R.string.filter_synth_sunset, R.string.cat_mood, adj(s = 0.3f, c = 0.2f, temp = 0.15f), tintS = 0x2C400090, tintH = 0x24FF6020),
        FilterPreset("lavender", R.string.filter_lavender, R.string.cat_mood, adj(tint = 0.12f, temp = -0.08f, fade = 0.15f, s = -0.05f), tintS = 0x18604090, tintH = 0x10E0C0FF),
        FilterPreset("mint", R.string.filter_mint, R.string.cat_mood, adj(tint = -0.18f, temp = -0.05f, e = 0.08f, fade = 0.1f, s = 0.05f), tintH = 0x12B0FFE0),
        FilterPreset("ember", R.string.filter_ember, R.string.cat_mood, adj(c = 0.25f, temp = 0.3f, s = 0.1f, e = -0.08f, vig = 0.35f), tintS = 0x20400800, tintH = 0x18FF8020),
        FilterPreset("midnight", R.string.filter_midnight, R.string.cat_mood, adj(e = -0.22f, c = 0.2f, temp = -0.3f, s = -0.1f, vig = 0.35f), tintS = 0x2C081840),
        FilterPreset("rainy", R.string.filter_rainy, R.string.cat_mood, adj(s = -0.3f, temp = -0.15f, c = 0.05f, fade = 0.15f, e = -0.05f), tintS = 0x14203040),
        FilterPreset("fiesta", R.string.filter_fiesta, R.string.cat_mood, adj(s = 0.45f, vib = 0.3f, c = 0.18f, temp = 0.08f, sharp = 0.2f)),
        FilterPreset("zen", R.string.filter_zen, R.string.cat_mood, adj(s = -0.18f, c = -0.08f, fade = 0.18f, e = 0.08f, tint = -0.06f)),
        FilterPreset("storm", R.string.filter_storm, R.string.cat_mood, adj(c = 0.3f, s = -0.35f, e = -0.1f, temp = -0.2f, sharp = 0.3f, vig = 0.3f)),
        FilterPreset("candlelight", R.string.filter_candlelight, R.string.cat_mood, adj(temp = 0.45f, e = -0.08f, c = 0.12f, s = 0.05f, vig = 0.4f, fade = 0.05f), tintH = 0x16FFB050),
        FilterPreset("aqua", R.string.filter_aqua, R.string.cat_mood, adj(temp = -0.2f, tint = -0.15f, s = 0.25f, vib = 0.2f, e = 0.05f), tintH = 0x1280FFF0),
        FilterPreset("blossom", R.string.filter_blossom, R.string.cat_mood, adj(tint = 0.18f, e = 0.12f, s = 0.1f, fade = 0.12f, c = -0.05f), tintH = 0x16FFC0D8),
    )

    fun filter(id: String): FilterPreset? = filters.firstOrNull { it.id == id }

    /** LUT incluidas en assets para "LUT propia". */
    val bundledLuts: List<String> = listOf(
        "asset:luts/teal_orange.cube", "asset:luts/matrix.cube", "asset:luts/bleach_bypass.cube",
        "asset:luts/warm_film.cube", "asset:luts/pastel.cube", "asset:luts/cool_night.cube",
    )

    // -----------------------------------------------------------------------------------------
    // Velocidad y voz
    // -----------------------------------------------------------------------------------------

    private fun curve(vararg v: Float) = v.mapIndexed { i, s -> SpeedPoint(i / (v.size - 1).toFloat(), s) }

    val speedPresets: List<SpeedPreset> = listOf(
        SpeedPreset("montage", R.string.speed_montage, curve(0.6f, 4f, 0.6f, 4f, 0.6f)),
        SpeedPreset("hero", R.string.speed_hero, curve(1f, 4f, 0.35f, 4f, 1f)),
        SpeedPreset("bullet", R.string.speed_bullet, curve(4f, 4f, 0.3f, 4f, 4f)),
        SpeedPreset("jump_cut", R.string.speed_jump_cut, curve(0.5f, 5f, 5f, 0.5f, 0.5f)),
        SpeedPreset("flash_in", R.string.speed_flash_in, curve(5f, 3f, 1f, 1f, 1f)),
        SpeedPreset("flash_out", R.string.speed_flash_out, curve(1f, 1f, 1f, 3f, 5f)),
        SpeedPreset("ramp_up", R.string.speed_ramp_up, curve(0.4f, 0.8f, 1.5f, 2.5f, 4f)),
        SpeedPreset("ramp_down", R.string.speed_ramp_down, curve(4f, 2.5f, 1.5f, 0.8f, 0.4f)),
    )

    val voicePresets: List<VoicePreset> = listOf(
        VoicePreset("chipmunk", R.string.voice_chipmunk, pitch = 7f),
        VoicePreset("deep", R.string.voice_deep, pitch = -5f),
        VoicePreset("giant", R.string.voice_giant, pitch = -9f, echo = 0.2f),
        VoicePreset("robot", R.string.voice_robot, pitch = 0f, robot = 1f),
        VoicePreset("alien", R.string.voice_alien, pitch = 4f, robot = 0.6f, tremolo = 0.5f),
        VoicePreset("echo", R.string.voice_echo, pitch = 0f, echo = 0.6f),
        VoicePreset("radio", R.string.voice_radio, pitch = 0f, tremolo = 0f, robot = 0f, echo = 0f),
        VoicePreset("tremolo", R.string.voice_tremolo, pitch = 0f, tremolo = 1f),
    )

    fun voice(id: String): VoicePreset? = voicePresets.firstOrNull { it.id == id }
}
