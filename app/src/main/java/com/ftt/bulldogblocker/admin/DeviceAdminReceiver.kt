package com.ftt.bulldogblocker.admin

// ⚠️ BUG FIX: Do NOT import android.app.admin.DeviceAdminReceiver — it causes the class
// to extend itself (same simple name), resulting in a compile error.
// Use the fully-qualified parent name below instead.

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Device Administrator Receiver.
 *
 * While this app is an active Device Admin, the OS BLOCKS uninstall.
 * User must first deactivate from:
 *   Settings → Security → Device Admin Apps → Bulldog Blocker → Deactivate
 */
class DeviceAdminReceiver : android.app.admin.DeviceAdminReceiver() {

    companion object {
        fun getComponentName(context: Context): ComponentName =
            ComponentName(context, DeviceAdminReceiver::class.java)

        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as android.app.admin.DevicePolicyManager
            return dpm.isAdminActive(getComponentName(context))
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(
            context,
            "✅ Bulldog Blocker: Device Admin সক্রিয়। App এখন সুরক্ষিত।",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "⚠️ Admin বন্ধ করলে Bulldog Blocker আনইনস্টল করা সম্ভব হবে। " +
               "Adult content সুরক্ষা বন্ধ হয়ে যাবে। আপনি কি নিশ্চিত?"
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(
            context,
            "❌ Bulldog Blocker: Admin বন্ধ। সুরক্ষা সরানো হয়েছে।",
            Toast.LENGTH_LONG
        ).show()
    }
}
