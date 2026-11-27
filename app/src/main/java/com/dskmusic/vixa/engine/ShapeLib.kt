package com.dskmusic.vixa.engine

import android.content.Context
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser
import org.json.JSONArray
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Biblioteca de formas con buscador es/en (assets/wordart/shapes.json): Phosphor (MIT), Tabler (MIT),
 * Lucide (ISC) y Material Symbols (Apache 2.0). Las formas clasicas de [Shapes] siguen disponibles.
 */
object ShapeLib {
    class Item(
        val id: String,
        val cat: String,
        val tokens: List<String>,
        val nameTokens: Set<String>,
        val d: String?,
        val w: Float,
    )

    val categories = listOf("classic", "animals", "nature", "food", "music", "sport", "symbols", "shapes", "people", "transport", "objects", "emoji")

    private var appContext: Context? = null

    /** Llamar desde Application.onCreate: carga el indice en segundo plano. */
    fun init(context: Context) {
        appContext = context.applicationContext
        Thread { all }.start()
    }

    val all: List<Item> by lazy { load() }
    private val byId: Map<String, Item> by lazy { all.associateBy { it.id } }
    private val defs = ConcurrentHashMap<String, ShapeDef>()

    private fun legacy(): List<Item> = Shapes.all.map {
        val n = it.id.replace('_', ' ')
        Item(it.id, "classic", tokenize("$n classic clasica clasicas"), tokenize(n).toSet(), null, 0f)
    }

    private fun load(): List<Item> {
        val ctx = appContext ?: return legacy()
        val lib = runCatching {
            val text = ctx.assets.open("wordart/shapes.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val n = o.optString("n")
                Item(
                    o.getString("i"), o.getString("k"),
                    tokenize("$n ${o.optString("e")} ${o.optString("s")}"),
                    tokenize(n).toSet(), o.getString("d"), o.optDouble("w", 0.0).toFloat(),
                )
            }
        }.getOrDefault(emptyList())
        return legacy() + lib
    }

    /** Definicion dibujable de una forma de la biblioteca (null si el id no existe). */
    fun def(id: String): ShapeDef? {
        defs[id]?.let { return it }
        val item = byId[id] ?: return null
        val d = item.d ?: return null
        val (p, _) = runCatching { normalize(PathParser.createPathFromPathData(d), item.w) }.getOrNull() ?: return null
        return ShapeDef(id, stroke = item.w > 0f) { out -> out.addPath(p) }.also { defs[id] = it }
    }

    private fun normalize(src: Path, strokeW: Float): Pair<Path, Float> {
        val b = RectF()
        src.computeBounds(b, true)
        val dim = max(b.width(), b.height()) + strokeW
        if (dim <= 0f) return src to 0f
        val s = 1f / dim
        val m = Matrix().apply {
            postTranslate(-b.centerX(), -b.centerY())
            postScale(s, s)
            postTranslate(0.5f, 0.5f)
        }
        val out = Path(src)
        out.transform(m)
        return out to strokeW * s
    }

    fun catLabel(id: String?, es: Boolean): String = when (id) {
        null -> if (es) "Todas" else "All"
        "classic" -> if (es) "Clásicas" else "Classic"
        "animals" -> if (es) "Animales" else "Animals"
        "nature" -> if (es) "Naturaleza" else "Nature"
        "food" -> if (es) "Comida" else "Food"
        "music" -> if (es) "Música" else "Music"
        "sport" -> if (es) "Deporte" else "Sports"
        "symbols" -> if (es) "Símbolos" else "Symbols"
        "shapes" -> if (es) "Formas" else "Shapes"
        "people" -> if (es) "Personas" else "People"
        "transport" -> if (es) "Transporte" else "Transport"
        "objects" -> if (es) "Objetos" else "Objects"
        else -> if (es) "Emojis" else "Emoji"
    }

    // ------------------------------------------------------------------ busqueda

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun stem(t: String): String = when {
        t.length > 4 && t.endsWith("ces") -> t.dropLast(3) + "z"
        t.length > 4 && t.endsWith("es") -> t.dropLast(2)
        t.length > 3 && t.endsWith("s") -> t.dropLast(1)
        else -> t
    }

    private fun tokenize(s: String): List<String> =
        norm(s).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.map(::stem).distinct()

    private fun lev(a: String, b: String, limit: Int): Int {
        if (abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun score(q: String, s: Item): Float {
        var best = 0f
        val limit = if (q.length >= 8) 2 else 1
        for (t in s.tokens) {
            val v = when {
                t == q -> 4f
                q.length >= 2 && t.startsWith(q) -> 3f
                q.length >= 4 && lev(q, t, limit) <= limit -> 1.5f
                else -> 0f
            }
            if (v > best) best = v
        }
        if (best > 0f && q in s.nameTokens) best += 1f
        return best
    }

    /** Busca en es/en sin tildes, con plurales y errores leves. */
    fun search(query: String, cat: String?): List<Item> {
        val base = all.filter { cat == null || it.cat == cat }
        val qs = tokenize(query)
        if (qs.isEmpty()) return base
        return base.mapNotNull { s ->
            var total = 0f
            for (q in qs) {
                val v = score(q, s)
                if (v == 0f) return@mapNotNull null
                total += v
            }
            s to total
        }.sortedByDescending { it.second }.map { it.first }
    }
}
