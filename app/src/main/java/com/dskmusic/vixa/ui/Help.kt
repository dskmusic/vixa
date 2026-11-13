package com.dskmusic.vixa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Cottage
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Nightlife
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dskmusic.vixa.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer

// ------------------------------------------------------------------------------------------------
// Ayuda completa: documento Markdown (assets/help/help_es.md y help_en.md) con índice y buscador siempre a mano.
// Formato admitido: # ## ### títulos, párrafos, listas (- y 1.), citas (>), bloques de código, tablas, ---,
// **negrita**, *cursiva*, `código` e iconos de las propias herramientas con {icon:Nombre}.
// ------------------------------------------------------------------------------------------------

/** Iconos que se pueden usar en el texto con {icon:Nombre}: los mismos que usan las herramientas de la app. */
private val HELP_ICONS: Map<String, ImageVector> = mapOf(
    "Add" to Icons.Default.Add, "PictureInPicture" to Icons.Default.PictureInPicture, "MusicNote" to Icons.Default.MusicNote,
    "TextFields" to Icons.Default.TextFields, "Dashboard" to Icons.Default.Dashboard, "EmojiEmotions" to Icons.Default.EmojiEmotions,
    "Subtitles" to Icons.Default.Subtitles, "Mic" to Icons.Default.Mic, "AutoAwesome" to Icons.Default.AutoAwesome, "Layers" to Icons.Default.Layers,
    "AspectRatio" to Icons.Default.AspectRatio, "AutoFixHigh" to Icons.Default.AutoFixHigh, "Save" to Icons.Default.Save, "History" to Icons.Default.History,
    "ContentCut" to Icons.Default.ContentCut, "Delete" to Icons.Default.Delete, "ContentCopy" to Icons.Default.ContentCopy, "Speed" to Icons.Default.Speed,
    "VolumeUp" to Icons.AutoMirrored.Filled.VolumeUp, "GraphicEq" to Icons.Default.GraphicEq, "Transform" to Icons.Default.Transform,
    "Animation" to Icons.Default.Animation, "FilterVintage" to Icons.Default.FilterVintage, "Tune" to Icons.Default.Tune, "SwapHoriz" to Icons.Default.SwapHoriz,
    "Crop" to Icons.Default.Crop, "Palette" to Icons.Default.Palette, "BlurOn" to Icons.Default.BlurOn, "Texture" to Icons.Default.Texture,
    "Wallpaper" to Icons.Default.Wallpaper, "Gradient" to Icons.Default.Gradient, "SwapVert" to Icons.Default.SwapVert, "AcUnit" to Icons.Default.AcUnit,
    "FastRewind" to Icons.Default.FastRewind, "Vibration" to Icons.Default.Vibration, "MyLocation" to Icons.Default.MyLocation, "Diamond" to Icons.Default.Diamond,
    "Undo" to Icons.Default.Undo, "Redo" to Icons.Default.Redo, "Fullscreen" to Icons.Default.Fullscreen, "FitScreen" to Icons.Default.FitScreen,
    "PanTool" to Icons.Default.PanTool, "Settings" to Icons.Default.Settings, "FolderOpen" to Icons.Default.FolderOpen, "Compress" to Icons.Default.Compress,
    "Gif" to Icons.Default.Gif, "Merge" to Icons.Default.Merge, "Movie" to Icons.Default.Movie, "Help" to Icons.AutoMirrored.Filled.HelpOutline,
    "Bolt" to Icons.Default.Bolt, "Spa" to Icons.Default.Spa, "Cloud" to Icons.Default.Cloud, "WbSunny" to Icons.Default.WbSunny, "Theaters" to Icons.Default.Theaters,
    "Favorite" to Icons.Default.Favorite, "Celebration" to Icons.Default.Celebration, "Explore" to Icons.Default.Explore, "Nightlight" to Icons.Default.Nightlight,
    "BeachAccess" to Icons.Default.BeachAccess, "FitnessCenter" to Icons.Default.FitnessCenter, "Nightlife" to Icons.Default.Nightlife,
    "RocketLaunch" to Icons.Default.RocketLaunch, "Eco" to Icons.Default.Eco, "LocationCity" to Icons.Default.LocationCity, "Cottage" to Icons.Default.Cottage,
    "LocalFlorist" to Icons.Default.LocalFlorist, "Cake" to Icons.Default.Cake, "BugReport" to Icons.Default.BugReport, "SportsEsports" to Icons.Default.SportsEsports,
    "Checkroom" to Icons.Default.Checkroom, "Headphones" to Icons.Default.Headphones, "AcUnit" to Icons.Default.AcUnit, "ChildCare" to Icons.Default.ChildCare,
    "LiveTv" to Icons.Default.LiveTv,"Image" to Icons.Default.Image, "Videocam" to Icons.Default.Videocam, "Shuffle" to Icons.Default.Shuffle,
    "DragHandle" to Icons.Default.DragHandle, "PlayArrow" to Icons.Default.PlayArrow, "Pause" to Icons.Default.Pause, "Close" to Icons.Default.Close,
    "Search" to Icons.Default.Search, "Menu" to Icons.Default.Menu, "Lightbulb" to Icons.Default.Lightbulb, "Warning" to Icons.Default.Warning,
    "Info" to Icons.Default.Info, "CloudDownload" to Icons.Default.CloudDownload, "IosShare" to Icons.Default.IosShare, "SkipPrevious" to Icons.Default.SkipPrevious,
    "SkipNext" to Icons.Default.SkipNext, "Check" to Icons.Default.Check, "Sports" to Icons.Default.SportsSoccer,
)

