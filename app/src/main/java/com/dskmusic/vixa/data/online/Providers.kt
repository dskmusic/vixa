package com.dskmusic.vixa.data.online

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable
enum class OnlineType { IMAGE, ILLUSTRATION, VIDEO, GIF, MUSIC, SFX }

@Serializable
data class OnlineItem(
    val id: String,
    val provider: String,
    val type: OnlineType,
    val title: String = "",
    val author: String = "",
    val thumbUrl: String = "",
    /** Vista previa: imagen grande, vídeo ligero o audio. */
    val previewUrl: String = "",
    val downloadUrl: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationSec: Float = 0f,
    val ext: String = "",
    /** Ruta local una vez descargado. */
    val localPath: String = "",
) {
    val key: String get() = "$provider:$id"
}

class Query(
    val type: OnlineType,
    val text: String = "",
    val page: Int = 1,
    /** "es" o "en". */
    val lang: String = "en",
    val nsfw: Boolean = true,
    /** "", "landscape", "portrait" o "square". */
    val orientation: String = "",
    /** "" o nombre de color admitido por la API (red, blue…). */
    val color: String = "",
    /** 0 = cualquiera; 1 = corto, 2 = medio, 3 = largo. */
    val duration: Int = 0,
    /** GIF: buscar stickers (fondo transparente) en vez de GIF. */
    val stickers: Boolean = false,
    /** Música (Jamendo): etiquetas separadas por espacios (basta con que coincida alguna), solo instrumental y velocidades (verylow, low, medium, high, veryhigh). */
    val tags: String = "",
    val instrumental: Boolean = false,
    val speed: String = "",
)

class Request(val url: HttpUrl, val headers: Map<String, String> = emptyMap())

/** Interfaz común de los proveedores: añadir uno nuevo es implementar esto y registrarlo en [Providers.all]. */
interface Provider {
    val id: String
    val name: String
    val types: Set<OnlineType>
    /** Ofrece contenido sin texto de búsqueda (populares, tendencias…). */
    val hasDefault: Boolean get() = true
    /** Etiquetas sugeridas (en inglés; todas las API las entienden). */
    fun suggestions(type: OnlineType): List<String> = emptyList()
    fun request(q: Query, key: String): Request
    fun parse(q: Query, root: JsonElement): List<OnlineItem>
}

