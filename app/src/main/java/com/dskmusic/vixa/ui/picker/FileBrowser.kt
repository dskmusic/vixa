package com.dskmusic.vixa.ui.picker

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.dskmusic.vixa.R
import com.dskmusic.vixa.media.Importer
import com.dskmusic.vixa.media.MediaType
import com.dskmusic.vixa.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Explorador de archivos propio con acceso a todo el almacenamiento.
 * [exts] vacío = cualquier archivo; [folderMode] = elegir una carpeta.
 */
@Composable
fun FileBrowser(
    modifier: Modifier = Modifier,
    exts: Set<String> = emptySet(),
    multi: Boolean = false,
    folderMode: Boolean = false,
    mediaOnly: Boolean = false,
    startDir: String = "",
    onPicked: (List<String>) -> Unit,
) {
    val root = remember { Environment.getExternalStorageDirectory() }
    var dirPath by rememberSaveable { mutableStateOf(startDir.ifEmpty { root.path }) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val dir = File(dirPath)
    LaunchedEffect(dirPath) {
        entries = withContext(Dispatchers.IO) {
            (dir.listFiles() ?: emptyArray()).filter { f ->
                !f.name.startsWith(".") && (f.isDirectory || (!folderMode && (exts.isEmpty() || f.extension.lowercase() in exts) && (!mediaOnly || Importer.typeOf(f.name) != MediaType.OTHER)))
            }.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }
    val canUp = dir.parentFile?.canRead() == true && dirPath != "/"
    BackHandler(enabled = canUp && dirPath != root.path) { dir.parentFile?.let { dirPath = it.path } }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ dir.parentFile?.let { dirPath = it.path } }, enabled = canUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.up)) }
            Text(dirPath, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (folderMode) Button({ onPicked(listOf(dirPath)) }, Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.choose_folder)) }
            else if (multi && selected.isNotEmpty()) Button({ onPicked(selected.toList()) }, Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.add_n, selected.size)) }
        }
        if (entries.isEmpty()) Text(stringResource(R.string.empty_folder), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.path }) { f ->
                val isSel = f.path in selected
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (f.isDirectory) dirPath = f.path
                        else if (multi) selected = if (isSel) selected - f.path else selected + f.path
                        else onPicked(listOf(f.path))
                    }.background(if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val type = if (f.isDirectory) MediaType.OTHER else Importer.typeOf(f.name)
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                        when {
                            f.isDirectory -> Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            type == MediaType.IMAGE || type == MediaType.VIDEO -> AsyncImage(f, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            type == MediaType.AUDIO -> Icon(Icons.Default.MusicNote, null)
                            else -> Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        if (!f.isDirectory) Text(formatBytes(f.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isSel) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** El explorador como diálogo a pantalla completa. */
@Composable
fun FileBrowserDialog(
    title: String,
    exts: Set<String> = emptySet(),
    multi: Boolean = false,
    folderMode: Boolean = false,
    startDir: String = "",
    onDismiss: () -> Unit,
    onPicked: (List<String>) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
                }
                FileBrowser(Modifier.weight(1f), exts, multi, folderMode, false, startDir) { onDismiss(); onPicked(it) }
            }
        }
    }
}
