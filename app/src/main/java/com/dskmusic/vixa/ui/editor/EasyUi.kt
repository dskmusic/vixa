package com.dskmusic.vixa.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Un botón grande de la barra visual del modo Fácil. [badge] marca que ya tiene algo puesto. */
class EasyTab(val key: String, val icon: ImageVector, val label: Int, val badge: Boolean = false)

/** true en modo Fácil: los paneles enseñan botones grandes con un solo bloque abierto; en Pro enseñan todo como siempre. */
@Composable
fun easyMode(vm: EditorViewModel): Boolean = !vm.container.settings.flow.collectAsState().value.proMode

/** Fila de botones grandes (icono + texto). Se desplaza si no caben. */
@Composable
fun EasyBar(tabs: List<EasyTab>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in tabs) {
            val active = t.key == selected
            val bg = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
            Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable { onSelect(t.key) }.padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(bg), contentAlignment = Alignment.Center) {
                    Icon(t.icon, null, Modifier.size(26.dp))
                    if (t.badge) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                }
                Text(stringResource(t.label), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}
