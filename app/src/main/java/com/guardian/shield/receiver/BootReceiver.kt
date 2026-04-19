package com.guardian.shield.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.guardian.shield.service.blocker.GuardianForegroundService
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.guardian.shield.RESTART_SERVICE") {

            Timber.d("BootReceiver: $action — starting service")
            try {
                val serviceIntent = Intent(context, GuardianForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(serviceIntent)
                else
                    context.startService(serviceIntent)
            } catch (e: Exception) {
                Timber.e(e, "BootReceiver: failed to start service")
            }
        }
    }
}