private const val K_H = 0
private const val K_P = 1
private const val K_B = 2
private const val K_Q = 3
private const val K_CODE = 4
private const val K_TABLE = 5
private const val K_RULE = 6
private const val K_OL = 7

private class HBlock(val kind: Int, val level: Int, val text: String, val rows: List<List<String>> = emptyList(), val n: Int = 0)

private val ICON_RE = Regex("""\{icon:(\w+)\}""")
private val OL_RE = Regex("""^\d+\. """)

private fun parseHelp(src: String): List<HBlock> {
    val out = ArrayList<HBlock>()
    val lines = src.replace("\r", "").split("\n")
    val para = StringBuilder()
    fun flush() {
        if (para.isNotEmpty()) { out.add(HBlock(K_P, 0, para.toString().trim())); para.clear() }
    }
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val t = raw.trim()
        when {
            t.isEmpty() -> flush()
            t.startsWith("```") -> {
                flush()
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { sb.append(lines[i]).append('\n'); i++ }
                out.add(HBlock(K_CODE, 0, sb.toString().trimEnd()))
            }
            t.startsWith("#") -> {
                flush()
                val lvl = t.takeWhile { it == '#' }.length
                out.add(HBlock(K_H, lvl.coerceIn(1, 3), t.drop(lvl).trim()))
            }
            t == "---" -> { flush(); out.add(HBlock(K_RULE, 0, "")) }
            t.startsWith(">") -> {
                flush()
                val sb = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) { sb.append(lines[i].trim().removePrefix(">").trim()).append(' '); i++ }
                i--
                out.add(HBlock(K_Q, 0, sb.toString().trim()))
            }
            t.startsWith("|") -> {
                flush()
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                    if (!cells.all { c -> c.isNotEmpty() && c.all { ch -> ch == '-' || ch == ':' } }) rows.add(cells)
                    i++
                }
                i--
                out.add(HBlock(K_TABLE, 0, "", rows))
            }
            t.startsWith("- ") || t.startsWith("* ") -> {
                flush()
                out.add(HBlock(K_B, (raw.length - raw.trimStart().length) / 2, t.drop(2).trim()))
            }
            OL_RE.containsMatchIn(t) -> {
                flush()
                out.add(HBlock(K_OL, 0, t.substringAfter(". ").trim(), n = t.substringBefore('.').toIntOrNull() ?: 1))
            }
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
        }
        i++
    }
    flush()
    return out
}

/** Texto sin marcas ni iconos, para buscar. */
private fun plainOf(s: String): String = s.replace(ICON_RE, "").replace("**", "").replace("`", "").replace(Regex("""(?<!\*)\*(?!\*)"""), "")

private fun HBlock.plain(): String = when (kind) {
    K_TABLE -> rows.joinToString(" · ") { r -> r.joinToString(" ") { plainOf(it) } }
    else -> plainOf(text)
}

/** Minúsculas y sin acentos, conservando la longitud (así los índices coinciden con el texto original). */
private fun fold(s: String): String = buildString {
    for (c in s) append(Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first().lowercaseChar())
}

private class Rich(val text: AnnotatedString, val inline: Map<String, InlineTextContent>)

