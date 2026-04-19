// app/src/main/java/com/guardian/shield/service/blur/RegionBlurOverlayManager.kt
package com.guardian.shield.service.blur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RegionBlurOverlayManager — draws blurred overlays ONLY on specific screen regions.
 *
 * Unlike the full-screen BlurOverlayManager, this manager shows a completely
 * transparent overlay except for the rectangular regions that contain unsafe content.
 * Each unsafe region is covered by a pixelated (blurred) version of its own pixels,
 * extracted from the latest screenshot.
 *
 * Visual result:
 *   • Sensitive region  → pixelated/blurred  (content unrecognisable)
 *   • Everything else   → fully transparent  (app still usable normally)
 *
 * No popup, no notification, no app block — just localised blur.
 *
 * Thread safety: showRegions() and hide() are safe to call from any thread.
 * All WindowManager operations are dispatched to the main thread.
 */
@Singleton
class RegionBlurOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "Guardian_RegionBlur"
    }

    @Volatile var isShowing = false
        private set

    private var blurView: RegionBlurView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Show (or update) blur patches on the given unsafe regions.
     *
     * [tiles] is a list of (screenRect, pixelatedBitmap) pairs produced by TileAnalyzer.
     * This method TAKES OWNERSHIP of the bitmaps — the caller must NOT recycle them.
     *
     * If [tiles] is empty, hides the overlay instead.
     */
    fun showRegions(tiles: List<Pair<Rect, Bitmap>>) {
        if (tiles.isEmpty()) {
            hide()
            return
        }
        mainHandler.post {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            if (blurView == null) {
                // First time — create the overlay window
                val view = RegionBlurView(context)
                try {
                    wm.addView(view, buildLayoutParams())
                    blurView = view
                    isShowing = true
                    Timber.d("$TAG overlay window created")
                } catch (e: SecurityException) {
                    Timber.e("$TAG SYSTEM_ALERT_WINDOW not granted — region blur blocked")
                    tiles.forEach { runCatching { it.second.recycle() } }
                    return@post
                } catch (e: Exception) {
                    Timber.e(e, "$TAG addView failed")
                    tiles.forEach { runCatching { it.second.recycle() } }
                    return@post
                }
            }

            // Hand tiles to the view (view will recycle old bitmaps)
            val view = blurView
            if (view != null) {
                view.updateTiles(tiles)
            } else {
                // View was removed between the null-check and this line — recycle tiles
                tiles.forEach { runCatching { it.second.recycle() } }
            }
        }
    }

    /**
     * Remove the overlay entirely. Safe to call repeatedly — no-op if not showing.
     */
    fun hide() {
        if (!isShowing && blurView == null) return
        mainHandler.post {
            val view = blurView ?: run {
                isShowing = false
                return@post
            }
            try {
                view.clearTiles()
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeViewImmediate(view)
                Timber.d("$TAG overlay hidden")
            } catch (e: Exception) {
                // View may already have been removed by the system
                Timber.w("$TAG hide error (may be harmless): ${e.message}")
            } finally {
                blurView = null
                isShowing = false
            }
        }
    }

    // ── Internal ───────────────────────────────────────────────────────

    private fun buildLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // FLAG_NOT_TOUCHABLE  → touches still reach the underlying app (no app disruption)
        // FLAG_NOT_FOCUSABLE  → keyboard / back button still work normally
        // FLAG_LAYOUT_IN_SCREEN → covers status bar area for correct coordinate alignment
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT      // ← key: transparent background between blur patches
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RegionBlurView — the actual View that draws the blur patches
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen transparent View.
 * Only the unsafe tile regions are rendered — everything else stays see-through.
 *
 * Each tile entry is a (Rect → pixelated Bitmap) pair.
 * The Rect is in physical screen coordinates; the Bitmap is already pre-pixelated.
 */
internal class RegionBlurView(context: Context) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tiles = mutableListOf<Pair<Rect, Bitmap>>()

    init {
        // The view itself must be transparent — only the tile bitmaps are opaque
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Replace current tiles with [newTiles]. Recycles old bitmaps.
     * Must be called on the main thread.
     */
    fun updateTiles(newTiles: List<Pair<Rect, Bitmap>>) {
        tiles.forEach { runCatching { it.second.recycle() } }
        tiles.clear()
        tiles.addAll(newTiles)
        invalidate()
    }

    /**
     * Remove all tiles and recycle their bitmaps.
     * Must be called on the main thread.
     */
    fun clearTiles() {
        tiles.forEach { runCatching { it.second.recycle() } }
        tiles.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Draw ONLY the blur patches — background is transparent
        for ((rect, bmp) in tiles) {
            if (!bmp.isRecycled) {
                canvas.drawBitmap(bmp, /* src= */ null, /* dst= */ rect, paint)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        tiles.forEach { runCatching { it.second.recycle() } }
        tiles.clear()
    }
}
