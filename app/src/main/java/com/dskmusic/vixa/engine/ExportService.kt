package com.dskmusic.vixa.engine

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dskmusic.vixa.MainActivity
import com.dskmusic.vixa.R
import com.dskmusic.vixa.VixaApp
import com.dskmusic.vixa.data.ErrorLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano que mantiene viva la exportación (también con la pantalla apagada) y
 * muestra el progreso, el tiempo restante y un botón para cancelar.
 */
class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.export_channel), NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val exporter = (application as VixaApp).container.exporter
        if (intent?.action == ACTION_CANCEL) {
            exporter.cancel()
            return START_NOT_STICKY
        }
        try {
            val type = when {
                Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else -> 0
            }
            val n = build(exporter.state.value)
            // Directo, sin ServiceCompat: en Android 16 este llegaba al sistema con tipo "none" (InvalidForegroundServiceTypeException).
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, type) else startForeground(NOTIF_ID, n)
        } catch (e: Throwable) {
            // Sin permiso de notificaciones o inicio no permitido: la exportación sigue en el proceso.
            ErrorLog.log("ExportService", e)
        }
        if (wakeLock == null) {
            try {
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vixa:export").apply { acquire(6 * 60 * 60 * 1000L) }
            } catch (e: Throwable) {
                ErrorLog.log("ExportService", e)
            }
        }
        if (job == null) {
            job = scope.launch {
                exporter.state.collectLatest { st ->
                    val nm = getSystemService(NotificationManager::class.java)
                    if (st.status == ExportStatus.RUNNING) {
                        try { nm.notify(NOTIF_ID, build(st)) } catch (e: Throwable) { }
                    } else {
                        ServiceCompat.stopForeground(this@ExportService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        // Si startForeground falló, la de progreso es una notificación normal y stopForeground no la quita.
                        try { nm.cancel(NOTIF_ID) } catch (e: Throwable) { }
                        if (st.status == ExportStatus.DONE || st.status == ExportStatus.ERROR) {
                            try { nm.notify(DONE_ID, build(st)) } catch (e: Throwable) { }
                        }
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(st: ExportState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
        when (st.status) {
            // El aviso final se cierra solo a los 10 s.
            ExportStatus.DONE -> b.setContentTitle(getString(R.string.export_done)).setContentText(st.title).setAutoCancel(true).setTimeoutAfter(10_000)
            ExportStatus.ERROR -> b.setContentTitle(getString(R.string.export_failed)).setContentText(st.message).setAutoCancel(true).setTimeoutAfter(10_000)
            else -> {
                val pct = (st.progress * 100).toInt().coerceIn(0, 100)
                val eta = if (st.etaMs > 0) " · " + getString(R.string.export_eta, formatEta(st.etaMs)) else ""
                val cancel = PendingIntent.getService(
                    this, 1, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                b.setContentTitle(getString(R.string.export_running, st.title))
                    .setContentText("$pct%$eta")
                    .setProgress(100, pct, st.progress <= 0f)
                    .setOngoing(true)
                    .addAction(0, getString(R.string.cancel), cancel)
            }
        }
        return b.build()
    }

    override fun onDestroy() {
        scope.cancel()
        try { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) } catch (e: Throwable) { }
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Throwable) { }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "export"
        private const val NOTIF_ID = 41
        private const val DONE_ID = 42
        private const val ACTION_CANCEL = "com.dskmusic.vixa.CANCEL_EXPORT"

        fun formatEta(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
            } catch (e: Throwable) {
                ErrorLog.log("ExportService", e)
            }
        }
    }
}
