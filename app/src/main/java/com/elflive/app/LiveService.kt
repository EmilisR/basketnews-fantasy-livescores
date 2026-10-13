package com.elflive.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.elflive.app.core.Engine
import com.elflive.app.core.LiveState
import com.elflive.app.core.Scoring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that polls every ~30 s while a game of a
 * watched team's round is in progress, and stops itself when nothing is live.
 */
class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val first = buildNotification(this, engine.state.value)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, first, type)
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        if (loop?.isActive != true) {
            loop = scope.launch {
                while (isActive) {
                    val s = engine.tick()
                    post(s)
                    if (!s.active) break
                    delay(POLL_MS)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // checked right here
    private fun post(s: LiveState) {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(this, s)) }
        }
    }

    // Android 15+: dataSync services get about 6 hours per day; stop cleanly when the system says so.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "live"
        const val NOTIFICATION_ID = 1
        const val POLL_MS = 30_000L

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            if (running) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, LiveService::class.java)) }
        }

        fun lines(s: LiveState?): List<String> = s?.teams.orEmpty().map { t ->
            when {
                !t.supported -> "${t.name}: ${Engine.UNSUPPORTED_MESSAGE}"
                else -> "${t.name}: ${Scoring.fmt(t.total)} pts" + if (t.live) " (live)" else ""
            }
        }

        fun buildNotification(context: Context, s: LiveState?): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val lines = lines(s)
            val style = NotificationCompat.InboxStyle()
            lines.forEach { style.addLine(it) }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_ball)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(lines.firstOrNull() ?: context.getString(R.string.notif_waiting))
                .setStyle(style)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(open)
                .build()
        }
    }
}
