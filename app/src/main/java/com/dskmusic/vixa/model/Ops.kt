package com.dskmusic.vixa.model

import kotlin.math.max
import kotlin.math.min

/** Operaciones puras sobre el modelo inmutable. Todas devuelven un proyecto nuevo ya normalizado. */

const val MIN_CLIP_US = 100_000L
const val MIN_TRANSITION_US = 100_000L
const val SPEED_SEGMENTS = 24

fun Long.snapMs(): Long = (this / 1000L) * 1000L

// ---------------------------------------------------------------------------------------------
// Velocidad
// ---------------------------------------------------------------------------------------------

/** Velocidad en la posición [pos] (0..1) del tramo de origen. */
fun Clip.speedAt(pos: Float): Float {
    if (speedCurve.size < 2) return speed.coerceIn(0.1f, 10f)
    val pts = speedCurve
    if (pos <= pts.first().pos) return pts.first().speed.coerceIn(0.1f, 10f)
    for (i in 1 until pts.size) {
        val a = pts[i - 1]
        val b = pts[i]
        if (pos <= b.pos) {
            val t = if (b.pos - a.pos <= 0f) 0f else (pos - a.pos) / (b.pos - a.pos)
            return (a.speed + (b.speed - a.speed) * t).coerceIn(0.1f, 10f)
        }
    }
    return pts.last().speed.coerceIn(0.1f, 10f)
}

/** Velocidades por segmento del tramo de origen (un único valor si la velocidad es constante). */
fun Clip.segmentSpeeds(): FloatArray {
    if (speedCurve.size < 2) return floatArrayOf(speed.coerceIn(0.1f, 10f))
    return FloatArray(SPEED_SEGMENTS) { i -> speedAt((i + 0.5f) / SPEED_SEGMENTS) }
}

val Clip.hasSpeedChange: Boolean get() = speedCurve.size >= 2 || speed != 1f

/** Duración en la línea de tiempo de un tramo de origen de [spanUs] con la velocidad de este clip. */
fun Clip.timelineDurationFor(spanUs: Long): Long {
    val speeds = segmentSpeeds()
    val seg = spanUs.toDouble() / speeds.size
    var total = 0.0
    for (s in speeds) total += seg / s
    return total.toLong()
}

/** Desplazamiento en el origen (desde srcInUs) que corresponde al instante local [localUs] del clip. */
fun Clip.sourceOffsetAt(localUs: Long): Long {
    if (!hasTimedMedia) return localUs
    val speeds = segmentSpeeds()
    val span = srcSpanUs.toDouble()
    val seg = span / speeds.size
    var remaining = localUs.coerceAtLeast(0).toDouble()
    var offset = 0.0
    for (s in speeds) {
        val segTimeline = seg / s
        if (remaining <= segTimeline) return (offset + remaining * s).toLong().coerceIn(0, srcSpanUs)
        remaining -= segTimeline
        offset += seg
    }
    return srcSpanUs
}

private fun Clip.subCurve(from: Float, to: Float): List<SpeedPoint> {
    if (speedCurve.size < 2) return emptyList()
    return List(5) { i ->
        val p = i / 4f
        SpeedPoint(p, speedAt(from + (to - from) * p))
    }
}

// ---------------------------------------------------------------------------------------------
// Keyframes
// ---------------------------------------------------------------------------------------------

fun List<Keyframe>.valueAt(tUs: Long): Float {
    if (isEmpty()) return 0f
    if (tUs <= first().tUs) return first().value
    for (i in 1 until size) {
        val a = this[i - 1]
        val b = this[i]
        if (tUs <= b.tUs) {
            val span = (b.tUs - a.tUs).coerceAtLeast(1)
            val t = (tUs - a.tUs).toFloat() / span.toFloat()
            return a.value + (b.value - a.value) * b.easing.ease(t)
        }
    }
    return last().value
}

/** Valor de la propiedad [prop] en el instante local [localUs]: keyframes si existen, si no [base]. */
fun Clip.kf(prop: String, localUs: Long, base: Float): Float {
    val list = keyframes[prop]
    return if (list.isNullOrEmpty()) base else list.valueAt(localUs)
}

fun Clip.hasKeyframes(prop: String): Boolean = !keyframes[prop].isNullOrEmpty()

/** Añade o sustituye un keyframe (tolerancia de medio fotograma a 30 fps). */
fun Clip.withKeyframe(prop: String, tUs: Long, value: Float, easing: Easing = Easing.EASE_IN_OUT): Clip {
    val tol = 16_000L
    val list = (keyframes[prop] ?: emptyList()).filter { kotlin.math.abs(it.tUs - tUs) > tol } +
        Keyframe(tUs.coerceIn(0, durationUs), value, easing)
    return copy(keyframes = keyframes + (prop to list.sortedBy { it.tUs }))
}

fun Clip.withoutKeyframe(prop: String, tUs: Long): Clip {
    val tol = 16_000L
    val list = (keyframes[prop] ?: emptyList()).filter { kotlin.math.abs(it.tUs - tUs) > tol }
    return copy(keyframes = if (list.isEmpty()) keyframes - prop else keyframes + (prop to list))
}

fun Clip.keyframeNear(prop: String, tUs: Long): Keyframe? =
    keyframes[prop]?.firstOrNull { kotlin.math.abs(it.tUs - tUs) <= 16_000L }

// ---------------------------------------------------------------------------------------------
// Normalización
// ---------------------------------------------------------------------------------------------

