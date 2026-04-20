// app/src/main/java/com/guardian/shield/service/blur/TileAnalyzer.kt
package com.guardian.shield.service.blur

import android.graphics.Bitmap
import android.graphics.Rect
import com.guardian.shield.service.detection.AiDetector
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TileAnalyzer @Inject constructor(
    private val aiDetector: AiDetector
) {
    companion object {
        private const val TAG = "Guardian_Tiles"
        const val COLS = 5
        const val ROWS = 16
        // ✅ FIX: Stronger pixelation (40x) — content unrecognizable
        private const val PIXELATE_FACTOR = 44

        const val TILE_THRESHOLD_FACTOR = 0.42f
        private const val MIN_TILE_SIZE_PX = 100
    }

    data class TileAnalysisResult(
        val unsafeTiles: List<Pair<Rect, Bitmap>>,
        val maxUnsafeScore: Float
    ) {
        val isAnyUnsafe: Boolean get() = unsafeTiles.isNotEmpty()

        fun recycle() {
            unsafeTiles.forEach {
                runCatching {
                    if (!it.second.isRecycled) it.second.recycle()
                }
            }
        }
    }

    fun analyzeTiles(
        croppedBitmap: Bitmap,
        fullBitmap: Bitmap,
        threshold: Float,
        screenOffsetY: Int
    ): TileAnalysisResult {
        val tileW = croppedBitmap.width / COLS
        val tileH = croppedBitmap.height / ROWS

        if (tileW < MIN_TILE_SIZE_PX || tileH < MIN_TILE_SIZE_PX) {
            Timber.w("$TAG bitmap too small for tile analysis")
            return TileAnalysisResult(emptyList(), 0f)
        }

        val tileThreshold = threshold * TILE_THRESHOLD_FACTOR
        val unsafeTiles = mutableListOf<Pair<Rect, Bitmap>>()
        var maxScore = 0f

        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val x = col * tileW
                val y = row * tileH
                val w = if (col == COLS - 1) croppedBitmap.width - x else tileW
                val h = if (row == ROWS - 1) croppedBitmap.height - y else tileH
                if (w <= 0 || h <= 0) continue

                var tile: Bitmap? = null
                try {
                    tile = Bitmap.createBitmap(croppedBitmap, x, y, w, h)

                    if (aiDetector.shouldSkipFrame(tile)) continue

                    val result = aiDetector.classify(tile, tileThreshold)
                    if (result.unsafeScore > maxScore) maxScore = result.unsafeScore

                    if (result.isUnsafe) {
                        val screenRect = Rect(
                            x,
                            y + screenOffsetY,
                            x + w,
                            y + screenOffsetY + h
                        )
                        val blurred = buildBlurredTile(fullBitmap, screenRect)
                        if (blurred != null) {
                            unsafeTiles.add(screenRect to blurred)
                            Timber.d("$TAG tile[$row,$col] UNSAFE ${(result.unsafeScore * 100).toInt()}%")
                        }
                    }
                } catch (e: Exception) {
                    Timber.w("$TAG tile[$row,$col] error: ${e.message}")
                } finally {
                    tile?.let { if (!it.isRecycled) it.recycle() }
                }
            }
        }

        Timber.d("$TAG ${unsafeTiles.size}/${COLS * ROWS} unsafe, maxScore=${(maxScore * 100).toInt()}%")
        return TileAnalysisResult(unsafeTiles, maxScore)
    }

    private fun buildBlurredTile(source: Bitmap, rect: Rect): Bitmap? {
        val safeLeft = rect.left.coerceIn(0, source.width - 1)
        val safeTop = rect.top.coerceIn(0, source.height - 1)
        val safeRight = rect.right.coerceIn(safeLeft + 1, source.width)
        val safeBottom = rect.bottom.coerceIn(safeTop + 1, source.height)
        val w = safeRight - safeLeft
        val h = safeBottom - safeTop
        if (w <= 0 || h <= 0) return null

        var region: Bitmap? = null
        return try {
            region = Bitmap.createBitmap(source, safeLeft, safeTop, w, h)
            pixelate(region)
        } catch (e: Exception) {
            Timber.w("$TAG buildBlurredTile error: ${e.message}")
            null
        } finally {
            region?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun pixelate(src: Bitmap): Bitmap {
        val smallW = (src.width / PIXELATE_FACTOR).coerceAtLeast(1)
        val smallH = (src.height / PIXELATE_FACTOR).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, false)
        val result = Bitmap.createScaledBitmap(small, src.width, src.height, false)
        if (small !== result) small.recycle()
        return result
    }
}