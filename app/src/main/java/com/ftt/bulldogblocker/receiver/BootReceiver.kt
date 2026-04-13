package com.ftt.bulldogblocker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ftt.bulldogblocker.service.BlockerForegroundService

/**
 * Starts BlockerForegroundService on device boot.
 * Declared in manifest with RECEIVE_BOOT_COMPLETED permission.
 *
 * FIX: Added Build.VERSION check — startForegroundService() requires API 26+.
 * On API 26+, use startForegroundService(). On API < 26, use startService().
 * Also wrapped in try-catch to prevent boot crash.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BDB_Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val triggers = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.ftt.bulldogblocker.RESTART_SERVICE"
        )
        if (intent.action !in triggers) return

        try {
            val svc = Intent(context, BlockerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service on boot", e)
        }
    }
}