private fun Clip.fixed(): Clip {
    var c = this
    if (c.hasTimedMedia) {
        val mediaDur = c.media?.durationUs ?: 0L
        var inUs = c.srcInUs.coerceAtLeast(0)
        var outUs = c.srcOutUs
        if (mediaDur > 0) {
            outUs = outUs.coerceAtMost(mediaDur)
            inUs = inUs.coerceAtMost(max(0L, mediaDur - MIN_CLIP_US))
        }
        if (outUs - inUs < MIN_CLIP_US) outUs = inUs + MIN_CLIP_US
        c = c.copy(srcInUs = inUs.snapMs(), srcOutUs = outUs.snapMs())
        c = c.copy(durationUs = c.timelineDurationFor(c.srcSpanUs).snapMs().coerceAtLeast(MIN_CLIP_US))
    } else {
        c = c.copy(durationUs = c.durationUs.snapMs().coerceAtLeast(MIN_CLIP_US))
    }
    return c.copy(
        startUs = c.startUs.coerceAtLeast(0).snapMs(),
        fadeInUs = c.fadeInUs.coerceIn(0, c.durationUs / 2),
        fadeOutUs = c.fadeOutUs.coerceIn(0, c.durationUs / 2),
        blackInUs = c.blackInUs.coerceIn(0, c.durationUs / 2),
        blackOutUs = c.blackOutUs.coerceIn(0, c.durationUs / 2),
    )
}

/** Pista principal "magnética": los clips van seguidos y las transiciones los solapan. */
private fun layoutMain(clips: List<Clip>): List<Clip> {
    val out = ArrayList<Clip>(clips.size)
    var cursor = 0L
    for ((i, raw) in clips.withIndex()) {
        var c = raw
        var overlap = 0L
        val tr = c.transitionIn
        if (i == 0 || tr == null) {
            if (tr != null) c = c.copy(transitionIn = null)
        } else {
            val prev = out[i - 1]
            // Un clip no puede solaparse a la vez con la transición anterior y la siguiente.
            val prevFree = prev.durationUs - (prev.transitionIn?.durationUs ?: 0L)
            val d = min(tr.durationUs, min(prevFree, c.durationUs) / 2).snapMs()
            if (d < MIN_TRANSITION_US) c = c.copy(transitionIn = null)
            else {
                overlap = d
                if (d != tr.durationUs) c = c.copy(transitionIn = tr.copy(durationUs = d))
            }
        }
        c = c.copy(startUs = cursor - overlap)
        cursor = c.startUs + c.durationUs
        out.add(c)
    }
    return out
}

/**
 * Superposición con transiciones (p. ej. una copia de la principal): un clip con transición se solapa con el anterior y
 * la transición dura exactamente ese solape. Sin solape suficiente la transición no puede existir y se quita.
 */
private fun layoutOverlay(clips: List<Clip>): List<Clip> {
    val out = ArrayList<Clip>(clips.size)
    for (raw in clips) {
        var c = raw
        val prev = out.lastOrNull()
        val ov = if (prev == null) 0L else prev.endUs - c.startUs
        val tr = c.transitionIn
        if (tr != null) {
            val prevFree = if (prev == null) 0L else prev.durationUs - (prev.transitionIn?.durationUs ?: 0L)
            c = if (ov < MIN_TRANSITION_US || ov > min(prevFree, c.durationUs)) c.copy(transitionIn = null)
            else if (ov != tr.durationUs) c.copy(transitionIn = tr.copy(durationUs = ov)) else c
        }
        // Sin transición no puede haber solape (p. ej. al quitarla): el clip pasa a empezar donde acaba el anterior.
        if (c.transitionIn == null && prev != null && c.startUs < prev.endUs) c = c.copy(startUs = prev.endUs)
        out.add(c)
    }
    return out
}

fun Project.normalized(): Project {
    var ts = tracks
    if (ts.none { it.type == TrackType.MAIN }) ts = ts + Track(type = TrackType.MAIN)
    ts = ts.map { t ->
        val fixed = t.clips.map { it.fixed() }
        if (t.type == TrackType.MAIN) t.copy(clips = layoutMain(fixed))
        else if (t.type == TrackType.OVERLAY) t.copy(clips = layoutOverlay(fixed.sortedBy { it.startUs }))
        else t.copy(clips = fixed.sortedBy { it.startUs }.map { if (it.transitionIn != null) it.copy(transitionIn = null) else it })
    }
    return copy(tracks = ts)
}

// ---------------------------------------------------------------------------------------------
// Utilidades de pistas
// ---------------------------------------------------------------------------------------------

fun ClipKind.trackType(): TrackType = when (this) {
    ClipKind.VIDEO, ClipKind.IMAGE, ClipKind.COLOR -> TrackType.OVERLAY
    ClipKind.AUDIO -> TrackType.AUDIO
    ClipKind.TEXT -> TrackType.TEXT
    ClipKind.STICKER -> TrackType.STICKER
    ClipKind.ADJUST -> TrackType.ADJUST
}

fun TrackType.accepts(kind: ClipKind): Boolean = when (this) {
    TrackType.MAIN, TrackType.OVERLAY -> kind == ClipKind.VIDEO || kind == ClipKind.IMAGE || kind == ClipKind.COLOR
    TrackType.AUDIO -> kind == ClipKind.AUDIO
    TrackType.TEXT -> kind == ClipKind.TEXT
    TrackType.STICKER -> kind == ClipKind.STICKER
    TrackType.ADJUST -> kind == ClipKind.ADJUST
}

fun Track.isFree(startUs: Long, endUs: Long, excludeId: String = ""): Boolean =
    clips.none { it.id != excludeId && it.startUs < endUs && startUs < it.endUs }

/**
 * Dos clips de audio de la misma pista pueden solaparse (crossfade automático) hasta la mitad del más corto;
 * el resto de pistas libres no admiten solapes.
 */
