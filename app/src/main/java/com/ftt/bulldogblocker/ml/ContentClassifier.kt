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
 * Supported output shapes:
 *   [1][2] → [safe_score, unsafe_score]         (2-class mobilenet_v2)
 *   [1][5] → [drawings, hentai, neutral, porn, sexy]  (5-class inception_v3)
 */
class ContentClassifier(private val context: Context) {

    data class Result(
        val safeScore:   Float,
        val unsafeScore: Float,
        val isAdult:     Boolean,
        val label:       String
    )

    companion object {
        const val MODEL_FILENAME = "saved_model.tflite"
        const val ML_DIR_NAME    = "BulldogML"
        private const val INPUT_SIZE = 224

        /** ML folder: filesDir/BulldogML/ — auto-created if missing */
        fun mlDir(ctx: Context): File =
            File(ctx.filesDir, ML_DIR_NAME).also { if (!it.exists()) it.mkdirs() }

        /**
         * Model file path: filesDir/BulldogML/saved_model.tflite
         *
         * BUG FIX: আগে migration (file copy/delete) এখানেই করা হতো।
         * কিন্তু এই function main thread থেকে call হয় (refreshStatus)-এ।
         * বড় model file-এ copy করলে UI freeze হতো।
         *
         * Fix: এই function শুধু File reference return করে — কোনো IO নেই।
         * Migration এখন শুধু migrateModelIfNeeded()-এ, background thread থেকে call করতে হবে।
         */
        fun modelFile(ctx: Context): File =
            File(mlDir(ctx), MODEL_FILENAME)

        /**
         * ⚠️ BACKGROUND THREAD ONLY — file IO করে।
         * পুরনো path (filesDir/saved_model.tflite) থেকে নতুন BulldogML/ folder-এ move করে।
         * ContentClassifier.load() এবং importModel() — উভয় জায়গাই background thread-এ call হয়।
         */
        fun migrateModelIfNeeded(ctx: Context) {
            val newFile = modelFile(ctx)
            val oldFile = File(ctx.filesDir, MODEL_FILENAME)
            if (oldFile.exists() && !newFile.exists()) {
                try {
                    oldFile.copyTo(newFile, overwrite = false)
                    oldFile.delete()
                } catch (_: Exception) {}
            }
        }

        fun isReady(ctx: Context): Boolean =
            modelFile(ctx).let { it.exists() && it.length() > 0 }
    }

    private var interpreter: Interpreter? = null

