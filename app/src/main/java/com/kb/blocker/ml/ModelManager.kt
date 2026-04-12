package com.kb.blocker.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * TFLite model manager - supports multiple models.
 *
 * Models loaded from: filesDir/models/ (user-added .tflite files)
 *
 * Inference: all models run on each bitmap.
 * Any model detecting adult content returns true (block).
 * If one model fails, the next one is tried (fallback).
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODELS_DIR = "models"
        private const val RGB = 3
        private const val FLOAT_BYTES = 4
    }

    private data class TFModel(
        val name: String,
        val interpreter: Interpreter,
        val inputWidth: Int,
        val inputHeight: Int,
        val outputSize: Int,
        val outputShape: IntArray
    )

    private val models = mutableListOf<TFModel>()
    private val lock = ReentrantReadWriteLock()

    init {
        loadAllModels()
    }

    // ---------- Public API ----------

    fun loadAllModels() {
        lock.write {
            models.forEach { safeClose(it.interpreter) }
            models.clear()
            loadUserModels()
            Log.d(TAG, "${models.size} model(s) ready")
        }
    }

    /**
     * Run all models on a bitmap.
     * Returns true if any model scores >= threshold.
     */
    fun isAdultContent(bitmap: Bitmap, threshold: Float = 0.75f): Boolean {
        lock.read {
            if (models.isEmpty()) {
                Log.w(TAG, "No models loaded")
                return false
            }
            for (model in models) {
                try {
                    val score = runInference(bitmap, model)
                    Log.d(TAG, "[${model.name}] score=$score threshold=$threshold")
                    if (score >= threshold) return true
                } catch (e: Exception) {
                    Log.e(TAG, "Inference failed for '${model.name}', falling back", e)
                }
            }
            return false
        }
    }

    /**
     * Copy a .tflite file into internal storage and reload all models.
     */
    fun addUserModel(sourceFile: File): Boolean {
        return try {
            val dir = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
            sourceFile.copyTo(File(dir, sourceFile.name), overwrite = true)
            loadAllModels()
            Log.d(TAG, "User model added: ${sourceFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add model: ${sourceFile.name}", e)
            false
        }
    }

    fun getModelCount(): Int = lock.read { models.size }

    fun getModelNames(): List<String> = lock.read { models.map { it.name } }

    fun close() {
        lock.write {
            models.forEach { safeClose(it.interpreter) }
            models.clear()
        }
    }

    // ---------- Private ----------

    private fun loadUserModels() {
        val dir = File(context.filesDir, MODELS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
            return
        }
        dir.listFiles { f -> f.extension == "tflite" || f.extension == "lite" }
            ?.forEach { file ->
                try {
                    val buf = FileInputStream(file).channel
                        .map(FileChannel.MapMode.READ_ONLY, 0, file.length())
                    createModel(file.name, buf)?.let { models.add(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "User model load failed: ${file.name}", e)
                }
            }
    }

    private fun createModel(name: String, buffer: MappedByteBuffer): TFModel? {
        return try {
            val opts = Interpreter.Options().apply {
                numThreads = 2
                useXNNPACK = true
            }
            val interp = Interpreter(buffer, opts)

            // Input shape: [batch, H, W, channels]
            val inShape = interp.getInputTensor(0).shape()
            val h = if (inShape.size >= 3) inShape[1] else 224
            val w = if (inShape.size >= 3) inShape[2] else 224

            // Output shape: e.g. [1,1], [1,2], [1,N]
            val outShape = interp.getOutputTensor(0).shape()
            val outSize = outShape.reduce { a, b -> a * b }

            Log.d(TAG, "Loaded '$name': in=${h}x${w} out=${outShape.contentToString()}")
            TFModel(name, interp, w, h, outSize, outShape)
        } catch (e: Exception) {
            Log.e(TAG, "createModel failed for '$name'", e)
            null
        }
    }

    private fun runInference(bitmap: Bitmap, model: TFModel): Float {
        val scaled = Bitmap.createScaledBitmap(
            bitmap, model.inputWidth, model.inputHeight, true
        )

        val buf = ByteBuffer
            .allocateDirect(1 * model.inputHeight * model.inputWidth * RGB * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())

        val pixels = IntArray(model.inputWidth * model.inputHeight)
        scaled.getPixels(pixels, 0, model.inputWidth, 0, 0, model.inputWidth, model.inputHeight)
        if (scaled != bitmap) scaled.recycle()

        for (px in pixels) {
            buf.putFloat(((px shr 16) and 0xFF) / 255f) // R
            buf.putFloat(((px shr 8) and 0xFF) / 255f)  // G
            buf.putFloat((px and 0xFF) / 255f)           // B
        }

        val out = Array(1) { FloatArray(model.outputSize) }
        model.interpreter.run(buf, out)
        return interpretScore(out[0])
    }

    /**
     * Map raw output to a 0.0-1.0 adult confidence score.
     * size 1: binary sigmoid   -> use directly
     * size 2: softmax          -> take index 1 (adult class)
     * size N: multi-class      -> take max value
     */
    private fun interpretScore(output: FloatArray): Float {
        if (output.isEmpty()) return 0f
        return when (output.size) {
            1 -> output[0]
            2 -> output[1]
            else -> output.max()
        }
    }

    private fun safeClose(interp: Interpreter) {
        try { interp.close() } catch (_: Exception) {}
    }
}
