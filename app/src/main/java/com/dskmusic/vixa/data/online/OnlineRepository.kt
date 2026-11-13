package com.dskmusic.vixa.data.online

import android.content.Context
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.data.SettingsStore
import com.dskmusic.vixa.data.VixaJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class SearchResult(
    val items: List<OnlineItem>,
    /** Nombres de las fuentes que fallaron o agotaron su cuota (se omiten sin romper el resto). */
    val failed: List<String>,
    val hasMore: Boolean,
)

class ProviderError(val code: Int, message: String) : IOException(message)

/**
 * Recursos online: lanza las búsquedas de todos los proveedores en paralelo, intercala los resultados,
 * cachea las respuestas en disco y gestiona favoritos y descargas.
 */
class OnlineRepository(context: Context, private val http: OkHttpClient, private val settings: SettingsStore) {
    private val cacheDir = File(context.cacheDir, "online").apply { mkdirs() }
    private val dataDir = File(context.filesDir, "online").apply { mkdirs() }
    val downloadsDir = File(dataDir, "downloads").apply { mkdirs() }
    private val favFile = File(dataDir, "favorites.json")
    private val dlFile = File(dataDir, "downloads.json")
    private val listSerializer = ListSerializer(OnlineItem.serializer())

    /** Proveedores en pausa por cuota agotada o error: id → instante (ms) hasta el que se omiten. */
    private val cooldown = ConcurrentHashMap<String, Long>()

    private val _favorites = MutableStateFlow(readList(favFile))
    val favorites: StateFlow<List<OnlineItem>> = _favorites
    private val _downloads = MutableStateFlow(readList(dlFile).filter { File(it.localPath).exists() })
    val downloads: StateFlow<List<OnlineItem>> = _downloads

    private fun readList(f: File): List<OnlineItem> = try {
        if (f.exists()) VixaJson.decodeFromString(listSerializer, f.readText()) else emptyList()
    } catch (e: Throwable) {
        emptyList()
    }

    private fun writeList(f: File, list: List<OnlineItem>) {
        try { f.writeText(VixaJson.encodeToString(listSerializer, list)) } catch (e: Throwable) { ErrorLog.log("Online", e) }
    }

    fun keyFor(provider: String): String = settings.value.apiKeys[provider]?.takeIf { it.isNotBlank() } ?: ApiConfig.DEFAULT_KEYS[provider] ?: ""

    fun isEnabled(provider: String): Boolean = provider !in settings.value.disabledProviders && keyFor(provider).isNotBlank()

    private fun cacheFile(url: String): File = File(cacheDir, Integer.toHexString(url.hashCode()) + "_" + url.length + ".json")

    private suspend fun fetch(p: Provider, q: Query, key: String, useCache: Boolean = true): List<OnlineItem> = withContext(Dispatchers.IO) {
        val req = p.request(q, key)
        val url = req.url.toString()
        val cf = cacheFile(url)
        val body: String = if (useCache && cf.exists() && System.currentTimeMillis() - cf.lastModified() < ApiConfig.cacheTtlMs(p.id)) {
            cf.readText()
        } else {
            val rb = okhttp3.Request.Builder().url(req.url).header("User-Agent", "Vixa/1.0 (Android)")
            for ((k, v) in req.headers) rb.header(k, v)
            http.newCall(rb.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw ProviderError(resp.code, "HTTP " + resp.code + " " + text.take(160))
                if (useCache) try { cf.writeText(text) } catch (e: Throwable) { }
                text
            }
        }
        p.parse(q, VixaJson.parseToJsonElement(body))
    }

