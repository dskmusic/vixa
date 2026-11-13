package com.dskmusic.vixa.data.online

import com.dskmusic.vixa.BuildConfig

/**
 * Claves de API por defecto de los recursos online y enlaces de cada servicio.
 *
 * Las claves NO están en el código: se leen al compilar de local.properties (api.pixabay=…) o de variables de entorno
 * (VIXA_API_PIXABAY=…) y llegan aquí por BuildConfig. Si faltan, quedan vacías y Ajustes → Personalizar APIs las pide
 * (con enlace a la web y a la página de la API de cada servicio). El usuario puede sobrescribirlas en esa pantalla
 * (se guardan en AppSettings.apiKeys); "Restablecer" vuelve a las de compilación.
 */
object ApiConfig {
    const val PIXABAY = "pixabay"
    const val PEXELS = "pexels"
    const val UNSPLASH = "unsplash"
    const val WALLHAVEN = "wallhaven"
    const val COVERR = "coverr"
    const val GIPHY = "giphy"
    const val KLIPY = "klipy"
    const val JAMENDO = "jamendo"
    const val FREESOUND = "freesound"

    val DEFAULT_KEYS: Map<String, String> = mapOf(
        PIXABAY to BuildConfig.KEY_PIXABAY,
        PEXELS to BuildConfig.KEY_PEXELS,
        UNSPLASH to BuildConfig.KEY_UNSPLASH,
        WALLHAVEN to BuildConfig.KEY_WALLHAVEN,
        COVERR to BuildConfig.KEY_COVERR,
        GIPHY to BuildConfig.KEY_GIPHY,
        KLIPY to BuildConfig.KEY_KLIPY,
        JAMENDO to BuildConfig.KEY_JAMENDO,
        FREESOUND to BuildConfig.KEY_FREESOUND,
    )

    /** (página principal del servicio, página donde se consigue la clave / documentación de su API). Comprueba que siguen vigentes. */
    val LINKS: Map<String, Pair<String, String>> = mapOf(
        PIXABAY to ("https://pixabay.com" to "https://pixabay.com/api/docs/"),
        PEXELS to ("https://www.pexels.com" to "https://www.pexels.com/api/"),
        UNSPLASH to ("https://unsplash.com" to "https://unsplash.com/developers"),
        WALLHAVEN to ("https://wallhaven.cc" to "https://wallhaven.cc/settings/account"),
        COVERR to ("https://coverr.co" to "https://api.coverr.co/docs/"),
        GIPHY to ("https://giphy.com" to "https://developers.giphy.com/"),
        KLIPY to ("https://klipy.com" to "https://klipy.com/developers"),
        JAMENDO to ("https://www.jamendo.com" to "https://developer.jamendo.com/v3.0"),
        FREESOUND to ("https://freesound.org" to "https://freesound.org/apiv2/apply/"),
    )

    /** Tiempo de validez de la caché de respuestas por proveedor (ms). Unsplash (50 pet./hora) se cachea más. */
    fun cacheTtlMs(provider: String): Long = when (provider) {
        UNSPLASH -> 24L * 60 * 60 * 1000
        PEXELS, GIPHY, KLIPY -> 3L * 60 * 60 * 1000
        else -> 60L * 60 * 1000
    }
}
