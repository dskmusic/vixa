package com.dskmusic.vixa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dskmusic.vixa.R
import kotlinx.coroutines.launch

private class Slide(val icon: ImageVector, val title: Int, val body: Int)

private val slides = listOf(
    Slide(Icons.Default.AutoAwesome, R.string.tut_12_title, R.string.tut_12_body),
    Slide(Icons.Default.Movie, R.string.tut_1_title, R.string.tut_1_body),
    Slide(Icons.Default.TouchApp, R.string.tut_2_title, R.string.tut_2_body),
    Slide(Icons.Default.ContentCut, R.string.tut_3_title, R.string.tut_3_body),
    Slide(Icons.Default.AutoAwesome, R.string.tut_4_title, R.string.tut_4_body),
    Slide(Icons.Default.TextFields, R.string.tut_5_title, R.string.tut_5_body),
    Slide(Icons.Default.CloudDownload, R.string.tut_6_title, R.string.tut_6_body),
    Slide(Icons.Default.IosShare, R.string.tut_7_title, R.string.tut_7_body),
    Slide(Icons.Default.AutoAwesome, R.string.tut_8_title, R.string.tut_8_body),
    Slide(Icons.Default.Subtitles, R.string.tut_9_title, R.string.tut_9_body),
    Slide(Icons.Default.GraphicEq, R.string.tut_10_title, R.string.tut_10_body),
    Slide(Icons.Default.MyLocation, R.string.tut_11_title, R.string.tut_11_body),
)

/** Tutorial de bienvenida: diapositivas deslizables con puntos, Omitir, Anterior y Siguiente / Empezar. */
@Composable
fun TutorialScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { slides.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == slides.size - 1
    // Mientras el proyecto demo se prepara en segundo plano, al terminar (o al omitir) se espera a que acabe, con un límite.
    val seed by com.dskmusic.vixa.engine.DemoSeeder.state.collectAsState()
    var confirmSkip by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    fun finish() {
        if (finished) return
        if (seed == com.dskmusic.vixa.engine.DemoSeeder.State.RUNNING) waiting = true else { finished = true; onDone() }
    }
    LaunchedEffect(waiting, seed) {
        if (waiting && seed != com.dskmusic.vixa.engine.DemoSeeder.State.RUNNING && !finished) { finished = true; onDone() }
    }
    LaunchedEffect(waiting) {
        if (waiting) { kotlinx.coroutines.delay(45_000); if (!finished) { finished = true; onDone() } }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                TextButton({ confirmSkip = true }) { Text(stringResource(R.string.skip)) }
            }
            HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f)) { page ->
                val s = slides[page]
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(
                        Modifier.size(170.dp).clip(CircleShape).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))),
                        contentAlignment = Alignment.Center,
                    ) { Icon(s.icon, null, Modifier.size(84.dp), tint = Color.White) }
                    Spacer(Modifier.height(32.dp))
                    Text(stringResource(s.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(s.body), Modifier.widthIn(max = 460.dp), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
                for (i in slides.indices) {
                    Box(Modifier.padding(4.dp).size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton({ scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) { Text(stringResource(R.string.previous)) }
                Button({ if (last) finish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) { Text(stringResource(if (last) R.string.start else R.string.next)) }
            }
        }
    }
    if (confirmSkip) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmSkip = false },
        title = { Text(stringResource(R.string.tut_skip_title)) },
        text = { Text(stringResource(R.string.tut_skip_body)) },
        confirmButton = { TextButton({ confirmSkip = false; finish() }) { Text(stringResource(R.string.skip)) } },
        dismissButton = { TextButton({ confirmSkip = false }) { Text(stringResource(R.string.tut_skip_stay)) } },
    )
    // Ventana cuadrada de bordes redondeados con un spinner mientras termina de prepararse el proyecto demo.
    if (waiting) androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(Modifier.size(190.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 8.dp) {
            Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(48.dp))
                Text(stringResource(R.string.demo_preparing_project), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            }
        }
    }
}
