package com.ftt.bulldogblocker.ml

import android.content.Context
import android.graphics.Bitmap
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
        private const val ADULT_THRESHOLD = 0.60f

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
            // ⚠️ BUG FIX: useXNNPACK is Java-only setter (no getter) in TFLite 2.14.0.
            // Kotlin property assignment (useXNNPACK = true) requires both getter + setter.
            // Fix: call setUseXNNPACK(true) directly, or omit (XNNPACK is on by default).
            val options = Interpreter.Options().apply {
                numThreads = 2
                setUseXNNPACK(true)   // ← correct way to call Java-only setter in Kotlin
            }
            interpreter = Interpreter(buf, options)
            true
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /**
     * Classify a bitmap.
     * Call load() first. This is a blocking call — run on a background thread.
     */
    fun classify(bitmap: Bitmap): Result {
        val interp = interpreter
            ?: return Result(1f, 0f, false, "Model লোড হয়নি")

        val input  = bitmapToBuffer(bitmap)
        val output = Array(1) { FloatArray(2) }
        interp.run(input, output)

        val safe   = output[0][0]
        val unsafe = output[0][1]
        val adult  = unsafe >= ADULT_THRESHOLD

        return Result(
            safeScore   = safe,
            unsafeScore = unsafe,
            isAdult     = adult,
            label = if (adult)
                "🚫 Adult Content — ${(unsafe * 100).toInt()}% নিশ্চিত"
            else
                "✅ নিরাপদ — ${(safe * 100).toInt()}% নিশ্চিত"
        )
    }

    fun isLoaded(): Boolean = interpreter != null

    fun close() {
        interpreter?.close()
        interpreter = null
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
        val fis = FileInputStream(f)
        return fis.channel.use { ch ->
            ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
        }
    }
}