@Composable
private fun rich(src: String, hl: String, bold: Boolean = false): Rich {
    val accent = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val markBg = Color(0x66FFD60A)
    val used = LinkedHashSet<String>()
    val a = buildAnnotatedString {
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("{icon:", i) && src.indexOf('}', i) > 0 -> {
                    val end = src.indexOf('}', i)
                    val name = src.substring(i + 6, end)
                    if (HELP_ICONS.containsKey(name)) { used.add(name); appendInlineContent("i_$name", "•") }
                    i = end + 1
                }
                src.startsWith("**", i) && src.indexOf("**", i + 2) > 0 -> {
                    val end = src.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(src.substring(i + 2, end)) }
                    i = end + 2
                }
                src[i] == '`' && src.indexOf('`', i + 1) > 0 -> {
                    val end = src.indexOf('`', i + 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = accent)) { append(src.substring(i + 1, end)) }
                    i = end + 1
                }
                src[i] == '*' && i + 1 < src.length && src[i + 1] != ' ' && src.indexOf('*', i + 1) > 0 -> {
                    val end = src.indexOf('*', i + 1)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(src.substring(i + 1, end)) }
                    i = end + 1
                }
                else -> { append(src[i]); i++ }
            }
        }
        if (hl.length >= 2) {
            val folded = fold(toAnnotatedString().text)
            val q = fold(hl)
            var at = folded.indexOf(q)
            while (at >= 0) {
                addStyle(SpanStyle(background = markBg), at, at + q.length)
                at = folded.indexOf(q, at + q.length)
            }
        }
    }
    val inline = used.associate { name ->
        "i_$name" to InlineTextContent(Placeholder(1.25.em, 1.25.em, PlaceholderVerticalAlign.TextCenter)) {
            Icon(HELP_ICONS.getValue(name), null, Modifier.fillMaxSize(), tint = accent)
        }
    }
    return Rich(a, inline)
}

@Composable
private fun RichText(src: String, hl: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge, color: Color = Color.Unspecified, weight: FontWeight? = null) {
    val r = rich(src, hl)
    Text(r.text, modifier, color = color, style = style, fontWeight = weight, inlineContent = r.inline)
}

@Composable
private fun BlockView(b: HBlock, hl: String) {
    val cs = MaterialTheme.colorScheme
    when (b.kind) {
        K_H -> when (b.level) {
            1 -> Column(Modifier.padding(top = 28.dp, bottom = 8.dp)) {
                RichText(b.text, hl, style = MaterialTheme.typography.headlineMedium, color = cs.primary, weight = FontWeight.Bold)
                HorizontalDivider(Modifier.padding(top = 6.dp), color = cs.primary.copy(alpha = 0.4f))
            }
            2 -> RichText(b.text, hl, Modifier.padding(top = 22.dp, bottom = 4.dp), MaterialTheme.typography.titleLarge, weight = FontWeight.Bold)
            else -> RichText(b.text, hl, Modifier.padding(top = 14.dp, bottom = 2.dp), MaterialTheme.typography.titleMedium, cs.tertiary, FontWeight.SemiBold)
        }
        K_P -> RichText(b.text, hl, Modifier.padding(vertical = 4.dp), MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp))
        K_B -> Row(Modifier.padding(start = (8 + b.level * 18).dp, top = 2.dp, bottom = 2.dp)) {
            Text(if (b.level == 0) "•" else "–", Modifier.width(18.dp), color = cs.primary, style = MaterialTheme.typography.bodyLarge)
            RichText(b.text, hl, Modifier.weight(1f), MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp))
        }
        K_OL -> Row(Modifier.padding(start = 8.dp, top = 3.dp, bottom = 3.dp)) {
            Text(b.n.toString() + ".", Modifier.width(26.dp), color = cs.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
            RichText(b.text, hl, Modifier.weight(1f), MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp))
        }
        K_Q -> Row(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(10.dp)).background(cs.secondaryContainer.copy(alpha = 0.55f))) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(cs.secondary))
            RichText(b.text, hl, Modifier.padding(12.dp), MaterialTheme.typography.bodyMedium)
        }
        K_CODE -> Text(
            b.text, Modifier.padding(vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHighest).horizontalScroll(rememberScrollState()).padding(12.dp),
            fontFamily = FontFamily.Monospace, fontSize = 13.sp,
        )
        K_TABLE -> Column(Modifier.padding(vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(cs.surfaceContainer)) {
            for ((ri, row) in b.rows.withIndex()) {
                Row(Modifier.fillMaxWidth().background(if (ri == 0) cs.primaryContainer.copy(alpha = 0.6f) else Color.Transparent).padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    for ((ci, cell) in row.withIndex()) RichText(
                        cell, hl, Modifier.weight(if (ci == 0 && row.size > 2) 0.9f else 1f).padding(end = 8.dp), MaterialTheme.typography.bodyMedium,
                        weight = if (ri == 0) FontWeight.Bold else null,
                    )
                }
                if (ri < b.rows.size - 1) HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
            }
        }
        K_RULE -> HorizontalDivider(Modifier.padding(vertical = 14.dp))
    }
}

