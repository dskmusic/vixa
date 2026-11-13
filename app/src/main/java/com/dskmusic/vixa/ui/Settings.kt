package com.dskmusic.vixa.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.dskmusic.vixa.engine.FxSamples
import com.dskmusic.vixa.engine.FxThumbs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import com.dskmusic.vixa.ui.editor.FilterThumbs
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.os.LocaleListCompat
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.R
import com.dskmusic.vixa.data.AppSettings
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.data.ThemeMode
import com.dskmusic.vixa.data.online.ApiConfig
import com.dskmusic.vixa.data.online.Providers
import com.dskmusic.vixa.engine.Exporter
import com.dskmusic.vixa.media.ImageCache
import com.dskmusic.vixa.media.Thumbs
import com.dskmusic.vixa.ui.editor.ASPECTS
import com.dskmusic.vixa.ui.picker.FileBrowserDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

const val DSK_URL = "https://www.dskmusic.com/dsk_dev_redirect.php"
const val GITHUB_URL = "https://github.com/dskmusic/vixa"

private fun dirSize(f: File?): Long = if (f == null || !f.exists()) 0L else if (f.isFile) f.length() else (f.listFiles() ?: emptyArray()).sumOf { dirSize(it) }

private fun clearDir(f: File?) { f?.listFiles()?.forEach { it.deleteRecursively() } }

