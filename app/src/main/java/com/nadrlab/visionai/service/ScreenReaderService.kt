package com.nadrlab.visionai.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.nadrlab.visionai.ai.ScreenTranslationEngine
import com.nadrlab.visionai.data.AppSettings
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.abs

/** Reads accessible screen text, translates it and draws translations in an overlay. */
class ScreenReaderService : AccessibilityService() {

    private data class ScreenItem(val text: String, val bounds: Rect)

    private var windowManager: WindowManager? = null
    private var floatingButton: TextView? = null
    private var translationOverlay: FrameLayout? = null
    private lateinit var appSettings: AppSettings

    @Volatile
    private var lastItems: List<ScreenItem> = emptyList()

    @Volatile
    private var destroyed = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val translationExecutor = Executors.newSingleThreadExecutor()

    private var generation = 0
    private var scanPending = false
    private var buttonRetryPending = false

    private val scanRunnable = Runnable {
        scanPending = false
        scanCurrentWindow()
    }

    private val buttonRetryRunnable = Runnable {
        buttonRetryPending = false
        if (!destroyed && floatingButton == null) showFloatingButton()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        destroyed = false
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        appSettings = AppSettings(applicationContext)
        addOverlay()
        showFloatingButton()
        scheduleScan(INITIAL_SCAN_DELAY_MS, reset = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || destroyed) return

        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage == packageName) {
            clearOverlay()
            ensureFloatingButton()
            return
        }
        if (eventPackage == SYSTEM_UI_PACKAGE) return

