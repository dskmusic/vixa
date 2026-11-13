package com.dskmusic.vixa.ai

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** Grupos del panel "Funciones con IA". */
enum class AiGroup { SPEECH, AUDIO, VIDEO }

/** Un archivo descargable de un modelo. Con [extract] es un .tar.bz2 que se descomprime en la carpeta del modelo. */
class ModelFile(val name: String, val url: String, val size: Long, val sha256: String, val extract: Boolean = false)

/**
 * Modelo descargable. El registro es solo datos: añadir un modelo es añadir una entrada a [Models.all].
 * [minRamMb] es la memoria total del dispositivo por debajo de la cual la función se muestra desactivada.
 */
class ModelDef(
    val id: String, val name: String, val group: AiGroup, val files: List<ModelFile>, val minRamMb: Int = 0,
    /** Voces: idioma ("es"), país ("ES"), calidad del modelo y número de hablantes (los multihablante traen varios). */
    val lang: String = "", val region: String = "", val quality: String = "", val speakers: Int = 1,
) {
    val size: Long get() = files.sumOf { it.size }
}

object Models {
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    private const val MP = "https://storage.googleapis.com/mediapipe-models"
    private const val TOKENS_SHA = "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"

    const val VAD = "vad"
    const val DENOISE_HQ = "denoise_dpdfnet"
    const val DENOISE_FAST = "denoise_gtcrn"
    const val SEPARATE = "uvr_mdx"
    const val SEPARATE_HQ = "uvr_mdx_hq"
    const val SELFIE = "selfie"
    const val SELFIE_HQ = "selfie_multiclass"
    const val FACE_MESH = "face_landmarker"
    const val MAGIC_TOUCH = "magic_touch"

    fun whisper(quality: String): String = "whisper_" + (if (quality == "tiny" || quality == "small") quality else "base")

    private fun whisperDef(q: String, label: String, enc: Long, encSha: String, dec: Long, decSha: String, ram: Int): ModelDef {
        val base = "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-$q/resolve/main"
        return ModelDef(
            "whisper_$q", "Whisper $label", AiGroup.SPEECH,
            listOf(
                ModelFile("encoder.onnx", "$base/$q-encoder.int8.onnx", enc, encSha),
                ModelFile("decoder.onnx", "$base/$q-decoder.int8.onnx", dec, decSha),
                ModelFile("tokens.txt", "$base/$q-tokens.txt", 816730, TOKENS_SHA),
            ),
            ram,
        )
    }

    /** Voz Piper: [code] = idioma_PAÍS-nombre-calidad. Cada voz es un .tar.bz2 con el modelo, tokens.txt y espeak-ng-data. */
    private fun voice(code: String, name: String, size: Long, sha: String, speakers: Int = 1): ModelDef {
        val region = code.substring(3, 5)
        val label = "Piper · $name (" + java.util.Locale("", region).getDisplayCountry(java.util.Locale.ENGLISH) + ")"
        return ModelDef(
            "tts_$code", label, AiGroup.SPEECH, listOf(ModelFile("$code.tar.bz2", "$GH/tts-models/vits-piper-$code.tar.bz2", size, sha, extract = true)), 2500,
            lang = code.substring(0, 2), region = region, quality = code.substringAfterLast('-'), speakers = speakers,
        )
    }

