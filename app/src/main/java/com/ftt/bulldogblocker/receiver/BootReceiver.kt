package com.ftt.bulldogblocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ftt.bulldogblocker.service.BlockerForegroundService

/**
 * Starts BlockerForegroundService on device boot.
 * Declared in manifest with RECEIVE_BOOT_COMPLETED permission.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val triggers = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.ftt.bulldogblocker.RESTART_SERVICE"
        )
        if (intent.action in triggers) {
            context.startForegroundService(
                Intent(context, BlockerForegroundService::class.java)
            )
        }
    }
}
