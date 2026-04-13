package com.ftt.bulldogblocker.ml

import android.content.Context
import android.graphics.Bitmap
import com.ftt.bulldogblocker.ThresholdManager
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TFLite adult content classifier.
 *
 * Model is NOT bundled in the APK.
 * User uploads saved_model.tflite via the app UI.
 * Saved to: context.filesDir/saved_model.tflite
 *
 * Expected output shape: [1][2] → [safe_score, unsafe_score]
 */
class ContentClassifier(private val context: Context) {

    data class Result(
        val safeScore:   Float,
        val unsafeScore: Float,
        val isAdult:     Boolean,
        val label:       String
    )

    companion object {
        const val MODEL_FILENAME   = "saved_model.tflite"
        private const val INPUT_SIZE      = 224
        // ── BUG FIX: ADULT_THRESHOLD আর hardcode নেই ──────────────────
        // আগে: private const val ADULT_THRESHOLD = 0.40f
        // এখন: classify() প্রতিবার ThresholdManager.getManual(context) পড়ে।
        // UI থেকে পরিবর্তন করলে সাথে সাথে কাজ করে।
        // ──────────────────────────────────────────────────────────────

        fun modelFile(ctx: Context): File = File(ctx.filesDir, MODEL_FILENAME)
        fun isReady(ctx: Context): Boolean =
            modelFile(ctx).let { it.exists() && it.length() > 0 }
    }

    private var interpreter: Interpreter? = null

    /**
     * Load the interpreter from internal storage.
     * Returns true on success.
     */
    fun load(): Boolean = try {
        val file = modelFile(context)
        if (!file.exists()) {
            false
        } else {
            val buf = loadMapped(file)
            val options = Interpreter.Options().apply {
                numThreads = 2
                setUseXNNPACK(true)
            }
            // BUG FIX: interpreter assignment was outside synchronized block —
            // concurrent close() call could race with this write → data race
            synchronized(this) {
                interpreter = Interpreter(buf, options)
            }
            true
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /**
     * Classify a bitmap using the user-set manual threshold.
     * Call load() first. This is a blocking call — run on a background thread.
     *
     * FIX: threshold এখন ThresholdManager.getManual() থেকে পড়া হয় —
     * hardcoded 0.40f এর বদলে user-set value ব্যবহার হয়।
     *
     * FIX: synchronized on interpreter reference to prevent concurrent access crash
     * when classifier is reloaded (via broadcast) while classify() is running.
     */
    fun classify(bitmap: Bitmap): Result {
        // Dynamic threshold — প্রতিবার ThresholdManager থেকে পড়া হয়
        val threshold = ThresholdManager.getManual(context)
        return classifyWithThreshold(bitmap, threshold)
    }

    /**
     * Custom threshold দিয়ে classify।
     * ScreenshotBlocker screenshot mode-এ ThresholdManager.getScreenshot() দিয়ে ডাকে।
     */
    fun classifyWithThreshold(bitmap: Bitmap, threshold: Float): Result {
        return try {
            val input  = bitmapToBuffer(bitmap)

            // BUG FIX: was two separate synchronized blocks with outputTensor.shape()[1]
            // called OUTSIDE — interpreter could be closed between the two blocks,
            // making outputTensor stale. Now: get shape and run inside one synchronized block.
            val outputSize: Int
            val output: Array<FloatArray>
            val ran = synchronized(this) {
                val interp = interpreter ?: return Result(1f, 0f, false, "Model লোড হয়নি")
                val shape = interp.getOutputTensor(0).shape()
                outputSize = shape[1]
                output = Array(1) { FloatArray(outputSize) }
                interp.run(input, output)
                true
            }
            if (!ran) return Result(1f, 0f, false, "Model লোড হয়নি")

            val scores = output[0]

            // Determine unsafe score based on output size
            val unsafe: Float
            val safe: Float
            when (outputSize) {
                2 -> {
                    // GantMan mobilenet_v2: [safe, unsafe]
                    safe   = scores[0]
                    unsafe = scores[1]
                }
                5 -> {
                    // GantMan inception_v3: [drawings, hentai, neutral, porn, sexy]
                    // FIX: sexy class weighted 1.5x — semi-nude/lingerie এর জন্য
                    safe   = scores[2]           // neutral
                    unsafe = scores[1] + scores[3] + (scores[4] * 1.5f)  // hentai + porn + sexy*1.5
                }
                else -> {
                    // Unknown model — treat last output as unsafe score
                    safe   = scores[0]
                    unsafe = scores.last()
                }
            }

            val adult = unsafe >= threshold
            Result(
                safeScore   = safe,
                unsafeScore = unsafe,
                isAdult     = adult,
                label = if (adult)
                    "🚫 Adult Content — ${(unsafe * 100).toInt()}% নিশ্চিত"
                else
                    "✅ নিরাপদ — ${(safe * 100).toInt()}% নিশ্চিত"
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Result(1f, 0f, false, "বিশ্লেষণ ব্যর্থ: ${e.message}")
        }
    }

    // BUG FIX: was `interpreter != null` without synchronized — data race with close()
    fun isLoaded(): Boolean = synchronized(this) { interpreter != null }

    fun close() {
        synchronized(this) {
            interpreter?.close()
            interpreter = null
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────

    private fun bitmapToBuffer(src: Bitmap): ByteBuffer {
        val bmp = Bitmap.createScaledBitmap(src, INPUT_SIZE, INPUT_SIZE, true)

        val buf = ByteBuffer
            .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .apply { order(ByteOrder.nativeOrder()) }

        val pix = IntArray(INPUT_SIZE * INPUT_SIZE)
        bmp.getPixels(pix, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        if (bmp !== src) bmp.recycle()

        for (p in pix) {
            buf.putFloat(((p shr 16) and 0xFF) / 255f) // R
            buf.putFloat(((p shr 8)  and 0xFF) / 255f) // G
            buf.putFloat( (p         and 0xFF) / 255f) // B
        }
        buf.rewind()
        return buf
    }

    private fun loadMapped(f: File): MappedByteBuffer {
        // FIX: FileInputStream must be closed explicitly.
        // Previously `fis.channel.use { }` closed the channel but not the FileInputStream.
        // Now we use `fis.use { }` which closes both the stream AND the channel.
        FileInputStream(f).use { fis ->
            return fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
        }
    }
}
