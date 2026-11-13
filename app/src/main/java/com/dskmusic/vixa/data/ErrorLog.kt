package com.dskmusic.vixa.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro interno de errores y cierres, consultable desde Ajustes (copiar / compartir). */
object ErrorLog {
    private const val MAX_BYTES = 400_000L
    @Volatile private var file: File? = null
    private val lock = Any()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "errors.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { write("CRASH [" + thread.name + "]", stack(error)) } catch (e: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    fun log(tag: String, t: Throwable) {
        Log.e("Vixa", tag, t)
        write(tag, stack(t))
    }

    fun log(tag: String, message: String) {
        Log.w("Vixa", "$tag: $message")
        write(tag, message)
    }

    private fun write(tag: String, body: String) {
        val f = file ?: return
        synchronized(lock) {
            try {
                if (f.exists() && f.length() > MAX_BYTES) {
                    val text = f.readText()
                    f.writeText(text.substring(text.length / 2))
                }
                f.appendText("== " + fmt.format(Date()) + " | " + tag + "\n" + body.trimEnd() + "\n\n")
            } catch (e: Throwable) {
                // Sin espacio o sin acceso: no hay nada más que hacer.
            }
        }
    }

    fun read(): String {
        val f = file ?: return ""
        val header = "Vixa 1.0.0 · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n"
        return synchronized(lock) { header + (if (f.exists()) f.readText() else "") }
    }

    fun isEmpty(): Boolean = file?.let { !it.exists() || it.length() == 0L } ?: true

    fun clear() {
        synchronized(lock) { file?.delete() }
    }

    fun file(): File? = file
}