        // Recover the button if the system removed its window while the service stayed enabled.
        ensureFloatingButton()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                clearOverlay()
                scheduleScan(SCROLL_SCAN_DELAY_MS, reset = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                scheduleScan(CONTENT_SCAN_DELAY_MS, reset = false)
            }
        }
    }

    private fun ensureFloatingButton() {
        val button = floatingButton
        if (button != null && button.isAttachedToWindow) return
        floatingButton = null
        showFloatingButton()
    }

    private fun scheduleScan(delay: Long, reset: Boolean) {
        if (destroyed) return
        if (reset) {
            mainHandler.removeCallbacks(scanRunnable)
            scanPending = false
        }
        if (scanPending) return
        scanPending = true
        mainHandler.postDelayed(scanRunnable, delay)
    }

    /** Accessibility tree traversal runs on a worker so it cannot freeze the service UI thread. */
    private fun scanCurrentWindow(onComplete: ((List<ScreenItem>) -> Unit)? = null) {
        if (destroyed) return

        try {
            scanExecutor.execute {
                var result = emptyList<ScreenItem>()
                try {
                    val root = rootInActiveWindow
                    if (root != null) {
                        if (root.packageName?.toString() != packageName) {
                            result = collectVisibleText(root)
                        } else {
                            root.recycle()
                        }
                    }
                } catch (error: Exception) {
                    Log.w(TAG, "Screen scan failed", error)
                }

                if (!destroyed) {
                    mainHandler.post {
                        if (destroyed) return@post
                        // Replace the cache only after a successful window read.
                        if (result.isNotEmpty()) lastItems = result
                        onComplete?.invoke(lastItems)
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to schedule screen scan", error)
            onComplete?.let { callback ->
                mainHandler.post { callback(lastItems) }
            }
        }
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): List<ScreenItem> {
        val result = mutableListOf<ScreenItem>()
        val seen = HashSet<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES && result.size < MAX_ITEMS) {
            val (node, depth) = queue.removeFirst()
            visited++
            try {
                if (node.isVisibleToUser && !node.isEditable && !node.isPassword) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() >= MIN_WIDTH_PX && bounds.height() >= MIN_HEIGHT_PX) {
                        val text = node.text?.toString()?.trim().orEmpty()
                        addItem(text, bounds, seen, result)
                        val description = node.contentDescription?.toString()?.trim().orEmpty()
                        if (description != text) addItem(description, bounds, seen, result)
                    }
                }

                if (depth < MAX_DEPTH) {
                    val count = minOf(node.childCount, MAX_CHILDREN_PER_NODE)
                    for (index in 0 until count) {
                        val child = try { node.getChild(index) } catch (_: Exception) { null }
                        if (child != null) queue.add(child to (depth + 1))
                    }
                }
            } catch (error: Exception) {
                Log.v(TAG, "Skipping inaccessible accessibility node", error)
            } finally {
                try { node.recycle() } catch (_: Exception) { }
            }
        }

        // Release nodes left in the queue if the safety limits were reached.
        while (queue.isNotEmpty()) {
            try { queue.removeFirst().first.recycle() } catch (_: Exception) { }
        }
        return result
    }

    private fun addItem(
        raw: String,
        bounds: Rect,
        seen: HashSet<String>,
        output: MutableList<ScreenItem>
    ) {
        val text = raw.trim()
        if (text.length < MIN_TEXT_LENGTH || text.none { it.isLetter() }) return
        if (!seen.add(text)) return
        if (output.size >= MAX_ITEMS) return
        output.add(ScreenItem(text.take(MAX_TEXT_LENGTH), Rect(bounds)))
    }

    private fun showFloatingButton() {
        if (destroyed) return
        val current = floatingButton
        if (current != null && current.isAttachedToWindow) return
        floatingButton = null

        val wm = windowManager ?: return
        val button = TextView(this).apply {
            text = "تر"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            contentDescription = "ترجمة الشاشة"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0284C7.toInt())
                setStroke(dp(2), 0xFFFFFFFF.toInt())
            }
            elevation = dp(6).toFloat()
            isClickable = true
            isFocusable = true
        }

        val params = WindowManager.LayoutParams(
            dp(52), dp(52),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(12)
        }

        var startX = 0f
        var startY = 0f
        var initialX = 0
        var initialY = 0
        var moved = false

        button.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (startX - event.rawX).toInt()
                    val dy = (event.rawY - startY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        params.x = (initialX + dx).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - dp(60)).coerceAtLeast(0)
                        params.y = (initialY + dy).coerceIn(0, maxY)
                        try {
                            wm.updateViewLayout(view, params)
                        } catch (error: Exception) {
                            Log.w(TAG, "Unable to move floating button", error)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        button.setOnClickListener { translateCurrentScreen() }

        try {
            wm.addView(button, params)
            floatingButton = button
            buttonRetryPending = false
            mainHandler.removeCallbacks(buttonRetryRunnable)
        } catch (error: Exception) {
            floatingButton = null
            Log.e(TAG, "Unable to create accessibility floating button", error)
            scheduleButtonRetry()
        }
    }

    private fun scheduleButtonRetry() {
        if (destroyed || buttonRetryPending) return
        buttonRetryPending = true
        mainHandler.postDelayed(buttonRetryRunnable, BUTTON_RETRY_DELAY_MS)
    }

    private fun translateCurrentScreen() {
        floatingButton?.isEnabled = false
        Toast.makeText(this, "جاري قراءة نصوص الشاشة...", Toast.LENGTH_SHORT).show()

        scanCurrentWindow { items ->
            if (items.isEmpty()) {
                floatingButton?.isEnabled = true
                Toast.makeText(
                    this,
                    "لم يتمكن التطبيق من قراءة نصوص هذه الشاشة عبر إمكانية الوصول.",
                    Toast.LENGTH_LONG
                ).show()
                return@scanCurrentWindow
            }

            Toast.makeText(this, "جاري ترجمة ${items.size} مقطعاً...", Toast.LENGTH_SHORT).show()
            val currentGeneration = ++generation

            try {
                translationExecutor.execute {
                    try {
                        val translations = kotlinx.coroutines.runBlocking {
                            ScreenTranslationEngine.translateBatch(items.map { it.text }, appSettings)
                        }
                        val translatedItems = items.mapIndexedNotNull { index, item ->
                            val translated = translations.getOrNull(index)?.trim().orEmpty()
                            if (translated.isBlank() || translated == item.text) null
                            else item to translated
                        }

                        mainHandler.post {
                            floatingButton?.isEnabled = true
                            if (destroyed || currentGeneration != generation) return@post
                            if (translatedItems.isEmpty()) {
                                Toast.makeText(
                                    this,
                                    "لم يعثر محرك الترجمة على نص يحتاج إلى ترجمة.",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                renderTranslations(translatedItems)
                                Toast.makeText(
                                    this,
                                    "تمت ترجمة ${translatedItems.size} مقطعاً.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (error: Exception) {
                        mainHandler.post {
                            floatingButton?.isEnabled = true
                            if (!destroyed && currentGeneration == generation) {
                                Toast.makeText(
                                    this,
                                    "تعذرت الترجمة: ${error.message ?: "خطأ غير معروف"}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                floatingButton?.isEnabled = true
                Log.e(TAG, "Unable to schedule translation", error)
            }
        }
    }

    private fun addOverlay() {
        val existing = translationOverlay
        if (existing != null && existing.isAttachedToWindow) return
        translationOverlay = null

        val wm = windowManager ?: return
        val overlay = FrameLayout(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }

        try {
            wm.addView(overlay, params)
            translationOverlay = overlay
        } catch (error: Exception) {
            translationOverlay = null
            Log.e(TAG, "Unable to create translation overlay", error)
        }
    }

    private fun renderTranslations(items: List<Pair<ScreenItem, String>>) {
        addOverlay()
        val overlay = translationOverlay ?: return
        overlay.removeAllViews()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val rtl = appSettings.screenTranslationTarget in setOf("ar", "fa", "ur", "he")

        items.forEach { (item, translation) ->
            val bounds = Rect(item.bounds)
            if (bounds.right <= 0 || bounds.bottom <= 0 ||
                bounds.left >= screenWidth || bounds.top >= screenHeight) return@forEach

            val width = bounds.width().coerceIn(dp(48), screenWidth)
            val height = bounds.height().coerceAtLeast(dp(30)).coerceAtMost(dp(120))
            val label = TextView(this).apply {
                text = translation
                textSize = 12f
                setTextColor(0xFF111111.toInt())
                gravity = Gravity.CENTER_VERTICAL or (if (rtl) Gravity.RIGHT else Gravity.LEFT)
                textDirection = if (rtl) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
                layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                maxLines = 5
                setPadding(dp(4), dp(2), dp(4), dp(2))
                background = GradientDrawable().apply {
                    setColor(0xF5FFF7D6.toInt())
                    cornerRadius = dp(4).toFloat()
                    setStroke(dp(1), 0xFFB8A66A.toInt())
                }
            }
            val layout = FrameLayout.LayoutParams(width, height).apply {
                leftMargin = bounds.left.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
                topMargin = bounds.top.coerceIn(0, (screenHeight - height).coerceAtLeast(0))
            }
            overlay.addView(label, layout)
        }
    }

    private fun clearOverlay() {
        generation++
        translationOverlay?.removeAllViews()
    }

    override fun onInterrupt() {
        clearOverlay()
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        floatingButton?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) { }
        }
        translationOverlay?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) { }
        }
        floatingButton = null
        translationOverlay = null
        lastItems = emptyList()
        scanExecutor.shutdownNow()
        translationExecutor.shutdownNow()
        ScreenTranslationEngine.close()
        windowManager = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "ScreenReaderService"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val INITIAL_SCAN_DELAY_MS = 500L
        private const val SCROLL_SCAN_DELAY_MS = 650L
        private const val CONTENT_SCAN_DELAY_MS = 900L
        private const val BUTTON_RETRY_DELAY_MS = 1500L
        private const val MAX_NODES = 120
        private const val MAX_DEPTH = 12
        private const val MAX_CHILDREN_PER_NODE = 20
        private const val MAX_ITEMS = 30
        private const val MAX_TEXT_LENGTH = 700
        private const val MIN_TEXT_LENGTH = 2
        private const val MIN_WIDTH_PX = 20
        private const val MIN_HEIGHT_PX = 10
    }
}