@Composable
private fun IndexList(toc: List<Pair<Int, HBlock>>, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.padding(vertical = 4.dp)) {
        itemsIndexed(toc) { _, (idx, b) ->
            val top = b.level == 1
            Row(Modifier.fillMaxWidth().clickable { onPick(idx) }.padding(start = if (top) 12.dp else 28.dp, end = 12.dp, top = if (top) 10.dp else 5.dp, bottom = if (top) 4.dp else 5.dp)) {
                val r = rich(b.text, "")
                Text(
                    r.text, inlineContent = r.inline, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = if (top) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (top) FontWeight.Bold else null, color = if (top) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Menú de ayuda de la pantalla de inicio: volver a ver el tutorial o abrir la ayuda completa. */
@Composable
fun HelpMenuButton(onTutorial: () -> Unit, onHelp: () -> Unit, onDemo: (() -> Unit)? = null) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Icon(Icons.AutoMirrored.Filled.HelpOutline, stringResource(R.string.help_title)) }
        DropdownMenu(open, { open = false }) {
            androidx.compose.material3.DropdownMenuItem({ Text(stringResource(R.string.view_tutorial)) }, { open = false; onTutorial() }, leadingIcon = { Icon(Icons.Default.PlayArrow, null) })
            androidx.compose.material3.DropdownMenuItem({ Text(stringResource(R.string.help_full)) }, { open = false; onHelp() }, leadingIcon = { Icon(Icons.Default.Search, null) })
            if (onDemo != null) androidx.compose.material3.DropdownMenuItem({ Text(stringResource(R.string.demo_project)) }, { open = false; onDemo() }, leadingIcon = { Icon(Icons.Default.AutoAwesome, null) })
        }
    }
}

@Composable
fun HelpScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val es = context.resources.configuration.locales[0].language == "es"
    var source by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(es) {
        source = withContext(Dispatchers.IO) {
            try { context.assets.open(if (es) "help/help_es.md" else "help/help_en.md").bufferedReader().use { it.readText() } } catch (e: Throwable) { "" }
        }
    }
    val blocks = remember(source) { source?.let { parseHelp(it) } ?: emptyList() }
    val toc = remember(blocks) { blocks.withIndex().filter { it.value.kind == K_H && it.value.level <= 2 }.map { it.index to it.value } }
    // Textos planos ya plegados (sin acentos ni mayúsculas) para buscar rápido.
    val folded = remember(blocks) { blocks.map { fold(it.plain()) } }
    var query by rememberSaveable { mutableStateOf("") }
    var highlight by remember { mutableStateOf("") }
    var showIndex by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    fun go(i: Int) { scope.launch { listState.scrollToItem(i) } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth >= 720.dp
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(R.string.help_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (!wide) IconButton({ showIndex = true }) { Icon(Icons.Default.Menu, stringResource(R.string.help_index)) }
                }
                OutlinedTextField(
                    query, { query = it; highlight = "" }, Modifier.fillMaxWidth().padding(horizontal = 12.dp), singleLine = true,
                    placeholder = { Text(stringResource(R.string.help_search)) }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty() || highlight.isNotEmpty()) IconButton({ query = ""; highlight = "" }) { Icon(Icons.Default.Close, stringResource(R.string.close)) } },
                )
                Row(Modifier.weight(1f).padding(top = 6.dp)) {
                    if (wide) {
                        Surface(Modifier.width(300.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column {
                                Text(stringResource(R.string.help_index), Modifier.padding(start = 12.dp, top = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                IndexList(toc, { query = ""; go(it) })
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (source == null) androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center))
                        else if (query.trim().length >= 2) {
                            // Resultados de la búsqueda: sección, fragmento con la coincidencia resaltada. Al tocar se salta al lugar.
                            val q = fold(query.trim())
                            val hits = remember(q, folded) {
                                val list = ArrayList<Int>()
                                for (i in folded.indices) if (folded[i].contains(q)) list.add(i)
                                list
                            }
                            if (hits.isEmpty()) Text(stringResource(R.string.help_no_results), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                                itemsIndexed(hits.take(120)) { _, bi ->
                                    val b = blocks[bi]
                                    val section = (bi downTo 0).firstOrNull { blocks[it].kind == K_H }?.let { plainOf(blocks[it].text) } ?: ""
                                    val plain = b.plain()
                                    val at = folded[bi].indexOf(q).coerceAtLeast(0)
                                    val from = (at - 50).coerceAtLeast(0)
                                    val snippet = (if (from > 0) "…" else "") + plain.substring(from, (at + 110).coerceAtMost(plain.length)) + (if (at + 110 < plain.length) "…" else "")
                                    Column(Modifier.fillMaxWidth().clickable { highlight = query.trim(); query = ""; go(bi) }.padding(vertical = 8.dp)) {
                                        if (section.isNotEmpty() && b.kind != K_H) Text(section, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        RichText(snippet.replace("*", ""), query.trim(), style = MaterialTheme.typography.bodyMedium)
                                        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize(), listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                                itemsIndexed(blocks) { _, b -> BlockView(b, highlight) }
                                item { Box(Modifier.padding(bottom = 48.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showIndex) Dialog(onDismissRequest = { showIndex = false }) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.heightIn(max = 560.dp)) {
                Text(stringResource(R.string.help_index), Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                IndexList(toc, { showIndex = false; query = ""; go(it) })
            }
        }
    }
}
