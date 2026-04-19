// app/src/main/java/com/guardian/shield/service/detection/AiDetector.kt
package com.guardian.shield.service.detection

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MODEL_FILENAME = "guardian_model.tflite"
        private const val INPUT_SIZE = 224
        private const val TAG = "Guardian_AI"

        fun modelFile(ctx: Context): File =
            File(ctx.filesDir, MODEL_FILENAME)

        fun isModelAvailable(ctx: Context): Boolean {
            val file = modelFile(ctx)
            val exists = file.exists() && file.length() > 1024
            Timber.d("$TAG isModelAvailable: exists=$exists, size=${file.length()}, path=${file.absolutePath}")
            return exists
        }
    }

    data class AiResult(
        val isUnsafe: Boolean,
        val unsafeScore: Float,
        val label: String
    )

    // BUG FIX: Removed shared pixelBuf/inputBuf instance fields.
    // These were allocated once and reused across all classify() calls, but classify()
    // runs on Dispatchers.Default which uses a thread pool. Concurrent calls would
    // clobber each other's buffer causing corrupted inference results.
    // Fix: allocate fresh buffers per classify() call in bitmapToBuffer().

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var loaded = false
    @Volatile private var outputSize = 0

    fun isLoaded(): Boolean = loaded

    /**
     * Load or reload the model.
     * Call from background thread.
     */
    fun load(): Boolean {
        return try {
            val file = modelFile(context)
            if (!file.exists()) {
                Timber.w("$TAG model file not found: ${file.absolutePath}")
                return false
            }
            if (file.length() < 1024) {
                Timber.w("$TAG model file too small: ${file.length()} bytes")
                return false
            }

            Timber.d("$TAG loading model: ${file.absolutePath} (${file.length() / 1024}KB)")

            val buf = mapFile(file)
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseXNNPACK(true)
            }

            synchronized(this) {
                interpreter?.close()
                interpreter = Interpreter(buf, options)

                // Read output shape to determine model type
                val shape = interpreter!!.getOutputTensor(0).shape()
                outputSize = shape[1]
                loaded = true
            }

            Timber.d("$TAG model loaded successfully! outputSize=$outputSize (${getModelType()})")
            true
        } catch (e: Exception) {
            Timber.e(e, "$TAG model load FAILED")
            loaded = false
            false
        }
    }

    fun unload() {
        synchronized(this) {
            interpreter?.close()
            interpreter = null
            loaded = false
            outputSize = 0
        }
        Timber.d("$TAG model unloaded")
    }

    /**
     * Reload model (unload + load). Used when new model is uploaded.
     */
    fun reload(): Boolean {
        Timber.d("$TAG reloading model...")
        unload()
        return load()
    }

    fun getModelType(): String = when (outputSize) {
        2 -> "2-class [safe, unsafe]"
        5 -> "5-class [drawings, hentai, neutral, porn, sexy]"
        else -> "$outputSize-class (unknown)"
    }

    /**
     * Classify a bitmap.
     * MUST be called from a background thread.
     */
    fun classify(bitmap: Bitmap, threshold: Float = 0.40f): AiResult {
        if (!loaded) {
            return AiResult(false, 0f, "Model not loaded")
        }

        return try {
            // BUG FIX: Read outputSize AND run inference inside the same synchronized block.
            // Previously outputSize was read OUTSIDE, so a concurrent unload() could set it to 0
            // between the block and parseOutput(), causing wrong classification path.
            val output: Array<FloatArray>
            val capturedOutputSize: Int
            synchronized(this) {
                val interp = interpreter
                    ?: return AiResult(false, 0f, "Model not loaded")
                if (!loaded) return AiResult(false, 0f, "Model not loaded")
                capturedOutputSize = outputSize
                val input = bitmapToBuffer(bitmap)
                output = Array(1) { FloatArray(capturedOutputSize) }
                interp.run(input, output)
            }

            val result = parseOutput(output[0], capturedOutputSize, threshold)
            Timber.d("$TAG classify result: unsafe=${result.unsafeScore}, label=${result.label}")
            result
        } catch (e: Exception) {
            Timber.e(e, "$TAG classify error")
            AiResult(false, 0f, "Error: ${e.message}")
        }
    }

    /**
     * Classify from AccessibilityNodeInfo text (for text-based NSFW detection).
     * This is a fallback for devices < Android 11 that can't do screenshots.
     */
    fun classifyText(text: String): AiResult {
        // TFLite image model can't classify text — this is a no-op placeholder
        // Real text classification would need a different model
        return AiResult(false, 0f, "Text-only (no image model)")
    }

    // ── Frame skip detection ──────────────────────────────────────────

    fun shouldSkipFrame(bitmap: Bitmap): Boolean {
        val sample = Bitmap.createScaledBitmap(bitmap, 32, 32, false)
        val pixels = IntArray(32 * 32)
        sample.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        if (sample !== bitmap) sample.recycle()

        var rM = 0.0; var rM2 = 0.0
        var gM = 0.0; var gM2 = 0.0
        var bM = 0.0; var bM2 = 0.0
        var brightness = 0L

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF).toDouble()
            val g = ((p shr 8) and 0xFF).toDouble()
            val b = (p and 0xFF).toDouble()
            brightness += (r + g + b).toLong()
            val n = (i + 1).toDouble()
            val dr = r - rM; rM += dr / n; rM2 += dr * (r - rM)
            val dg = g - gM; gM += dg / n; gM2 += dg * (g - gM)
            val db = b - bM; bM += db / n; bM2 += db * (b - bM)
        }
        val avg = brightness / (pixels.size * 3)
        if (avg < 15) {
            Timber.d("$TAG skip: mostly black (avg=$avg)")
            return true
        }
        val variance = ((rM2 + gM2 + bM2) / pixels.size).toFloat()
        if (variance < 150f) {
            Timber.d("$TAG skip: uniform color (variance=$variance)")
            return true
        }
        return false
    }

    // ── Output parsing ────────────────────────────────────────────────

    private fun parseOutput(scores: FloatArray, size: Int, threshold: Float): AiResult {
        Timber.d("$TAG raw scores: ${scores.toList()}")

        return when (size) {
            2 -> {
                // 2-class: [safe, unsafe]
                val unsafe = scores[1]
                AiResult(
                    isUnsafe    = unsafe >= threshold,
                    unsafeScore = unsafe,
                    label       = if (unsafe >= threshold) "Unsafe (${(unsafe * 100).toInt()}%)" else "Safe (${((1-unsafe) * 100).toInt()}%)"
                )
            }
            5 -> {
                // 5-class: [drawings, hentai, neutral, porn, sexy]
                val drawings = scores[0]
                val hentai   = scores[1]
                val neutral  = scores[2]
                val porn     = scores[3]
                val sexy     = scores[4]

                Timber.d("$TAG 5-class: drawings=$drawings, hentai=$hentai, neutral=$neutral, porn=$porn, sexy=$sexy")

                // ── Cartoon / animation safeguard ─────────────────────
                // Tom & Jerry, Mr. Bean cartoons, anime intros etc. score
                // high on "drawings" or "neutral". Without this guard they
                // were triggering false positives because the raw
                // hentai+porn+sexy sum exceeded threshold even when
                // drawings was the clearly dominant class.
                //
                // Rules (applied in order):
                //  1. drawings is dominant AND clearly above NSFW classes
                //     → strong dampening (cartoon content, almost certainly safe)
                //  2. neutral is dominant AND sexy/hentai/porn are all low
                //     → treat as safe (live-action comedy, news, etc.)
                //  3. Otherwise: standard NSFW scoring
                val nsfwRaw = hentai + porn + sexy
                val safeRaw = drawings + neutral

                val cartoonDampening: Float = when {
                    // Case 1: clearly cartoon/animated — drawings leads by margin
                    drawings > 0.40f
                            && drawings > hentai * 3f
                            && drawings > porn   * 3f
                            && drawings > sexy   * 2f -> 0.15f   // 85% reduction

                    // Case 2: drawings present and dominant over explicit NSFW
                    drawings > 0.25f
                            && drawings > hentai
                            && drawings > porn   -> 0.40f         // 60% reduction

                    // Case 3: neutral/safe content dominates
                    neutral > 0.55f
                            && nsfwRaw < 0.25f   -> 0.30f         // 70% reduction

                    // Case 4: safe signals stronger than NSFW signals overall
                    safeRaw > nsfwRaw * 2.5f     -> 0.50f         // 50% reduction

                    else                         -> 1.0f           // no dampening
                }

                if (cartoonDampening < 1.0f) {
                    Timber.d("$TAG cartoon/safe dampening applied: ${cartoonDampening}x (drawings=$drawings, neutral=$neutral)")
                }

                // NSFW score = hentai + porn + weighted sexy, then dampened
                val sexyContrib = when {
                    sexy > 0.35f -> sexy * 1.5f
                    sexy > 0.20f -> sexy * 0.6f
                    else         -> sexy * 0.1f
                }
                val rawUnsafe   = (hentai + porn + sexyContrib).coerceIn(0f, 1f)
                val unsafeScore = (rawUnsafe * cartoonDampening).coerceIn(0f, 1f)

                // For the sexy threshold check, also apply dampening
                val isUnsafe = unsafeScore >= threshold
                        || (sexy * cartoonDampening) >= 0.45f

                // Find dominant label
                val labels = listOf("drawings", "hentai", "neutral", "porn", "sexy")
                val maxIdx = scores.indices.maxByOrNull { scores[it] } ?: 2
                val dominantLabel = labels[maxIdx]

                AiResult(
                    isUnsafe    = isUnsafe,
                    unsafeScore = unsafeScore,
                    label       = if (isUnsafe)
                        "NSFW: $dominantLabel (${(unsafeScore * 100).toInt()}%)"
                    else
                        "Safe: $dominantLabel (dampening=${cartoonDampening})"
                )
            }
            else -> {
                // Unknown model — assume last class is unsafe
                val unsafe = scores.last()
                AiResult(
                    isUnsafe    = unsafe >= threshold,
                    unsafeScore = unsafe,
                    label       = "Score: ${(unsafe * 100).toInt()}%"
                )
            }
        }
    }

    // ── Buffer helpers ────────────────────────────────────────────────

    private fun bitmapToBuffer(src: Bitmap): ByteBuffer {
        val bmp = if (src.width != INPUT_SIZE || src.height != INPUT_SIZE) {
            Bitmap.createScaledBitmap(src, INPUT_SIZE, INPUT_SIZE, true)
        } else {
            src
        }
        // BUG FIX: Allocate fresh buffers per call (thread-safe).
        // Shared instance buffers caused data corruption on concurrent classify() calls.
        val pixelBuf = IntArray(INPUT_SIZE * INPUT_SIZE)
        val inputBuf = ByteBuffer
            .allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .apply { order(ByteOrder.nativeOrder()) }

        bmp.getPixels(pixelBuf, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (bmp !== src) bmp.recycle()

        for (p in pixelBuf) {
            inputBuf.putFloat(((p shr 16) and 0xFF) / 255f)  // R
            inputBuf.putFloat(((p shr 8) and 0xFF) / 255f)   // G
            inputBuf.putFloat((p and 0xFF) / 255f)            // B
        }
        inputBuf.rewind()
        return inputBuf
    }

    private fun mapFile(f: File): MappedByteBuffer =
        FileInputStream(f).use { fis ->
            fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
        }
}