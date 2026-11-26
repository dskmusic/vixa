package com.dskmusic.vixa.ui.editor

import android.media.MediaPlayer
import com.dskmusic.vixa.R
import com.dskmusic.vixa.ai.AiTasks
import com.dskmusic.vixa.ai.Models
import com.dskmusic.vixa.ai.Speech
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.engine.CompositionBuilder
import com.dskmusic.vixa.media.Importer
import com.dskmusic.vixa.media.MediaType
import com.dskmusic.vixa.model.AiAudioRef
import com.dskmusic.vixa.model.Clip
import com.dskmusic.vixa.model.ClipKind
import com.dskmusic.vixa.model.FitMode
import com.dskmusic.vixa.model.Keyframe
import com.dskmusic.vixa.model.Easing
import com.dskmusic.vixa.model.PersonCut
import com.dskmusic.vixa.model.StickerSpec
import com.dskmusic.vixa.model.Transform
import com.dskmusic.vixa.ai.Vision
import com.dskmusic.vixa.model.Layer
import com.dskmusic.vixa.model.MIN_CLIP_US
import com.dskmusic.vixa.model.TextSpec
import com.dskmusic.vixa.model.TrackType
import com.dskmusic.vixa.model.addToTrack
import com.dskmusic.vixa.model.cutToSourceRanges
import com.dskmusic.vixa.model.splitClipAtTimes
import com.dskmusic.vixa.model.updateClip
import java.io.File

/**
 * Acciones de IA del editor. Cada una comprueba lo que necesita, lanza un trabajo en [com.dskmusic.vixa.ai.AiJobs]
 * (servicio en primer plano, cancelable, con progreso) y aplica el resultado como un único paso del historial.
 * El medio original nunca se toca: el audio procesado es un archivo de caché que se referencia desde el clip.
 */
class AiActions(private val vm: EditorViewModel) {
    private val container get() = vm.container
    private val store get() = container.models
    private var previewPlayer: MediaPlayer? = null

    /** Clip con audio propio al que se le puede aplicar IA de voz. */
    fun hasAudio(c: Clip?): Boolean = c != null && c.hasTimedMedia && c.media?.hasAudio == true && c.aiAudio?.kind != AiTasks.TTS

    private fun refresh() { vm.project.value?.let { vm.player.update(it) } }

    /** Tiempo de la línea de tiempo de un instante del medio dentro de [c] (velocidad constante). */
    private fun toTimeline(c: Clip, srcUs: Long): Long = c.startUs + ((srcUs - c.srcInUs) / c.speed).toLong()

    // ------------------------------------------------------------------------------------------
    // Subtítulos (funciones 1 y 11)
    // ------------------------------------------------------------------------------------------

    /**
     * Subtítulos automáticos de [wholeProject] o del clip seleccionado. Con [translate] Whisper los traduce al
     * inglés y se añaden en una pista nueva, conservando los originales.
     */
    fun subtitles(wholeProject: Boolean, quality: String, language: String, translate: Boolean, maxChars: Int, maxLines: Int, karaoke: Boolean) {
        val p = vm.project.value ?: return
        val sel = vm.selectedClip
        val targets = if (wholeProject || sel == null) p.allClips().filter { hasAudio(it) && !it.muted } else listOfNotNull(sel.takeIf { hasAudio(it) })
        if (targets.isEmpty()) { vm.toast(vm.str(R.string.err_no_audio)); return }
        container.aiJobs.run(vm.str(if (translate) R.string.ai_job_translate else R.string.ai_job_subtitles), { progress ->
            val out = ArrayList<Clip>()
            for ((i, c) in targets.withIndex()) {
                val path = CompositionBuilder.audioPath(c, c.media!!.path)
                val lines = AiTasks.transcribe(store, path, c.srcInUs, c.srcOutUs, quality, language, translate) { progress((i + it) / targets.size) }
                for (s in Speech.subtitles(lines, maxChars, maxLines)) {
                    val a = toTimeline(c, s.startUs).coerceAtLeast(0)
                    val b = toTimeline(c, s.endUs)
                    out.add(
                        Clip(
                            kind = ClipKind.TEXT, startUs = a, durationUs = (b - a).coerceAtLeast(MIN_CLIP_US),
                            text = TextSpec(listOf(Layer(text = s.text)), subtitle = true, wordsUs = s.wordsUs.map { (it / c.speed).toLong() }),
                        ),
                    )
                }
            }
            out
        }) { subs ->
            if (!vm.alive) return@run
            if (subs.isEmpty()) { vm.toast(vm.str(R.string.ai_no_speech)); return@run }
            vm.edit(R.string.hist_subtitles) { pr ->
                var r = pr
                for (s in subs) r = r.addToTrack(s, TrackType.TEXT)
                if (karaoke && r.karaokeColor == 0) r = r.copy(karaokeColor = 0xFFFFD60A.toInt())
                r
            }
            vm.toast(vm.str(R.string.subtitles_imported, subs.size))
        }
    }