// --- Ayudas de lectura de JSON ---
internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonObject?.o(k: String): JsonObject? = this?.get(k) as? JsonObject
internal fun JsonObject?.a(k: String): List<JsonElement> = (this?.get(k) as? JsonArray) ?: emptyList()
internal fun JsonObject?.s(k: String): String = (this?.get(k) as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content ?: ""
internal fun JsonObject?.i(k: String): Int = s(k).toDoubleOrNull()?.toInt() ?: 0
internal fun JsonObject?.f(k: String): Float = s(k).toFloatOrNull() ?: 0f

private fun extOf(url: String, fallback: String): String {
    val e = url.substringBefore('?').substringAfterLast('.', "").lowercase()
    return if (e.length in 2..4 && e.all { it.isLetterOrDigit() }) e else fallback
}

private val IMAGE_TAGS = listOf("nature", "city", "people", "food", "travel", "animals", "technology", "abstract", "sky", "texture")
private val VIDEO_TAGS = listOf("nature", "city", "ocean", "people", "aerial", "night", "abstract", "technology", "fire", "clouds")

object Providers {

    private object Pixabay : Provider {
        override val id = ApiConfig.PIXABAY
        override val name = "Pixabay"
        override val types = setOf(OnlineType.IMAGE, OnlineType.ILLUSTRATION, OnlineType.VIDEO)
        override fun suggestions(type: OnlineType) = if (type == OnlineType.VIDEO) VIDEO_TAGS else IMAGE_TAGS

        override fun request(q: Query, key: String): Request {
            val video = q.type == OnlineType.VIDEO
            val b = (if (video) "https://pixabay.com/api/videos/" else "https://pixabay.com/api/").toHttpUrl().newBuilder()
                .addQueryParameter("key", key)
                .addQueryParameter("page", q.page.toString())
                .addQueryParameter("per_page", "30")
                .addQueryParameter("safesearch", if (q.nsfw) "false" else "true")
                .addQueryParameter("lang", q.lang)
                .addQueryParameter("order", "popular")
            if (q.text.isNotBlank()) b.addQueryParameter("q", q.text.take(100))
            if (!video) {
                b.addQueryParameter("image_type", if (q.type == OnlineType.ILLUSTRATION) "illustration" else "photo")
                when (q.orientation) { "landscape" -> b.addQueryParameter("orientation", "horizontal"); "portrait" -> b.addQueryParameter("orientation", "vertical") }
                if (q.color.isNotEmpty()) b.addQueryParameter("colors", q.color)
            }
            return Request(b.build())
        }

        override fun parse(q: Query, root: JsonElement): List<OnlineItem> = root.obj().a("hits").mapNotNull { e ->
            val h = e.obj() ?: return@mapNotNull null
            if (q.type == OnlineType.VIDEO) {
                val v = h.o("videos")
                val best = v.o("large")?.takeIf { it.s("url").isNotEmpty() } ?: v.o("medium") ?: return@mapNotNull null
                val small = v.o("small")?.takeIf { it.s("url").isNotEmpty() } ?: v.o("tiny") ?: best
                OnlineItem(h.s("id"), id, q.type, h.s("tags"), h.s("user"), small.s("thumbnail").ifEmpty { best.s("thumbnail") },
                    small.s("url"), best.s("url"), best.i("width"), best.i("height"), h.f("duration"), "mp4")
            } else {
                val url = h.s("largeImageURL")
                OnlineItem(h.s("id"), id, q.type, h.s("tags"), h.s("user"), h.s("webformatURL").ifEmpty { h.s("previewURL") },
                    url, url, h.i("imageWidth"), h.i("imageHeight"), 0f, extOf(url, "jpg"))
            }
        }
    }

    private object Pexels : Provider {
        override val id = ApiConfig.PEXELS
        override val name = "Pexels"
        override val types = setOf(OnlineType.IMAGE, OnlineType.VIDEO)
        override fun suggestions(type: OnlineType) = if (type == OnlineType.VIDEO) VIDEO_TAGS else IMAGE_TAGS

        override fun request(q: Query, key: String): Request {
            val video = q.type == OnlineType.VIDEO
            val base = when {
                video && q.text.isBlank() -> "https://api.pexels.com/videos/popular"
                video -> "https://api.pexels.com/videos/search"
                q.text.isBlank() -> "https://api.pexels.com/v1/curated"
                else -> "https://api.pexels.com/v1/search"
            }
            val b = base.toHttpUrl().newBuilder().addQueryParameter("page", q.page.toString()).addQueryParameter("per_page", "30")
            if (q.text.isNotBlank()) {
                b.addQueryParameter("query", q.text)
                b.addQueryParameter("locale", if (q.lang == "es") "es-ES" else "en-US")
                if (q.orientation.isNotEmpty()) b.addQueryParameter("orientation", q.orientation)
                if (q.color.isNotEmpty() && !video) b.addQueryParameter("color", q.color)
            }
            return Request(b.build(), mapOf("Authorization" to key))
        }

        override fun parse(q: Query, root: JsonElement): List<OnlineItem> =
            if (q.type == OnlineType.VIDEO) root.obj().a("videos").mapNotNull { e ->
                val v = e.obj() ?: return@mapNotNull null
                val files = v.a("video_files").mapNotNull { it.obj() }.filter { it.s("file_type") == "video/mp4" }
                if (files.isEmpty()) return@mapNotNull null
                // Descarga: la mayor hasta 1080p de lado menor; vista previa: la menor.
                val sorted = files.sortedBy { minOf(it.i("width"), it.i("height")) }
                val best = sorted.lastOrNull { minOf(it.i("width"), it.i("height")) <= 1080 } ?: sorted.first()
                OnlineItem(v.s("id"), id, q.type, "", v.o("user").s("name"), v.s("image"), sorted.first().s("link"), best.s("link"),
                    best.i("width"), best.i("height"), v.f("duration"), "mp4")
            } else root.obj().a("photos").mapNotNull { e ->
                val p = e.obj() ?: return@mapNotNull null
                val src = p.o("src")
                OnlineItem(p.s("id"), id, q.type, p.s("alt"), p.s("photographer"), src.s("medium"), src.s("large"), src.s("large2x").ifEmpty { src.s("original") },
                    p.i("width"), p.i("height"), 0f, "jpg")
            }
    }

    private object Unsplash : Provider {
        override val id = ApiConfig.UNSPLASH
        override val name = "Unsplash"
        override val types = setOf(OnlineType.IMAGE)
        /** Modo demo (50 peticiones/hora): solo responde a búsquedas con texto. */
        override val hasDefault = false

        override fun request(q: Query, key: String): Request {
            val b = "https://api.unsplash.com/search/photos".toHttpUrl().newBuilder()
                .addQueryParameter("query", q.text)
                .addQueryParameter("page", q.page.toString())
                .addQueryParameter("per_page", "30")
                .addQueryParameter("lang", q.lang)
                .addQueryParameter("content_filter", if (q.nsfw) "low" else "high")
            when (q.orientation) { "landscape", "portrait" -> b.addQueryParameter("orientation", q.orientation); "square" -> b.addQueryParameter("orientation", "squarish") }
            if (q.color.isNotEmpty()) b.addQueryParameter("color", if (q.color == "grayscale") "black_and_white" else q.color)
            return Request(b.build(), mapOf("Authorization" to "Client-ID $key", "Accept-Version" to "v1"))
        }

        override fun parse(q: Query, root: JsonElement): List<OnlineItem> = root.obj().a("results").mapNotNull { e ->
            val p = e.obj() ?: return@mapNotNull null
            val u = p.o("urls")
            OnlineItem(p.s("id"), id, q.type, p.s("alt_description").ifEmpty { p.s("description") }, p.o("user").s("name"),
                u.s("small"), u.s("regular"), u.s("full").ifEmpty { u.s("regular") }, p.i("width"), p.i("height"), 0f, "jpg")
        }
    }

    private object Wallhaven : Provider {
        override val id = ApiConfig.WALLHAVEN
        override val name = "Wallhaven"
        override val types = setOf(OnlineType.IMAGE)

        override fun request(q: Query, key: String): Request {
            val b = "https://wallhaven.cc/api/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("apikey", key)
                .addQueryParameter("page", q.page.toString())
                .addQueryParameter("categories", "111")
                .addQueryParameter("purity", if (q.nsfw) "111" else "100")
                .addQueryParameter("sorting", if (q.text.isBlank()) "toplist" else "relevance")
            if (q.text.isNotBlank()) b.addQueryParameter("q", q.text)
            when (q.orientation) { "landscape" -> b.addQueryParameter("ratios", "landscape"); "portrait" -> b.addQueryParameter("ratios", "portrait"); "square" -> b.addQueryParameter("ratios", "1x1") }
            return Request(b.build())
        }

        override fun parse(q: Query, root: JsonElement): List<OnlineItem> = root.obj().a("data").mapNotNull { e ->
            val w = e.obj() ?: return@mapNotNull null
            val path = w.s("path")
            if (path.isEmpty()) return@mapNotNull null
            val t = w.o("thumbs")
            OnlineItem(w.s("id"), id, q.type, w.s("resolution"), "", t.s("small").ifEmpty { t.s("large") }, t.s("original").ifEmpty { path }, path,
                w.i("dimension_x"), w.i("dimension_y"), 0f, extOf(path, "jpg"))
        }
    }

    private object Coverr : Provider {
        override val id = ApiConfig.COVERR
        override val name = "Coverr"
        override val types = setOf(OnlineType.VIDEO)
        override fun suggestions(type: OnlineType) = VIDEO_TAGS

        override fun request(q: Query, key: String): Request {
            val b = "https://api.coverr.co/videos".toHttpUrl().newBuilder()
                .addQueryParameter("api_key", key)
                .addQueryParameter("page", (q.page - 1).coerceAtLeast(0).toString())
                .addQueryParameter("page_size", "20")
                .addQueryParameter("urls", "true")
                .addQueryParameter("sort", "popular")
            if (q.text.isNotBlank()) b.addQueryParameter("query", q.text)
            return Request(b.build())
        }

        override fun parse(q: Query, root: JsonElement): List<OnlineItem> = root.obj().a("hits").mapNotNull { e ->
            val v = e.obj() ?: return@mapNotNull null
            val u = v.o("urls")
            val mp4 = u.s("mp4").ifEmpty { u.s("mp4_download") }
            if (mp4.isEmpty()) return@mapNotNull null
            val vertical = v.s("is_vertical") == "true"
            if ((q.orientation == "portrait" && !vertical) || (q.orientation == "landscape" && vertical)) return@mapNotNull null
            OnlineItem(v.s("id"), id, q.type, v.s("title"), "", v.s("thumbnail").ifEmpty { v.s("poster") }, u.s("mp4_preview").ifEmpty { mp4 }, mp4,
                v.i("max_width"), v.i("max_height"), v.f("duration"), "mp4")
        }
    }

    private object Giphy : Provider {
        override val id = ApiConfig.GIPHY
        override val name = "GIPHY"
        override val types = setOf(OnlineType.GIF)
        override fun suggestions(type: OnlineType) = listOf("reactions", "funny", "love", "party", "arrows", "subscribe", "emoji", "effects", "text", "animals")

        override fun request(q: Query, key: String): Request {
            val kind = if (q.stickers) "stickers" else "gifs"
            val b = ("https://api.giphy.com/v1/$kind/" + if (q.text.isBlank()) "trending" else "search").toHttpUrl().newBuilder()
                .addQueryParameter("api_key", key)
                .addQueryParameter("limit", "25")
                .addQueryParameter("offset", ((q.page - 1) * 25).toString())
                .addQueryParameter("rating", if (q.nsfw) "r" else "g")
            if (q.text.isNotBlank()) b.addQueryParameter("q", q.text.take(50)).addQueryParameter("lang", q.lang)
            return Request(b.build())
        }

        override fun parse(q: Query, root: JsonElement): List<OnlineItem> = root.obj().a("data").mapNotNull { e ->
            val g = e.obj() ?: return@mapNotNull null
            val im = g.o("images")
            val orig = im.o("original")
            val url = orig.s("url")
            if (url.isEmpty()) return@mapNotNull null
            val small = im.o("fixed_width")
            OnlineItem(g.s("id"), id, q.type, g.s("title"), g.s("username"), small.s("url").ifEmpty { url }, url, url,
                orig.i("width"), orig.i("height"), 0f, "gif")
        }
    }

    private object Klipy : Provider {
        override val id = ApiConfig.KLIPY
        override val name = "KLIPY"
        override val types = setOf(OnlineType.GIF)

        override fun request(q: Query, key: String): Request {
            val kind = if (q.stickers) "stickers" else "gifs"
            val b = "https://api.klipy.com/api/v1".toHttpUrl().newBuilder()
                .addPathSegment(key).addPathSegment(kind).addPathSegment(if (q.text.isBlank()) "trending" else "search")
                .addQueryParameter("page", q.page.toString())
                .addQueryParameter("per_page", "24")
                .addQueryParameter("customer_id", "vixa-user")
                .addQueryParameter("locale", if (q.lang == "es") "es" else "us")
                .addQueryParameter("content_filter", if (q.nsfw) "off" else "high")
            if (q.text.isNotBlank()) b.addQueryParameter("q", q.text)
            return Request(b.build())
        }

        override fun parse(q: Query, root: JsonElement): List<OnlineItem> = root.obj().o("data").a("data").mapNotNull { e ->
            val g = e.obj() ?: return@mapNotNull null
            val file = g.o("file")
            val big = (file.o("hd") ?: file.o("md")).o("gif")
            val url = big.s("url")
            if (url.isEmpty()) return@mapNotNull null
            val small = (file.o("sm") ?: file.o("md")).o("gif")
            OnlineItem(g.s("slug").ifEmpty { g.s("id") }, id, q.type, g.s("title"), "", small.s("url").ifEmpty { url }, url, url,
                big.i("width"), big.i("height"), 0f, "gif")
        }
    }

    private object Jamendo : Provider {
        override val id = ApiConfig.JAMENDO
        override val name = "Jamendo"
        override val types = setOf(OnlineType.MUSIC)
        override fun suggestions(type: OnlineType) = listOf("pop", "rock", "electronic", "hiphop", "jazz", "classical", "ambient", "lounge", "cinematic", "happy", "epic")

        override fun request(q: Query, key: String): Request {
            val b = "https://api.jamendo.com/v3.0/tracks/".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", key)
                .addQueryParameter("format", "json")
                .addQueryParameter("limit", "30")
                .addQueryParameter("offset", ((q.page - 1) * 30).toString())
                .addQueryParameter("audioformat", "mp32")
                .addQueryParameter("imagesize", "200")
                .addQueryParameter("order", if (q.text.isBlank() && q.tags.isBlank()) "popularity_week" else "relevance")
            if (q.text.isNotBlank()) b.addQueryParameter("search", q.text)
            if (q.tags.isNotBlank()) b.addQueryParameter("fuzzytags", q.tags).addQueryParameter("boost", "popularity_month")
            if (q.instrumental) b.addQueryParameter("vocalinstrumental", "instrumental")
            if (q.speed.isNotBlank()) b.addQueryParameter("speed", q.speed)
            when (q.duration) { 1 -> b.addQueryParameter("durationbetween", "0_90"); 2 -> b.addQueryParameter("durationbetween", "90_240"); 3 -> b.addQueryParameter("durationbetween", "240_3600") }
            return Request(b.build())
        }

        override fun parse(q: Query, root: JsonElement): List<OnlineItem> = root.obj().a("results").mapNotNull { e ->
            val t = e.obj() ?: return@mapNotNull null
            val audio = t.s("audio")
            if (audio.isEmpty()) return@mapNotNull null
            val dl = if (t.s("audiodownload_allowed") != "false" && t.s("audiodownload").isNotEmpty()) t.s("audiodownload") else audio
            OnlineItem(t.s("id"), id, q.type, t.s("name"), t.s("artist_name"), t.s("image"), audio, dl, 0, 0, t.f("duration"), "mp3")
        }
    }

    private object Freesound : Provider {
        override val id = ApiConfig.FREESOUND
        override val name = "Freesound"
        override val types = setOf(OnlineType.SFX, OnlineType.MUSIC)
        override fun suggestions(type: OnlineType) =
            if (type == OnlineType.MUSIC) listOf("loop", "piano", "guitar", "drums", "ambient", "synth")
            else listOf("whoosh", "click", "impact", "pop", "notification", "applause", "rain", "glitch", "riser", "laugh", "footsteps")

        override fun request(q: Query, key: String): Request {
            val music = q.type == OnlineType.MUSIC
            val filter = when {
                music -> (when (q.duration) { 1 -> "duration:[20 TO 90]"; 2 -> "duration:[90 TO 240]"; 3 -> "duration:[240 TO *]"; else -> "duration:[20 TO *]" }) + " tag:music"
                else -> when (q.duration) { 1 -> "duration:[0 TO 3]"; 2 -> "duration:[3 TO 12]"; 3 -> "duration:[12 TO 60]"; else -> "duration:[0 TO 45]" }
            }
            val b = "https://freesound.org/apiv2/search/text/".toHttpUrl().newBuilder()
                .addQueryParameter("token", key)
                .addQueryParameter("query", q.text)
                .addQueryParameter("page", q.page.toString())
                .addQueryParameter("page_size", "30")
                .addQueryParameter("filter", filter)
                .addQueryParameter("fields", "id,name,duration,username,previews,images")
                .addQueryParameter("sort", if (q.text.isBlank()) "downloads_desc" else "score")
            return Request(b.build())
        }

        override fun parse(q: Query, root: JsonElement): List<OnlineItem> = root.obj().a("results").mapNotNull { e ->
            val s = e.obj() ?: return@mapNotNull null
            // Previsualización de alta calidad (el original exige OAuth2 de usuario).
            val hq = s.o("previews").s("preview-hq-mp3")
            if (hq.isEmpty()) return@mapNotNull null
            OnlineItem(s.s("id"), id, q.type, s.s("name"), s.s("username"), s.o("images").s("waveform_m"), hq, hq, 0, 0, s.f("duration"), "mp3")
        }
    }

    val all: List<Provider> = listOf(Pixabay, Pexels, Unsplash, Wallhaven, Coverr, Giphy, Klipy, Jamendo, Freesound)

    fun get(id: String): Provider? = all.firstOrNull { it.id == id }
    fun forType(type: OnlineType): List<Provider> = all.filter { type in it.types }
}
