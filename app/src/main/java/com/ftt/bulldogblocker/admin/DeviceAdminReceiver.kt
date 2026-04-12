package com.ftt.bulldogblocker.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.ftt.bulldogblocker.ui.UninstallDelayActivity

/**
 * Device Administrator Receiver.
 *
 * While this app is an active Device Admin, the OS BLOCKS uninstall.
 * The user must first go to:
 *   Settings → Security → Device Admin Apps → Bulldog Blocker → Deactivate
 * Only after deactivation can uninstall proceed.
 *
 * onDisableRequested fires when the user TRIES to deactivate.
 * We return a warning message shown by the system dialog.
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context, DeviceAdminReceiver::class.java)

        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(getComponentName(context))
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(
            context,
            "✅ Bulldog Blocker: Device Admin activated. App is now protected.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Called when user tries to deactivate admin.
     * Return a warning string shown in the system confirmation dialog.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "⚠️ Deactivating admin will allow Bulldog Blocker to be uninstalled. " +
               "Adult content protection will be REMOVED. Are you sure?"
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(
            context,
            "❌ Bulldog Blocker: Admin disabled. Protection removed.",
            Toast.LENGTH_LONG
        ).show()
    }
}