    // ------------------------------------------------------------------------------------------
    // Voz con VAD (función 2)
    // ------------------------------------------------------------------------------------------

    private fun withSpeech(c: Clip, titleRes: Int, done: (Clip, List<Pair<Long, Long>>) -> Unit) {
        val m = c.media ?: return
        container.aiJobs.run(vm.str(titleRes), { progress -> AiTasks.speechRanges(store, m.path, c.srcInUs, c.srcOutUs, progress) }) { ranges ->
            if (vm.alive) done(c, ranges)
        }
    }

    /** Quita los silencios con el VAD: conserva solo los tramos con voz (con un pequeño margen). */
    fun removeSilences(c: Clip) = withSpeech(c, R.string.ai_job_vad) { clip, ranges ->
        val keep = AiTasks.merge(ranges, 400_000L, 120_000L, clip.srcInUs, clip.srcOutUs)
        if (keep.isEmpty() || (keep.size == 1 && keep[0].second - keep[0].first >= clip.srcSpanUs - 300_000L)) vm.toast(vm.str(R.string.no_silences))
        else { vm.edit(R.string.hist_silences) { it.cutToSourceRanges(clip.id, keep) }; vm.toast(vm.str(R.string.silences_removed, keep.size)) }
    }

    /** Divide el clip en cada pausa entre frases. */
    fun cutPhrases(c: Clip) = withSpeech(c, R.string.ai_job_vad) { clip, ranges ->
        val merged = AiTasks.merge(ranges, 300_000L, 0L, clip.srcInUs, clip.srcOutUs)
        val cuts = merged.zipWithNext { a, b -> toTimeline(clip, (a.second + b.first) / 2) }
        if (cuts.isEmpty()) vm.toast(vm.str(R.string.no_scenes))
        else { vm.edit(R.string.hist_cut_phrases) { it.splitClipAtTimes(clip.id, cuts) }; vm.toast(vm.str(R.string.scenes_found, cuts.size + 1)) }
    }

    /** Marca el clip como voz con sus tramos hablados: la música baja solo mientras alguien habla. */
    fun duckByVoice(c: Clip) = withSpeech(c, R.string.ai_job_vad) { clip, ranges ->
        val merged = AiTasks.merge(ranges, 500_000L, 0L, clip.srcInUs, clip.srcOutUs)
        val flat = ArrayList<Long>()
        for ((a, b) in merged) { flat.add(toTimeline(clip, a) - clip.startUs); flat.add(toTimeline(clip, b) - clip.startUs) }
        if (flat.isEmpty()) { vm.toast(vm.str(R.string.ai_no_speech)); return@withSpeech }
        vm.edit(R.string.hist_ai_duck) { p -> p.updateClip(clip.id) { it.copy(voiceRangesUs = flat, audioFx = it.audioFx.copy(isVoice = true)) }.copy(autoDuck = true) }
        vm.toast(vm.str(R.string.ai_duck_done))
    }

    // ------------------------------------------------------------------------------------------
    // Audio (funciones 3 y 4)
    // ------------------------------------------------------------------------------------------

    fun denoise(c: Clip, model: String, amount: Float) {
        val m = c.media ?: return
        val ref = AiTasks.denoiseRef(store, m.path, model, amount)
        container.aiJobs.run(vm.str(R.string.ai_job_denoise), { progress -> AiTasks.ensure(store, m.path, ref, progress) }) {
            if (!vm.alive) return@run
            vm.updateClip(R.string.hist_ai_denoise, c.id) { it.copy(aiAudio = ref) }
        }
    }

