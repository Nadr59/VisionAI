package com.nadrlab.visionai.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * قارئ شاشة تجريبي محدود الحمل:
 * - يقرأ النصوص المتاحة في شجرة إمكانية الوصول فقط.
 * - لا يلتقط صورة الشاشة ولا يرسل أي بيانات إلى الإنترنت.
 * - يحدّ عدد العناصر ومعدل الفحص لتجنب تجميد واجهة الهاتف.
 */
class ScreenReaderService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatingButton: TextView? = null
    private var lastDetectedText: String = ""
    private var lastScanTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() == packageName) return

        // لا نفحص الشجرة مع كل حدث؛ بعض التطبيقات ترسل أحداثاً كثيرة جداً.
        val relevantEvent = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> true
            else -> false
        }
        if (!relevantEvent) return

        val now = SystemClock.uptimeMillis()
        if (now - lastScanTime < MIN_SCAN_INTERVAL_MS) return
        lastScanTime = now

        // إعادة إنشاء الزر إن فُقد لأي سبب بينما الخدمة ما زالت تعمل.
        if (floatingButton == null) showFloatingButton()

        try {
            val root = rootInActiveWindow ?: return
            lastDetectedText = collectVisibleText(root).take(MAX_CAPTURE_LENGTH)
        } catch (_: Exception) {
            // بعض التطبيقات تمنع الوصول إلى شجرة المحتوى أو تغيّرها أثناء القراءة.
        }
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val output = StringBuilder()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES && output.length < MAX_CAPTURE_LENGTH) {
            val (node, depth) = queue.removeFirst()
            visited++
            try {
                appendUnique(output, node.text?.toString().orEmpty())
                appendUnique(output, node.contentDescription?.toString().orEmpty())
                if (depth < MAX_DEPTH) {
                    val childCount = minOf(node.childCount, MAX_CHILDREN_PER_NODE)
                    for (index in 0 until childCount) {
                        val child = try { node.getChild(index) } catch (_: Exception) { null }
                        if (child != null) queue.add(child to (depth + 1))
                    }
                }
            } catch (_: Exception) {
                // تجاهل العنصر الذي تعذرت قراءته ومتابعة بقية العناصر.
            }
        }
        return output.toString().trim()
    }

    private fun appendUnique(output: StringBuilder, rawValue: String) {
        val value = rawValue.trim().take(MAX_SINGLE_TEXT_LENGTH)
        if (value.isBlank() || output.contains(value)) return
        if (output.isNotEmpty()) output.append('\n')
        val remaining = MAX_CAPTURE_LENGTH - output.length
        if (remaining > 0) output.append(value.take(remaining))
    }

    private fun showFloatingButton() {
        if (floatingButton != null) return
        val wm = windowManager ?: return

        val button = TextView(this).apply {
            text = "تر"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            contentDescription = "زر مترجم الشاشة"
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

        button.setOnClickListener {
            val text = lastDetectedText
            val message = if (text.isBlank()) {
                "لم يُكتشف نص متاح. انتقل إلى صفحة تحتوي على نص ثم اضغط مجدداً."
            } else {
                val lineCount = text.lines().count { it.isNotBlank() }
                "تم رصد $lineCount مقطعاً نصياً. ستُضاف الترجمة في المرحلة التالية."
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        try {
            wm.addView(button, params)
            floatingButton = button
        } catch (_: Exception) {
            floatingButton = null
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        floatingButton?.let { view ->
            try { windowManager?.removeView(view) } catch (_: Exception) { }
        }
        floatingButton = null
        windowManager = null
        lastDetectedText = ""
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MIN_SCAN_INTERVAL_MS = 700L
        private const val MAX_NODES = 140
        private const val MAX_DEPTH = 12
        private const val MAX_CHILDREN_PER_NODE = 30
        private const val MAX_SINGLE_TEXT_LENGTH = 300
        private const val MAX_CAPTURE_LENGTH = 1800
    }
}
