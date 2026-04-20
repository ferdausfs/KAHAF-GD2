// app/src/main/java/com/guardian/shield/service/blur/RegionBlurOverlayManager.kt
package com.guardian.shield.service.blur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

@Singleton
class RegionBlurOverlayManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "Guardian_RegionBlur"
    }

    @Volatile var isShowing = false
        private set

    @Volatile private var blurView: RegionBlurView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    fun showRegions(tiles: List<Pair<Rect, Bitmap>>) {
        if (tiles.isEmpty()) {
            hide()
            return
        }
        mainHandler.post {
            synchronized(lock) {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                if (blurView == null) {
                    val view = RegionBlurView(context)
                    try {
                        wm.addView(view, buildLayoutParams())
                        blurView = view
                        isShowing = true
                        Timber.d("$TAG overlay created")
                    } catch (e: SecurityException) {
                        Timber.e("$TAG No SYSTEM_ALERT_WINDOW")
                        tiles.forEach { runCatching { if (!it.second.isRecycled) it.second.recycle() } }
                        return@synchronized
                    } catch (e: Exception) {
                        Timber.e(e, "$TAG addView failed")
                        tiles.forEach { runCatching { if (!it.second.isRecycled) it.second.recycle() } }
                        return@synchronized
                    }
                }

                val view = blurView
                if (view != null) {
                    view.updateTiles(tiles)
                } else {
                    tiles.forEach { runCatching { if (!it.second.isRecycled) it.second.recycle() } }
                }
            }
        }
    }

    fun hide() {
        mainHandler.post {
            synchronized(lock) {
                if (!isShowing && blurView == null) return@synchronized
                val view = blurView ?: run {
                    isShowing = false
                    return@synchronized
                }
                try {
                    view.clearTiles()
                    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    wm.removeViewImmediate(view)
                    Timber.d("$TAG hidden")
                } catch (e: Exception) {
                    Timber.w("$TAG hide: ${e.message}")
                } finally {
                    blurView = null
                    isShowing = false
                }
            }
        }
    }

    private fun buildLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }
}

internal class RegionBlurView(context: Context) : View(context) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(180, 220, 40, 40)
    }
    private val tiles = mutableListOf<Pair<Rect, Bitmap>>()
    private val tilesLock = Any()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateTiles(newTiles: List<Pair<Rect, Bitmap>>) {
        synchronized(tilesLock) {
            tiles.forEach { 
                runCatching { 
                    if (!it.second.isRecycled) it.second.recycle() 
                } 
            }
            tiles.clear()
            tiles.addAll(newTiles)
        }
        invalidate()
    }

    fun clearTiles() {
        synchronized(tilesLock) {
            tiles.forEach { 
                runCatching { 
                    if (!it.second.isRecycled) it.second.recycle() 
                } 
            }
            tiles.clear()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        synchronized(tilesLock) {
            for ((rect, bmp) in tiles) {
                if (!bmp.isRecycled) {
                    canvas.drawBitmap(bmp, null, rect, paint)
                    canvas.drawRect(rect, borderPaint)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        synchronized(tilesLock) {
            tiles.forEach { 
                runCatching { 
                    if (!it.second.isRecycled) it.second.recycle() 
                } 
            }
            tiles.clear()
        }
    }
}