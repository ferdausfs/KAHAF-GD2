package com.ftt.bulldogblocker.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TFLite adult content classifier.
 *
 * Model NOT bundled in APK.
 * User uploads saved_model.tflite via the app UI →
 * saved to:  context.filesDir/saved_model.tflite
 *
 * Output shape expected: [1][2] → [safe_score, unsafe_score]
 */
class ContentClassifier(private val context: Context) {

    data class Result(
        val safeScore:   Float,
        val unsafeScore: Float,
        val isAdult:     Boolean,
        val label:       String
    )

    companion object {
        const  val MODEL_FILENAME = "saved_model.tflite"
        private const val INPUT_SIZE      = 224
        private const val ADULT_THRESHOLD = 0.60f

        fun modelFile(ctx: Context): File = File(ctx.filesDir, MODEL_FILENAME)
        fun isReady(ctx: Context): Boolean =
            modelFile(ctx).exists() && modelFile(ctx).length() > 0
    }

    private var interpreter: Interpreter? = null

    /** Load interpreter from internal storage. Returns true on success. */
    fun load(): Boolean = try {
        val file = modelFile(context)
        if (!file.exists()) false
        else {
            val buf = loadMapped(file)
            interpreter = Interpreter(buf, Interpreter.Options().apply {
                numThreads = 2
                useNNAPI   = true
            })
            true
        }
    } catch (e: Exception) {
        e.printStackTrace(); false
    }

    /** Classify a bitmap. Call load() first. */
    fun classify(bitmap: Bitmap): Result {
        val interp = interpreter
            ?: return Result(1f, 0f, false, "⚠️ Model not loaded")

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
                "🔞 Adult — ${(unsafe * 100).toInt()}% confidence"
            else
                "✅ Safe  — ${(safe * 100).toInt()}% confidence"
        )
    }

    fun close() { interpreter?.close(); interpreter = null }

    // ── helpers ─────────────────────────────────

    private fun bitmapToBuffer(src: Bitmap): ByteBuffer {
        val bmp = Bitmap.createScaledBitmap(src, INPUT_SIZE, INPUT_SIZE, true)
        val buf = ByteBuffer
            .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .apply { order(ByteOrder.nativeOrder()) }
        val pix = IntArray(INPUT_SIZE * INPUT_SIZE)
        bmp.getPixels(pix, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pix) {
            buf.putFloat(((p shr 16) and 0xFF) / 255f)
            buf.putFloat(((p shr 8)  and 0xFF) / 255f)
            buf.putFloat( (p         and 0xFF) / 255f)
        }
        buf.rewind(); return buf
    }

    private fun loadMapped(f: File): MappedByteBuffer =
        f.inputStream().channel.use { ch ->
            ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
        }
}
