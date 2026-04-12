package com.kb.blocker.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device boot বা app update এর পর watchdog আবার schedule করে।
 * WorkManager normally persist করে, কিন্তু কিছু OEM এ clear হয়ে যায়।
 * এই receiver সেই gap cover করে।
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.d("BootReceiver", "Received: $action — rescheduling watchdog")
            ServiceWatchdog.schedule(context)
        }
    }
}
