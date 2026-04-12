package com.kb.blocker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class KBApplication : Application() {

    companion object {
        const val CHANNEL_ID = "kb_service_channel"
        const val WATCHDOG_CHANNEL_ID = "kb_watchdog_channel"
        const val FOREGROUND_NOTIF_ID = 1001
        const val WATCHDOG_NOTIF_ID = 1002
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Service channel (low importance — silent)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.channel_desc)
                    setShowBadge(false)
                }
            )

            // Watchdog alert channel (high importance — makes sound)
            nm.createNotificationChannel(
                NotificationChannel(
                    WATCHDOG_CHANNEL_ID,
                    getString(R.string.watchdog_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }
}
