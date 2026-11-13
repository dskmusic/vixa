package com.dskmusic.vixa.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dskmusic.vixa.model.ExportSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK, OLED }

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Int = 0xFF0A84FF.toInt(),
    /** "" = automático (idioma del sistema), "es" o "en". */
    val language: String = "",
    val tutorialSeen: Boolean = false,
    // Edición
    val defAspectW: Int = 9,
    val defAspectH: Int = 16,
    val defShortSide: Int = 1080,
    val defFps: Int = 30,
    val imageDurationMs: Int = 3000,
    val colorDurationMs: Int = 3000,
    val transitionDurationMs: Int = 500,
    val snap: Boolean = true,
    val haptics: Boolean = true,
    val autosave: Boolean = true,
    val previewSide: Int = 540,
    val useProxies: Boolean = true,
    val showGrid: Boolean = false,
    /** Color del área de trabajo alrededor del lienzo de la vista previa. */
    val workspaceColor: Int = 0xFF3A3A44.toInt(),
    val showSafeZones: Boolean = false,
    // Exportación
    val export: ExportSettings = ExportSettings(),
    val exportDir: String = "",
    val exportPresets: List<ExportSettings> = emptyList(),
    // Online
    val nsfw: Boolean = true,
    val apiKeys: Map<String, String> = emptyMap(),
    val disabledProviders: Set<String> = emptySet(),
    // Sesión: proyecto abierto en el editor (para restaurar si el sistema mata la app).
    val openProjectId: String = "",
    val openPositionUs: Long = 0,
    val recentMedia: List<String> = emptyList(),
    val recentColors: List<Int> = emptyList(),
    /** Pequeño rebote en barras/listas con más contenido fuera de vista (una vez por proyecto y zona). */
    val scrollHint: Boolean = true,
    /** Al recorrer la línea de tiempo, el cabezal se imanta a los cortes de los clips. */
    val playheadSnap: Boolean = true,
    /** Elementos marcados como favoritos (clave "tipo:id"): salen los primeros de su categoría. */
    val favorites: Set<String> = emptySet(),
    val hintsSeen: Set<String> = emptySet(),
    /** Imagen de muestra de efectos, filtros y animaciones: 0 = la de serie; otro valor = la elegida (la hora en que se cambió, para refrescar las vistas previas). */
    val fxSampleVersion: Long = 0L,
    /** El proyecto demo ya se preparó (o se descartó) en el primer arranque: no se vuelve a crear solo. */
    val demoSeeded: Boolean = false,
    /** Imagen de muestra elegida: número de una fx_sample_N de serie ("1" por defecto) o "custom" (la propia). */
    val fxSample: String = "1",
    /** Imagen distinta (al azar) cada vez que se abre el editor. */
    val fxSampleRandom: Boolean = false,
    // IA
    /** Descargar modelos solo con Wi-Fi. */
    val aiWifiOnly: Boolean = true,
    /** Calidad por defecto de los subtítulos automáticos: "tiny", "base" o "small". */
    val aiSubQuality: String = "base",
    /** Idioma por defecto de la transcripción: "" = detección automática, "es" o "en". */
    val aiLanguage: String = "",
    /** URLs de descarga cambiadas por el usuario (clave "modelo/archivo"). */
    val aiUrls: Map<String, String> = emptyMap(),
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vixa")

val VixaJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = true
    coerceInputValues = true
}

class SettingsStore(private val context: Context, private val scope: CoroutineScope) {
    private val key = stringPreferencesKey("settings")

    private val _flow = MutableStateFlow(
        // Lectura inicial síncrona (archivo diminuto): evita un parpadeo de tema/idioma al arrancar.
        runBlocking {
            try {
                context.dataStore.data.first()[key]?.let { VixaJson.decodeFromString(AppSettings.serializer(), it) } ?: AppSettings()
            } catch (e: Throwable) {
                ErrorLog.log("Settings", e)
                AppSettings()
            }
        },
    )
    val flow: StateFlow<AppSettings> = _flow
    val value: AppSettings get() = _flow.value

    fun update(f: (AppSettings) -> AppSettings) {
        val next = f(_flow.value)
        if (next == _flow.value) return
        _flow.value = next
        scope.launch {
            try {
                context.dataStore.edit { it[key] = VixaJson.encodeToString(AppSettings.serializer(), _flow.value) }
            } catch (e: Throwable) {
                ErrorLog.log("Settings", e)
            }
        }
    }
}