fun applyLanguage(tag: String) {
    AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        content()
        HorizontalDivider(Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun Label(text: String) = Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)

@Composable
fun SettingsScreen(container: AppContainer, onBack: () -> Unit, onTutorial: () -> Unit, onHelp: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by container.settings.flow.collectAsState()
    fun upd(f: (AppSettings) -> AppSettings) = container.settings.update(f)
    var accentPicker by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(false) }
    var sizes by remember { mutableStateOf(listOf(0L, 0L, 0L)) }
    var sizeTick by remember { mutableIntStateOf(0) }
    // Imagen de muestra de las vistas previas de efectos, filtros y animaciones (la elegida, sin sortear de nuevo al abrir Ajustes).
    remember { FxSamples.refresh(context.applicationContext, s.fxSample, s.fxSampleRandom, false); 0 }
    val pickSample = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            if (FxThumbs.setCustom(context.applicationContext, uri)) {
                // La propia pasa a ser la elegida (el contenido pudo cambiar aunque la clave sea la misma).
                FxSamples.dropCache(); FilterThumbs.clear()
                FxSamples.refresh(context.applicationContext, FxSamples.CUSTOM, s.fxSampleRandom, true)
                upd { it.copy(fxSample = FxSamples.CUSTOM, fxSampleVersion = System.currentTimeMillis()) }
            }
            else android.widget.Toast.makeText(context, R.string.fx_sample_error, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(sizeTick) {
        sizes = withContext(Dispatchers.IO) { listOf(dirSize(context.cacheDir), dirSize(container.proxies.dir), dirSize(container.online.downloadsDir)) }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 720.dp)) {
                    Group(stringResource(R.string.set_language)) {
                        ChipRow(listOf("", "es", "en"), s.language, { when (it) { "es" -> "Español"; "en" -> "English"; else -> stringResource(R.string.automatic) } }) { tag ->
                            upd { it.copy(language = tag) }
                            applyLanguage(tag)
                        }
                    }
                    Group(stringResource(R.string.set_appearance)) {
                        Label(stringResource(R.string.theme))
                        ChipRow(ThemeMode.entries, s.theme, { stringResource(when (it) { ThemeMode.SYSTEM -> R.string.automatic; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark; ThemeMode.OLED -> R.string.theme_oled }) }) { t -> upd { it.copy(theme = t) } }
                        Label(stringResource(R.string.accent_color))
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            for (c in AccentPresets) ColorDot(c, c == s.accent) { upd { it.copy(accent = c) } }
                            AssistChip({ accentPicker = true }, { Text(stringResource(R.string.custom) + " (HSL)") })
                        }
                    }
                    Group(stringResource(R.string.set_editing)) {
                        Label(stringResource(R.string.default_project))
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((w, h) in ASPECTS) AspectChip(w, h, s.defAspectW == w && s.defAspectH == h) { upd { it.copy(defAspectW = w, defAspectH = h) } }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (r in listOf(480, 720, 1080, 1440, 2160)) FilterChip(s.defShortSide == r, { upd { it.copy(defShortSide = r) } }, { Text(if (r == 2160) "4K" else r.toString() + "p") })
                            for (f in listOf(24, 25, 30, 50, 60)) FilterChip(s.defFps == f, { upd { it.copy(defFps = f) } }, { Text("$f fps") })
                        }
                        val sec: (Float) -> String = { String.format(Locale.US, "%.1f s", it) }
                        PreciseSlider(stringResource(R.string.image_duration), s.imageDurationMs / 1000f, { v -> upd { it.copy(imageDurationMs = (v * 1000).toInt()) } }, range = 0.5f..15f, default = 3f, format = sec, inputScale = 1f)
                        PreciseSlider(stringResource(R.string.color_duration), s.colorDurationMs / 1000f, { v -> upd { it.copy(colorDurationMs = (v * 1000).toInt()) } }, range = 0.5f..15f, default = 3f, format = sec, inputScale = 1f)
                        PreciseSlider(stringResource(R.string.transition_duration), s.transitionDurationMs / 1000f, { v -> upd { it.copy(transitionDurationMs = (v * 1000).toInt()) } }, range = 0.1f..3f, default = 0.5f, format = sec, inputScale = 1f)
                        SwitchRowSimple(stringResource(R.string.magnetic_snap), s.snap) { on -> upd { it.copy(snap = on) } }
                        SwitchRowSimple(stringResource(R.string.haptics), s.haptics) { on -> upd { it.copy(haptics = on) } }
                        SwitchRowSimple(stringResource(R.string.playhead_snap), s.playheadSnap) { on -> upd { it.copy(playheadSnap = on) } }
                        SwitchRowSimple(stringResource(R.string.scroll_hint), s.scrollHint) { on -> upd { it.copy(scrollHint = on, hintsSeen = if (on) emptySet() else it.hintsSeen) } }
                        SwitchRowSimple(stringResource(R.string.autosave), s.autosave) { on -> upd { it.copy(autosave = on) } }
                        SwitchRowSimple(stringResource(R.string.use_proxies), s.useProxies) { on -> upd { it.copy(useProxies = on) } }
                        Label(stringResource(R.string.workspace_color))
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in listOf(0xFF3A3A44, 0xFF2A2A30, 0xFF55555F, 0xFF808089, 0xFF000000)) ColorDot(c.toInt(), c.toInt() == s.workspaceColor) { upd { it.copy(workspaceColor = c.toInt()) } }
                        }
                        Label(stringResource(R.string.preview_quality))
                        ChipRow(listOf(360, 540, 720, 1080), s.previewSide, { it.toString() + "p" }) { q -> upd { it.copy(previewSide = q) } }
                    }
                    Group(stringResource(R.string.fx_sample_title)) {
                        Text(stringResource(R.string.fx_sample_note), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // Todas las fx_sample_N de res/drawable-nodpi (se buscan solas) y, si existe, la propia; la última casilla añade/cambia la propia.
                        val sampleKeys = remember(s.fxSampleVersion) { FxSamples.keys(context.applicationContext) }
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (k in sampleKeys) {
                                val bmp = remember(k, s.fxSampleVersion) { FxSamples.thumb(context.applicationContext, k)?.asImageBitmap() }
                                val sel = s.fxSample == k
                                val shape = RoundedCornerShape(10.dp)
                                androidx.compose.foundation.layout.Box(
                                    Modifier.size(104.dp, 66.dp).clip(shape)
                                        .border(if (sel) 2.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                                        .clickable {
                                            FxSamples.refresh(context.applicationContext, k, s.fxSampleRandom, true)
                                            upd { it.copy(fxSample = k, fxSampleVersion = System.currentTimeMillis()) }
                                        },
                                ) {
                                    if (bmp != null) androidx.compose.foundation.Image(bmp, null, Modifier.fillMaxSize().alpha(if (s.fxSampleRandom) 0.55f else 1f), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                    if (k == FxSamples.CUSTOM) Text(stringResource(R.string.fx_sample_mine), Modifier.align(Alignment.BottomStart).background(androidx.compose.ui.graphics.Color(0x99000000)).padding(horizontal = 4.dp), style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color.White)
                                }
                            }
                            androidx.compose.foundation.layout.Box(
                                Modifier.size(104.dp, 66.dp).clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                    .clickable { pickSample.launch("image/*") },
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Add, null)
                                    Text(stringResource(R.string.fx_sample_change), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        SwitchRowSimple(stringResource(R.string.fx_sample_random), s.fxSampleRandom) { on ->
                            FxSamples.refresh(context.applicationContext, s.fxSample, on, true)
                            upd { it.copy(fxSampleRandom = on, fxSampleVersion = System.currentTimeMillis()) }
                        }
                        Text(stringResource(R.string.fx_sample_random_note), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton({
                            FxSamples.refresh(context.applicationContext, "1", false, true)
                            upd { it.copy(fxSample = "1", fxSampleRandom = false, fxSampleVersion = System.currentTimeMillis()) }
                        }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), enabled = s.fxSample != "1" || s.fxSampleRandom) { Text(stringResource(R.string.fx_sample_restore)) }
                    }
                    Group(stringResource(R.string.set_export)) {
                        Label(stringResource(R.string.default_export))
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (r in listOf(480, 720, 1080, 1440, 2160)) FilterChip(s.export.shortSide == r, { upd { it.copy(export = it.export.copy(shortSide = r)) } }, { Text(if (r == 2160) "4K" else r.toString() + "p") })
                        }
                        Label(stringResource(R.string.destination))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((com.dskmusic.vixa.data.Storage.custom(s.exportDir) ?: com.dskmusic.vixa.data.Storage.root).path, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton({ folder = true }) { Text(stringResource(R.string.change)) }
                        }
                        if (s.exportPresets.isNotEmpty()) Label(stringResource(R.string.presets))
                        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (p in s.exportPresets) AssistChip({ upd { it.copy(exportPresets = it.exportPresets - p) } }, { Text(p.presetName + "  ✕") })
                        }
                    }
                    Group(stringResource(R.string.set_online)) {
                        SwitchRowSimple(stringResource(R.string.nsfw), s.nsfw) { on -> upd { it.copy(nsfw = on) } }
                        Text(stringResource(R.string.nsfw_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Group(stringResource(R.string.set_apis)) {
                        if (Providers.all.any { container.online.keyFor(it.id).isBlank() }) Text(stringResource(R.string.api_missing_note), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (p in Providers.all) ApiRow(container, s, p.id, p.name)
                        OutlinedButton({ upd { it.copy(apiKeys = emptyMap()) } }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { Text(stringResource(R.string.reset_all_keys)) }
                    }
                    Group(stringResource(R.string.set_storage)) {
                        StorageRow(stringResource(R.string.storage_cache), sizes[0]) {
                            scope.launch { withContext(Dispatchers.IO) { clearDir(context.cacheDir); Thumbs.init(context); com.dskmusic.vixa.media.Waveforms.init(context) }; Thumbs.clearMemory(); ImageCache.clear(); container.online.clearResponseCache(); sizeTick++ }
                        }
                        StorageRow(stringResource(R.string.storage_proxies), sizes[1]) { scope.launch { withContext(Dispatchers.IO) { clearDir(container.proxies.dir) }; sizeTick++ } }
                        StorageRow(stringResource(R.string.storage_downloads), sizes[2]) { scope.launch { withContext(Dispatchers.IO) { container.online.clearDownloads() }; sizeTick++ } }
                    }
                    Group(stringResource(R.string.set_help)) {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ log = true }) { Text(stringResource(R.string.error_log)) }
                            OutlinedButton(onTutorial) { Text(stringResource(R.string.view_tutorial)) }
                            OutlinedButton(onHelp) { Text(stringResource(R.string.help_full)) }
                        }
                    }
                    AiSettingsGroup(container)
                    HorizontalDivider(Modifier.padding(top = 10.dp))
                    // Enlace al proyecto en GitHub, encima de la atribución.
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Throwable) { ErrorLog.log("Link", e) } }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_github), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurface)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.github_project), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.github_project_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("github.com/dskmusic/vixa", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    // Atribución.
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text("Made with ❤️ by ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "DSK", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable {
                                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DSK_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Throwable) { ErrorLog.log("Link", e) }
                            },
                        )
                    }
                }
            }
        }
    }
    if (accentPicker) ColorPickerDialog(s.accent, false, { accentPicker = false }) { c -> upd { it.copy(accent = c) } }
    if (folder) FileBrowserDialog(stringResource(R.string.destination), folderMode = true, startDir = s.exportDir, onDismiss = { folder = false }) { d -> d.firstOrNull()?.let { path -> upd { it.copy(exportDir = path) } } }
    if (log) LogDialog { log = false }
}