fun crossfadeOk(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean {
    val ov = min(aEnd, bEnd) - max(aStart, bStart)
    return ov <= 0 || ov <= min(aEnd - aStart, bEnd - bStart) / 2
}

/**
 * ¿Cabe un clip en [startUs, endUs)? En audio se admite el solape de crossfade; en superposición, el solape de una
 * transición (el clip que empieza después lleva [Clip.transitionIn]). [moving] es el clip que se coloca.
 */
fun Track.fits(startUs: Long, endUs: Long, excludeId: String = "", moving: Clip? = null): Boolean = when (type) {
    TrackType.AUDIO -> clips.all { it.id == excludeId || crossfadeOk(startUs, endUs, it.startUs, it.endUs) }
    TrackType.OVERLAY -> clips.all { o ->
        if (o.id == excludeId) return@all true
        val ov = min(endUs, o.endUs) - max(startUs, o.startUs)
        if (ov <= 0) return@all true
        val laterHasTr = if (startUs >= o.startUs) moving?.transitionIn != null else o.transitionIn != null
        laterHasTr && crossfadeOk(startUs, endUs, o.startUs, o.endUs)
    }
    else -> isFree(startUs, endUs, excludeId)
}

private fun Project.mapTracks(f: (Track) -> Track): Project = copy(tracks = tracks.map(f))

fun Project.updateClip(id: String, f: (Clip) -> Clip): Project =
    mapTracks { t -> if (t.clips.any { it.id == id }) t.copy(clips = t.clips.map { if (it.id == id) f(it) else it }) else t }.normalized()

fun Project.updateClips(ids: Collection<String>, f: (Clip) -> Clip): Project =
    mapTracks { t -> t.copy(clips = t.clips.map { if (it.id in ids) f(it) else it }) }.normalized()

fun Project.updateTrack(id: String, f: (Track) -> Track): Project =
    mapTracks { if (it.id == id) f(it) else it }.normalized()

/** Posición en la lista donde insertar una pista nueva: las visuales encima de la principal, el audio debajo. */
private fun Project.insertIndexFor(type: TrackType): Int {
    val mainIdx = tracks.indexOfFirst { it.type == TrackType.MAIN }.let { if (it < 0) tracks.size else it }
    return when (type) {
        TrackType.AUDIO -> tracks.size
        TrackType.MAIN -> mainIdx
        TrackType.OVERLAY -> mainIdx
        else -> 0
    }
}

fun Project.addTrack(type: TrackType): Project {
    if (type == TrackType.MAIN) return this
    val list = tracks.toMutableList()
    list.add(insertIndexFor(type), Track(type = type))
    return copy(tracks = list).normalized()
}

fun Project.removeTrack(id: String): Project {
    val t = track(id) ?: return this
    if (t.type == TrackType.MAIN) return this
    return copy(tracks = tracks.filter { it.id != id }).normalized()
}

/**
 * Sube ([delta] < 0) o baja una pista con todo su contenido (incluida la principal). El orden es el de las capas:
 * la de más arriba se ve encima, así que una superposición bajo la principal queda tapada por ella.
 */
fun Project.moveTrack(id: String, delta: Int): Project {
    val i = tracks.indexOfFirst { it.id == id }
    if (i < 0) return this
    val j = i + delta
    if (j < 0 || j >= tracks.size) return this
    val list = tracks.toMutableList()
    val t = list.removeAt(i)
    list.add(j, t)
    return copy(tracks = list)
}

fun Project.canMoveTrack(id: String, delta: Int): Boolean = moveTrack(id, delta) !== this

/**
 * Duplica una pista con todo su contenido justo debajo de ella. Los clips copiados llevan ids nuevos.
 * La copia de la principal sale como superposición (encima de la principal) en las mismas posiciones.
 */
fun Project.duplicateTrack(id: String): Project {
    val i = tracks.indexOfFirst { it.id == id }
    if (i < 0) return this
    val t = tracks[i]
    val isMain = t.type == TrackType.MAIN
    val groups = HashMap<String, String>()
    // El hueco inicial de la principal no es contenido: no se copia.
    val clips = t.clips.filter { !(isMain && it.gap) }.map { c ->
        c.copy(
            id = newId(),
            groupId = if (c.groupId.isEmpty()) "" else groups.getOrPut(c.groupId) { newId() },
        )
    }
    // La copia es idéntica: mismas posiciones y mismas transiciones (las superposiciones también las admiten).
    val copyTrack = t.copy(id = newId(), type = if (isMain) TrackType.OVERLAY else t.type, clips = clips)
    val list = tracks.toMutableList()
    list.add(if (isMain) i else i + 1, copyTrack)
    return copy(tracks = list).normalized()
}

// ---------------------------------------------------------------------------------------------
// Añadir / quitar clips
// ---------------------------------------------------------------------------------------------

/** Inserta clips en la pista principal en [index] (-1 = al final). */
fun Project.insertMain(clips: List<Clip>, index: Int = -1): Project {
    val base = if (tracks.none { it.type == TrackType.MAIN }) copy(tracks = tracks + Track(type = TrackType.MAIN)) else this
    return base.mapTracks { t ->
        if (t.type != TrackType.MAIN) t else {
            val list = t.clips.toMutableList()
            val at = if (index < 0 || index > list.size) list.size else index
            list.addAll(at, clips)
            t.copy(clips = list)
        }
    }.normalized()
}

/** Índice de inserción en la pista principal para el instante [timeUs]. */
fun Project.mainIndexAt(timeUs: Long): Int {
    val clips = mainTrack.clips
    for ((i, c) in clips.withIndex()) if (timeUs < c.startUs + c.durationUs / 2) return i
    return clips.size
}

/** Índice de inserción en la principal justo después del clip que hay bajo [timeUs] (al final si no hay ninguno). */
fun Project.mainIndexAfter(timeUs: Long): Int {
    val clips = mainTrack.clips
    for ((i, c) in clips.withIndex()) if (timeUs < c.endUs) return i + 1
    return clips.size
}

/** Coloca un clip en la primera pista libre de su tipo (creándola si hace falta). */
fun Project.addToTrack(clip: Clip, type: TrackType = clip.kind.trackType()): Project {
    val c = clip.copy(startUs = clip.startUs.coerceAtLeast(0))
    val target = tracks.firstOrNull { it.type == type && !it.locked && it.isFree(c.startUs, c.startUs + c.durationUs) }
    if (target != null) return updateTrack(target.id) { it.copy(clips = it.clips + c) }
    val list = tracks.toMutableList()
    list.add(insertIndexFor(type), Track(type = type, clips = listOf(c)))
    return copy(tracks = list).normalized()
}

/** Expande una selección con los clips de los mismos grupos. */
fun Project.withGroups(ids: Collection<String>): Set<String> {
    val groups = allClips().filter { it.id in ids && it.groupId.isNotEmpty() }.map { it.groupId }.toSet()
    if (groups.isEmpty()) return ids.toSet()
    return ids.toSet() + allClips().filter { it.groupId in groups }.map { it.id }
}

fun Project.removeClips(ids: Collection<String>): Project {
    val all = withGroups(ids)
    return mapTracks { t -> t.copy(clips = t.clips.filter { it.id !in all }) }.normalized()
}

fun Project.duplicateClip(id: String): Project {
    val t = trackOf(id) ?: return this
    val c = clip(id) ?: return this
    val copy = c.copy(id = newId(), transitionIn = null, groupId = "")
    return if (t.type == TrackType.MAIN) {
        insertMain(listOf(copy), t.clips.indexOfFirst { it.id == id } + 1)
    } else {
        addToTrack(copy.copy(startUs = c.endUs), t.type)
    }
}

// ---------------------------------------------------------------------------------------------
// Dividir, recortar, mover
// ---------------------------------------------------------------------------------------------

private fun Clip.splitAt(atUs: Long): Pair<Clip, Clip>? {
    val local = atUs - startUs
    if (local < MIN_CLIP_US || durationUs - local < MIN_CLIP_US) return null
    val off = sourceOffsetAt(local)
    val frac = if (srcSpanUs > 0) off.toFloat() / srcSpanUs.toFloat() else 0f
    val leftKf = keyframes.mapValues { (_, l) -> l.filter { it.tUs <= local } }.filterValues { it.isNotEmpty() }
    val rightKf = keyframes.mapValues { (_, l) -> l.filter { it.tUs >= local }.map { it.copy(tUs = it.tUs - local) } }
        .filterValues { it.isNotEmpty() }
    val left = copy(
        durationUs = local,
        srcOutUs = if (hasTimedMedia) srcInUs + off else srcOutUs,
        speedCurve = subCurve(0f, frac),
        keyframes = leftKf, animOut = null, fadeOutUs = 0, blackOutUs = 0,
    )
    val right = copy(
        id = newId(),
        startUs = atUs,
        durationUs = durationUs - local,
        srcInUs = if (hasTimedMedia) srcInUs + off else srcInUs,
        speedCurve = subCurve(frac, 1f),
        keyframes = rightKf, animIn = null, fadeInUs = 0, blackInUs = 0, transitionIn = null,
    )
    return left to right
}

/** Divide el clip [id] en el instante [atUs] de la línea de tiempo. */
fun Project.splitClip(id: String, atUs: Long, keep: Int = 0): Project {
    val c = clip(id) ?: return this
    val parts = c.splitAt(atUs.snapMs()) ?: return this
    return mapTracks { t ->
        val i = t.clips.indexOfFirst { it.id == id }
        if (i < 0) t else {
            val list = t.clips.toMutableList()
            // keep: 0 = ambas partes, 1 = solo la anterior (borra lo posterior), 2 = solo la posterior (borra lo anterior).
            when (keep) {
                1 -> list[i] = parts.first
                2 -> list[i] = parts.second
                else -> { list[i] = parts.first; list.add(i + 1, parts.second) }
            }
            t.copy(clips = list)
        }
    }.normalized()
}

/** Divide un clip en varios instantes de la línea de tiempo (cortes al ritmo, escenas…). */
fun Project.splitClipAtTimes(id: String, timesUs: List<Long>): Project {
    var p = this
    var currentId = id
    for (t in timesUs.sorted()) {
        val c = p.clip(currentId) ?: break
        if (t <= c.startUs + MIN_CLIP_US || t >= c.endUs - MIN_CLIP_US) continue
        val track = p.trackOf(currentId) ?: break
        val idx = track.clips.indexOfFirst { it.id == currentId }
        p = p.splitClip(currentId, t)
        currentId = p.track(track.id)?.clips?.getOrNull(idx + 1)?.id ?: break
    }
    return p
}

/** Sustituye un clip por los tramos de origen [keepSrc] (inicio/fin en tiempo de origen). Para quitar silencios. */
fun Project.cutToSourceRanges(id: String, keepSrc: List<Pair<Long, Long>>): Project {
    val c = clip(id) ?: return this
    if (keepSrc.isEmpty() || !c.hasTimedMedia) return this
    val t = trackOf(id) ?: return this
    var cursor = c.startUs
    val pieces = keepSrc.mapIndexed { i, (a, b) ->
        val piece = c.copy(
            id = if (i == 0) c.id else newId(), startUs = cursor, srcInUs = a, srcOutUs = b,
            speedCurve = emptyList(), keyframes = emptyMap(),
            transitionIn = if (i == 0) c.transitionIn else null,
        ).fixed()
        cursor += piece.durationUs
        piece
    }
    return mapTracks { tr ->
        if (tr.id != t.id) tr else {
            val list = tr.clips.toMutableList()
            val i = list.indexOfFirst { it.id == id }
            list.removeAt(i)
            list.addAll(i, pieces)
            tr.copy(clips = list)
        }
    }.normalized()
}

/**
 * Recorta el extremo inicial ([start] = true) o final de un clip moviéndolo [deltaUs] en la línea de tiempo.
 * Respeta los límites del medio y de los clips vecinos.
 */
fun Project.trimClip(id: String, start: Boolean, deltaUs: Long): Project {
    val t = trackOf(id) ?: return this
    val c = clip(id) ?: return this
    val isMain = t.type == TrackType.MAIN
    val i = t.clips.indexOfFirst { it.id == id }
    // En audio (crossfade) y en superposición con transición el vecino puede solaparse: el límite es su punto medio.
    val audioTrack = t.type == TrackType.AUDIO
    val ovPrev = audioTrack || (t.type == TrackType.OVERLAY && c.transitionIn != null)
    val ovNext = audioTrack || (t.type == TrackType.OVERLAY && t.clips.getOrNull(i + 1)?.transitionIn != null)
    val prevEnd = if (isMain || i <= 0) 0L else t.clips[i - 1].let { if (ovPrev) it.startUs + it.durationUs / 2 else it.endUs }
    val nextStart = if (isMain || i >= t.clips.size - 1) Long.MAX_VALUE else t.clips[i + 1].let { if (ovNext) it.startUs + it.durationUs / 2 else it.startUs }
    var d = deltaUs
    val avgSpeed = if (c.durationUs > 0 && c.hasTimedMedia) c.srcSpanUs.toDouble() / c.durationUs.toDouble() else 1.0
    val nc: Clip
    if (start) {
        d = d.coerceAtMost(c.durationUs - MIN_CLIP_US)
        if (!isMain) d = d.coerceAtLeast(prevEnd - c.startUs)
        if (c.hasTimedMedia) {
            val srcDelta = (d * avgSpeed).toLong().coerceAtLeast(-c.srcInUs)
            d = (srcDelta / avgSpeed).toLong()
            nc = c.copy(srcInUs = c.srcInUs + srcDelta, startUs = if (isMain) c.startUs else c.startUs + d)
        } else {
            nc = c.copy(startUs = if (isMain) c.startUs else c.startUs + d, durationUs = c.durationUs - d)
        }
    } else {
        d = d.coerceAtLeast(MIN_CLIP_US - c.durationUs)
        if (!isMain && nextStart != Long.MAX_VALUE) d = d.coerceAtMost(nextStart - c.endUs)
        if (c.hasTimedMedia) {
            val mediaDur = c.media?.durationUs ?: 0L
            var srcDelta = (d * avgSpeed).toLong()
            if (mediaDur > 0) srcDelta = srcDelta.coerceAtMost(mediaDur - c.srcOutUs)
            nc = c.copy(srcOutUs = c.srcOutUs + srcDelta)
        } else {
            nc = c.copy(durationUs = c.durationUs + d)
        }
    }
    return updateClip(id) { nc }
}

/** Mueve un clip de pista libre a [startUs] en la pista [trackId] si hay hueco; si no, no cambia nada. */
fun Project.moveClip(id: String, trackId: String, startUs: Long): Project {
    val c = clip(id) ?: return this
    val from = trackOf(id) ?: return this
    val to = track(trackId) ?: return this
    if (from.type == TrackType.MAIN || to.type == TrackType.MAIN) return this
    if (!to.type.accepts(c.kind) || to.locked) return this
    val s = startUs.coerceAtLeast(0).snapMs()
    if (!to.fits(s, s + c.durationUs, excludeId = id, moving = c)) return this
    val moved = c.copy(startUs = s)
    return mapTracks { t ->
        when {
            t.id == from.id && t.id == to.id -> t.copy(clips = t.clips.map { if (it.id == id) moved else it })
            t.id == from.id -> t.copy(clips = t.clips.filter { it.id != id })
            t.id == to.id -> t.copy(clips = t.clips + moved)
            else -> t
        }
    }.normalized()
}

/**
 * Arrastre vertical entre la pista principal y una de superposición (vídeo/imagen). Al subir a superposición el clip
 * pasa a ser un Picture-in-Picture (escala 50 % si no tenía transformación propia); al bajar a la principal recupera
 * pantalla completa si era ese PiP por defecto. Si la pista destino no tiene hueco, no cambia nada.
 */
fun Project.moveAcrossMain(id: String, toTrackId: String, startUs: Long): Project {
    val c = clip(id) ?: return this
    val from = trackOf(id) ?: return this
    val to = track(toTrackId) ?: return this
    if (from.id == to.id || to.locked || !c.isVisual) return this
    val pip = Transform(scale = 0.5f)
    return when {
        from.type == TrackType.MAIN && to.type == TrackType.OVERLAY -> {
            val s = startUs.coerceAtLeast(0).snapMs()
            if (!to.isFree(s, s + c.durationUs)) return this
            val moved = c.copy(startUs = s, transitionIn = null, transform = if (c.transform == Transform()) pip else c.transform)
            mapTracks { t -> when (t.id) { from.id -> t.copy(clips = t.clips.filter { it.id != id }); to.id -> t.copy(clips = t.clips + moved); else -> t } }.normalized()
        }
        from.type == TrackType.OVERLAY && to.type == TrackType.MAIN -> {
            val moved = c.copy(transform = if (c.transform == pip) Transform() else c.transform)
            val removed = mapTracks { t -> if (t.id == from.id) t.copy(clips = t.clips.filter { it.id != id }) else t }
            removed.insertMain(listOf(moved), removed.mainIndexAt(c.startUs))
        }
        else -> this
    }
}

/** Mueve un grupo de clips de pistas libres el mismo desplazamiento (si todos caben). */
fun Project.shiftClips(ids: Collection<String>, deltaUs: Long): Project {
    val set = ids.toSet()
    var d = deltaUs
    for (c in allClips()) if (c.id in set) d = d.coerceAtLeast(-c.startUs)
    val candidate = mapTracks { t ->
        if (t.type == TrackType.MAIN) t else t.copy(clips = t.clips.map { if (it.id in set) it.copy(startUs = it.startUs + d) else it })
    }
    for (t in candidate.tracks) {
        if (t.type == TrackType.MAIN) continue
        val sorted = t.clips.sortedBy { it.startUs }
        for (k in 1 until sorted.size) {
            val a = sorted[k - 1]
            val b = sorted[k]
            val allowed = t.type == TrackType.AUDIO || (t.type == TrackType.OVERLAY && b.transitionIn != null)
            if (b.startUs < a.endUs && (!allowed || !crossfadeOk(a.startUs, a.endUs, b.startUs, b.endUs))) return this
        }
    }
    return candidate.normalized()
}

/** Reordena un clip de la pista principal a la posición [newIndex]. */
fun Project.reorderMain(id: String, newIndex: Int): Project = mapTracks { t ->
    if (t.type != TrackType.MAIN) t else {
        val i = t.clips.indexOfFirst { it.id == id }
        if (i < 0) t else {
            val list = t.clips.toMutableList()
            // La transición viaja con el clip (solo se pierde si queda el primero, donde no puede haberla).
            val c = list.removeAt(i)
            list.add(newIndex.coerceIn(0, list.size), c)
            t.copy(clips = list)
        }
    }
}.normalized()

/** Lleva un clip de pista libre al principio (0) o tras el último clip de su pista, si hay hueco. */
fun Project.moveFreeToEdge(id: String, toEnd: Boolean): Project {
    val c = clip(id) ?: return this
    val t = trackOf(id) ?: return this
    if (t.type == TrackType.MAIN) return this
    val s = if (toEnd) t.clips.filter { it.id != id }.maxOfOrNull { it.endUs } ?: 0L else 0L
    return moveClip(id, t.id, s)
}

/** Intercambia un clip de pista libre con el anterior ([delta] = -1) o el siguiente (+1) de su pista. */
fun Project.swapFree(id: String, delta: Int): Project {
    val t = trackOf(id) ?: return this
    if (t.type == TrackType.MAIN) return this
    val sorted = t.clips.sortedBy { it.startUs }
    val i = sorted.indexOfFirst { it.id == id }
    val j = i + delta
    if (i < 0 || j < 0 || j >= sorted.size) return this
    val a = sorted[min(i, j)]
    val b = sorted[max(i, j)]
    // b pasa a empezar donde empezaba a, y a justo después respetando el hueco que había entre ambos.
    val gapAB = b.startUs - a.endUs
    val nb = b.copy(startUs = a.startUs)
    val na = a.copy(startUs = nb.endUs + gapAB)
    val list = t.clips.map { when (it.id) { a.id -> na; b.id -> nb; else -> it } }
    val sortedNew = list.sortedBy { it.startUs }
    for (k in 1 until sortedNew.size) if (sortedNew[k].startUs < sortedNew[k - 1].endUs) return this
    return updateTrack(t.id) { it.copy(clips = list) }
}

/** Mueve un clip entre la pista principal y una de superposición. */
fun Project.toggleMainOverlay(id: String): Project {
    val c = clip(id) ?: return this
    val t = trackOf(id) ?: return this
    if (!c.isVisual) return this
    val removed = mapTracks { tr -> tr.copy(clips = tr.clips.filter { it.id != id }) }
    return if (t.type == TrackType.MAIN) removed.addToTrack(c.copy(transitionIn = null), TrackType.OVERLAY)
    else removed.insertMain(listOf(c), removed.mainIndexAt(c.startUs))
}

// ---------------------------------------------------------------------------------------------
// Operaciones de clip
// ---------------------------------------------------------------------------------------------

fun Project.replaceMedia(id: String, media: MediaRef): Project = updateClip(id) { c ->
    val kind = when {
        c.kind == ClipKind.AUDIO -> ClipKind.AUDIO
        media.isImage -> ClipKind.IMAGE
        else -> ClipKind.VIDEO
    }
    val span = if (media.durationUs > 0) min(media.durationUs, max(c.srcSpanUs, c.durationUs)) else c.durationUs
    c.copy(kind = kind, media = media, srcInUs = 0, srcOutUs = span, reversed = false, slot = false, stabilize = false)
}

/** Separa el audio de un clip de vídeo en un clip de audio alineado. */
fun Project.detachAudio(id: String): Project {
    val c = clip(id) ?: return this
    if (c.kind != ClipKind.VIDEO || c.media?.hasAudio != true || c.audioDetached) return this
    val audio = Clip(
        kind = ClipKind.AUDIO, startUs = c.startUs, durationUs = c.durationUs, media = c.media,
        srcInUs = c.srcInUs, srcOutUs = c.srcOutUs, speed = c.speed, speedCurve = c.speedCurve,
        keepPitch = c.keepPitch, volume = c.volume, fadeInUs = c.fadeInUs, fadeOutUs = c.fadeOutUs,
        audioFx = c.audioFx, label = c.label,
    )
    return updateClip(id) { it.copy(audioDetached = true) }.addToTrack(audio, TrackType.AUDIO)
}

/** Inserta una imagen congelada de [durUs] en el instante [atUs] del clip (lo divide). */
fun Project.insertFreeze(id: String, atUs: Long, image: MediaRef, durUs: Long): Project {
    val t = trackOf(id) ?: return this
    val c = clip(id) ?: return this
    val still = Clip(
        kind = ClipKind.IMAGE, startUs = atUs, durationUs = durUs, media = image,
        transform = c.transform, color = c.color, filterId = c.filterId, filterIntensity = c.filterIntensity,
        lutPath = c.lutPath,
    )
    val split = splitClip(id, atUs)
    val wasSplit = split.track(t.id)?.clips?.size != t.clips.size
    if (t.type == TrackType.MAIN) {
        val idx = split.mainTrack.clips.indexOfFirst { it.id == id } + if (wasSplit || atUs >= c.endUs - MIN_CLIP_US) 1 else 0
        return split.insertMain(listOf(still), idx)
    }
    // Pista libre: se desplazan los clips posteriores para hacer sitio.
    val shifted = split.mapTracks { tr ->
        if (tr.id != t.id) tr else tr.copy(clips = tr.clips.map { if (it.startUs >= atUs && it.id != id) it.copy(startUs = it.startUs + durUs) else it } + still)
    }
    return shifted.normalized()
}

fun Project.groupClips(ids: Collection<String>): Project {
    if (ids.size < 2) return this
    val g = newId()
    return updateClips(ids) { it.copy(groupId = g) }
}

fun Project.ungroupClips(ids: Collection<String>): Project = updateClips(withGroups(ids)) { it.copy(groupId = "") }

/** Atributos que se pueden copiar de un clip a otros. */
enum class Attr { TRANSFORM, COLOR, FILTER, EFFECTS, MASK, ANIMATION, AUDIO, SPEED, TEXT_STYLE }

fun Project.pasteAttributes(from: Clip, toIds: Collection<String>, attrs: Set<Attr>): Project = updateClips(toIds) { c ->
    var r = c
    if (Attr.TRANSFORM in attrs && c.isVisual) r = r.copy(transform = from.transform)
    if (Attr.COLOR in attrs) r = r.copy(color = from.color)
    if (Attr.FILTER in attrs) r = r.copy(filterId = from.filterId, filterIntensity = from.filterIntensity, lutPath = from.lutPath)
    if (Attr.EFFECTS in attrs) r = r.copy(effects = from.effects.map { it.copy(id = newId()) })
    if (Attr.MASK in attrs) r = r.copy(mask = from.mask, chroma = from.chroma, zone = from.zone, zones = from.zones)
    if (Attr.ANIMATION in attrs) r = r.copy(animIn = from.animIn, animOut = from.animOut, animLoop = from.animLoop)
    if (Attr.AUDIO in attrs) r = r.copy(volume = from.volume, fadeInUs = from.fadeInUs, fadeOutUs = from.fadeOutUs, audioFx = from.audioFx)
    if (Attr.SPEED in attrs && c.hasTimedMedia) r = r.copy(speed = from.speed, speedCurve = from.speedCurve, keepPitch = from.keepPitch)
    if (Attr.TEXT_STYLE in attrs && c.text != null && from.text != null) {
        val style = from.text.layers.firstOrNull { it.kind == LayerKind.TEXT }
        if (style != null) r = r.copy(text = c.text.copy(layers = c.text.layers.map { l ->
            if (l.kind != LayerKind.TEXT) l else style.copy(id = l.id, text = l.text, x = l.x, y = l.y, delayUs = l.delayUs, dynamic = l.dynamic)
        }))
    }
    r
}

fun Project.applyTransitionToAll(tr: Transition?): Project = mapTracks { t ->
    if (t.type != TrackType.MAIN) t else t.copy(clips = t.clips.mapIndexed { i, c -> if (i == 0) c else c.copy(transitionIn = tr) })
}.normalized()

fun Project.addBlack(atStart: Boolean, durUs: Long): Project {
    val black = Clip(kind = ClipKind.COLOR, durationUs = durUs, fill = ColorFill())
    return insertMain(listOf(black), if (atStart) 0 else -1)
}

/**
 * Hueco al inicio: un clip negro marcado como [Clip.gap] en la pista principal (que va siempre pegada). Si ya existe,
 * se alarga. El desplazamiento del resto de pistas lo hace [rippleGap] (al crearlo, estirarlo, recortarlo o borrarlo).
 */
fun Project.insertGapAtStart(durUs: Long, audio: Boolean): Project {
    if (durUs <= 0) return this
    val first = mainTrack.clips.firstOrNull()
    if (first?.gap == true) return updateClip(first.id) { it.copy(durationUs = it.durationUs + durUs) }
    val gap = Clip(kind = ClipKind.COLOR, durationUs = durUs, fill = ColorFill(), gap = true, gapAudio = audio)
    return insertMain(listOf(gap), 0)
}

/**
 * Mantiene cuadrado el hueco inicial: si la duración total de los clips-hueco cambia respecto a [before] (crear,
 * estirar, recortar, borrar), el resto de pistas y los marcadores se mueven lo mismo. Audio solo si el hueco lo indica.
 */
fun Project.rippleGap(before: Project): Project {
    fun gapUs(p: Project) = p.mainTrack.clips.filter { it.gap }.sumOf { it.durationUs }
    val d = gapUs(this) - gapUs(before)
    if (d == 0L) return this
    val ref = mainTrack.clips.firstOrNull { it.gap } ?: before.mainTrack.clips.first { it.gap }
    return mapTracks { t ->
        if (t.type == TrackType.MAIN || (!ref.gapAudio && t.type == TrackType.AUDIO)) t
        else t.copy(clips = t.clips.map { it.copy(startUs = (it.startUs + d).coerceAtLeast(0L)) })
    }.copy(markers = markers.map { it.copy(timeUs = (it.timeUs + d).coerceAtLeast(0L)) }).normalized()
}

/** Fundido desde / a negro de todo el proyecto (lo pinta OverlayRender encima de todo). 0 lo quita. */
fun Project.fadeBlack(atStart: Boolean, durUs: Long): Project = if (atStart) copy(fadeInUs = durUs) else copy(fadeOutUs = durUs)

/** Reencuadre rápido: cambia el lienzo; las transformaciones son relativas, así que se conserva el encuadre. */
fun Project.reframed(w: Int, h: Int, fill: Boolean): Project = copy(width = w, height = h).mapTracks { t ->
    if (t.type != TrackType.MAIN) t
    else t.copy(clips = t.clips.map { it.copy(transform = it.transform.copy(fit = if (fill) FitMode.FILL else FitMode.FIT)) })
}.normalized()

// ---------------------------------------------------------------------------------------------
// Marcadores y ajuste magnético
// ---------------------------------------------------------------------------------------------

fun Project.addMarker(timeUs: Long, label: String = ""): Project =
    if (markers.any { kotlin.math.abs(it.timeUs - timeUs) < 20_000L }) this
    else copy(markers = (markers + Marker(timeUs = timeUs.snapMs(), label = label)).sortedBy { it.timeUs })

fun Project.addMarkers(timesUs: List<Long>): Project {
    var p = this
    for (t in timesUs) p = p.addMarker(t)
    return p
}

fun Project.removeMarker(id: String): Project = copy(markers = markers.filter { it.id != id })

/** Puntos de ajuste magnético: bordes de clips, marcadores, inicio y cabezal. */
fun Project.snapPoints(excludeIds: Collection<String>, playheadUs: Long): LongArray {
    val pts = ArrayList<Long>()
    pts.add(0L)
    pts.add(playheadUs)
    for (m in markers) pts.add(m.timeUs)
    for (t in tracks) for (c in t.clips) if (c.id !in excludeIds) { pts.add(c.startUs); pts.add(c.endUs) }
    return pts.distinct().sorted().toLongArray()
}

/** Devuelve el punto magnético más cercano a [timeUs] dentro de [toleranceUs], o null. */
fun LongArray.snap(timeUs: Long, toleranceUs: Long): Long? {
    var best: Long? = null
    var bestD = toleranceUs
    for (p in this) {
        val d = kotlin.math.abs(p - timeUs)
        if (d <= bestD) { bestD = d; best = p }
    }
    return best
}

/** Clip de la pista [trackId] (o de cualquier pista visual) bajo el instante dado. */
fun Project.clipAt(timeUs: Long, type: TrackType): Clip? =
    tracks.firstOrNull { it.type == type }?.clips?.lastOrNull { timeUs >= it.startUs && timeUs < it.endUs }

/** Proyecto reducido al tramo [startUs, endUs) de la línea de tiempo (para exportar solo una parte). */
fun Project.slice(startUs: Long, endUs: Long): Project {
    if (startUs <= 0 && endUs >= durationUs) return this
    val a = startUs.coerceAtLeast(0).snapMs()
    val b = endUs.coerceAtMost(durationUs).snapMs()
    if (b - a < MIN_CLIP_US) return this
    val ts = tracks.map { t ->
        t.copy(clips = t.clips.mapNotNull { c ->
            if (c.endUs <= a || c.startUs >= b) return@mapNotNull null
            var r = c
            if (r.startUs < a) r = r.splitAt(a)?.second?.copy(id = c.id, transitionIn = null) ?: r
            if (r.endUs > b) r = r.splitAt(b)?.first ?: r
            r.copy(startUs = (r.startUs - a).coerceAtLeast(0))
        })
    }
    return copy(tracks = ts, markers = markers.filter { it.timeUs in a..b }.map { it.copy(timeUs = it.timeUs - a) }).normalized()
}

/** Aplica [f] a todas las rutas de archivo del proyecto (medios, stickers de imagen, LUT, marca de agua). */
fun Project.mapPaths(f: (String) -> String): Project = copy(
    watermark = if (watermark.imagePath.isEmpty()) watermark else watermark.copy(imagePath = f(watermark.imagePath)),
    tracks = tracks.map { t ->
        t.copy(clips = t.clips.map { c ->
            var r = c
            val m = c.media
            if (m != null) r = r.copy(media = m.copy(path = f(m.path)))
            val wrm = c.wmRemove
            if (wrm != null && wrm.patchPath.isNotEmpty()) r = r.copy(wmRemove = wrm.copy(patchPath = f(wrm.patchPath)))
            val mo = c.morph
            if (mo != null) r = r.copy(morph = mo.copy(images = mo.images.map { it.copy(path = f(it.path)) }))
            val st = c.sticker
            if (st != null && (st.type == StickerType.IMAGE || st.type == StickerType.ANIMATED)) r = r.copy(sticker = st.copy(value = f(st.value)))
            if (c.lutPath.isNotEmpty() && !c.lutPath.startsWith("asset:")) r = r.copy(lutPath = f(c.lutPath))
            val ai = c.aiAudio
            if (ai != null && ai.path.isNotEmpty()) r = r.copy(aiAudio = ai.copy(path = f(ai.path)))
            val pc = c.personCut
            if (pc != null && pc.path.isNotEmpty()) r = r.copy(personCut = pc.copy(path = f(pc.path)))
            if (c.objTracks.isNotEmpty()) r = r.copy(objTracks = c.objTracks.map { if (it.path.isEmpty()) it else it.copy(path = f(it.path)) })
            r
        })
    },
)

/** Archivos generados por IA: si faltan no hay que reenlazarlos, se regeneran. */
fun Project.aiPaths(): Set<String> = allClips().flatMap { c -> listOfNotNull(c.aiAudio?.path, c.personCut?.path, c.wmRemove?.patchPath) + c.objTracks.map { it.path } }.filter { it.isNotEmpty() }.toSet()

/** Todas las rutas de archivo que usa el proyecto. */
fun Project.allPaths(): Set<String> {
    val out = LinkedHashSet<String>()
    mapPaths { out.add(it); it }
    return out
}

/**
 * Si un clip cambia de duración (recorte, estiramiento, velocidad) sin que se hayan tocado sus fotogramas clave,
 * los reparte en la misma proporción: un movimiento de 3 s que pasa a 6 s sigue ocupando todo el clip.
 */
fun Project.rescaleKeyframes(old: Project): Project {
    var p = this
    for ((id, oldDur) in keyframeDurationChanges(old)) p = p.rescaleClipKeyframes(id, oldDur)
    return p
}

/** Clips que han cambiado de duración sin tocar sus fotogramas clave: (id, duración anterior). Vacío si cambia el número de clips. */
fun Project.keyframeDurationChanges(old: Project): List<Pair<String, Long>> {
    if (allClips().size != old.allClips().size) return emptyList()
    val before = old.allClips().associateBy { it.id }
    val out = ArrayList<Pair<String, Long>>()
    for (c in allClips()) {
        if (c.keyframes.isEmpty()) continue
        val o = before[c.id] ?: continue
        if (o.durationUs == c.durationUs || o.durationUs <= 0L || o.keyframes != c.keyframes) continue
        out.add(c.id to o.durationUs)
    }
    return out
}

/** Reparte los fotogramas clave del clip en proporción a su duración actual respecto a [oldDur]. */
fun Project.rescaleClipKeyframes(id: String, oldDur: Long): Project {
    val c = clip(id) ?: return this
    if (oldDur <= 0L || c.durationUs == oldDur || c.keyframes.isEmpty()) return this
    val k = c.durationUs.toDouble() / oldDur
    val max = c.durationUs
    return updateClip(id) { cl -> cl.copy(keyframes = cl.keyframes.mapValues { (_, l) -> l.map { it.copy(tUs = (it.tUs * k).toLong().coerceIn(0L, max)) } }) }
}
