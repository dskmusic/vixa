package com.dskmusic.vixa.ui.editor

import com.dskmusic.vixa.engine.Registry
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.allZones
import com.dskmusic.vixa.model.withZone
import com.dskmusic.vixa.model.zoneKeyIndex
import com.dskmusic.vixa.model.hasKeyframes
import com.dskmusic.vixa.model.kf
import com.dskmusic.vixa.model.withKeyframe

/**
 * Acceso uniforme por nombre a las propiedades animables de un clip (las mismas claves que usan los
 * keyframes y el motor): transformación, volumen, color, máscara, zona y parámetros de efectos.
 */
fun Clip.baseProp(name: String): Float = when (name) {
    "x" -> transform.x
    "y" -> transform.y
    "scale" -> transform.scale
    "sx" -> transform.sx
    "sy" -> transform.sy
    "rotation" -> transform.rotation
    "opacity" -> transform.opacity
    "volume" -> volume
    "brightness" -> color.brightness
    "contrast" -> color.contrast
    "saturation" -> color.saturation
    "exposure" -> color.exposure
    "temperature" -> color.temperature
    "tint" -> color.tint
    "highlights" -> color.highlights
    "shadows" -> color.shadows
    "vibrance" -> color.vibrance
    "fade" -> color.fade
    "hue" -> color.hue
    "lightness" -> color.lightness
    "sharpen" -> color.sharpen
    "vignette" -> color.vignette
    "grain" -> color.grain
    "mask.cx" -> mask.cx
    "mask.cy" -> mask.cy
    "mask.w" -> mask.w
    "mask.h" -> mask.h
    else -> if (name.startsWith("fx:")) {
        val parts = name.split(':')
        val inst = effects.firstOrNull { it.id == parts.getOrNull(1) }
        val key = parts.getOrNull(2) ?: ""
        inst?.params?.get(key) ?: Registry.effect(inst?.defId ?: "")?.params?.firstOrNull { it.key == key }?.def ?: 0f
    } else {
        val z = allZones.getOrNull(zoneKeyIndex(name))
        when (name.substringAfter('.')) { "cx" -> z?.cx; "cy" -> z?.cy; "w" -> z?.w; "h" -> z?.h; else -> null } ?: 0f
    }
}

fun Clip.withBaseProp(name: String, v: Float): Clip = when (name) {
    "x" -> copy(transform = transform.copy(x = v))
    "y" -> copy(transform = transform.copy(y = v))
    "scale" -> copy(transform = transform.copy(scale = v))
    "sx" -> copy(transform = transform.copy(sx = v))
    "sy" -> copy(transform = transform.copy(sy = v))
    "rotation" -> copy(transform = transform.copy(rotation = v))
    "opacity" -> copy(transform = transform.copy(opacity = v))
    "volume" -> copy(volume = v)
    "brightness" -> copy(color = color.copy(brightness = v))
    "contrast" -> copy(color = color.copy(contrast = v))
    "saturation" -> copy(color = color.copy(saturation = v))
    "exposure" -> copy(color = color.copy(exposure = v))
    "temperature" -> copy(color = color.copy(temperature = v))
    "tint" -> copy(color = color.copy(tint = v))
    "highlights" -> copy(color = color.copy(highlights = v))
    "shadows" -> copy(color = color.copy(shadows = v))
    "vibrance" -> copy(color = color.copy(vibrance = v))
    "fade" -> copy(color = color.copy(fade = v))
    "hue" -> copy(color = color.copy(hue = v))
    "lightness" -> copy(color = color.copy(lightness = v))
    "sharpen" -> copy(color = color.copy(sharpen = v))
    "vignette" -> copy(color = color.copy(vignette = v))
    "grain" -> copy(color = color.copy(grain = v))
    "mask.cx" -> copy(mask = mask.copy(cx = v))
    "mask.cy" -> copy(mask = mask.copy(cy = v))
    "mask.w" -> copy(mask = mask.copy(w = v))
    "mask.h" -> copy(mask = mask.copy(h = v))
    else -> if (name.startsWith("fx:")) {
        val parts = name.split(':')
        copy(effects = effects.map { if (it.id == parts.getOrNull(1)) it.copy(params = it.params + ((parts.getOrNull(2) ?: "") to v)) else it })
    } else {
        val zi = zoneKeyIndex(name)
        val z = allZones.getOrNull(zi)
        if (z == null) this
        else withZone(zi, when (name.substringAfter('.')) { "cx" -> z.copy(cx = v); "cy" -> z.copy(cy = v); "w" -> z.copy(w = v); "h" -> z.copy(h = v); else -> z })
    }
}

/** Valor actual (con keyframes) en el instante local. */
fun Clip.prop(name: String, localUs: Long): Float = kf(name, localUs, baseProp(name))

/** Cambia el valor: si la propiedad está animada crea/actualiza el keyframe del instante; si no, el valor base. */
fun Clip.setProp(name: String, localUs: Long, v: Float): Clip =
    if (hasKeyframes(name)) withKeyframe(name, localUs.coerceIn(0, durationUs), v) else withBaseProp(name, v)