@Composable
private fun StorageRow(label: String, bytes: Long, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text(formatBytes(bytes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClear) { Text(stringResource(R.string.clear)) }
    }
}

@Composable
private fun ApiRow(container: AppContainer, s: AppSettings, id: String, name: String) {
    val scope = rememberCoroutineScope()
    var key by remember(s.apiKeys[id]) { mutableStateOf(s.apiKeys[id] ?: ApiConfig.DEFAULT_KEYS[id] ?: "") }
    // Sin clave (versión compilada sin ellas): la fila sale desplegada para que se vea dónde ponerla.
    var open by remember { mutableStateOf(key.isBlank()) }
    val ctx = LocalContext.current
    fun browse(url: String) { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Throwable) { ErrorLog.log("Link", e) } }
    var result by remember { mutableStateOf("") }
    val okText = stringResource(R.string.key_ok)
    val failText = stringResource(R.string.key_failed)
    val testing = stringResource(R.string.testing)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text(Providers.get(id)?.types?.joinToString(", ") { it.name.lowercase() } ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.apiKeys.containsKey(id)) Text(stringResource(R.string.custom), Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            Switch(id !in s.disabledProviders, { on -> container.settings.update { it.copy(disabledProviders = if (on) it.disabledProviders - id else it.disabledProviders + id) } })
        }
        if (open) {
            if (key.isBlank()) Text(stringResource(R.string.api_no_key_hint), Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            ApiConfig.LINKS[id]?.let { (site, api) ->
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ browse(site) }) { Text(stringResource(R.string.api_site)) }
                    TextButton({ browse(api) }) { Text(stringResource(R.string.api_get_key)) }
                }
            }
            OutlinedTextField(key, { key = it; result = "" }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, label = { Text(stringResource(R.string.api_key)) }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ container.settings.update { it.copy(apiKeys = if (key.isBlank() || key == ApiConfig.DEFAULT_KEYS[id]) it.apiKeys - id else it.apiKeys + (id to key.trim())) } }) { Text(stringResource(R.string.save)) }
                TextButton({ result = testing; scope.launch { val err = container.online.testKey(id, key.trim()); result = if (err == null) okText else "$failText: $err" } }) { Text(stringResource(R.string.test_key)) }
                TextButton({ container.settings.update { it.copy(apiKeys = it.apiKeys - id) }; key = ApiConfig.DEFAULT_KEYS[id] ?: ""; result = "" }) { Text(stringResource(R.string.reset)) }
            }
            if (result.isNotEmpty()) Text(result, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = if (result == okText) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Registro de errores interno con copiar / compartir / borrar. */
@Composable
fun LogDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { text = withContext(Dispatchers.IO) { ErrorLog.read() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(R.string.error_log), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                }
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Vixa log", text.takeLast(200_000))) }) { Text(stringResource(R.string.copy)) }
                    OutlinedButton({
                        try {
                            val f = File(File(context.cacheDir, "share").apply { mkdirs() }, "vixa_log.txt")
                            f.writeText(text)
                            shareFile(context, f, "text/plain")
                        } catch (e: Throwable) { ErrorLog.log("Log", e) }
                    }) { Text(stringResource(R.string.share)) }
                    TextButton({ ErrorLog.clear(); tick++ }) { Text(stringResource(R.string.clear)) }
                }
                Text(if (ErrorLog.isEmpty()) text + stringResource(R.string.log_empty) else text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}
