package com.dskmusic.vixa.engine

import com.dskmusic.vixa.data.ErrorLog

/**
 * Registro de todo lo que reproduce (o graba) algo en la app. Cuando la app pasa a segundo plano o se bloquea la pantalla,
 * MainActivity llama a [stopAll] y cada reproductor registrado se detiene: así nunca suena nada con la app fuera de vista.
 * Se usa siempre desde el hilo principal.
 */
object PlaybackGuard {
    private val stoppers = LinkedHashMap<Int, () -> Unit>()
    private var next = 0

    /** Registra una acción que detiene la reproducción. Devuelve la función que la da de baja (llamarla al liberar el reproductor). */
    fun register(stop: () -> Unit): () -> Unit {
        val id = next++
        stoppers[id] = stop
        return { stoppers.remove(id); Unit }
    }

    fun stopAll() {
        for (s in stoppers.values.toList()) {
            try { s() } catch (e: Throwable) { ErrorLog.log("PlaybackGuard", e) }
        }
    }
}
