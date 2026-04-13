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
 * The user uploads saved_model.tflite via the app UI.
 * It is saved to: context.filesDir/saved_model.tflite
 *
 * Expected output shape: [1][2] -> [safe_score, unsafe_score]
 */
class ContentClassifier(private val context: Context) {

    data class Result(
        val safeScore:   Float,
        val unsafeScore: Float,
        val isAdult:     Boolean,
        val label:       String
    )

    companion object {
        const val MODEL_FILENAME    = "saved_model.tflite"
        private const val INPUT_SIZE      = 224
        private const val ADULT_THRESHOLD = 0.60f

        fun modelFile(ctx: Context): File = File(ctx.filesDir, MODEL_FILENAME)
        fun isReady(ctx: Context): Boolean =
            modelFile(ctx).exists() && modelFile(ctx).length() > 0
    }

    private var interpreter: Interpreter? = null

    /** Load the interpreter from internal storage. Returns true on success. */
    fun load(): Boolean = try {
        val file = modelFile(context)
        if (!file.exists()) false
        else {
            val buf = loadMapped(file)
            interpreter = Interpreter(buf, Interpreter.Options().apply {
                numThreads = 2
                // useNNAPI is deprecated and crashes on many devices.
                // useXNNPACK is the correct modern replacement.
                useXNNPACK = true
            })
            true
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /**
     * Classify a bitmap. Call load() first.
     * This is a blocking call — run it on a background thread.
     */
    fun classify(bitmap: Bitmap): Result {
        val interp = interpreter
            ?: return Result(1f, 0f, false, "Model not loaded")

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
                "Adult content — ${(unsafe * 100).toInt()}% confidence"
            else
                "Safe — ${(safe * 100).toInt()}% confidence"
        )
    }

    fun isLoaded(): Boolean = interpreter != null

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    // --- Helpers ---

    private fun bitmapToBuffer(src: Bitmap): ByteBuffer {
        // Scale to model input size
        val bmp = Bitmap.createScaledBitmap(src, INPUT_SIZE, INPUT_SIZE, true)

        val buf = ByteBuffer
            .allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .apply { order(ByteOrder.nativeOrder()) }

        val pix = IntArray(INPUT_SIZE * INPUT_SIZE)
        bmp.getPixels(pix, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // Recycle the scaled copy if it is a new bitmap (not the original)
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
        // Open a channel, map it, then close the stream.
        // The MappedByteBuffer remains valid after the channel is closed.
        val fis = FileInputStream(f)
        return fis.channel.use { ch ->
            ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size())
        }
    }
}