    /** Voces de texto a voz (publicaciones oficiales de sherpa-onnx; tamaños y SHA-256 comprobados el 2026-11-12). */
    private val VOICES: List<ModelDef> = listOf(
        voice("es_ES-davefx-medium", "Davefx", 67184952, "a3f6beb54a9cb893279f72978a22f807a4d9fc9c7848157b524d5cc7b7f58b22"),
        voice("es_ES-sharvard-medium", "Sharvard", 80318184, "b30a7a83df0518f0ee1c7039506648cade99f1f9b498fc49ed2ced2e2536bb5a", 2),
        voice("es_ES-carlfm-x_low", "Carlfm", 26520563, "15585c5add2ab1915ce69e8c966c7c9fb0b6afb21f9b92f18110fda5a4787f99"),
        voice("es_ES-miro-high", "Miro", 67162898, "15c7593c9e6eed29b48df983708672091c6ce3556485f351c2eb917cf3e5c2db"),
        voice("es_MX-ald-medium", "Ald", 67196497, "c60cf7bc853eb01a875fba0756523fecf9d9f46c763b19785d223fc981fb4fb6"),
        voice("es_MX-claude-high", "Claude", 67207890, "ec33fb689c248fe64810aab564cba97babf0f506672cfd404928d46e751a4721"),
        voice("es_AR-daniela-high", "Daniela", 115562134, "71cbf6b7f646ab74f3c51336151abef41e6c54467ac929ffdb19ed706a07dd7b"),
        voice("en_US-amy-medium", "Amy", 67223746, "9a5d1fc497f85e8022b785bff5f8105203b1e33099ee6265203efc70b0cb0264"),
        voice("en_US-ryan-medium", "Ryan", 67213100, "c546af78b6395b4e7c4ce1ed899438b64426a362f5d4ec5fecd090ded9ad7505"),
        voice("en_US-lessac-medium", "Lessac", 67230653, "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e"),
        voice("en_US-kristin-medium", "Kristin", 67259230, "c2206f572df2956c50b1ae3367eebce3853c663e890cba8048cd62b1e4dbe6c7"),
        voice("en_US-hfc_female-medium", "HFC female", 67228166, "3fffdceb0c65bd9415a085d09c3cb88cc82f9d74a6ca453f8ce7fc5eaee81ff8"),
        voice("en_US-hfc_male-medium", "HFC male", 67214049, "76388f84acfca8ba5c0ed1636a26ada14c598abd52e76f110d4756fe326fc5f2"),
        voice("en_US-joe-medium", "Joe", 67169394, "261b903a1fb2ce621e2b5b14233cd41223c3b9f9590f896c11daee4df22d9bd3"),
        voice("en_US-kathleen-low", "Kathleen", 67118360, "3dd0adaf077e19de32876608c3ac3d4a0a46a6f06310cc7a5633b3b47d762cde"),
        voice("en_GB-alan-medium", "Alan", 67220121, "a48d4017da0f77668b27bed63fe6e04dd64c6397e1fadad4f460efb0ef7c9012"),
        voice("en_GB-jenny_dioco-medium", "Jenny", 67225842, "a0888024569bafbefc05a4b48ddf8419d8dbbf3205f4af37cf7c6f1a87cc20c5"),
        voice("en_GB-alba-medium", "Alba", 67212349, "fcd45962906933eec4431d3688f7d74aaac8713c87c6717f91fd3b23463aa1a1"),
        voice("en_GB-southern_english_female-medium", "Southern female", 80275090, "cea2dcbe8a8e2a44a4bab6824e10dc38fcd5cf84458e8b3f3e5be1c5fb804512"),
        voice("en_GB-cori-medium", "Cori", 67257412, "49c9a5361bbdd95d7ca9687c4de11e5908481f65e7c7c368960df79949fdac2b"),
        voice("fr_FR-siwis-medium", "Siwis", 67207459, "375909aa30842b3a4efa10b1beb1d761af792960ae6873b4d53889f96c66195b"),
        voice("fr_FR-tom-medium", "Tom", 67202955, "3d8258ef8466b5d5f2cc46c06e1415d3d01c9cbaeed8b6d5b3d152073672472c"),
        voice("fr_FR-upmc-medium", "UPMC", 80422639, "e9830a331a16f6cc5ef3116a287065e015d3495c3f56b974889a266da7f89a7f", 2),
        voice("de_DE-thorsten-medium", "Thorsten", 67214254, "50487d9c95fdf2191f31d2588569381063ba1591dcd4c7d4bdd30f12b2191714"),
        voice("de_DE-eva_k-x_low", "Eva K", 26521242, "daec8658456d8e5ec714972f2e5a71f8a84d58b974db396f4eaeace0df06455a"),
        voice("de_DE-ramona-low", "Ramona", 67084795, "79f985c10f86c4d58205b519abc488aa65eb713d54665ff729880a02e2625a42"),
        voice("it_IT-paola-medium", "Paola", 67221173, "7541f75778afa164e44e34baaef63befad7698595df26a95ca944b63ef1a16b4"),
        voice("it_IT-riccardo-x_low", "Riccardo", 26496614, "8ad0791558ae2b3e96054fc5756ae3ae2f6457fb1765bc01f43bbab85e0a7021"),
        voice("pt_BR-faber-medium", "Faber", 67183065, "7add3f923ad6bc25ca8a192805fd1a64d1b3893e4611c4a9719545a825039a83"),
        voice("pt_BR-cadu-medium", "Cadu", 67207562, "aba78157d4b89acc17ddef15a70f4b2474f4c189d4de6035002ac7fec9d5d303"),
        voice("pt_PT-tugao-medium", "Tugão", 67195014, "6b4f0a7bfc957bef26d6ac0703a8fa1ba659c8ab2df8b294f20206551859070c"),
        voice("ca_ES-upc_ona-medium", "Ona", 67223188, "b6c50f22e13cfc72ce51c496d22b987adf6704047f6d342968197b4b0b3127a5"),
        voice("nl_NL-alex-medium", "Alex", 67172584, "52be645a8617eda0e592c604eb67f419fcb09376310c3ec7fc781beeb8e84bb6"),
        voice("pl_PL-gosia-medium", "Gosia", 67211182, "75bd34dcbdc4dd98d763954756b4b34b4208100497c836381542e4d73dcefa9c"),
        voice("pl_PL-darkman-medium", "Darkman", 67215635, "444727aa46eb6db645a2bc88fe73868e4bd7596b7f56ca39fad5ef53c41210d4"),
        voice("ru_RU-denis-medium", "Denis", 67190991, "efa4c18e0b5e32b81d1b6df36b9d312831e5d545200e27848ef926a4cd930300"),
    )

