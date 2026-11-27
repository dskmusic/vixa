package com.dskmusic.vixa.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import com.dskmusic.vixa.engine.ShapeLib
import com.dskmusic.vixa.engine.Shapes

/**
 * Selector de formas con buscador (es/en), categorias y miniaturas compactas.
 * [sort] permite a la app poner primero sus favoritas; [onLong] es la pulsacion larga.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ShapePicker(
    tint: Color,
    sort: (List<String>) -> List<String> = { it },
    onLong: ((String) -> Unit)? = null,
    onPick: (String) -> Unit,
) {
    val es = remember { java.util.Locale.getDefault().language == "es" }
    var query by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }
    val results = sort(ShapeLib.search(query, cat).map { it.id }).take(200)

    Column(Modifier.padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(if (es) "Buscar formas (gato, cat, flor…)" else "Search shapes (cat, gato, flower…)") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (c in listOf<String?>(null) + ShapeLib.categories) {
                FilterChip(cat == c, { cat = c }, { Text(ShapeLib.catLabel(c, es)) })
            }
        }
        FlowRow(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (id in results) {
                val def = Shapes.get(id) ?: continue
                val path = remember(id) { android.graphics.Path().also { def.build(it) }.asComposePath() }
                Canvas(
                    Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .combinedClickable(onLongClick = onLong?.let { f -> { f(id) } }) { onPick(id) }
                        .padding(7.dp),
                ) {
                    scale(size.width, size.height, pivot = Offset.Zero) {
                        if (def.stroke) drawPath(path, tint, style = Stroke(0.08f)) else drawPath(path, tint)
                    }
                }
            }
        }
        if (results.isEmpty()) {
            Text(
                if (es) "Ninguna forma coincide" else "No shapes match",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}