    /**
     * Load the interpreter from internal storage.
     * Returns true on success.
     */
    fun load(): Boolean = try {
        migrateModelIfNeeded(context)   // BUG FIX: migration only on background thread
        val file = modelFile(context)
        if (!file.exists()) {
            false
        } else {
            val buf = loadMapped(file)
            val options = Interpreter.Options().apply {
                numThreads = 2
                setUseXNNPACK(true)
            }
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
     */
    fun classify(bitmap: Bitmap): Result {
        val threshold      = ThresholdManager.getManual(context)
        val sexyAlone      = ThresholdManager.getSexyAlone(context)
        return classifyWithThreshold(bitmap, threshold, sexyAlone)
    }

    /**
     * Custom threshold দিয়ে classify।
     * ScreenshotBlocker screenshot mode-এ ThresholdManager.getScreenshot() দিয়ে ডাকে।
     *
     * @param threshold     combined unsafe score threshold
     * @param sexyAloneThreshold  sexy class standalone threshold (semi-nude/lingerie)
     *                            0f পাস করলে standalone check বন্ধ থাকে।
     */
    fun classifyWithThreshold(
        bitmap: Bitmap,
        threshold: Float,
        sexyAloneThreshold: Float = ThresholdManager.DEFAULT_SEXY_ALONE
    ): Result {
        return try {
            // inputBuf/pixelBuf are instance fields — bitmapToBuffer() must be inside
            // synchronized(this) to prevent race between MainActivity test and ScreenshotBlocker
            val outputSize: Int
            val output: Array<FloatArray>
            val ran = synchronized(this) {
                val interp = interpreter ?: return Result(1f, 0f, false, "Model লোড হয়নি")
                val input  = bitmapToBuffer(bitmap)   // safe: inside lock
                val shape  = interp.getOutputTensor(0).shape()
                outputSize = shape[1]
                output     = Array(1) { FloatArray(outputSize) }
                interp.run(input, output)
                true
            }
            if (!ran) return Result(1f, 0f, false, "Model লোড হয়নি")

            val scores = output[0]

            val unsafe: Float
            val safe: Float
            var sexyScore = 0f   // 5-class মডেলে sexy score আলাদা রাখা হয়

            when (outputSize) {
                2 -> {
                    // 2-class mobilenet_v2: [safe, unsafe]
                    safe   = scores[0]
                    unsafe = scores[1]
                }
                5 -> {
                    // 5-class inception_v3: [drawings(0), hentai(1), neutral(2), porn(3), sexy(4)]
                    safe      = scores[2]           // neutral
                    sexyScore = scores[4]

                    // ── v6 FIX: স্মার্ট sexy contribution ──────────────────────
                    // সমস্যা: আগে sexy*1.5 সবসময় যোগ হতো।
                    // ফলে exercise/sports ছবিতেও sexy=0.20 থাকলে 0.30 যোগ হতো → false positive।
                    //
                    // নতুন নিয়ম:
                    //   sexy > 0.35 হলে → পুরো weight (1.5x) — clearly semi-nude
                    //   sexy 0.20-0.35  → কম weight (0.6x) — uncertain, বেশি contribute না করুক
                    //   sexy < 0.20     → নগণ্য (0.1x)    — background noise
                    val sexyContrib = when {
                        sexyScore > 0.35f -> sexyScore * 1.5f
                        sexyScore > 0.20f -> sexyScore * 0.6f
                        else              -> sexyScore * 0.1f
                    }
                    unsafe = scores[1] + scores[3] + sexyContrib  // hentai + porn + sexyContrib
                    // ────────────────────────────────────────────────────────────
                }
                else -> {
                    // Unknown model — treat last output as unsafe
                    safe   = scores[0]
                    unsafe = scores.last()
                }
            }

            // ── Primary check: combined unsafe score ──────────────────────────
            var adult = unsafe >= threshold

            // ── v6 NEW: Semi-nude standalone check ───────────────────────────
            // 5-class model-এ sexy score একাই sexyAloneThreshold পার করলে block।
            // bra, panty, lingerie, one-piece — এগুলো sexy class-এ পড়ে।
            // combined score threshold পার না করলেও এখানে ধরা পড়বে।
            // sexyAloneThreshold = 0f হলে এই check বন্ধ (2-class model)।
            val blockedBySexy = outputSize == 5
                    && sexyAloneThreshold > 0f
                    && sexyScore >= sexyAloneThreshold
            // ─────────────────────────────────────────────────────────────────

            val finalAdult = adult || blockedBySexy

            val label = when {
                blockedBySexy && !adult ->
                    "🚫 Semi-nude — ${(sexyScore * 100).toInt()}% sexy (standalone block)"
                finalAdult ->
                    "🚫 Adult Content — ${(unsafe * 100).toInt()}% নিশ্চিত"
                else ->
                    "✅ নিরাপদ — ${(safe * 100).toInt()}% নিশ্চিত"
            }

            // unsafeScore হিসেবে সর্বোচ্চ value দাও (report-এ কাজে লাগে)
            val reportScore = if (blockedBySexy && sexyScore > unsafe) sexyScore else unsafe

            Result(
                safeScore   = safe,
                unsafeScore = reportScore,
                isAdult     = finalAdult,
                label       = label
            )

        } catch (e: Exception) {
            e.printStackTrace()
            Result(1f, 0f, false, "বিশ্লেষণ ব্যর্থ: ${e.message}")
        }
    }

    fun isLoaded(): Boolean = synchronized(this) { interpreter != null }

    fun close() {
        synchronized(this) {
            interpreter?.close()
            interpreter = null
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────

    // Pre-allocated buffers — reused across classify calls (access protected by synchronized block)
    private val pixelBuf   = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val inputBuf   = ByteBuffer
        .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        .apply { order(ByteOrder.nativeOrder()) }

    private fun bitmapToBuffer(src: Bitmap): ByteBuffer {
        val bmp = Bitmap.createScaledBitmap(src, INPUT_SIZE, INPUT_SIZE, true)

        inputBuf.rewind()
        bmp.getPixels(pixelBuf, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        if (bmp !== src) bmp.recycle()

        for (p in pixelBuf) {
            inputBuf.putFloat(((p shr 16) and 0xFF) / 255f) // R
            inputBuf.putFloat(((p shr 8)  and 0xFF) / 255f) // G
            inputBuf.putFloat( (p         and 0xFF) / 255f) // B
        }
        inputBuf.rewind()
        return inputBuf
    }

    private fun loadMapped(f: File): MappedByteBuffer {
        FileInputStream(f).use { fis ->
            return fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
        }
    }
}
