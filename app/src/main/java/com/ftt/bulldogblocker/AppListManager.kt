package com.ftt.bulldogblocker

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Blacklist  = এই apps-এ blocker পুরোপুরি active (ML + keyword scan)
 * Whitelist  = এই apps-এ blocker কিছুই করে না (bypass)
 */
object AppListManager {

    private const val PREFS        = "bdb_app_lists"
    private const val KEY_BLACK    = "blacklist"
    private const val KEY_WHITE    = "whitelist"

    const val ACTION_LISTS_CHANGED = "com.ftt.bulldogblocker.LISTS_CHANGED"

    // ── Read ──────────────────────────────────────────────────────────

    fun getBlacklist(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_BLACK, emptySet()) ?: emptySet()

    fun getWhitelist(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_WHITE, emptySet()) ?: emptySet()

    // ── Write ─────────────────────────────────────────────────────────

    fun addBlacklist(ctx: Context, pkg: String) {
        val s = getBlacklist(ctx).toMutableSet().also { it.add(pkg) }
        prefs(ctx).edit().putStringSet(KEY_BLACK, s).apply()
        // যদি whitelist-এ থাকে তাহলে সরাও
        removeWhitelist(ctx, pkg)
        notifyChanged(ctx)
    }

    fun removeBlacklist(ctx: Context, pkg: String) {
        val s = getBlacklist(ctx).toMutableSet().also { it.remove(pkg) }
        prefs(ctx).edit().putStringSet(KEY_BLACK, s).apply()
        notifyChanged(ctx)
    }

    fun addWhitelist(ctx: Context, pkg: String) {
        val s = getWhitelist(ctx).toMutableSet().also { it.add(pkg) }
        prefs(ctx).edit().putStringSet(KEY_WHITE, s).apply()
        // যদি blacklist-এ থাকে তাহলে সরাও
        removeBlacklist(ctx, pkg)
        notifyChanged(ctx)
    }

    fun removeWhitelist(ctx: Context, pkg: String) {
        val s = getWhitelist(ctx).toMutableSet().also { it.remove(pkg) }
        prefs(ctx).edit().putStringSet(KEY_WHITE, s).apply()
        notifyChanged(ctx)
    }

    // ── Installed app list (user apps only) ───────────────────────────

    data class AppInfo(val name: String, val pkg: String)

    fun getInstalledUserApps(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .filter { it.packageName != ctx.packageName }
            .map { AppInfo(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.name.lowercase() }
    }

    fun getAppName(ctx: Context, pkg: String): String {
        return try {
            val pm = ctx.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) { pkg }
    }

    // ── Private ───────────────────────────────────────────────────────

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun notifyChanged(ctx: Context) {
        ctx.sendBroadcast(android.content.Intent(ACTION_LISTS_CHANGED).apply {
            setPackage(ctx.packageName)
        })
    }
}
