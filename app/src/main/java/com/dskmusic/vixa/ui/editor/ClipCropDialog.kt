package com.dskmusic.vixa.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dskmusic.vixa.R
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.MediaRef
import com.dskmusic.vixa.ui.CropBox
import com.dskmusic.vixa.ui.CropRect
import com.dskmusic.vixa.ui.RatioChips
import com.dskmusic.vixa.ui.fitRatio
import com.dskmusic.vixa.ui.ratioOf
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Un fotograma del medio del clip (en la posición de origen [atUs]) para encuadrar sobre él. */
private fun frameOf(m: MediaRef, atUs: Long): Bitmap? = try {
    if (m.isImage) {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(m.path, b)
        var s = 1
        while (max(b.outWidth, b.outHeight) / s > 1600) s *= 2
        BitmapFactory.decodeFile(m.path, BitmapFactory.Options().apply { inSampleSize = s })
    } else {
        val r = MediaMetadataRetriever()
        try { r.setDataSource(m.path); r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } finally { r.release() }
    }
} catch (e: Throwable) { null }

/** Encuadre visual de un clip: el cuadro se arrastra sobre un fotograma, con presets de proporción. Escribe los mismos cuatro recortes de Transformar. */
@Composable
fun ClipCropDialog(vm: EditorViewModel, c: Clip, onDismiss: () -> Unit) {
    val m = c.media
    if (m == null || m.width <= 0 || m.height <= 0) { onDismiss(); return }
    val tr = c.transform
    var crop by remember { mutableStateOf(CropRect(tr.cropL, tr.cropT, tr.cropR, tr.cropB)) }
    var ratioId by remember { mutableStateOf("free") }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(m.path) { frame = withContext(Dispatchers.IO) { frameOf(m, c.srcInUs) }?.asImageBitmap() }
    val cw = (m.width * crop.w).toInt()
    val ch = (m.height * crop.h).toInt()
    fun pick(id: String) {
        ratioId = id
        when (id) {
            "free" -> {}
            "orig" -> crop = CropRect()
            else -> ratioOf(id)?.let { crop = fitRatio(it, m.width, m.height) }
        }
    }
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val topBar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
            Text(stringResource(R.string.crop), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton({
                vm.updateClip(R.string.hist_crop, c.id) { it.copy(transform = it.transform.copy(cropL = crop.l, cropR = crop.r, cropT = crop.t, cropB = crop.b)) }
                onDismiss()
            }) { Icon(Icons.Default.Check, stringResource(R.string.ok)) }
        }
    }
    val preview: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(m.width.toFloat() / m.height).clipToBounds()) {
                frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                CropBox(crop, ratioOf(ratioId), { crop = it }, true, Modifier.fillMaxSize())
            }
        }
    }
    val controls: @Composable () -> Unit = {
        Text(
            stringResource(R.string.qe_size_dims, m.width, m.height, cw, ch) + "\n" + stringResource(R.string.qe_ratio_flip_hint),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.padding(bottom = 8.dp)) { RatioChips(ratioId) { pick(it) } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (landscape) {
                Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    preview(Modifier.weight(1.25f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight()) { topBar(); controls() }
                }
            } else {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    topBar()
                    preview(Modifier.weight(1f))
                    controls()
                }
            }
        }
    }
}
