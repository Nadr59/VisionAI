package com.nadrlab.visionai.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var scanPending = false

    private val scanRunnable = Runnable {
        scanPending = false
        scanCurrentWindow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        appSettings = AppSettings(applicationContext)
        addOverlay()
        showFloatingButton()
        scheduleScan(INITIAL_SCAN_DELAY_MS, reset = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val eventPackage = event.packageName?.toString() ?: return
        if (eventPackage == packageName) {
            clearOverlay()
            return
        }
        if (eventPackage == SYSTEM_UI_PACKAGE) return

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

    private fun scheduleScan(delay: Long, reset: Boolean) {
        if (reset) {
            mainHandler.removeCallbacks(scanRunnable)
            scanPending = false
        }
        if (scanPending) return
        scanPending = true
        mainHandler.postDelayed(scanRunnable, delay)
    }

    private fun scanCurrentWindow() {
        try {
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() == packageName) return
            lastItems = collectVisibleText(root)
        } catch (_: Exception) {
            // Keep the last successful scan if the app temporarily blocks tree access.
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
            } catch (_: Exception) {
                // Ignore inaccessible nodes and continue.
            }
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
        output.add(ScreenItem(text.take(MAX_TEXT_LENGTH), Rect(bounds)))
    }

    private fun showFloatingButton() {
        if (floatingButton != null) return
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
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) { }
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
        } catch (_: Exception) {
            floatingButton = null
        }
    }

    private fun translateCurrentScreen() {
        scanCurrentWindow()
        val items = lastItems.toList()
        if (items.isEmpty()) {
            Toast.makeText(
                this,
                "لم يتمكن التطبيق من قراءة نصوص هذه الشاشة عبر إمكانية الوصول.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        floatingButton?.isEnabled = false
        Toast.makeText(this, "جاري ترجمة ${items.size} مقطعاً...", Toast.LENGTH_SHORT).show()
        val currentGeneration = ++generation

        worker.execute {
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
                    if (currentGeneration != generation) return@post
                    if (translatedItems.isEmpty()) {
                        Toast.makeText(this, "لم يعثر محرك الترجمة على نص يحتاج إلى ترجمة.", Toast.LENGTH_LONG).show()
                    } else {
                        renderTranslations(translatedItems)
                        Toast.makeText(this, "تمت ترجمة ${translatedItems.size} مقطعاً.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (error: Exception) {
                mainHandler.post {
                    floatingButton?.isEnabled = true
                    if (currentGeneration == generation) {
                        Toast.makeText(
                            this,
                            "تعذرت الترجمة: ${error.message ?: "خطأ غير معروف"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun addOverlay() {
        if (translationOverlay != null) return
        val wm = windowManager ?: return
        val overlay = FrameLayout(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }

        try {
            wm.addView(overlay, params)
            translationOverlay = overlay
        } catch (_: Exception) {
            translationOverlay = null
        }
    }

    private fun renderTranslations(items: List<Pair<ScreenItem, String>>) {
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
        mainHandler.removeCallbacksAndMessages(null)
        floatingButton?.let { try { windowManager?.removeView(it) } catch (_: Exception) { } }
        translationOverlay?.let { try { windowManager?.removeView(it) } catch (_: Exception) { } }
        floatingButton = null
        translationOverlay = null
        lastItems = emptyList()
        worker.shutdownNow()
        ScreenTranslationEngine.close()
        windowManager = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val INITIAL_SCAN_DELAY_MS = 350L
        private const val SCROLL_SCAN_DELAY_MS = 550L
        private const val CONTENT_SCAN_DELAY_MS = 750L
        private const val MAX_NODES = 220
        private const val MAX_DEPTH = 18
        private const val MAX_CHILDREN_PER_NODE = 35
        private const val MAX_ITEMS = 60
        private const val MAX_TEXT_LENGTH = 1000
        private const val MIN_TEXT_LENGTH = 2
        private const val MIN_WIDTH_PX = 20
        private const val MIN_HEIGHT_PX = 10
    }
}
