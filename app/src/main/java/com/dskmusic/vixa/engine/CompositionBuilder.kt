package com.dskmusic.vixa.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.OverlaySettings
import androidx.media3.common.SpeedParameters
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.Size
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.Project
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.hasSpeedChange
import com.dskmusic.vixa.model.segmentSpeeds
import java.io.File
import java.io.FileOutputStream
import kotlin.math.pow

/** Opciones de construcción. [mediaPath] permite sustituir un medio por su proxy; [reversedPath] da el archivo invertido. */
class BuildOptions(
    val canvasW: Int,
    val canvasH: Int,
    val mediaPath: (String) -> String = { it },
    val reversedPath: (Clip) -> String? = { null },
    val includeVideo: Boolean = true,
    val includeAudio: Boolean = true,
)

class Built(val composition: Composition, val durationUs: Long, val ctx: RenderCtx)

/** Velocidad por tramos sobre el tiempo del medio (relativo al inicio del recorte). */
private class SegmentedSpeed(private val speeds: FloatArray, private val spanUs: Long) : SpeedProvider {
    private val segUs = (spanUs / speeds.size).coerceAtLeast(1)

    override fun getSpeed(timeUs: Long): Float {
        val i = (timeUs / segUs).toInt().coerceIn(0, speeds.size - 1)
        return speeds[i]
    }

    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        if (speeds.size == 1) return C.TIME_UNSET
        val next = (timeUs / segUs + 1)
        return if (next >= speeds.size) C.TIME_UNSET else next * segUs
    }
}

object CompositionBuilder {

    /** Fábrica de procesado de fotogramas: los shaders trabajan en el espacio de color original (gamma). */
    fun frameProcessorFactory(): DefaultVideoFrameProcessor.Factory =
        DefaultVideoFrameProcessor.Factory.Builder()
            .setSdrWorkingColorSpace(DefaultVideoFrameProcessor.WORKING_COLOR_SPACE_ORIGINAL)
            .build()

    /** PNG transparente usado como base de tiempos, huecos en capas y soporte de los clips de color. */
    fun blankFile(context: Context): File {
        val f = File(context.filesDir, "blank.png")
        if (!f.exists() || f.length() == 0L) {
            val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            b.recycle()
        }
        return f
    }

    /**
     * Clave de estructura: si dos proyectos dan la misma clave, la composición ya construida sirve para
     * ambos (los parámetros se leen en vivo) y no hace falta reconstruirla.
     */
    fun structureKey(p: Project, o: BuildOptions): String {
        val sb = StringBuilder(1024)
        sb.append(o.canvasW).append('x').append(o.canvasH).append('@').append(p.fps).append('|').append(p.durationUs)
        for (t in p.tracks) {
            sb.append("\nT").append(t.id).append(t.type.ordinal).append(if (t.hidden) 'h' else 'v')
            for (c in t.clips) {
                sb.append(';').append(c.id).append(',').append(c.kind.ordinal).append(',').append(c.startUs).append(',').append(c.durationUs)
                val m = c.media
                if (m != null) {
                    sb.append(',').append(if (c.reversed) o.reversedPath(c) else o.mediaPath(m.path))
                        .append(',').append(c.srcInUs).append(',').append(c.srcOutUs).append(',').append(c.speed)
                        .append(',').append(c.speedCurve.hashCode()).append(',').append(c.keepPitch).append(',').append(c.audible)
                        .append(',').append(pitchOf(c)).append(',').append(audioPath(c, m.path))
                }
                sb.append(',').append(c.transitionIn?.id ?: "")
                for (e in c.effects) sb.append('+').append(e.id).append(e.defId)
            }
        }
        return sb.toString()
    }

    /** Audio que suena en el clip: el procesado con IA si está activo y existe; si no, el del medio. */
    fun audioPath(c: Clip, mediaPath: String): String {
        val ai = c.aiAudio
        return if (ai != null && ai.enabled && ai.path.isNotEmpty() && File(ai.path).exists()) ai.path else mediaPath
    }

    private fun pitchOf(c: Clip): Float = c.audioFx.pitch + (Registry.voice(c.audioFx.voice)?.pitch ?: 0f)

