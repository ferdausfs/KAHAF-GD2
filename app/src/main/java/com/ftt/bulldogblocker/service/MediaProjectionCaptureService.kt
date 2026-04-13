package com.ftt.bulldogblocker.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.ftt.bulldogblocker.ml.ContentClassifier
import com.ftt.bulldogblocker.ui.BlockScreenActivity
import com.ftt.bulldogblocker.ui.MainActivity
import kotlinx.coroutines.*

/**
 * MediaProjection ব্যবহার করে screen capture করে।
 * SurfaceView video (YouTube, browser video ইত্যাদি) সহ সব ধরে।
 *
 * User একবার consent দিলে চলতে থাকে।
 * Phone restart হলে আবার consent লাগবে।
 */
class MediaProjectionCaptureService : Service() {

    companion object {
        private const val TAG           = "BDB_MediaProj"
        const val CHANNEL_ID            = "bdb_mediaprojection"
        const val NOTIFICATION_ID       = 1002
        const val EXTRA_RESULT_CODE     = "result_code"
        const val EXTRA_RESULT_DATA     = "result_data"
        const val PREF_FILE             = "bdb_mediaprojection"
        const val PREF_ENABLED          = "enabled"

        private const val CAPTURE_INTERVAL = 350L       // ms
        private const val THRESHOLD        = 0.20f      // video-তে আরো aggressive
        private const val COOLDOWN_MS      = 3_000L

        fun isEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREF_FILE, MODE_PRIVATE)
                .getBoolean(PREF_ENABLED, false)

        fun setEnabled(ctx: Context, v: Boolean) =
            ctx.getSharedPreferences(PREF_FILE, MODE_PRIVATE)
                .edit().putBoolean(PREF_ENABLED, v).apply()

        fun start(ctx: Context, resultCode: Int, data: Intent) {
            val i = Intent(ctx, MediaProjectionCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MediaProjectionCaptureService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay?   = null
    private var imageReader: ImageReader?         = null
    private var classifier: ContentClassifier?    = null

    @Volatile private var lastBlock = 0L
    @Volatile private var scanning  = false

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode == -1 || resultData == null) {
            Log.e(TAG, "Invalid projection data — stopping")
            stopSelf(); return START_NOT_STICKY
        }

        initProjection(resultCode, resultData)
        return START_STICKY
    }

    override fun onDestroy() {
        scanning = false
        scope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        classifier?.close()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Setup ─────────────────────────────────────────────────────────

    private fun initProjection(resultCode: Int, data: Intent) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getMetrics(metrics)

        // Capture at 720p max — model only needs 224x224 anyway
        val w = minOf(metrics.widthPixels,  720)
        val h = minOf(metrics.heightPixels, 1280)
        val dpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "BDB_Capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        // Classifier load
        scope.launch(Dispatchers.IO) {
            val c = ContentClassifier(applicationContext)
            if (c.load()) {
                classifier = c
                Log.d(TAG, "Classifier ready")
                startScanLoop()
            } else {
                c.close()
                Log.e(TAG, "Classifier load failed")
                stopSelf()
            }
        }
    }

    // ── Scan loop ─────────────────────────────────────────────────────

    private fun startScanLoop() {
        scanning = true
        scope.launch {
            while (isActive && scanning) {
                delay(CAPTURE_INTERVAL)
                if (System.currentTimeMillis() - lastBlock < COOLDOWN_MS) continue
                processFrame()
            }
        }
    }

    private fun processFrame() {
        val reader = imageReader ?: return
        val c      = classifier  ?: return
        if (!c.isLoaded()) return

        var bmp: Bitmap? = null
        try {
            val image = reader.acquireLatestImage() ?: return
            val planes = image.planes
            val buffer = planes[0].buffer
            val rowStride    = planes[0].rowStride
            val pixelStride  = planes[0].pixelStride
            val w = image.width
            val h = image.height

            // RGBA → Bitmap
            val raw = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888)
            raw.copyPixelsFromBuffer(buffer)
            image.close()

            bmp = if (raw.width != w)
                Bitmap.createBitmap(raw, 0, 0, w, h).also { raw.recycle() }
            else raw

            // Black screen check (FLAG_SECURE / lock screen)
            if (isMostlyBlack(bmp)) return

            val result = c.classifyWithThreshold(bmp, THRESHOLD)
            if (result.isAdult) {
                Log.w(TAG, "Video adult content: ${(result.unsafeScore * 100).toInt()}%")
                lastBlock = System.currentTimeMillis()
                triggerBlock("Video ML: adult content (${(result.unsafeScore * 100).toInt()}%)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFrame error", e)
        } finally {
            bmp?.recycle()
        }
    }

    private fun triggerBlock(reason: String) {
        try {
            startActivity(Intent(this, BlockScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("reason", reason)
            })
        } catch (e: Exception) { Log.e(TAG, "launch BlockScreen failed", e) }
    }

    private fun isMostlyBlack(bmp: Bitmap): Boolean {
        val s = Bitmap.createScaledBitmap(bmp, 32, 32, false)
        val px = IntArray(1024); s.getPixels(px, 0, 32, 0, 0, 32, 32); s.recycle()
        var total = 0L
        for (p in px) total += ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF))
        return (total / (px.size * 3)) < 15
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun createChannel() {
        val ch = android.app.NotificationChannel(
            CHANNEL_ID, "Video Scan", android.app.NotificationManager.IMPORTANCE_LOW
        ).apply { description = "ML video content scanning" }
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎥 Video Scan চালু")
            .setContentText("Video content ML দিয়ে scan হচ্ছে")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE)
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
