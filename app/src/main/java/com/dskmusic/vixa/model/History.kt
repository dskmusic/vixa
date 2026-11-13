package com.dskmusic.vixa.model

/**
 * Historial de deshacer/rehacer sobre el modelo inmutable. Cada entrada guarda una referencia al
 * proyecto completo; como las copias comparten estructura (listas y clips no modificados), el coste por
 * paso es mínimo y el límite práctico es muy alto.
 */
class History(initial: Project, initialLabel: String) {

    data class Entry(val label: String, val project: Project, val timeMs: Long)

    private val entries = ArrayList<Entry>()
    private var index = 0

    init {
        entries.add(Entry(initialLabel, initial, System.currentTimeMillis()))
    }

    val current: Project get() = entries[index].project
    val canUndo: Boolean get() = index > 0
    val canRedo: Boolean get() = index < entries.size - 1
    val position: Int get() = index

    fun list(): List<Entry> = entries.toList()

    /** Recupera un historial guardado de una sesión anterior. */
    fun restore(list: List<Entry>, idx: Int) {
        if (list.isEmpty()) return
        entries.clear(); entries.addAll(list)
        index = idx.coerceIn(0, entries.size - 1)
    }

    fun push(label: String, project: Project) {
        if (project === current) return
        while (entries.size > index + 1) entries.removeAt(entries.size - 1)
        entries.add(Entry(label, project, System.currentTimeMillis()))
        if (entries.size > MAX_ENTRIES) entries.removeAt(0)
        index = entries.size - 1
    }

    /** Sustituye el estado actual sin crear un paso nuevo (cambios continuos de un mismo gesto). */
    fun replaceCurrent(project: Project) {
        val e = entries[index]
        entries[index] = e.copy(project = project)
    }

    fun undo(): Project? {
        if (!canUndo) return null
        index--
        return current
    }

    fun redo(): Project? {
        if (!canRedo) return null
        index++
        return current
    }

    fun jumpTo(position: Int): Project? {
        if (position !in entries.indices) return null
        index = position
        return current
    }

    companion object {
        const val MAX_ENTRIES = 2000
    }
}
