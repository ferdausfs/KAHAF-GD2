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
            return exists
        }
    }

    data class AiResult(
        val isUnsafe: Boolean,
        val unsafeScore: Float,
        val label: String
    )

    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var loaded = false
    @Volatile private var outputSize = 0

    fun isLoaded(): Boolean = loaded

    fun load(): Boolean {
        return try {
            val file = modelFile(context)
            if (!file.exists()) {
                Timber.w("$TAG model file not found")
                return false
            }
            if (file.length() < 1024) {
                Timber.w("$TAG model too small: ${file.length()}")
                return false
            }

            Timber.d("$TAG loading: ${file.length() / 1024}KB")

            val buf = mapFile(file)
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseXNNPACK(true)
            }

            synchronized(this) {
                interpreter?.close()
                interpreter = Interpreter(buf, options)
                val shape = interpreter!!.getOutputTensor(0).shape()
                outputSize = shape[1]
                loaded = true
            }

            Timber.d("$TAG loaded: outputSize=$outputSize (${getModelType()})")
            true
        } catch (e: Exception) {
            Timber.e(e, "$TAG load FAILED")
            loaded = false
            false
        }
    }

    fun unload() {
        synchronized(this) {
            try { interpreter?.close() } catch (_: Exception) {}
            interpreter = null
            loaded = false
            outputSize = 0
        }
        Timber.d("$TAG unloaded")
    }

    fun reload(): Boolean {
        Timber.d("$TAG reloading...")
        unload()
        return load()
    }

    fun getModelType(): String = when (outputSize) {
        2 -> "2-class [safe, unsafe]"
        5 -> "5-class [drawings, hentai, neutral, porn, sexy]"
        else -> "$outputSize-class"
    }

    fun classify(bitmap: Bitmap, threshold: Float = 0.35f): AiResult {
        if (!loaded) return AiResult(false, 0f, "Not loaded")

        return try {
            val output: Array<FloatArray>
            val capturedOutputSize: Int
            synchronized(this) {
                val interp = interpreter ?: return AiResult(false, 0f, "Not loaded")
                if (!loaded) return AiResult(false, 0f, "Not loaded")
                capturedOutputSize = outputSize
                val input = bitmapToBuffer(bitmap)
                output = Array(1) { FloatArray(capturedOutputSize) }
                interp.run(input, output)
            }

            parseOutput(output[0], capturedOutputSize, threshold)
        } catch (e: Exception) {
            Timber.e(e, "$TAG classify error")
            AiResult(false, 0f, "Error")
        }
    }

    fun shouldSkipFrame(bitmap: Bitmap): Boolean {
        return try {
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
            if (avg < 15) return true  // mostly black
            val variance = ((rM2 + gM2 + bM2) / pixels.size).toFloat()
            variance < 150f  // uniform color
        } catch (e: Exception) {
            Timber.w("$TAG shouldSkipFrame error: ${e.message}")
            false
        }
    }

    private fun parseOutput(scores: FloatArray, size: Int, threshold: Float): AiResult {
        return when (size) {
            2 -> {
                val unsafe = scores[1]
                AiResult(
                    isUnsafe = unsafe >= threshold,
                    unsafeScore = unsafe,
                    label = if (unsafe >= threshold) 
                        "Unsafe ${(unsafe * 100).toInt()}%" 
                    else 
                        "Safe ${((1 - unsafe) * 100).toInt()}%"
                )
            }
            5 -> {
                val drawings = scores[0]
                val hentai = scores[1]
                val neutral = scores[2]
                val porn = scores[3]
                val sexy = scores[4]

                val nsfwRaw = hentai + porn + sexy
                val safeRaw = drawings + neutral

                // Cartoon/safe content dampening
                val dampening: Float = when {
                    drawings > 0.40f && drawings > hentai * 3f 
                        && drawings > porn * 3f && drawings > sexy * 2f -> 0.15f
                    drawings > 0.25f && drawings > hentai 
                        && drawings > porn -> 0.40f
                    neutral > 0.55f && nsfwRaw < 0.25f -> 0.30f
                    safeRaw > nsfwRaw * 2.5f -> 0.50f
                    else -> 1.0f
                }

                val sexyContrib = when {
                    sexy > 0.35f -> sexy * 1.5f
                    sexy > 0.20f -> sexy * 0.6f
                    else -> sexy * 0.1f
                }
                val rawUnsafe = (hentai + porn + sexyContrib).coerceIn(0f, 1f)
                val unsafeScore = (rawUnsafe * dampening).coerceIn(0f, 1f)

                val isUnsafe = unsafeScore >= threshold 
                    || (sexy * dampening) >= 0.45f

                val labels = listOf("drawings", "hentai", "neutral", "porn", "sexy")
                val maxIdx = scores.indices.maxByOrNull { scores[it] } ?: 2
                val dominantLabel = labels[maxIdx]

                AiResult(
                    isUnsafe = isUnsafe,
                    unsafeScore = unsafeScore,
                    label = if (isUnsafe)
                        "NSFW: $dominantLabel ${(unsafeScore * 100).toInt()}%"
                    else
                        "Safe: $dominantLabel"
                )
            }
            else -> {
                val unsafe = scores.last()
                AiResult(
                    isUnsafe = unsafe >= threshold,
                    unsafeScore = unsafe,
                    label = "${(unsafe * 100).toInt()}%"
                )
            }
        }
    }

    private fun bitmapToBuffer(src: Bitmap): ByteBuffer {
        val bmp = if (src.width != INPUT_SIZE || src.height != INPUT_SIZE) {
            Bitmap.createScaledBitmap(src, INPUT_SIZE, INPUT_SIZE, true)
        } else src

        val pixelBuf = IntArray(INPUT_SIZE * INPUT_SIZE)
        val inputBuf = ByteBuffer
            .allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .apply { order(ByteOrder.nativeOrder()) }

        bmp.getPixels(pixelBuf, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (bmp !== src) bmp.recycle()

        for (p in pixelBuf) {
            inputBuf.putFloat(((p shr 16) and 0xFF) / 255f)
            inputBuf.putFloat(((p shr 8) and 0xFF) / 255f)
            inputBuf.putFloat((p and 0xFF) / 255f)
        }
        inputBuf.rewind()
        return inputBuf
    }

    private fun mapFile(f: File): MappedByteBuffer =
        FileInputStream(f).use { fis ->
            fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, fis.channel.size())
        }
}