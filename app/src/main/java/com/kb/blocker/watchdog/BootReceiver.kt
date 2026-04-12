package com.kb.blocker.watchdog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Reschedules the watchdog after device boot or app update.
 * WorkManager normally persists across reboots, but some OEM ROMs
 * (e.g. MIUI, Realme UI) clear scheduled work on restart.
 * This receiver covers that gap.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.d("BootReceiver", "Received: $action - rescheduling watchdog")
            ServiceWatchdog.schedule(context)
        }
    }
}
