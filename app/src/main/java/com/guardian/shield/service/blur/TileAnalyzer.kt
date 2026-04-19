// app/src/main/java/com/guardian/shield/service/blur/TileAnalyzer.kt
package com.guardian.shield.service.blur

import android.graphics.Bitmap
import android.graphics.Rect
import com.guardian.shield.service.detection.AiDetector
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TileAnalyzer — splits a screenshot into a COLS×ROWS grid and runs the AI
 * classifier on each cell to locate which specific regions contain sensitive content.
 *
 * Only called after the whole-frame classifier has already flagged the frame as unsafe,
 * so the extra N inferences only fire when content is actually detected.
 *
 * Returns pre-pixelated (blurred) bitmaps ready for the overlay to draw,
 * paired with their screen-space Rects.
 *
 * MEMORY CONTRACT:
 *   Caller must call [TileAnalysisResult.recycle] when done, unless the result
 *   was passed to RegionBlurOverlayManager.showRegions() which takes ownership.
 */
@Singleton
class TileAnalyzer @Inject constructor(
    private val aiDetector: AiDetector
) {
    companion object {
        private const val TAG = "Guardian_Tiles"
        const val COLS = 3       // 3 columns
        const val ROWS = 4       // 4 rows  →  12 tiles total
        private const val PIXELATE_FACTOR = 14   // higher = more aggressive blur

        // Tiles are 1/12 of the screen — the AI model scores them much lower than a
        // whole-frame inference even when unsafe content is present. Using the same
        // threshold means tiles almost never get flagged individually.
        // Fix: lower the per-tile threshold to 55% of the main threshold.
        // e.g. main = 0.40f → tile threshold = 0.22f
        const val TILE_THRESHOLD_FACTOR = 0.55f
    }

    data class TileAnalysisResult(
        /** Pairs of (screen-space Rect, pre-pixelated Bitmap) for each unsafe tile. */
        val unsafeTiles: List<Pair<Rect, Bitmap>>,
        val maxUnsafeScore: Float
    ) {
        val isAnyUnsafe: Boolean get() = unsafeTiles.isNotEmpty()

        /** Recycle all bitmaps. Call this if you did NOT pass the result to showRegions(). */
        fun recycle() { unsafeTiles.forEach { runCatching { it.second.recycle() } } }
    }

    /**
     * @param croppedBitmap  The AI-inference bitmap (status/nav bar already removed).
     * @param fullBitmap     Full-screen bitmap — used to extract pixel data for blur rendering
     *                       so the blurred tiles show actual screen content.
     * @param threshold      Same unsafe threshold used for the whole-frame check.
     * @param screenOffsetY  Pixels cropped from the top (status bar height) — added back so
     *                       returned Rects are in physical screen coordinates.
     */
    fun analyzeTiles(
        croppedBitmap: Bitmap,
        fullBitmap: Bitmap,
        threshold: Float,
        screenOffsetY: Int
    ): TileAnalysisResult {
        val tileW = croppedBitmap.width / COLS
        val tileH = croppedBitmap.height / ROWS
        if (tileW <= 4 || tileH <= 4) {
            Timber.w("$TAG bitmap too small for tile analysis")
            return TileAnalysisResult(emptyList(), 0f)
        }

        // Per-tile threshold is lower than the whole-frame threshold.
        // Tiles are 1/12 of the screen, so the model scores them lower even when
        // unsafe content is present. TILE_THRESHOLD_FACTOR corrects for this.
        val tileThreshold = threshold * TILE_THRESHOLD_FACTOR

        val unsafeTiles = mutableListOf<Pair<Rect, Bitmap>>()
        var maxScore = 0f

        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                val x = col * tileW
                val y = row * tileH
                // Last column/row gets any remainder pixels
                val w = if (col == COLS - 1) croppedBitmap.width - x else tileW
                val h = if (row == ROWS - 1) croppedBitmap.height - y else tileH
                if (w <= 0 || h <= 0) continue

                val tile: Bitmap = try {
                    Bitmap.createBitmap(croppedBitmap, x, y, w, h)
                } catch (e: Exception) {
                    Timber.w("$TAG createBitmap failed for tile[$row,$col]: ${e.message}")
                    continue
                }

                try {
                    // Skip blank / all-black tiles (loading screens, etc.)
                    if (aiDetector.shouldSkipFrame(tile)) continue

                    val result = aiDetector.classify(tile, tileThreshold)
                    if (result.unsafeScore > maxScore) maxScore = result.unsafeScore

                    if (result.isUnsafe) {
                        // Convert cropped-bitmap coords → physical screen coords
                        val screenRect = Rect(
                            x,
                            y + screenOffsetY,
                            x + w,
                            y + screenOffsetY + h
                        )
                        val blurred = buildBlurredTile(fullBitmap, screenRect)
                        if (blurred != null) {
                            unsafeTiles.add(screenRect to blurred)
                            Timber.d("$TAG tile[$row,$col] UNSAFE ${(result.unsafeScore * 100).toInt()}%  rect=$screenRect")
                        }
                    }
                } finally {
                    tile.recycle()
                }
            }
        }

        Timber.d("$TAG result: ${unsafeTiles.size}/${COLS * ROWS} tiles unsafe, maxScore=${(maxScore * 100).toInt()}%")
        return TileAnalysisResult(unsafeTiles, maxScore)
    }

    // ── Blur rendering ─────────────────────────────────────────────────

    /**
     * Extract [rect] from [source] and apply heavy pixelation so the content
     * is unrecognisable. Returns null if the rect is out of bounds.
     */
    private fun buildBlurredTile(source: Bitmap, rect: Rect): Bitmap? {
        val safeLeft   = rect.left.coerceIn(0, source.width - 1)
        val safeTop    = rect.top.coerceIn(0, source.height - 1)
        val safeRight  = rect.right.coerceIn(safeLeft + 1, source.width)
        val safeBottom = rect.bottom.coerceIn(safeTop + 1, source.height)
        val w = safeRight - safeLeft
        val h = safeBottom - safeTop
        if (w <= 0 || h <= 0) return null

        return try {
            val region = Bitmap.createBitmap(source, safeLeft, safeTop, w, h)
            val pixelated = pixelate(region)
            region.recycle()
            pixelated
        } catch (e: Exception) {
            Timber.w("$TAG buildBlurredTile error: ${e.message}")
            null
        }
    }

    /**
     * Pixelation: scale WAY down (loses all detail), then scale back up
     * without bilinear filtering → large colour blocks that obscure content.
     * Works on ALL Android versions with no extra libraries.
     */
    private fun pixelate(src: Bitmap): Bitmap {
        val smallW = (src.width / PIXELATE_FACTOR).coerceAtLeast(1)
        val smallH = (src.height / PIXELATE_FACTOR).coerceAtLeast(1)
        // Scale down — destroys fine detail
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, /* filter= */ false)
        // Scale back up WITHOUT filtering → harsh pixel blocks
        val result = Bitmap.createScaledBitmap(small, src.width, src.height, /* filter= */ false)
        small.recycle()
        return result
    }
}
