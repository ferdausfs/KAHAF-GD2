package com.kb.blocker.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kb.blocker.KBApplication
import com.kb.blocker.R
import com.kb.blocker.ui.MainActivity

/**
 * Foreground Service - keeps the process at high priority.
 *
 * Why needed:
 * AccessibilityService alone runs in a low-priority process.
 * With this foreground service active, Android OS is much less
 * likely to kill the process. Helps against OEM battery killers
 * (MIUI, OneUI, ColorOS, etc.).
 *
 * START_STICKY: OS will restart this service if it is killed.
 */
class BlockerForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(KBApplication.FOREGROUND_NOTIF_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, KBApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
