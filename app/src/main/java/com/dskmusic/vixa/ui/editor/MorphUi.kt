package com.dskmusic.vixa.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dskmusic.vixa.R
import com.dskmusic.vixa.ai.Models
import com.dskmusic.vixa.ui.ModelGateDialog

/** Opciones del morph tras elegir las fotos. Si falta el modelo de caras, ofrece descargarlo antes de crear. */
@Composable
fun MorphOptionsDialog(vm: EditorViewModel, paths: List<String>, onDismiss: () -> Unit) {
    var zoom by remember { mutableStateOf(true) }
    var ping by remember { mutableStateOf(false) }
    var gate by remember { mutableStateOf(false) }
    val go = { vm.createMorph(paths, zoom, ping); onDismiss() }
    if (gate) {
        ModelGateDialog(vm.container, listOf(Models.FACE_MESH), { gate = false }) { gate = false; go() }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.morph_title, paths.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.morph_desc), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.morph_zoom)); Switch(zoom, { zoom = it })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.morph_ping)); Switch(ping, { ping = it })
                }
            }
        },
        confirmButton = { TextButton({ if (vm.container.models.isReady(Models.FACE_MESH)) go() else gate = true }) { Text(stringResource(R.string.morph_create)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
