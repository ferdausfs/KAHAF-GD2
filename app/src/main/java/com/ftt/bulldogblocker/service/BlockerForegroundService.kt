package com.ftt.bulldogblocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.ftt.bulldogblocker.admin.DeviceAdminReceiver
import com.ftt.bulldogblocker.receiver.BootReceiver
import com.ftt.bulldogblocker.ui.MainActivity
import kotlinx.coroutines.*

/**
 * Foreground service — keeps the app alive in the background.
 * Uses foregroundServiceType="dataSync" (no Play approval needed).
 *
 * IMPORTANT: startForeground() must be called within 5 seconds of onCreate().
 * We call it immediately in onCreate() before any other work.
 */
class BlockerForegroundService : Service() {

    companion object {
        private const val TAG = "BDB_Service"
        const val CHANNEL_ID    = "bulldog_blocker_ch"
        const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // ① Create channel FIRST
        createNotificationChannel()
        // ② Call startForeground IMMEDIATELY — must be within 5 seconds
        startForegroundCompat()
        // ③ Start watchdog after foreground is established
        startWatchdog()
        Log.d(TAG, "BlockerForegroundService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
        // FIX: Use explicit Intent with component — setClass sets the component correctly.
        // Previously the action string and setClass were redundant; keep setClass only.
        try {
            val restartIntent = Intent(this, BootReceiver::class.java).apply {
                action = "com.ftt.bulldogblocker.RESTART_SERVICE"
            }
            sendBroadcast(restartIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send restart broadcast", e)
        }
    }

    // ─── startForeground with correct type for API 29+ ──────────────

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: must specify foreground service type
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ─── Watchdog ───────────────────────────────────────────────────

    private fun startWatchdog() {
        serviceScope.launch {
            while (isActive) {
                delay(30_000L)
                checkAdminStatus()
            }
        }
    }

    private fun checkAdminStatus() {
        if (!DeviceAdminReceiver.isAdminActive(this)) {
            Log.w(TAG, "Device Admin disabled — prompting user")
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("admin_lost", true)
            }
            try { startActivity(intent) } catch (e: Exception) {
                Log.e(TAG, "Cannot launch MainActivity from service", e)
            }
        }
    }

    // ─── Notification ────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Bulldog Blocker",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active content blocking"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🐶 Bulldog Blocker")
            .setContentText("Adult content protection is ON")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
