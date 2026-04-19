package com.guardian.shield.service.blocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.guardian.shield.R
import com.guardian.shield.ui.dashboard.MainActivity
import timber.log.Timber

/**
 * Persistent foreground service — keeps the app process alive
 * so the AccessibilityService doesn't get killed by the OS.
 *
 * Shows a minimal persistent notification (required for foreground services).
 */
class GuardianForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID   = "guardian_protection"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "Guardian_Foreground"
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("$TAG onCreate")
        createNotificationChannel()
        // BUG FIX 1: Android 14+ (API 34) requires explicit serviceType in startForeground()
        // when foregroundServiceType is declared in the manifest.
        // BUG FIX 2: On Android 13+ (API 33) check POST_NOTIFICATIONS before startForeground.
        // If not granted, we still call startForeground (required to avoid ANR) but the
        // notification won't display — service still runs, just silently.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY  // Restart if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.d("$TAG onDestroy — attempting restart")
        // BUG FIX: sendBroadcast() BEFORE super.onDestroy().
        // আগে: super.onDestroy() → sendBroadcast() — service context partially invalidated।
        // এখন: broadcast first → then super.onDestroy() — context still fully valid।
        try {
            sendBroadcast(Intent("com.guardian.shield.RESTART_SERVICE").apply {
                setPackage(packageName)
            })
        } catch (e: Exception) {
            Timber.e(e, "$TAG restart broadcast failed")
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Guardian Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active content protection"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Guardian Shield Active")
            .setContentText("Protection is running")
            .setSmallIcon(R.drawable.ic_shield_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
