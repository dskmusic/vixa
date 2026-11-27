package com.dskmusic.vixa.ui.editor

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dskmusic.vixa.engine.ShapeLib
import com.dskmusic.vixa.engine.Shapes
import com.dskmusic.vixa.engine.cloud.BackgroundSpec
import com.dskmusic.vixa.engine.cloud.BgKind
import com.dskmusic.vixa.engine.cloud.CloudEngine
import com.dskmusic.vixa.engine.cloud.Geom
import com.dskmusic.vixa.engine.cloud.RotMode
import com.dskmusic.vixa.engine.cloud.WordArtConfig
import com.dskmusic.vixa.engine.cloud.WordLists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.random.Random

private fun geomOf(id: String): Geom? {
    val def = Shapes.get(id) ?: return null
    val p = android.graphics.Path().also { def.build(it) }
    return Geom(p, null, if (def.stroke) 0.07f else 0f)
}

@Composable
private fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in options) FilterChip(o == selected, { onSelect(o) }, { Text(label(o)) })
    }
}

@Composable
private fun Head(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp))
}

/** Nube de palabras con forma: elige forma y palabras y se anade al video como pegatina transparente. */
@Composable
fun CloudsDialog(vm: EditorViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val es = remember { Locale.getDefault().language == "es" }
    fun t(e: String, s: String) = if (es) s else e

    var shapeId by remember { mutableStateOf(ShapeLib.all.firstOrNull { it.id.contains("heart") && it.d != null }?.id ?: ShapeLib.all.first().id) }
    var listId by remember { mutableStateOf("love") }
    var words by remember { mutableStateOf(WordLists.presets.first().text(es)) }
    var mainWord by remember { mutableStateOf("") }
    var palIdx by remember { mutableIntStateOf(0) }
    var rot by remember { mutableStateOf(RotMode.MIXED) }
    var fontIdx by remember { mutableIntStateOf(0) }
    var aspectIdx by remember { mutableIntStateOf(0) }
    var bgIdx by remember { mutableIntStateOf(0) }
    var density by remember { mutableFloatStateOf(70f) }
    var inverted by remember { mutableStateOf(false) }
    var seed by remember { mutableLongStateOf(1L) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var working by remember { mutableStateOf(false) }

    val ratios = listOf(1f, 16f / 9f, 9f / 16f)
    val bgColors = listOf(0, 0xFFFFFFFF.toInt(), 0xFF111111.toInt(), 0xFFFFF3E0.toInt(), 0xFF0D1B3E.toInt())
    val cfg = WordArtConfig(
        aspect = ratios[aspectIdx],
        inverted = inverted,
        wordsText = words,
        mainWord = mainWord,
        density = density,
        rotMode = rot,
        palette = WordArtConfig.PALETTES[palIdx].second,
        seed = seed,
        bgCanvas = if (bgIdx == 0) BackgroundSpec() else BackgroundSpec(BgKind.SOLID, color = bgColors[bgIdx]),
    )
    val tfs = when (fontIdx) {
        0 -> listOf(Typeface.DEFAULT_BOLD)
        1 -> listOf(Typeface.create(Typeface.SERIF, Typeface.BOLD))
        else -> listOf(Typeface.DEFAULT_BOLD, Typeface.SERIF, Typeface.MONOSPACE, Typeface.create("sans-serif-condensed", Typeface.BOLD))
    }

    // Combinacion al azar de forma, palabras, colores, tipografia y fondo; se aplica al abrir.
    fun surprise() {
        val rnd = Random(System.nanoTime())
        val filled = ShapeLib.all.filter { it.d != null && it.w == 0f }
        shapeId = (if (filled.isNotEmpty() && rnd.nextInt(100) < 85) filled else ShapeLib.all).random(rnd).id
        val preset = WordLists.presets.random(rnd)
        listId = preset.id
        words = preset.text(es)
        mainWord = ""
        bgIdx = rnd.nextInt(bgColors.size)
        // Paletas con contraste: claras sobre fondo oscuro, vivas u oscuras sobre claro.
        val pals = when (bgIdx) {
            2, 4 -> listOf(0, 5, 6, 8, 4)
            1, 3 -> listOf(0, 1, 2, 3, 4, 7)
            else -> listOf(0, 1, 2, 3, 4, 8)
        }
        palIdx = pals.random(rnd)
        rot = listOf(RotMode.MIXED, RotMode.MIXED, RotMode.HORIZONTAL, RotMode.RANDOM).random(rnd)
        fontIdx = rnd.nextInt(3)
        density = 55f + rnd.nextInt(30)
        inverted = rnd.nextInt(100) < 12
        seed = rnd.nextLong(1, 1_000_000_000)
    }
    LaunchedEffect(Unit) { surprise() }

    LaunchedEffect(cfg, shapeId, fontIdx) {
        delay(250)
        working = true
        try {
            val g = geomOf(shapeId) ?: return@LaunchedEffect
            val layout = CloudEngine.layout(cfg, g, tfs)
            val (w, h) = CloudEngine.dims(cfg.aspect, 640)
            preview = withContext(Dispatchers.Default) { CloudEngine.render(cfg, g, layout, tfs, w, h) { _, _ -> null } }
        } finally {
            working = false
        }
    }

    fun add() {
        scope.launch {
            working = true
            val file = runCatching {
                withContext(Dispatchers.Default) {
                    val g = geomOf(shapeId)!!
                    val layout = CloudEngine.layout(cfg, g, tfs)
                    val (w, h) = CloudEngine.dims(cfg.aspect, 1600)
                    val bm = CloudEngine.render(cfg, g, layout, tfs, w, h) { _, _ -> null }
                    val f = File(File(context.cacheDir, "clouds").apply { mkdirs() }, "nube_${System.currentTimeMillis()}.png")
                    f.outputStream().use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bm.recycle()
                    f
                }
            }.getOrNull()
            working = false
            if (file != null) {
                vm.addMedia(listOf(file.absolutePath), AddTarget.STICKER)
                onDismiss()
            } else vm.toast(t("Could not create the cloud", "No se pudo crear la nube"))
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, null) }
                    Text(t("Clouds", "Nubes"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { surprise() }) { Icon(Icons.Filled.Shuffle, t("Surprise me", "Sorpréndeme")) }
                    IconButton(onClick = { seed = Random.nextLong(1, 1_000_000_000) }) { Icon(Icons.Filled.Casino, t("Regenerate", "Regenerar")) }
                    Button(onClick = { add() }, enabled = !working && preview != null) { Text(t("Add", "Añadir")) }
                }
                Box(Modifier.fillMaxWidth().height(200.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit) }
                        if (working) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    Head(t("Shape", "Forma"))
                    Box(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                        ShapePicker(MaterialTheme.colorScheme.onSurface) { shapeId = it }
                    }
                    Head(t("Words", "Palabras"))
                    Chips(WordLists.presets.map { it.id }, listId, { id -> WordLists.presets.first { it.id == id }.name(es) }) { id ->
                        listId = id
                        words = WordLists.presets.first { it.id == id }.text(es)
                    }
                    OutlinedTextField(
                        value = words, onValueChange = { words = it }, minLines = 3, maxLines = 6,
                        placeholder = { Text(t("One per line. Weight: word:5", "Una por línea. Peso: palabra:5")) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    OutlinedTextField(
                        value = mainWord, onValueChange = { mainWord = it }, singleLine = true,
                        label = { Text(t("Main word (optional)", "Palabra principal (opcional)")) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                    Head(t("Colors", "Colores"))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WordArtConfig.PALETTES.forEachIndexed { i, (_, cols) ->
                            Row(
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .border(if (i == palIdx) 2.dp else 1.dp, if (i == palIdx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable { palIdx = i }.padding(3.dp),
                            ) { cols.take(6).forEach { Box(Modifier.size(width = 12.dp, height = 22.dp).background(Color(it))) } }
                        }
                    }
                    Head(t("Rotation", "Rotación"))
                    Chips(
                        listOf(RotMode.HORIZONTAL, RotMode.VERTICAL, RotMode.MIXED, RotMode.RANDOM), rot,
                        { when (it) { RotMode.HORIZONTAL -> t("Horizontal", "Horizontal"); RotMode.VERTICAL -> t("Vertical", "Vertical"); RotMode.MIXED -> t("Mixed", "Mixta"); else -> t("Random", "Aleatoria") } },
                    ) { rot = it }
                    Head(t("Font", "Fuente"))
                    Chips(listOf(0, 1, 2), fontIdx, { listOf(t("Bold", "Negrita"), t("Serif", "Serif"), t("Mix", "Mezcla"))[it] }) { fontIdx = it }
                    Head(t("Canvas format", "Formato"))
                    Chips(listOf(0, 1, 2), aspectIdx, { listOf("1:1", "16:9", "9:16")[it] }) { aspectIdx = it }
                    Head(t("Background", "Fondo"))
                    Chips(listOf(0, 1, 2, 3, 4), bgIdx, { listOf(t("Transparent", "Transparente"), t("White", "Blanco"), t("Black", "Negro"), t("Cream", "Crema"), t("Night", "Noche"))[it] }) { bgIdx = it }
                    Head(t("Density", "Densidad"))
                    Slider(density, { density = it }, valueRange = 0f..100f, modifier = Modifier.padding(horizontal = 12.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t("Inverted mode", "Modo invertido"), style = MaterialTheme.typography.bodyMedium)
                            Text(t("Fill the outside, leave the shape empty", "Rellena el exterior y deja la forma libre"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(inverted, { inverted = it })
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
