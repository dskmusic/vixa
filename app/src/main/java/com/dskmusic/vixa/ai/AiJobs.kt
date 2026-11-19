package com.dskmusic.vixa.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dskmusic.vixa.MainActivity
import com.dskmusic.vixa.R
import com.dskmusic.vixa.VixaApp
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.engine.ExportService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Trabajo de IA en curso: lo muestran la notificación y el indicador del editor. */
class AiJobState(val title: String, val progress: Float = -1f, val etaMs: Long = -1, val queued: Int = 0)

/**
 * Cola de trabajos de IA (descargas de modelos e inferencia). Se ejecutan de uno en uno, fuera de la
 * interfaz, mantenidos por [AiService]; el editor sigue usable mientras tanto.
 */
class AiJobs(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private var current: Job? = null
    private val waiting = java.util.concurrent.atomic.AtomicInteger(0)

    private val _state = MutableStateFlow<AiJobState?>(null)
    val state: StateFlow<AiJobState?> = _state
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Errores y avisos para mostrar al usuario. */
    val messages: SharedFlow<String> = _messages

    val isRunning: Boolean get() = _state.value != null

    /** Aviso informativo (p. ej. dónde se guardó un resultado) por el mismo canal que los errores. */
    fun post(message: String) { _messages.tryEmit(message) }

    /**
     * Encola [work] (en Dispatchers.Default). [onDone] se llama en el hilo principal solo si termina bien.
     * Los errores van al registro y a [messages]; nunca se propagan.
     */
    fun <T> run(title: String, work: suspend (progress: (Float) -> Unit) -> T, onDone: (T) -> Unit = {}): Job {
        waiting.incrementAndGet()
        return scope.launch(Dispatchers.Default) {
            var counted = true
            try {
                mutex.withLock {
                    waiting.decrementAndGet()
                    counted = false
                    current = coroutineContext[Job]
                    val start = SystemClock.elapsedRealtime()
                    _state.value = AiJobState(title, queued = waiting.get())
                    AiService.start(app)
                    var last = 0L
                    val result = work { pr ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - last >= 300) {
                            last = now
                            val p = pr.coerceIn(0f, 1f)
                            val eta = if (p > 0.03f && now - start > 2000) ((now - start) / p * (1f - p)).toLong() else -1
                            _state.value = AiJobState(title, p, eta, waiting.get())
                        }
                    }
                    withContext(Dispatchers.Main) { onDone(result) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // cancelado por el usuario
            } catch (e: Throwable) {
                ErrorLog.log("AI/$title", e)
                _messages.tryEmit(title + ": " + (e.message ?: e.javaClass.simpleName))
            } finally {
                if (counted) waiting.decrementAndGet()
                current = null
                if (!mutex.isLocked && waiting.get() <= 0) _state.value = null
            }
        }
    }

    fun cancel() { current?.cancel() }
}

/** Servicio en primer plano que mantiene vivos los trabajos de IA y muestra progreso, tiempo restante y cancelar. */
class AiService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.ai_channel), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobs = (application as VixaApp).container.aiJobs
        if (intent?.action == ACTION_CANCEL) {
            jobs.cancel()
            return START_NOT_STICKY
        }
        try {
            val type = when {
                Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else -> 0
            }
            val n = build(jobs.state.value ?: AiJobState(getString(R.string.ai_title)))
            // Directo, sin ServiceCompat: en Android 16 este llegaba al sistema con tipo "none" (InvalidForegroundServiceTypeException).
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, type) else startForeground(NOTIF_ID, n)
        } catch (e: Throwable) {
            ErrorLog.log("AiService", e)
        }
        if (wakeLock == null) {
            try {
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vixa:ai").apply { acquire(3 * 60 * 60 * 1000L) }
            } catch (e: Throwable) {
                ErrorLog.log("AiService", e)
            }
        }
        if (job == null) {
            job = scope.launch {
                jobs.state.collectLatest { st ->
                    val nm = getSystemService(NotificationManager::class.java)
                    if (st != null) {
                        try { nm.notify(NOTIF_ID, build(st)) } catch (e: Throwable) { }
                    } else {
                        ServiceCompat.stopForeground(this@AiService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        try { nm.cancel(NOTIF_ID) } catch (e: Throwable) { }
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(st: AiJobState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, AiService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pct = (st.progress * 100).toInt().coerceIn(0, 100)
        val eta = if (st.etaMs > 0) " · " + getString(R.string.export_eta, ExportService.formatEta(st.etaMs)) else ""
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentTitle(st.title)
            .setContentText(if (st.progress >= 0f) "$pct%$eta" else getString(R.string.ai_working))
            .setProgress(100, pct, st.progress < 0f)
            .addAction(0, getString(R.string.cancel), cancel)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        try { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) } catch (e: Throwable) { }
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Throwable) { }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "ai"
        private const val NOTIF_ID = 51
        private const val ACTION_CANCEL = "com.dskmusic.vixa.CANCEL_AI"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, AiService::class.java))
            } catch (e: Throwable) {
                ErrorLog.log("AiService", e)
            }
        }
    }
}