    private fun speedOf(c: Clip): SpeedParameters? =
        if (!c.hasSpeedChange) null else SpeedParameters(SegmentedSpeed(c.segmentSpeeds(), c.srcSpanUs), c.keepPitch)

    /** Única conversión modelo → Composition. La usan la vista previa y la exportación. */
    fun build(context: Context, structure: Project, live: () -> Project, o: BuildOptions): Built {
        val app = context.applicationContext
        val ctx = RenderCtx(app, o.canvasW, o.canvasH, live)
        val blank = Uri.fromFile(blankFile(app))
        val fps = structure.fps.coerceIn(1, 120)
        val totalUs = structure.durationUs.coerceAtLeast(500_000L)
        val sequences = ArrayList<EditedMediaItemSequence>()

        fun imageItem(uri: Uri, durationUs: Long, effects: List<Effect>): EditedMediaItem {
            val ms = (durationUs / 1000L).coerceAtLeast(1)
            return EditedMediaItem.Builder(MediaItem.Builder().setUri(uri).setImageDurationMs(ms).build())
                .setDurationUs(ms * 1000L)
                .setFrameRate(fps)
                .setEffects(Effects(emptyList(), effects))
                .build()
        }

        fun timedMediaItem(c: Clip, path: String, inUs: Long, outUs: Long): MediaItem =
            MediaItem.Builder().setUri(Uri.fromFile(File(path)))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionUs(inUs).setEndPositionUs(outUs).build(),
                ).build()

        if (o.includeVideo) {
            // Secuencia 0: base de tiempos (primaria). Marca un fotograma por cada 1/fps de toda la duración.
            sequences.add(
                EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
                    .addItem(imageItem(blank, totalUs, listOf(ClipEffect(ctx, "", MODE_BLANK))))
                    .build(),
            )

            fun visualItem(c: Clip, isMain: Boolean, onTop: Boolean, outId: String): EditedMediaItem {
                val effects = ArrayList<Effect>()
                if (c.kind != ClipKind.COLOR) for (e in c.effects) effects.add(FxEffect(ctx, c.id, e.id, e.defId, false))
                effects.add(ClipEffect(ctx, c.id, MODE_TEXTURE, if (isMain) c.transitionIn?.id ?: "" else "", outId, onTop, onTop, isMain))
                val m = c.media
                if (c.kind == ClipKind.VIDEO && m != null) {
                    val reversed = if (c.reversed) o.reversedPath(c) else null
                    val path = reversed ?: o.mediaPath(m.path)
                    // El archivo invertido contiene el medio completo al revés.
                    val inUs = if (reversed != null) (m.durationUs - c.srcOutUs).coerceAtLeast(0) else c.srcInUs
                    val outUs = if (reversed != null) (m.durationUs - c.srcInUs).coerceAtLeast(inUs + 1000) else c.srcOutUs
                    val b = EditedMediaItem.Builder(timedMediaItem(c, path, inUs, outUs))
                        .setDurationUs(m.durationUs.coerceAtLeast(outUs))
                        .setRemoveAudio(true)
                        .setEffects(Effects(emptyList(), effects))
                    speedOf(c)?.let { b.setSpeed(it) }
                    return b.build()
                }
                val uri = if (c.kind == ClipKind.IMAGE && m != null) Uri.fromFile(File(m.path)) else blank
                return imageItem(uri, c.durationUs, effects)
            }

            fun lane(clips: List<Pair<Clip, EditedMediaItem>>): EditedMediaItemSequence? {
                if (clips.isEmpty()) return null
                val b = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
                var cursor = 0L
                for ((c, item) in clips) {
                    val gap = c.startUs - cursor
                    if (gap >= 1000L) {
                        val g = imageItem(blank, gap, listOf(ClipEffect(ctx, "", MODE_BLANK)))
                        b.addItem(g)
                        cursor += g.presentationDurationUs
                    }
                    b.addItem(item)
                    cursor += item.presentationDurationUs
                }
                // Todas las secuencias de vídeo duran lo mismo: una que termina antes deja el compositor esperando
                // fotogramas (la vista previa se queda "cargando" al llegar al cambio de clip/transición).
                if (totalUs - cursor >= 1000L) b.addItem(imageItem(blank, totalUs - cursor, listOf(ClipEffect(ctx, "", MODE_BLANK))))
                return b.build()
            }

            // De arriba abajo: la secuencia anterior se dibuja encima de la siguiente.
            for (t in structure.tracks) {
                if (t.hidden) continue
                if (t.type == TrackType.OVERLAY) {
                    lane(t.clips.map { it to visualItem(it, false, true, "") })?.let { sequences.add(it) }
                } else if (t.type == TrackType.MAIN) {
                    // Las transiciones solapan clips: cada clip con transición cambia de carril (A/B).
                    val a = ArrayList<Pair<Clip, EditedMediaItem>>()
                    val bLane = ArrayList<Pair<Clip, EditedMediaItem>>()
                    var laneIdx = 0
                    for ((i, c) in t.clips.withIndex()) {
                        if (c.transitionIn != null) laneIdx = 1 - laneIdx
                        val outId = t.clips.getOrNull(i + 1)?.transitionIn?.id ?: ""
                        val item = visualItem(c, true, laneIdx == 0, outId)
                        if (laneIdx == 0) a.add(c to item) else bLane.add(c to item)
                    }
                    lane(a)?.let { sequences.add(it) }
                    lane(bLane)?.let { sequences.add(it) }
                }
            }
        }