    /** URLs y sumas comprobadas el 2026-11-12 (publicaciones oficiales de cada proyecto). */
    val all: List<ModelDef> = listOf(
        ModelDef(VAD, "Silero VAD", AiGroup.SPEECH, listOf(ModelFile("silero_vad.onnx", "$GH/asr-models/silero_vad.onnx", 643854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"))),
        whisperDef("tiny", "tiny", 12937772, "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434", 89855401, "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925", 2500),
        whisperDef("base", "base", 29120534, "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11", 130672026, "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d", 3500),
        whisperDef("small", "small", 112442483, "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9", 262226114, "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee", 5500),
        ModelDef(DENOISE_HQ, "DPDFNet 48 kHz", AiGroup.AUDIO, listOf(ModelFile("model.onnx", "$GH/speech-enhancement-models/dpdfnet2_48khz_hr.onnx", 10596848, "0b399f8a58dc4d70d8cd97541f5c39869406145193b957d00a03b66070944928")), 2500),
        ModelDef(DENOISE_FAST, "GTCRN 16 kHz", AiGroup.AUDIO, listOf(ModelFile("model.onnx", "$GH/speech-enhancement-models/gtcrn_simple.onnx", 535638, "e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534"))),
        ModelDef(SEPARATE, "UVR MDX-Net", AiGroup.AUDIO, listOf(ModelFile("model.onnx", "$GH/source-separation-models/UVR_MDXNET_9482.onnx", 29704738, "9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d")), 3500),
        ModelDef(SEPARATE_HQ, "UVR MDX-Net Voc FT", AiGroup.AUDIO, listOf(ModelFile("model.onnx", "$GH/source-separation-models/UVR-MDX-NET-Voc_FT.onnx", 66762795, "e411182ce2c53541fefdcc99a8f46f2fe03978eb22038b9497c1d9d95a00fad4")), 5500),
        ModelDef(SELFIE, "MediaPipe Selfie Segmenter", AiGroup.VIDEO, listOf(ModelFile("model.tflite", "$MP/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite", 249537, "191ac9529ae506ee0beefa6b2c945a172dab9d07d1e802a290a4e4038226658b"))),
        ModelDef(SELFIE_HQ, "MediaPipe Selfie Multiclass", AiGroup.VIDEO, listOf(ModelFile("model.tflite", "$MP/image_segmenter/selfie_multiclass_256x256/float32/latest/selfie_multiclass_256x256.tflite", 16371837, "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0")), 2500),
        ModelDef(FACE_MESH, "MediaPipe Face Landmarker", AiGroup.VIDEO, listOf(ModelFile("model.task", "$MP/face_landmarker/face_landmarker/float16/latest/face_landmarker.task", 3758596, "64184e229b263107bc2b804c6625db1341ff2bb731874b0bcc2fe6544e0bc9ff"))),
        ModelDef(MAGIC_TOUCH, "MediaPipe Interactive Segmenter", AiGroup.VIDEO, listOf(ModelFile("model.tflite", "$MP/interactive_segmenter/magic_touch/float32/latest/magic_touch.tflite", 6227884, "e24338a717c1b7ad8d159666677ef400babb7f33b8ad60c4d96db4ecf694cd25"))),
    ) + VOICES

    fun get(id: String): ModelDef? = all.firstOrNull { it.id == id }

    /** Voces ordenadas: primero las que hablan español, luego inglés y después el resto por idioma y nombre. */
    val voices: List<ModelDef> = all.filter { it.id.startsWith("tts_") }.sortedWith(
        compareBy<ModelDef>({ when (it.lang) { "es" -> 0; "en" -> 1; else -> 2 } }, { java.util.Locale(it.lang).getDisplayLanguage(java.util.Locale.ENGLISH) }, { it.region }, { it.name }),
    )

    /** Frase corta de muestra en el idioma de cada voz. */
    fun sample(lang: String): String = when (lang) {
        "es" -> "Hola, esta es una muestra de mi voz."
        "fr" -> "Bonjour, ceci est un échantillon de ma voix."
        "de" -> "Hallo, das ist eine Probe meiner Stimme."
        "it" -> "Ciao, questo è un esempio della mia voce."
        "pt" -> "Olá, esta é uma amostra da minha voz."
        "ca" -> "Hola, aquesta és una mostra de la meva veu."
        "nl" -> "Hallo, dit is een voorbeeld van mijn stem."
        "pl" -> "Cześć, to jest próbka mojego głosu."
        "ru" -> "Привет, это образец моего голоса."
        else -> "Hello, this is a sample of my voice."
    }
}

enum class ModelStatus { MISSING, DOWNLOADING, READY, ERROR }

class ModelState(val status: ModelStatus, val progress: Float = 0f, val message: String = "")

/**
 * Almacén de modelos en files/ai/models/<id>/ (sobrevive a las actualizaciones de la app). Un modelo está
 * listo cuando existe su marca ".ok", que solo se escribe tras verificar todos sus archivos.
 */
class ModelStore(context: Context, private val http: OkHttpClient, private val settings: SettingsStore) {
    val context: Context = context.applicationContext
    val root = File(context.filesDir, "ai/models").apply { mkdirs() }

    private val _states = MutableStateFlow(Models.all.associate { it.id to ModelState(if (isReady(it.id)) ModelStatus.READY else ModelStatus.MISSING) })
    val states: StateFlow<Map<String, ModelState>> = _states

    fun dir(id: String): File = File(root, id)
    fun file(id: String, name: String): File = File(dir(id), name)
    fun isReady(id: String): Boolean = File(dir(id), ".ok").exists()

    /** Primer archivo con la extensión dada dentro del modelo (los .tar.bz2 de Piper traen su propia carpeta). */
    fun find(id: String, ext: String): File? = dir(id).walkTopDown().firstOrNull { it.isFile && it.extension == ext }
    fun findDir(id: String, name: String): File? = dir(id).walkTopDown().firstOrNull { it.isDirectory && it.name == name }

    fun usedBytes(id: String): Long = dir(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun set(id: String, s: ModelState) { _states.value = _states.value + (id to s) }

    fun delete(id: String) {
        dir(id).deleteRecursively()
        set(id, ModelState(ModelStatus.MISSING))
    }

    /** Direcciones del archivo assets/ai_model_urls.json (clave "modelo/archivo" -> origen y alternativa). */
    private val manifest: Map<String, Pair<String, String>> by lazy {
        try {
            val files = Json.parseToJsonElement(context.assets.open("ai_model_urls.json").bufferedReader().use { it.readText() }).jsonObject["files"]?.jsonObject
            files?.mapValues { (_, v) ->
                val o = v.jsonObject
                (o["url"]?.jsonPrimitive?.content ?: "") to (o["fallback"]?.jsonPrimitive?.content ?: "")
            } ?: emptyMap()
        } catch (e: Throwable) {
            ErrorLog.log("AI/manifest", e)
            emptyMap()
        }
    }

    /** Origen por defecto: el del JSON y, si falta, el del registro. */
    fun defaultUrl(def: ModelDef, f: ModelFile): String = manifest[def.id + "/" + f.name]?.first?.takeIf { it.isNotBlank() } ?: f.url

    /** Alternativa por defecto (vacía = sin alternativa). */
    fun defaultFallback(def: ModelDef, f: ModelFile): String = manifest[def.id + "/" + f.name]?.second ?: ""

    /** Los cambios hechos en Ajustes mandan sobre el JSON. Claves: "modelo/archivo" y "modelo/archivo#fallback". */
    fun urlOf(def: ModelDef, f: ModelFile): String = settings.value.aiUrls[def.id + "/" + f.name]?.takeIf { it.isNotBlank() } ?: defaultUrl(def, f)

    fun fallbackOf(def: ModelDef, f: ModelFile): String = settings.value.aiUrls[def.id + "/" + f.name + "#fallback"] ?: defaultFallback(def, f)

    /** Motivo (recurso de texto) por el que este dispositivo no puede usar el modelo, o 0 si puede. */
    fun unsupported(id: String): Int {
        val def = Models.get(id) ?: return R.string.ai_err_unknown_model
        if (Build.SUPPORTED_ABIS.none { it == "arm64-v8a" || it == "x86_64" }) return R.string.ai_err_abi
        if (def.minRamMb > 0) {
            val mi = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
            // totalMem excluye la memoria reservada por el sistema: un equipo "de 4 GB" informa ~3,6 GB.
            if (mi.totalMem / (1024 * 1024) < def.minRamMb * 85L / 100L) return R.string.ai_err_memory
        }
        return 0
    }

    private fun onWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Descarga (reanudable) y verifica todos los archivos del modelo. Lanza una excepción con un mensaje
     * legible si falla; la cancelación de la corrutina deja los .part para reanudar después.
     */
    suspend fun download(id: String, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val def = Models.get(id) ?: throw IOException(context.getString(R.string.ai_err_unknown_model))
        if (isReady(id)) { set(id, ModelState(ModelStatus.READY)); return@withContext }
        try {
            if (settings.value.aiWifiOnly && !onWifi()) throw IOException(context.getString(R.string.ai_err_wifi))
            val d = dir(id).apply { mkdirs() }
            // Un archivo comprimido necesita además el espacio de su contenido.
            val need = def.files.sumOf { if (it.extract) it.size * 3 else it.size } + 30_000_000L
            val free = StatFs(d.path).availableBytes
            if (free < need) throw IOException(context.getString(R.string.err_no_space, need / 1_000_000, free / 1_000_000))
            val total = def.size.coerceAtLeast(1)
            var done = 0L
            set(id, ModelState(ModelStatus.DOWNLOADING))
            for (f in def.files) {
                val target = File(d, f.name)
                val base = done
                if (!(target.exists() && target.length() == f.size && !f.extract)) {
                    // Primero el origen; si falla (red, HTTP o suma incorrecta) se prueba la alternativa configurada.
                    val primary = urlOf(def, f)
                    val urls = listOf(primary, fallbackOf(def, f)).filter { it.isNotBlank() }.distinct()
                    var failure: Throwable? = null
                    var fetched = false
                    for ((n, u) in urls.withIndex()) {
                        try {
                            if (n > 0) File(d, f.name + ".part").delete() // una alternativa empieza de cero
                            fetch(u, f, target) { got ->
                                val pr = ((base + got).toFloat() / total).coerceIn(0f, 1f)
                                set(id, ModelState(ModelStatus.DOWNLOADING, pr))
                                onProgress(pr)
                            }
                            fetched = true
                            break
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            failure = e
                            ErrorLog.log("AI/download $id " + (if (n == 0) "(origen)" else "(alternativa)"), e)
                        }
                    }
                    if (!fetched) throw failure ?: IOException("download failed")
                    if (f.extract) { untar(target, d); target.delete() }
                }
                done += f.size
            }
            File(d, ".ok").writeText(def.files.joinToString("\n") { it.name + " " + it.sha256 })
            set(id, ModelState(ModelStatus.READY))
        } catch (e: kotlinx.coroutines.CancellationException) {
            set(id, ModelState(ModelStatus.MISSING))
            throw e
        } catch (e: Throwable) {
            ErrorLog.log("AI/model $id", e)
            set(id, ModelState(ModelStatus.ERROR, message = e.message ?: e.javaClass.simpleName))
            throw e
        }
    }

    /** Descarga a "<nombre>.part" continuando lo que hubiera, comprueba tamaño y SHA-256 y renombra. */
    private suspend fun fetch(url: String, f: ModelFile, target: File, onBytes: (Long) -> Unit) {
        val part = File(target.parentFile, target.name + ".part")
        var have = if (part.exists()) part.length() else 0L
        if (have > f.size) { part.delete(); have = 0 }
        if (have < f.size) {
            val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP " + resp.code + " · " + url)
                // 206 = el servidor continúa donde lo dejamos; 200 = manda el archivo entero otra vez.
                val append = resp.code == 206 && have > 0
                if (!append) have = 0
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    val input = resp.body.byteStream()
                    var lastTick = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have - lastTick > 400_000L) { lastTick = have; onBytes(have) }
                    }
                }
            }
        }
        if (part.length() != f.size) { part.delete(); throw IOException(context.getString(R.string.ai_err_integrity, f.name)) }
        if (f.sha256.isNotEmpty()) {
            val md = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(f.sha256, ignoreCase = true)) { part.delete(); throw IOException(context.getString(R.string.ai_err_integrity, f.name)) }
        }
        onBytes(f.size)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("rename " + target.name)
    }

    private suspend fun untar(archive: File, into: File) {
        val root = into.canonicalPath + File.separator
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 16))).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val e = tar.nextEntry ?: break
                val out = File(into, e.name)
                // Nunca escribir fuera de la carpeta del modelo.
                if (!out.canonicalPath.startsWith(root)) continue
                if (e.isDirectory) { out.mkdirs(); continue }
                if (e.isSymbolicLink || e.isLink) continue
                out.parentFile?.mkdirs()
                FileOutputStream(out).use { tar.copyTo(it, 1 shl 16) }
            }
        }
    }
}
