package com.nblaisot.voxcrew.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nblaisot.voxcrew.MainActivity
import com.nblaisot.voxcrew.R

class SessionForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val transport = intent?.getStringExtra(EXTRA_TRANSPORT) ?: "Connexion…"
                startForeground(NOTIFICATION_ID, buildNotification(transport))
            }
        }
        return START_STICKY
    }

    private fun buildNotification(transportLabel: String): Notification {
        createChannel()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SessionForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_session_title))
            .setContentText("$transportLabel — ${getString(R.string.notification_session_text)}")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_leave_session), stopIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_session),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "voxcrew_session"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.nblaisot.voxcrew.STOP_SESSION"
        const val EXTRA_TRANSPORT = "transport"

        fun start(context: Context, transportLabel: String) {
            val intent = Intent(context, SessionForegroundService::class.java)
                .putExtra(EXTRA_TRANSPORT, transportLabel)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SessionForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
