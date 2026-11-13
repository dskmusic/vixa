package com.dskmusic.vixa.engine

import android.content.Context
import com.dskmusic.vixa.AppContainer
import com.dskmusic.vixa.data.ErrorLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Prepara el proyecto demo en el primer arranque, en el ámbito de la aplicación (no depende de ninguna pantalla: sobrevive a la
 * navegación y al tutorial). No descarga nada: usa solo assets/demo (imágenes, vídeos y demo.mp3). El aviso de "ya preparada" se
 * guarda solo cuando termina bien, así que si falla o se interrumpe se reintenta en el siguiente arranque.
 */
object DemoSeeder {
    enum class State { IDLE, RUNNING, DONE, FAILED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    private var started = false

    fun start(context: Context, container: AppContainer) {
        if (started) return
        if (container.settings.value.demoSeeded) { _state.value = State.DONE; return }
        started = true
        _state.value = State.RUNNING
        val app = context.applicationContext
        container.appScope.launch {
            try {
                // Con proyectos ya creados (actualización de una versión anterior) no se añade nada.
                if (container.projects.list().isNotEmpty()) {
                    container.settings.update { it.copy(demoSeeded = true) }
                    _state.value = State.DONE
                    return@launch
                }
                val es = app.resources.configuration.locales[0].language == "es"
                val r = DemoBuilder.build(app, container, es, online = false)
                container.projects.save(r.project, updateThumb = true)
                container.settings.update { it.copy(demoSeeded = true) }
                _state.value = State.DONE
            } catch (e: kotlinx.coroutines.CancellationException) {
                started = false
                _state.value = State.IDLE
                throw e
            } catch (e: Throwable) {
                ErrorLog.log("Demo/seed", e)
                _state.value = State.FAILED
            }
        }
    }
}
