package com.ftt.bulldogblocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ftt.bulldogblocker.ui.MainActivity
import kotlinx.coroutines.*

/**
 * Foreground Service — keeps Bulldog Blocker alive even when the app is
 * in the background. Shows a persistent notification.
 *
 * Responsibilities:
 *  - Keep process alive so AccessibilityService stays active
 *  - Periodically verify Device Admin is still enabled
 *  - Re-launch setup if admin was somehow disabled
 */
class BlockerForegroundService : Service() {

    companion object {
        private const val TAG = "BDB_Service"
        const val CHANNEL_ID = "bulldog_blocker_service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP  = "ACTION_STOP"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startWatchdog()
        Log.d(TAG, "BlockerForegroundService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY   // restart automatically if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        // Restart self via broadcast if destroyed unexpectedly
        sendBroadcast(Intent(this, BootReceiver::class.java).apply {
            action = "com.ftt.bulldogblocker.RESTART_SERVICE"
        })
    }

    // ─── Watchdog ───────────────────────────────

    private fun startWatchdog() {
        serviceScope.launch {
            while (isActive) {
                checkAdminStatus()
                delay(30_000L)  // check every 30 seconds
            }
        }
    }

    private fun checkAdminStatus() {
        val adminActive = com.ftt.bulldogblocker.admin.DeviceAdminReceiver
            .isAdminActive(this)
        if (!adminActive) {
            Log.w(TAG, "Device Admin was disabled! Nudging user to re-enable.")
            // Launch setup to prompt re-activation
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("admin_lost", true)
            }
            startActivity(intent)
        }
    }

    // ─── Notification ───────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bulldog Blocker Protection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active content blocking service"
            setShowBadge(false)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐶 Bulldog Blocker Active")
            .setContentText("Adult content protection is ON")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
