package com.kb.blocker.watchdog

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.kb.blocker.KBApplication
import com.kb.blocker.R
import java.util.concurrent.TimeUnit

/**
 * WorkManager periodic task.
 * Checks every 15 minutes whether the Accessibility Service is still enabled.
 * If it is off, sends a high-priority notification to the user.
 *
 * WorkManager uses a system-level scheduler, so it survives app process death.
 */
class ServiceWatchdog(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ServiceWatchdog"
        private const val WORK_NAME = "kb_service_watchdog"

        /** Schedule the watchdog. Idempotent - existing work is kept if already scheduled. */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ServiceWatchdog>(
                15, TimeUnit.MINUTES
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
            .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req
            )
            Log.d(TAG, "Watchdog scheduled")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** Check whether the Accessibility Service is currently enabled in system settings. */
        fun isAccessibilityEnabled(context: Context): Boolean {
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val target = "${context.packageName}/.accessibility.ContentBlockerService"
                enabled.contains(target, ignoreCase = true)
            } catch (e: Exception) {
                Log.e(TAG, "isAccessibilityEnabled error", e)
                false
            }
        }
    }

    override suspend fun doWork(): Result {
        if (!isAccessibilityEnabled(context)) {
            Log.w(TAG, "Service is OFF - notifying user")
            notifyUser()
        } else {
            Log.d(TAG, "Service is alive")
        }
        return Result.success()
    }

    private fun notifyUser() {
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(context, KBApplication.WATCHDOG_CHANNEL_ID)
            .setContentTitle("KeywordBlocker Service Off")
            .setContentText("Accessibility Service stopped. Tap to re-enable.")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        context.getSystemService(NotificationManager::class.java)
            ?.notify(KBApplication.WATCHDOG_NOTIF_ID, notif)
    }
}