    /**
     * Busca en todas las fuentes activas que ofrezcan [q].type (o solo en [only] si no está vacío).
     * Sin texto se carga el contenido por defecto de cada API (populares, tendencias…).
     */
    suspend fun search(q: Query, only: Set<String> = emptySet()): SearchResult = coroutineScope {
        val now = System.currentTimeMillis()
        val candidates = Providers.forType(q.type).filter { p ->
            isEnabled(p.id) && (only.isEmpty() || p.id in only) && (q.text.isNotBlank() || p.hasDefault)
        }
        val failed = ArrayList<String>()
        val jobs = candidates.map { p ->
            async {
                if ((cooldown[p.id] ?: 0L) > now) return@async p to null
                val items = try {
                    withTimeoutOrNull(20_000) { fetch(p, q, keyFor(p.id)) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: ProviderError) {
                    // Cuota agotada o clave rechazada: se deja descansar a la fuente.
                    if (e.code == 429 || e.code == 403 || e.code == 401) cooldown[p.id] = now + 10 * 60_000L
                    ErrorLog.log("Online/" + p.name, e.message ?: "")
                    null
                } catch (e: Throwable) {
                    ErrorLog.log("Online/" + p.name, e.message ?: e.javaClass.simpleName)
                    null
                }
                p to items
            }
        }
        val results = jobs.awaitAll()
        for ((p, items) in results) if (items == null) failed.add(p.name)
        // Intercalado: un elemento de cada fuente por turno.
        val lists = results.mapNotNull { it.second }.filter { it.isNotEmpty() }
        val out = ArrayList<OnlineItem>()
        val seen = HashSet<String>()
        var i = 0
        var added = true
        while (added) {
            added = false
            for (l in lists) {
                if (i < l.size) {
                    added = true
                    if (seen.add(l[i].key)) out.add(l[i])
                }
            }
            i++
        }
        SearchResult(out, failed, lists.any { it.size >= 15 })
    }

    /** Prueba una clave: devuelve null si funciona o el mensaje de error. */
    suspend fun testKey(provider: String, key: String): String? {
        val p = Providers.get(provider) ?: return "?"
        val q = Query(p.types.first(), if (p.hasDefault) "" else "nature")
        return try {
            withTimeoutOrNull(20_000) { fetch(p, q, key, useCache = false) } ?: return "timeout"
            cooldown.remove(provider)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.message ?: e.javaClass.simpleName
        }
    }

    fun isFavorite(item: OnlineItem): Boolean = _favorites.value.any { it.key == item.key }

    fun toggleFavorite(item: OnlineItem) {
        val list = if (isFavorite(item)) _favorites.value.filter { it.key != item.key } else listOf(item) + _favorites.value
        _favorites.value = list
        writeList(favFile, list)
    }

    fun localFile(item: OnlineItem): File = File(downloadsDir, item.provider + "_" + item.id.replace(Regex("[^A-Za-z0-9_-]"), "_") + "." + item.ext.ifEmpty { "bin" })

    fun isDownloaded(item: OnlineItem): Boolean = localFile(item).let { it.exists() && it.length() > 0 }

    /** Descarga con progreso y caché local: si ya está descargado se devuelve directamente. */
    suspend fun download(item: OnlineItem, onProgress: (Float) -> Unit = {}): File? = withContext(Dispatchers.IO) {
        val target = localFile(item)
        if (target.exists() && target.length() > 0) { remember(item, target); return@withContext target }
        val tmp = File(target.path + ".part")
        try {
            val rb = okhttp3.Request.Builder().url(item.downloadUrl).header("User-Agent", "Vixa/1.0 (Android)")
            if (item.provider == ApiConfig.PEXELS) rb.header("Authorization", keyFor(ApiConfig.PEXELS))
            http.newCall(rb.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw ProviderError(resp.code, "HTTP " + resp.code)
                val body = resp.body ?: throw IOException("empty")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            remember(item, target)
            target
        } catch (e: kotlinx.coroutines.CancellationException) {
            tmp.delete()
            throw e
        } catch (e: Throwable) {
            ErrorLog.log("Download/" + item.provider, e)
            tmp.delete()
            null
        }
    }

    private fun remember(item: OnlineItem, file: File) {
        if (_downloads.value.any { it.key == item.key }) return
        val list = listOf(item.copy(localPath = file.path)) + _downloads.value
        _downloads.value = list
        writeList(dlFile, list)
    }

    fun removeDownload(item: OnlineItem) {
        localFile(item).delete()
        val list = _downloads.value.filter { it.key != item.key }
        _downloads.value = list
        writeList(dlFile, list)
    }

    fun clearResponseCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        cooldown.clear()
    }

    fun clearDownloads() {
        downloadsDir.listFiles()?.forEach { it.delete() }
        _downloads.value = emptyList()
        writeList(dlFile, emptyList())
    }
}