        if (o.includeAudio) {
            val audible = structure.tracks.flatMap { it.clips }.filter { it.audible && it.media != null }.sortedBy { it.startUs }
            val lanes = ArrayList<ArrayList<Clip>>()
            for (c in audible) {
                val target = lanes.firstOrNull { it.last().endUs <= c.startUs }
                if (target != null) target.add(c) else lanes.add(arrayListOf(c))
            }
            for (l in lanes) {
                val b = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                var cursor = 0L
                for (c in l) {
                    val m = c.media ?: continue
                    val audio = audioPath(c, m.path)
                    // Una voz sintetizada que aún no se ha regenerado no debe tumbar la composición: queda en silencio
                    // (el ruido y la separación, en cambio, suenan con el audio original hasta que se regeneran).
                    if (c.aiAudio?.kind == "tts" && !File(audio).exists()) continue
                    val gap = c.startUs - cursor
                    if (gap >= 1000L) { b.addGap(gap); cursor += gap }
                    val processors = ArrayList<AudioProcessor>()
                    val semitones = pitchOf(c)
                    if (semitones != 0f) processors.add(SonicAudioProcessor().apply { setPitch(2.0.pow(semitones / 12.0).toFloat()) })
                    processors.add(VixaAudioProcessor(live, c.id))
                    val ib = EditedMediaItem.Builder(timedMediaItem(c, audio, c.srcInUs, c.srcOutUs))
                        .setDurationUs(m.durationUs.coerceAtLeast(c.srcOutUs))
                        .setRemoveVideo(true)
                        .setEffects(Effects(processors, emptyList()))
                    speedOf(c)?.let { ib.setSpeed(it) }
                    val item = ib.build()
                    b.addItem(item)
                    cursor += item.presentationDurationUs
                }
                // Completa hasta el final para que todas las secuencias duren lo mismo.
                if (totalUs - cursor >= 1000L) b.addGap(totalUs - cursor)
                sequences.add(b.build())
            }
            if (lanes.isEmpty() && !o.includeVideo) {
                sequences.add(EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).addGap(totalUs).build())
            }
        }

        val compEffects = ArrayList<Effect>()
        if (o.includeVideo) {
            compEffects.add(ClipEffect(ctx, "", MODE_FRAME))
            for (t in structure.tracks) {
                if (t.type != TrackType.ADJUST || t.hidden) continue
                // Una pasada por pista y hueco de efecto (no una por cada efecto de cada capa): ver FxSlotEffect.
                for (k in 0 until (t.clips.maxOfOrNull { it.effects.size } ?: 0)) compEffects.add(FxSlotEffect(ctx, t.id, k))
            }
            compEffects.add(OverlayEffect(listOf(VixaOverlay(ctx))))
        }

        val settings = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>): Size = Size(o.canvasW, o.canvasH)
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings = object : OverlaySettings {}
        }

        val composition = Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), compEffects))
            .setVideoCompositorSettings(settings)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        return Built(composition, totalUs, ctx)
    }
}
