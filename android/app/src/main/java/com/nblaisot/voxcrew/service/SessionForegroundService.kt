package com.nblaisot.voxcrew.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nblaisot.voxcrew.MainActivity
import com.nblaisot.voxcrew.R
import com.nblaisot.voxcrew.VoxCrewApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Permanent foreground service for the whole signed-in session, started as soon as
 * the mic permission is granted and only stopped at sign-out. The notification is
 * kept continuously in sync with [com.nblaisot.voxcrew.lanlink.LanIntercomEngine]
 * (link/discovery status + VOX on/off), so the intercom remains visible and
 * trustworthy whether the app is backgrounded or the screen is off.
 */
class SessionForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                observeJob?.cancel()
                releaseWifiLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val initialLabel = intent?.getStringExtra(EXTRA_TRANSPORT) ?: "Connexion…"
                startForeground(NOTIFICATION_ID, buildNotification(initialLabel, voxEnabled = false))
                acquireWifiLock()
                observeEngine()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observeJob?.cancel()
        releaseWifiLock()
        super.onDestroy()
    }

    private fun observeEngine() {
        if (observeJob?.isActive == true) return
        val engine = (application as? VoxCrewApp)?.container?.lanIntercomEngine ?: return
        observeJob = serviceScope.launch {
            combine(engine.statusText, engine.voxEnabled) { status, vox -> status to vox }
                .collect { (status, vox) ->
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification(status, vox))
                }
        }
    }

    private fun acquireWifiLock() {
        if (wifiLock != null) return
        runCatching {
            val wifi = getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "voxcrew-lan-link")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    private fun buildNotification(statusLabel: String, voxEnabled: Boolean): Notification {
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
        val voxLabel = if (voxEnabled) {
            getString(R.string.notification_vox_on)
        } else {
            getString(R.string.notification_vox_off)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_session_title))
            .setContentText("$statusLabel • $voxLabel")
            .setSmallIcon(R.mipmap.ic_launcher)
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

        fun start(context: Context, transportLabel: String? = null) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val intent = Intent(context, SessionForegroundService::class.java)
            transportLabel?.let { intent.putExtra(EXTRA_TRANSPORT, it) }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SessionForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
