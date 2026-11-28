package com.nadrlab.visionai.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Foreground service that keeps a MediaProjection virtual display alive so the accessibility service
 * can grab a screenshot on demand (used for OCR when an app exposes no readable text).
 * Works on Android 8+ (takeScreenshot of AccessibilityService needs Android 11).
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var latestImage: Image? = null
    private val lock = Any()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var width = 0
    private var height = 0
    private var densityDpi = 0

    @Volatile
    private var ready = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (resultCode != Activity.RESULT_OK || data == null) {
            ScreenReaderService.onCaptureUnavailable("لم تصل بيانات إذن التقاط الشاشة.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            // Must be a foreground service of type mediaProjection BEFORE getMediaProjection().
            startAsForeground()
            setupProjection(resultCode, data)
            instance = this
            ready = true
            Log.i(TAG, "Screen capture ready: ${width}x$height")
            ScreenReaderService.onCaptureReady()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start screen capture", error)
            ScreenReaderService.onCaptureUnavailable("تعذر بدء التقاط الشاشة: ${error.javaClass.simpleName}: ${error.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "التقاط الشاشة للترجمة", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("مترجم الشاشة: OCR جاهز")
            .setContentText("يُلتقط الشاشة عند الضغط على الفقاعة فقط.")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "إيقاف",
                    stopIntent
                ).build()
            )
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val newProjection = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection غير متاح")
        projection = newProjection

        val newThread = HandlerThread("VisionAI-capture").also { it.start() }
        thread = newThread
        val newHandler = Handler(newThread.looper)
        handler = newHandler

        // Android 14+ requires a callback to be registered before creating the virtual display.
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by the system")
                stopSelf()
            }
        }
        projectionCallback = callback
        newProjection.registerCallback(callback, newHandler)

        updateMetrics()
        createReader()
        virtualDisplay = newProjection.createVirtualDisplay(
            "VisionAI-capture", width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, newHandler
        )
    }

    @Suppress("DEPRECATION")
    private fun updateMetrics() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        width = metrics.widthPixels
        height = metrics.heightPixels
        densityDpi = metrics.densityDpi
    }

    private fun createReader() {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            try {
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                synchronized(lock) {
                    latestImage?.close()
                    latestImage = image
                }
            } catch (error: Exception) {
                Log.w(TAG, "Unable to acquire frame", error)
            }
        }, handler)
        imageReader = reader
    }

    /** Rebuilds the reader when the screen size changed (rotation). */
    private fun ensureSize() {
        val oldWidth = width
        val oldHeight = height
        updateMetrics()
        if (oldWidth == width && oldHeight == height) return

        synchronized(lock) {
            latestImage?.close()
            latestImage = null
        }
        val oldReader = imageReader
        createReader()
        virtualDisplay?.resize(width, height, densityDpi)
        virtualDisplay?.surface = imageReader!!.surface
        oldReader?.close()
    }

    /** Blocking; call from a worker thread. Returns the most recent frame as a bitmap. */
    private fun captureBitmapBlocking(timeoutMs: Long): Bitmap? {
        if (!ready) return null
        ensureSize()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            synchronized(lock) {
                val image = latestImage
                if (image != null) {
                    return try { imageToBitmap(image) } catch (error: Exception) {
                        Log.w(TAG, "Unable to convert frame", error)
                        null
                    }
                }
            }
            Thread.sleep(50)
        }
        return null
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        buffer.rewind() // the same frame can be converted more than once on a static screen
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        ready = false
        if (instance === this) instance = null
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        synchronized(lock) {
            try { latestImage?.close() } catch (_: Exception) { }
            latestImage = null
        }
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
        projectionCallback?.let { try { projection?.unregisterCallback(it) } catch (_: Exception) { } }
        try { projection?.stop() } catch (_: Exception) { }
        projection = null
        thread?.quitSafely()
        thread = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_STOP = "com.nadrlab.visionai.action.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val CAPTURE_TIMEOUT_MS = 2500L

        @Volatile
        private var instance: ScreenCaptureService? = null

        /** True while the projection is alive and frames can be captured. */
        val isReady: Boolean
            get() = instance?.ready == true

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        /** Blocking; worker thread only. Null if capture is not ready or no frame arrived in time. */
        fun captureBlocking(): Bitmap? = instance?.captureBitmapBlocking(CAPTURE_TIMEOUT_MS)
    }
}
