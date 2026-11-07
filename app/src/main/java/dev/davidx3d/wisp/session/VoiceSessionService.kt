package dev.davidx3d.wisp.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.davidx3d.wisp.R
import dev.davidx3d.wisp.ui.AssistantActivity
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import javax.inject.Inject
import dev.davidx3d.wisp.i18n.tr

/**
 * Keeps the microphone alive while a conversation runs, with a notification to end it.
 * It stops itself as soon as the session is no longer active.
 */
@AndroidEntryPoint
class VoiceSessionService : LifecycleService() {

    @Inject lateinit var manager: VoiceSessionManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            manager.stop()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, build(manager.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        lifecycleScope.launch {
            manager.state.distinctUntilChangedBy { it.active to it.phase }.collect { s ->
                if (!s.active) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(s))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(s: SessionState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (s.phase) {
            Phase.CONNECTING -> tr("Verbinde …", "Connecting …")
            Phase.RECONNECTING -> tr("Verbindung wird wiederhergestellt …", "Reconnecting …")
            Phase.SPEAKING -> tr("Wisp spricht", "Wisp is speaking")
            Phase.THINKING -> tr("Denkt nach …", "Thinking …")
            Phase.USER_SPEAKING -> tr("Hört dir zu", "Listening to you")
            else -> if (s.muted) tr("Stummgeschaltet", "Muted") else tr("Hört zu", "Listening")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wisp)
            .setContentTitle(tr("Gespräch mit Wisp", "Talking to Wisp"))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .addAction(0, tr("Beenden", "End"), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "dev.davidx3d.wisp.STOP"

        fun createChannel(context: Context) {
            val ch = NotificationChannel(CHANNEL_ID, tr("Laufendes Gespräch", "Ongoing conversation"), NotificationManager.IMPORTANCE_LOW).apply {
                description = tr("Zeigt an, dass Wisp gerade zuhört", "Shows that Wisp is listening")
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }
}
