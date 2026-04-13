package com.ftt.bulldogblocker.ml

import android.graphics.Bitmap

/**
 * Average Hash (aHash) — image fingerprinting।
 *
 * Same বা similar image-এর hash-এর Hamming distance কম হবে।
 * False positive report করা image-এর hash store করে,
 * পরে similar image detect হলে block skip করা যায়।
 *
 * aHash: 8×8 grayscale → average → প্রতিটি pixel > avg হলে bit=1
 * Result: 64-bit Long
 *
 * Hamming distance ≤ 8 = similar image
 */
object ImageHashUtil {

    private const val HASH_SIZE    = 8     // 8×8 = 64 bit hash
    private const val MAX_DISTANCE = 8     // ≤8 bit difference = similar

    /**
     * Bitmap থেকে 64-bit perceptual hash তৈরি করো।
     * Blocking call — background thread-এ চালাও।
     */
    fun computeHash(bitmap: Bitmap): Long {
        // ① 8×8 তে resize করো
        val small = Bitmap.createScaledBitmap(bitmap, HASH_SIZE, HASH_SIZE, true)
        val pixels = IntArray(HASH_SIZE * HASH_SIZE)
        small.getPixels(pixels, 0, HASH_SIZE, 0, 0, HASH_SIZE, HASH_SIZE)
        if (small !== bitmap) small.recycle()

        // ② Grayscale luminance
        val gray = IntArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8)  and 0xFF
            val b =  p         and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }

        // ③ Average
        val avg = gray.average()

        // ④ Hash: pixel > avg → bit 1
        var hash = 0L
        gray.forEachIndexed { i, v ->
            if (v > avg) hash = hash or (1L shl i)
        }
        return hash
    }

    /** দুটো hash-এর মধ্যে different bit-এর সংখ্যা */
    fun hammingDistance(a: Long, b: Long): Int =
        java.lang.Long.bitCount(a xor b)

    /** Similar image কিনা (Hamming distance ≤ MAX_DISTANCE) */
    fun isSimilar(a: Long, b: Long): Boolean =
        hammingDistance(a, b) <= MAX_DISTANCE
}
