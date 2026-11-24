package it.matteo.gamesync

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class SessionService : Service() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)

    companion object {
        const val CH = "session"
        const val ACTION_FINISH = "it.matteo.gamesync.FINISH"
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, SessionService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, SessionService::class.java))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "Sessione di gioco", NotificationManager.IMPORTANCE_LOW))
        val n = build("Sessione: ${Store.session?.entry?.name ?: "?"} — copia all'uscita")
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)
        if (intent?.action == ACTION_FINISH) {
            scope.launch { SessionManager.finish(this@SessionService, "manuale"); stopSelf() }
            return START_NOT_STICKY
        }
        scope.launch { watch() }
        return START_NOT_STICKY
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val fin = PendingIntent.getForegroundService(this, 1,
            Intent(this, SessionService::class.java).setAction(ACTION_FINISH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("GameSync").setContentText(text)
            .setOngoing(true).setContentIntent(open)
            .addAction(0, "Copia e chiudi", fin).build()
    }

    private suspend fun watch() {
        var misses = 0
        while (scope.isActive) {
            delay(3000)
            val s = Store.session ?: break
            if (System.currentTimeMillis() - s.started > 5 * 3600_000L) { SessionManager.finish(this, "timeout"); break }
            val top = withContext(Dispatchers.IO) { runCatching { Bridge.service?.topPackage() ?: "" }.getOrDefault("") }
            if (top.isEmpty()) continue
            if (top == s.entry.pkg) misses = 0 else misses++
            if (misses >= 2) { SessionManager.finish(this, "gioco chiuso"); break }
        }
        stopSelf()
    }

    override fun onDestroy() { job.cancel(); super.onDestroy() }
}
