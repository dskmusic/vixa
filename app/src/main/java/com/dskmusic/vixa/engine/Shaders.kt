package com.dskmusic.vixa.engine

/**
 * Fuentes GLSL (ES 2.0). Convenciones:
 *  - vUv: 0..1 con origen abajo-izquierda (convención GL y de las texturas internas de Media3).
 *  - "pantalla": p = (vUv.x, 1 - vUv.y), origen arriba-izquierda. Todo el modelo usa pantalla.
 *  - Las pasadas por clip emiten alfa recto (no premultiplicado); el compositor de Media3 mezcla con
 *    SRC_ALPHA / ONE_MINUS_SRC_ALPHA y su salida queda premultiplicada.
 */
object Shaders {

    const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vUv;
void main() {
  gl_Position = aFramePosition;
  vUv = aFramePosition.xy * 0.5 + 0.5;
}
"""

    private const val HEADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform sampler2D uTex;
float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123); }
float noise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x),
             mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
"""

    /** Pasada única por clip / generador de color / fotograma completo (fondo + capas de ajuste). */
    fun clipFragment(transInBody: String, transOutBody: String): String = HEADER + """
uniform sampler2D uLut;
uniform sampler2D uCurve;
uniform sampler2D uPersonTex;
uniform vec4 uPerson;
uniform vec4 uPColor;
uniform vec4 uPOut;
uniform vec3 uM0;
uniform vec3 uM1;
uniform vec4 uCrop;
uniform vec4 uSize;
uniform vec4 uSrc;
uniform vec4 uMode;
uniform vec4 uA;
uniform vec4 uB;
uniform vec4 uC;
uniform vec4 uD;
uniform vec4 uTintS;
uniform vec4 uTintH;
uniform vec4 uLutP;
uniform vec4 uChroma;
uniform vec4 uChromaP;
uniform vec4 uMask;
uniform vec4 uMaskP;
uniform vec4 uZone;
uniform vec4 uZoneP;
uniform vec4 uReveal;
uniform vec4 uGen0;
uniform vec4 uGen1;
uniform vec4 uGen2;
uniform vec4 uGen3;
uniform vec4 uGenQ;
uniform vec4 uGenP;
uniform vec4 uFill;
uniform vec4 uBg0;
uniform vec4 uBg1;
uniform vec4 uBgP;
uniform vec4 uTr;
uniform vec4 uTrP;

float tA;
vec2 tOfs;
float tZoom;
float tRot;
vec4 tTint;
float tBlur;
float tPix;

void tReset() {
  tA = 1.0; tOfs = vec2(0.0); tZoom = 1.0; tRot = 0.0; tTint = vec4(0.0); tBlur = 0.0; tPix = 0.0;
}
void trIn(float p, vec2 uv, float role) {
$transInBody
}
void trOut(float p, vec2 uv, float role) {
$transOutBody
}
vec2 applyLayer(vec2 q) {
  float asp = uSize.z / uSize.w;
  vec2 c = q - 0.5;
  c.x *= asp;
  float cs = cos(tRot);
  float sn = sin(tRot);
  c = vec2(c.x * cs + c.y * sn, -c.x * sn + c.y * cs);
  c.x /= asp;
  c /= max(tZoom, 0.001);
  return c + 0.5 - tOfs;
}
vec4 tex(vec2 su) { return texture2D(uTex, vec2(su.x, 1.0 - su.y)); }
vec4 blurTex(vec2 su, float r) {
  vec4 acc = tex(su);
  float asp = uSrc.y / max(uSrc.x, 0.000001);
  // Espiral áurea: el ángulo se acumula girando por un paso constante (sin cos/sin por muestra).
  vec2 dir = vec2(1.0, 0.0);
  const vec2 rot = vec2(-0.7373688, 0.6754632);
  for (int i = 0; i < 16; i++) {
    float d = sqrt((float(i) + 0.5) / 16.0) * r;
    acc += tex(clamp(su + vec2(dir.x, dir.y * asp) * d, 0.0, 1.0));
    dir = vec2(dir.x * rot.x - dir.y * rot.y, dir.x * rot.y + dir.y * rot.x);
  }
  return acc / 17.0;
}
vec3 lutLookup(vec3 c) {
  float n = uLutP.y;
  c = clamp(c, 0.0, 1.0);
  float b = c.b * (n - 1.0);
  float b0 = floor(b);
  float b1 = min(b0 + 1.0, n - 1.0);
  float f = b - b0;
  float u = (c.r * (n - 1.0) + 0.5) / n;
  float v = (c.g * (n - 1.0) + 0.5) / n;
  vec3 c0 = texture2D(uLut, vec2((b0 + u) / n, v)).rgb;
  vec3 c1 = texture2D(uLut, vec2((b1 + u) / n, v)).rgb;
  return mix(c0, c1, f);
}
vec3 hueRotate(vec3 c, float a) {
  float cs = cos(a);
  float sn = sin(a);
  vec3 k = vec3(0.57735);
  return c * cs + cross(k, c) * sn + k * dot(k, c) * (1.0 - cs);
}
// Corrección de color parametrizada: cada filtro es un conjunto de valores para estos uniforms.
vec3 grade(vec3 c, vec2 l) {
  if (uLutP.x > 0.001) c = mix(c, lutLookup(c), uLutP.x);
  c *= pow(2.0, uA.x * 1.5);
  c *= vec3(1.0 + uB.x * 0.18, 1.0 + uB.y * 0.12, 1.0 - uB.x * 0.18);
  c += uA.y * 0.25;
  c = (c - 0.5) * (1.0 + uA.z * (uA.z > 0.0 ? 1.0 : 0.75)) + 0.5;
  float y = luma(c);
  c += uB.w * 0.35 * (1.0 - smoothstep(0.0, 0.55, y)) * (1.0 - y);
  c += uB.z * 0.35 * smoothstep(0.45, 1.0, y) * y;
  y = luma(c);
  c = mix(vec3(y), c, 1.0 + uA.w);
  float mx = max(c.r, max(c.g, c.b));
  float mn = min(c.r, min(c.g, c.b));
  c = mix(vec3(y), c, 1.0 + uC.x * (1.0 - clamp(mx - mn, 0.0, 1.0)));
  if (abs(uC.z) > 0.001) c = hueRotate(c, uC.z * 3.14159265);
  c += uC.w * 0.3;
  c = mix(c, c * 0.75 + 0.16, max(uC.y, 0.0));
  c = mix(c, (c - 0.08) * 1.1, max(-uC.y, 0.0));
  y = luma(c);
  c = mix(c, vec3(y), uD.w);
  c += uTintS.rgb * uTintS.a * (1.0 - y);
  c += uTintH.rgb * uTintH.a * y;
  if (uLutP.z > 0.5) {
    c = clamp(c, 0.0, 1.0);
    c = vec3(texture2D(uCurve, vec2(c.r * 0.996 + 0.002, 0.5)).r,
             texture2D(uCurve, vec2(c.g * 0.996 + 0.002, 0.5)).g,
             texture2D(uCurve, vec2(c.b * 0.996 + 0.002, 0.5)).b);
  }
  if (uD.y > 0.001) {
    float d = distance(l, vec2(0.5)) * 1.41421;
    c *= 1.0 - uD.y * smoothstep(0.35, 1.05, d);
  }
  if (uD.z > 0.001) {
    float g = hash(l * uSize.zw + vec2(uMode.w * 61.0, uMode.w * 17.0)) - 0.5;
    c += g * uD.z * 0.35;
  }
  return c;
}
vec3 gradient(vec4 a, vec4 b, float ang, float two, vec2 l) {
  if (two < 0.5) return a.rgb;
  float g = dot(l - 0.5, vec2(cos(ang), sin(ang))) + 0.5;
  return mix(a.rgb, b.rgb, clamp(g, 0.0, 1.0));
}
// Mezcla con el alfa premultiplicado: un color transparente se desvanece sin ensuciar el degradado.
vec4 mixp(vec4 a, vec4 b, float t) {
  vec4 pa = vec4(a.rgb * a.a, a.a);
  vec4 pb = vec4(b.rgb * b.a, b.a);
  vec4 r = mix(pa, pb, clamp(t, 0.0, 1.0));
  return r.a > 0.0001 ? vec4(r.rgb / r.a, r.a) : vec4(b.rgb, 0.0);
}
// Degradado de hasta 4 colores (con alfa), lineal u radial, en espacio de píxeles del clip. info: x ángulo, y nº de colores, z radial.
vec4 gradientN(vec4 s0, vec4 s1, vec4 s2, vec4 s3, vec4 info, vec2 l) {
  float n = info.y;
  if (n < 1.5) return s0;
  vec2 pxl = (l - 0.5) * uSize.xy;
  float g;
  if (info.z > 0.5) {
    g = length(pxl) / max(0.5 * length(uSize.xy), 1.0);
  } else {
    vec2 d = vec2(cos(info.x), sin(info.x));
    g = dot(pxl, d) / max(abs(d.x) * uSize.x + abs(d.y) * uSize.y, 1.0) + 0.5;
  }
  g = clamp(g, 0.0, 1.0);
  float t = g * (n - 1.0);
  if (n < 2.5) return mixp(s0, s1, t);
  if (n < 3.5) return t < 1.0 ? mixp(s0, s1, t) : mixp(s1, s2, t - 1.0);
  return t < 1.0 ? mixp(s0, s1, t) : (t < 2.0 ? mixp(s1, s2, t - 1.0) : mixp(s2, s3, t - 2.0));
}
void main() {
  vec2 p = vec2(vUv.x, 1.0 - vUv.y);
  float mode = uMode.x;
  if (mode > 2.5) {
    gl_FragColor = vec4(0.0);
    return;
  }
  if (mode > 1.5) {
    vec4 s = texture2D(uTex, vUv);
    vec3 bg = uBgP.z < 0.5 ? vec3(0.0) : gradient(uBg0, uBg1, uBgP.x, uBgP.y, p);
    vec3 full = s.rgb + bg * (1.0 - s.a);
    if (uBgP.w > 0.5) full = grade(full, p);
    gl_FragColor = vec4(clamp(full, 0.0, 1.0), 1.0);
    return;
  }
  // Transiciones: mueven/escalan la capa completa y dan el alfa cuando el clip es la capa superior.
  float trAlpha = 1.0;
  vec4 tint = vec4(0.0);
  float blurAmt = uZoneP.w;
  float pix = 0.0;
  vec2 q = p;
  float inCanvas = 1.0;
  if (uTr.x > 0.5) {
    tReset();
    trIn(uTr.y, p, 1.0);
    q = applyLayer(q);
    if (uTrP.x > 0.5) trAlpha *= tA;
    tint = tTint;
    blurAmt = max(blurAmt, tBlur);
    pix = max(pix, tPix);
  }
  if (uTr.z > 0.5) {
    tReset();
    trOut(uTr.w, p, 0.0);
    q = applyLayer(q);
    if (uTrP.y > 0.5) trAlpha *= tA;
    tint = mix(tint, tTint, step(tint.a, tTint.a));
    blurAmt = max(blurAmt, tBlur);
    pix = max(pix, tPix);
  }
  if (uTr.x > 0.5 || uTr.z > 0.5) {
    inCanvas = step(0.0, q.x) * step(q.x, 1.0) * step(0.0, q.y) * step(q.y, 1.0);
  }
  vec2 l = vec2(dot(uM0, vec3(q, 1.0)), dot(uM1, vec3(q, 1.0)));
  vec2 e = min(l, 1.0 - l) * uSize.xy;
  float inside = clamp(min(e.x, e.y) + 0.5, 0.0, 1.0);
  inside *= step(uReveal.x, l.x) * step(l.x, 1.0 - uReveal.z) * step(uReveal.y, l.y) * step(l.y, 1.0 - uReveal.w);
  vec4 c;
  if (mode > 0.5) {
    c = gradientN(uGen0, uGen1, uGen2, uGen3, vec4(uGenP.x, uGenQ.x, uGenQ.y, 0.0), l);
  } else {
    vec2 lf = l;
    if (uSrc.z > 0.5) lf.x = 1.0 - lf.x;
    if (uSrc.w > 0.5) lf.y = 1.0 - lf.y;
    vec2 su = vec2(uCrop.x + lf.x * (1.0 - uCrop.x - uCrop.z), uCrop.y + lf.y * (1.0 - uCrop.y - uCrop.w));
    su = clamp(su, 0.0, 1.0);
    float zt = uZoneP.x;
    float zoneBlur = 0.0;
    if (zt > 0.5) {
      vec2 zd = (su - uZone.xy) / max(uZone.zw * 0.5, vec2(0.0001));
      float inZone = uZoneP.y > 0.5 ? step(length(zd), 1.0) : step(max(abs(zd.x), abs(zd.y)), 1.0);
      if (inZone > 0.5) {
        if (zt > 1.5) pix = max(pix, 0.15 + uZoneP.z * 0.85);
        else zoneBlur = 0.01 + uZoneP.z * 0.06;
      }
    }
    if (pix > 0.001) {
      float n = mix(160.0, 10.0, clamp(pix, 0.0, 1.0));
      vec2 grid = vec2(n, n * uSrc.x / max(uSrc.y, 0.000001));
      su = (floor(su * grid) + 0.5) / grid;
    }
    float br = max(blurAmt, zoneBlur);
    if (br > 0.0005) c = blurTex(su, br);
    else c = tex(su);
    if (uD.x > 0.001) {
      vec3 nb = tex(su + vec2(uSrc.x, 0.0)).rgb + tex(su - vec2(uSrc.x, 0.0)).rgb
              + tex(su + vec2(0.0, uSrc.y)).rgb + tex(su - vec2(0.0, uSrc.y)).rgb;
      c.rgb += (c.rgb * 4.0 - nb) * uD.x * 0.6;
    }
    if (uChroma.a > 0.5) {
      vec3 kc = uChroma.rgb;
      vec2 kcc = vec2(dot(kc, vec3(-0.1146, -0.3854, 0.5)), dot(kc, vec3(0.5, -0.4542, -0.0458)));
      vec2 ccc = vec2(dot(c.rgb, vec3(-0.1146, -0.3854, 0.5)), dot(c.rgb, vec3(0.5, -0.4542, -0.0458)));
      float d = distance(ccc, kcc);
      float ka = smoothstep(uChromaP.x, uChromaP.x + uChromaP.y + 0.001, d);
      float sp = 1.0 - smoothstep(uChromaP.x, uChromaP.x + uChromaP.y + 0.25, d);
      c.rgb = mix(c.rgb, vec3(luma(c.rgb)), sp * uChromaP.z);
      c.a *= ka;
    }
    // Personas sin croma: máscara precalculada (rojo = probabilidad de persona), con el mismo mapeo que el medio.
    // x: 0 no, 1 fondo transparente, 2 fondo de color, 3 fondo desenfocado · y: suavizado · z: desenfoque · w: contorno.
    if (uPerson.x > 0.5) {
      float raw = texture2D(uPersonTex, su).r;
      float soft = clamp(uPerson.y, 0.0, 1.0) * 0.45 + 0.02;
      float pm = smoothstep(0.5 - soft, 0.5 + soft, raw);
      float ring = 0.0;
      if (uPerson.w > 0.001) {
        float rad = uPerson.w * 0.04;
        float acc = 0.0;
        for (int k = 0; k < 8; k++) {
          float ang = float(k) * 0.785398;
          vec2 o = vec2(cos(ang), sin(ang)) * rad;
          acc += smoothstep(0.5 - soft, 0.5 + soft, texture2D(uPersonTex, clamp(su + o, 0.0, 1.0)).r);
        }
        ring = clamp(acc / 8.0 * 1.6, 0.0, 1.0) * (1.0 - pm);
      }
      if (uPerson.x < 1.5) {
        c.a *= pm;
      } else if (uPerson.x < 2.5) {
        c.rgb = mix(uPColor.rgb, c.rgb, pm);
      } else {
        c.rgb = mix(blurTex(su, 0.01 + uPerson.z * 0.05).rgb, c.rgb, pm);
      }
      if (ring > 0.0) {
        c.rgb = mix(c.rgb, uPOut.rgb, ring);
        c.a = max(c.a, ring);
      }
    }
    c.rgb = grade(c.rgb, l);
  }
  float maskA = 1.0;
  float mt = uMaskP.x;
  if (mt > 0.5) {
    float asp = uSize.x / max(uSize.y, 0.001);
    vec2 d = l - uMask.xy;
    d.x *= asp;
    float cs = cos(uMaskP.y);
    float sn = sin(uMaskP.y);
    d = vec2(d.x * cs + d.y * sn, -d.x * sn + d.y * cs);
    vec2 hs = max(vec2(uMask.z * asp, uMask.w) * 0.5, vec2(0.0005));
    float f = max(uMaskP.z, 0.0005);
    if (mt < 1.5) {
      maskA = 1.0 - smoothstep(-f, f, d.y);
    } else if (mt < 2.5) {
      float dist = (length(d / hs) - 1.0) * min(hs.x, hs.y);
      maskA = 1.0 - smoothstep(-f, f, dist);
    } else {
      vec2 dd = abs(d) - hs;
      maskA = 1.0 - smoothstep(-f, f, max(dd.x, dd.y));
    }
    if (uMaskP.w > 0.5) maskA = 1.0 - maskA;
  }
  c.rgb = mix(c.rgb, tint.rgb, tint.a);
  c.rgb *= 1.0 - uLutP.w;
  float a = c.a * inside * maskA;
  if (uGenP.z > 0.5 && mode < 0.5) {
    // Fondo: el propio vídeo desenfocado rellenando el lienzo.
    vec2 fu = clamp(q * uFill.xy + uFill.zw, 0.0, 1.0);
    vec3 bgc = grade(blurTex(fu, 0.02 + uGenP.w * 0.07).rgb, q) * 0.78;
    c.rgb = mix(bgc, c.rgb, a);
    a = 1.0;
  }
  float bm = uMode.z;
  if (bm > 0.5) {
    c.rgb = clamp(c.rgb, 0.0, 1.0);
    if (bm < 2.5 || (bm > 3.5 && bm < 4.5)) {
      // Pantalla / sumar / aclarar: el brillo decide cuánto se añade sobre lo de abajo.
      float m = max(c.r, max(c.g, c.b));
      c.rgb = c.rgb / max(m, 0.001);
      a *= bm > 1.5 && bm < 2.5 ? min(m * 1.35, 1.0) : m;
    } else {
      // Multiplicar / oscurecer: la oscuridad decide cuánto se oscurece lo de abajo.
      float y2 = luma(c.rgb);
      c.rgb *= y2 * 0.6;
      a *= 1.0 - y2;
    }
  }
  gl_FragColor = vec4(clamp(c.rgb, 0.0, 1.0), clamp(a * uMode.y * trAlpha * inCanvas, 0.0, 1.0));
}
"""

    /** Pasada de efecto: [body] es el cuerpo de `vec4 fx(vec2 uv)`. uP0/uP1 = parámetros, uFx = (activo, tiempo, -, -). */
    fun fxFragment(body: String): String = HEADER + """
uniform vec2 uRes;
uniform vec4 uP0;
uniform vec4 uP1;
uniform vec4 uFx;
#define uTime uFx.y
vec4 T(vec2 uv) { return texture2D(uTex, clamp(uv, 0.0, 1.0)); }
vec4 fx(vec2 uv) {
$body
}
void main() {
  if (uFx.x < 0.5) {
    gl_FragColor = texture2D(uTex, vUv);
  } else {
    vec4 r = fx(vUv);
    gl_FragColor = mix(texture2D(uTex, vUv), r, clamp(uFx.z, 0.0, 1.0));
  }
}
"""
}
