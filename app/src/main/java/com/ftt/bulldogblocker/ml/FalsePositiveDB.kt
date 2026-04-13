package com.ftt.bulldogblocker.ml

import android.content.Context

/**
 * False Positive ও Confirmed True image hash database।
 *
 * ─ False DB:  user "❌ ভুল" চাপলে hash এখানে যায়।
 *              পরে similar image detect হলে block skip হয়।
 *
 * ─ True DB:   user "✅ সঠিক" চাপলে hash এখানে যায়।
 *              পরে similar image-এ score কম হলেও report dialog আসে না —
 *              সরাসরি block হয়।
 *
 * Storage: SharedPreferences → Set<String> (Long.toString() format)
 * Max: প্রতিটি list-এ 200টা hash (পুরনো entries ছেঁটে ফেলা হয়)
 */
object FalsePositiveDB {

    private const val PREFS     = "bdb_fp_db"
    private const val KEY_FALSE = "false_hashes"
    private const val KEY_TRUE  = "true_hashes"
    private const val MAX_SIZE  = 200

    // ── Write ─────────────────────────────────────────────────────────

    /** User "❌ ভুল (False Positive)" চাপলে */
    fun addFalse(ctx: Context, hash: Long) {
        if (hash == 0L) return
        val set = getFalseHashes(ctx).toMutableSet()
        set.add(hash.toString())
        if (set.size > MAX_SIZE) {
            // পুরনো entry সরাও
            val trimmed = set.toList().takeLast(MAX_SIZE).toMutableSet()
            save(ctx, KEY_FALSE, trimmed)
        } else {
            save(ctx, KEY_FALSE, set)
        }
        // যদি true DB-তে থাকে সরাও
        removeFromTrue(ctx, hash)
    }

    /** User "✅ সঠিক" চাপলে */
    fun addTrue(ctx: Context, hash: Long) {
        if (hash == 0L) return
        val set = getTrueHashes(ctx).toMutableSet()
        set.add(hash.toString())
        if (set.size > MAX_SIZE) {
            val trimmed = set.toList().takeLast(MAX_SIZE).toMutableSet()
            save(ctx, KEY_TRUE, trimmed)
        } else {
            save(ctx, KEY_TRUE, set)
        }
        // যদি false DB-তে থাকে সরাও
        removeFromFalse(ctx, hash)
    }

    // ── Query ─────────────────────────────────────────────────────────

    /**
     * এই image কি আগে false positive হিসেবে report হয়েছিল?
     * হ্যাঁ হলে → block skip করো।
     */
    fun isFalsePositive(ctx: Context, hash: Long): Boolean {
        if (hash == 0L) return false
        val stored = getFalseHashes(ctx)
        return stored.any { storedHash ->
            ImageHashUtil.isSimilar(hash, storedHash)
        }
    }

    /**
     * এই image কি আগে confirmed true adult content হিসেবে mark হয়েছিল?
     * হ্যাঁ হলে → score কম হলেও report dialog ছাড়াই block করো।
     */
    fun isConfirmedTrue(ctx: Context, hash: Long): Boolean {
        if (hash == 0L) return false
        val stored = getTrueHashes(ctx)
        return stored.any { storedHash ->
            ImageHashUtil.isSimilar(hash, storedHash)
        }
    }

    /** Total stored entries (debug/display এর জন্য) */
    fun stats(ctx: Context): String {
        val fp = getFalseHashes(ctx).size
        val tr = getTrueHashes(ctx).size
        return "False: $fp | True: $tr"
    }

    /** সব false positive clear করো */
    fun clearFalse(ctx: Context) = save(ctx, KEY_FALSE, emptySet())

    /** সব confirmed true clear করো */
    fun clearTrue(ctx: Context) = save(ctx, KEY_TRUE, emptySet())

    // ── Internal ──────────────────────────────────────────────────────

    private fun getFalseHashes(ctx: Context): Set<Long> =
        prefs(ctx).getStringSet(KEY_FALSE, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toHashSet() ?: hashSetOf()

    private fun getTrueHashes(ctx: Context): Set<Long> =
        prefs(ctx).getStringSet(KEY_TRUE, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toHashSet() ?: hashSetOf()

    private fun removeFromFalse(ctx: Context, hash: Long) {
        val set = prefs(ctx).getStringSet(KEY_FALSE, emptySet())
            ?.toMutableSet() ?: return
        set.remove(hash.toString())
        save(ctx, KEY_FALSE, set)
    }

    private fun removeFromTrue(ctx: Context, hash: Long) {
        val set = prefs(ctx).getStringSet(KEY_TRUE, emptySet())
            ?.toMutableSet() ?: return
        set.remove(hash.toString())
        save(ctx, KEY_TRUE, set)
    }

    private fun save(ctx: Context, key: String, set: Set<String>) =
        prefs(ctx).edit().putStringSet(key, set).apply()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