    /** Activa o desactiva el audio procesado (comparar antes y después) o lo quita del todo. */
    fun setAiAudio(c: Clip, enabled: Boolean?) {
        vm.updateClip(R.string.hist_ai_denoise, c.id) { it.copy(aiAudio = if (enabled == null) null else it.aiAudio?.copy(enabled = enabled)) }
    }

    /** [mode]: "remove_voice" (queda la música), "remove_music" (queda la voz) o "split" (dos clips en pistas distintas). */
    fun separate(c: Clip, model: String, mode: String) {
        val m = c.media ?: return
        val vocals = AiTasks.stemRef(store, m.path, model, true)
        val music = AiTasks.stemRef(store, m.path, model, false)
        container.aiJobs.run(vm.str(R.string.ai_job_separate), { progress -> AiTasks.ensure(store, m.path, vocals, progress) }) {
            if (!vm.alive) return@run
            when (mode) {
                "remove_voice" -> vm.updateClip(R.string.hist_ai_separate, c.id) { it.copy(aiAudio = music) }
                "remove_music" -> vm.updateClip(R.string.hist_ai_separate, c.id) { it.copy(aiAudio = vocals) }
                else -> vm.launch {
                    val base = Importer.clipFor(m, 0, asAudio = true)
                    val second = base.copy(
                        startUs = c.startUs, srcInUs = c.srcInUs, srcOutUs = c.srcOutUs, durationUs = c.durationUs, speed = c.speed, speedCurve = c.speedCurve,
                        aiAudio = music, label = vm.str(R.string.ai_stem_music),
                    )
                    vm.edit(R.string.hist_ai_separate) { p ->
                        p.updateClip(c.id) { it.copy(aiAudio = vocals, audioFx = it.audioFx.copy(isVoice = true)) }.addToTrack(second, TrackType.AUDIO)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Texto a voz (función 5)
    // ------------------------------------------------------------------------------------------

    fun stopPreview() {
        try { previewPlayer?.release() } catch (e: Throwable) { }
        previewPlayer = null
    }

    /** Genera (con caché) y reproduce la voz para escucharla antes de añadirla. */
    fun previewTts(text: String, voice: String, speed: Float, speaker: Int = 0) {
        if (text.isBlank()) return
        val ref = AiTasks.ttsRef(store, voice, text, speed, speaker)
        container.aiJobs.run(vm.str(R.string.ai_job_tts), { _ -> AiTasks.ensure(store, "", ref) }) {
            try {
                stopPreview()
                previewPlayer = MediaPlayer().apply {
                    setDataSource(ref.path)
                    setOnCompletionListener { stopPreview() }
                    prepare()
                    start()
                }
            } catch (e: Throwable) {
                ErrorLog.log("AI/tts preview", e)
                vm.toast(vm.str(R.string.err_generic))
            }
        }
    }

    private suspend fun voiceClip(ref: AiAudioRef, startUs: Long): Clip? {
        val m = Importer.probe(store.context, ref.path, MediaType.AUDIO) ?: return null
        return Importer.clipFor(m, 0, asAudio = true).let { it.copy(startUs = startUs, aiAudio = ref, audioFx = it.audioFx.copy(isVoice = true), label = vm.str(R.string.ai_tts_label)) }
    }

    /** Añade la voz generada como clip de audio en [atUs]. */
    fun addTts(text: String, voice: String, speed: Float, atUs: Long, speaker: Int = 0) {
        if (text.isBlank()) return
        val ref = AiTasks.ttsRef(store, voice, text, speed, speaker)
        container.aiJobs.run(vm.str(R.string.ai_job_tts), { _ -> AiTasks.ensure(store, "", ref) }) {
            if (!vm.alive) return@run
            vm.launch {
                val c = voiceClip(ref, atUs)
                if (c == null) vm.toast(vm.str(R.string.err_generic)) else { vm.edit(R.string.hist_ai_tts) { it.addToTrack(c, TrackType.AUDIO) }; vm.selectOnly(c.id) }
            }
        }
    }

    /** Narra cada subtítulo del proyecto con la voz elegida, colocando el audio donde empieza el subtítulo. */
    fun narrateSubtitles(voice: String, speed: Float, speaker: Int = 0) {
        val subs = subtitleClips(vm.project.value ?: return).filter { (it.text?.layers?.firstOrNull()?.text ?: "").isNotBlank() }
        if (subs.isEmpty()) { vm.toast(vm.str(R.string.no_subtitles)); return }
        val items = subs.map { it.startUs to AiTasks.ttsRef(store, voice, (it.text?.layers?.first()?.text ?: "").replace('\n', ' '), speed, speaker) }
        container.aiJobs.run(vm.str(R.string.ai_job_tts), { progress ->
            for ((i, it) in items.withIndex()) { AiTasks.ensure(store, "", it.second); progress((i + 1f) / items.size) }
        }) {
            if (!vm.alive) return@run
            vm.launch {
                val clips = items.mapNotNull { (t, ref) -> voiceClip(ref, t) }
                if (clips.isEmpty()) vm.toast(vm.str(R.string.err_generic))
                else vm.edit(R.string.hist_ai_tts) { p -> var r = p; for (c in clips) r = r.addToTrack(c, TrackType.AUDIO); r }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Vídeo y personas (funciones 6 y 8)
    // ------------------------------------------------------------------------------------------

    fun canSegment(c: Clip?): Boolean = c != null && (c.kind == ClipKind.VIDEO || c.kind == ClipKind.IMAGE) && c.media != null

    /** Detecta a las personas del clip (máscara en caché) y activa el fondo transparente; los ajustes se cambian en vivo. */
    fun personCut(c: Clip, quality: String) {
        val m = c.media ?: return
        val image = c.kind == ClipKind.IMAGE
        val model = if (quality == "high") Models.SELFIE_HQ else Models.SELFIE
        val from = if (image) 0L else c.srcInUs
        val to = if (image) 0L else c.srcOutUs
        container.aiJobs.run(vm.str(R.string.ai_job_person), { progress -> AiTasks.ensurePerson(store, m.path, image, model, from, to, progress) }) { file ->
            if (!vm.alive) return@run
            vm.updateClip(R.string.hist_ai_person, c.id) { cl ->
                val old = cl.personCut
                cl.copy(personCut = (old ?: PersonCut()).copy(mode = if (old == null || old.mode == 0) 1 else old.mode, model = model, path = file.path, fromUs = from, toUs = to))
            }
        }
    }

    /** Genera con IA los parches que rellenan la zona de "Quitar marca de agua" de [c] (por cada muestra del vídeo) y los guarda en el clip. */
    fun removeWatermark(c: Clip) {
        val wr = c.wmRemove ?: return
        container.aiJobs.run(vm.str(R.string.ai_job_wm), { progress ->
            com.dskmusic.vixa.ai.WmInpaint.generate(store.context, store, c, wr, progress)
        }) { res ->
            if (!vm.alive) return@run
            vm.updateClip(R.string.hist_wm_remove, c.id) {
                it.copy(wmRemove = (it.wmRemove ?: wr).copy(mode = 2, patchPath = res.patchPath, patchStartUs = res.startUs, patchFps = res.fps, patchCount = res.count))
            }
            refresh()
        }
    }

    private class Pt(val t: Long, var x: Float, var y: Float, var s: Float, var r: Float)

    /**
     * Pone un sticker (emoji o imagen) que sigue la cara del vídeo [c]. Se analiza la cara una vez (caché) y el
     * movimiento, tamaño y giro quedan como keyframes normales del sticker, editables. [anchor]: "head", "eyes", "nose" o "face".
     * Los keyframes se calculan con la posición y escala actuales del vídeo: si luego las cambias, vuelve a generarlo.
     */
    fun faceSticker(c: Clip, spec: StickerSpec, anchor: String) {
        val m = c.media ?: return
        val p = vm.project.value ?: return
        container.aiJobs.run(vm.str(R.string.ai_job_face), { progress ->
            val track = AiTasks.faceTrack(store, m.path, c.srcInUs, c.srcOutUs, progress)
            faceKeyframes(p.width, p.height, c, spec.size, track, anchor)
        }) { pts ->
            if (!vm.alive) return@run
            if (pts == null) { vm.toast(vm.str(R.string.ai_face_none)); return@run }
            fun kf(sel: (Pt) -> Float) = pts.map { Keyframe(it.t, sel(it), Easing.LINEAR) }
            val first = pts.first()
            val sticker = Clip(
                kind = ClipKind.STICKER, startUs = c.startUs, durationUs = c.durationUs, sticker = spec,
                transform = Transform(x = first.x, y = first.y, scale = first.s, rotation = first.r),
                keyframes = mapOf("x" to kf { it.x }, "y" to kf { it.y }, "scale" to kf { it.s }, "rotation" to kf { it.r }),
            )
            vm.edit(R.string.hist_ai_face) { it.addToTrack(sticker, TrackType.STICKER) }
            vm.selectOnly(sticker.id)
        }
    }

    /** Convierte la pista de cara (coordenadas del fotograma) en posición, escala y giro del sticker sobre el lienzo. */
    private fun faceKeyframes(cw: Int, ch: Int, c: Clip, stickerSize: Float, track: List<Vision.FaceSample>, anchor: String): List<Pt>? {
        val m = c.media ?: return null
        if (track.none { it.found }) return null
        val tr = c.transform
        val canvasW = cw.toFloat(); val canvasH = ch.toFloat()
        val cropW = (1f - tr.cropL - tr.cropR).coerceAtLeast(0.01f)
        val cropH = (1f - tr.cropT - tr.cropB).coerceAtLeast(0.01f)
        val srcAspect = (m.width * cropW) / (m.height * cropH).coerceAtLeast(1f)
        val canvasAspect = canvasW / canvasH
        var bw = 1f; var bh = 1f
        when (tr.fit) {
            FitMode.FIT -> if (srcAspect > canvasAspect) bh = canvasAspect / srcAspect else bw = srcAspect / canvasAspect
            FitMode.FILL -> if (srcAspect > canvasAspect) bw = srcAspect / canvasAspect else bh = canvasAspect / srcAspect
            FitMode.STRETCH -> {}
        }
        val qw = bw * tr.scale * tr.sx
        val qh = bh * tr.scale * tr.sy
        val th = Math.toRadians(tr.rotation.toDouble())
        val cs = Math.cos(th).toFloat(); val sn = Math.sin(th).toFloat()
        val minSide = minOf(canvasW, canvasH)
        val raw = ArrayList<Pt>()
        var last: Pt? = null
        for (s in track) {
            val pt: Pt
            if (s.found) {
                val u: Float; val v: Float; val wFrac: Float
                when (anchor) {
                    "eyes" -> { u = s.eyeX; v = s.eyeY; wFrac = s.eyeD * 2.6f }
                    "nose" -> { u = s.noseX; v = s.noseY; wFrac = s.w * 0.4f }
                    "mouth" -> { u = s.mouthX; v = s.mouthY; wFrac = s.w * 0.35f }
                    "face" -> { u = s.cx; v = s.cy; wFrac = s.w * 1.25f }
                    else -> { u = s.headX; v = s.headY; wFrac = s.w * 1.3f }
                }
                var a = (u - tr.cropL) / cropW - 0.5f
                var b = (v - tr.cropT) / cropH - 0.5f
                if (tr.flipH) a = -a
                if (tr.flipV) b = -b
                val ox = a * qw * canvasW
                val oy = b * qh * canvasH
                val rx = ox * cs - oy * sn
                val ry = ox * sn + oy * cs
                val widthPx = wFrac / cropW * qw * canvasW
                val roll = if (tr.flipH) -s.roll else s.roll
                pt = Pt(((s.srcUs - c.srcInUs) / c.speed).toLong().coerceIn(0L, c.durationUs), tr.x + rx / canvasW, tr.y + ry / canvasH, (widthPx / (stickerSize * minSide)).coerceIn(0.02f, 20f), roll + tr.rotation)
                last = pt
            } else {
                // Sin cara en este fotograma: se mantiene la última posición conocida.
                val l = last
                pt = if (l != null) Pt(((s.srcUs - c.srcInUs) / c.speed).toLong().coerceIn(0L, c.durationUs), l.x, l.y, l.s, l.r) else Pt(-1, 0f, 0f, 1f, 0f)
            }
            raw.add(pt)
        }
        // Los fotogramas anteriores a la primera cara toman su posición.
        val firstIdx = raw.indexOfFirst { it.t >= 0 }
        if (firstIdx < 0) return null
        for (i in 0 until firstIdx) { val f = raw[firstIdx]; raw[i] = Pt(((track[i].srcUs - c.srcInUs) / c.speed).toLong().coerceIn(0L, c.durationUs), f.x, f.y, f.s, f.r) }
        // Giros continuos (sin saltos de ±360) y suavizado de 3 puntos para quitar el temblor.
        for (i in 1 until raw.size) { while (raw[i].r - raw[i - 1].r > 180f) raw[i].r -= 360f; while (raw[i].r - raw[i - 1].r < -180f) raw[i].r += 360f }
        val sm = raw.mapIndexed { i, q ->
            val a = raw[maxOf(i - 1, 0)]; val b = raw[minOf(i + 1, raw.size - 1)]
            Pt(q.t, (a.x + q.x * 2 + b.x) / 4f, (a.y + q.y * 2 + b.y) / 4f, (a.s + q.s * 2 + b.s) / 4f, (a.r + q.r * 2 + b.r) / 4f)
        }
        return sm.distinctBy { it.t }
    }

    // ------------------------------------------------------------------------------------------
    // Regeneración de resultados que faltan en la caché
    // ------------------------------------------------------------------------------------------

    /** Al abrir el proyecto: los archivos de IA borrados de la caché se vuelven a generar en segundo plano. */
    fun regenerate() {
        val p = vm.project.value ?: return
        val missing = p.allClips().filter { c -> val a = c.aiAudio; a != null && a.path.isNotEmpty() && !File(a.path).exists() && store.isReady(a.model) }
        // Máscaras de personas borradas de la caché: se calculan de nuevo con los mismos datos.
        val masks = p.allClips().filter { c -> val pc = c.personCut; pc != null && pc.path.isNotEmpty() && !File(pc.path).exists() && c.media != null && store.isReady(pc.model) }
        // Análisis de seguimiento de objetos cuyo archivo no está: se calculan de nuevo con sus mismos parámetros.
        val trks = p.allClips().filter { it.media != null }.flatMap { c -> c.objTracks.filter { it.path.isNotEmpty() && !File(it.path).exists() }.map { c to it } }
        if (missing.isEmpty() && masks.isEmpty() && trks.isEmpty()) return
        container.aiJobs.run(vm.str(R.string.ai_job_regen), { progress ->
            val total = missing.size + masks.size + trks.size
            for ((i, c) in missing.withIndex()) {
                try { AiTasks.ensure(store, c.media?.path ?: "", c.aiAudio!!) { progress((i + it) / total) } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { ErrorLog.log("AI/regen", e) }
            }
            for ((j, c) in masks.withIndex()) {
                val pc = c.personCut!!
                try {
                    val f = AiTasks.ensurePerson(store, c.media!!.path, c.kind == ClipKind.IMAGE, pc.model, pc.fromUs, pc.toUs) { progress((missing.size + j + it) / total) }
                    // La ruta es determinista; si difiere (otro medio reenlazado) se copia al sitio que espera el proyecto.
                    if (f.path != pc.path) f.copyTo(File(pc.path), overwrite = true)
                }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { ErrorLog.log("AI/regen", e) }
            }
            for ((j, pair) in trks.withIndex()) {
                val (c, ot) = pair
                try {
                    com.dskmusic.vixa.engine.ObjectTracker.track(c.media!!.path, ot.fromUs, ot.toUs, ot.startUs, ot.u, ot.v, ot.size) { progress((missing.size + masks.size + j + it) / total) }?.save(File(ot.path))
                }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { ErrorLog.log("AI/regen", e) }
            }
        }) { if (vm.alive) refresh() }
    }
}
