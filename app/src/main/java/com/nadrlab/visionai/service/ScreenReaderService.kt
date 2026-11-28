package com.nadrlab.visionai.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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
 * قارئ شاشة محدود الحمل:
 * - يقرأ النصوص المتاحة عبر Accessibility فقط.
 * - يؤجل الفحص حتى تستقر أحداث الشاشة بدلاً من فحصها مع كل حدث.
 * - يضع حدوداً صارمة لعدد العناصر وعمق الشجرة.
 * - لا يلتقط الشاشة ولا يرسل النصوص إلى الإنترنت.
 */
class ScreenReaderService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatingButton: TextView? = null
    private var lastDetectedText: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanScheduled = false

    private val scanRunnable = Runnable {
        scanScheduled = false
        scanActiveWindow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingButton()
        scheduleScan(INITIAL_SCAN_DELAY_MS, reset = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                scheduleScan(WINDOW_CHANGE_DELAY_MS, reset = true)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                scheduleScan(SCROLL_DELAY_MS, reset = true)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // لا نعيد ضبط المؤقت مع كل حدث؛ حتى لا يتأخر الفحص إلى ما لا نهاية.
                scheduleScan(CONTENT_CHANGE_DELAY_MS, reset = false)
            }

            else -> Unit
        }
    }

    /**
     * جدولة الفحص على الخيط الرئيسي بعد هدوء أحداث الواجهة.
     * الفحص متزامن ومحدود، ولا توجد مهام متوازية تتراكم.
     */
    private fun scheduleScan(delayMs: Long, reset: Boolean) {
        if (reset) {
            mainHandler.removeCallbacks(scanRunnable)
            scanScheduled = false
        }
        if (scanScheduled) return

        scanScheduled = true
        mainHandler.postDelayed(scanRunnable, delayMs)
    }

    private fun scanActiveWindow() {
        if (floatingButton == null || floatingButton?.isAttachedToWindow != true) {
            floatingButton = null
            showFloatingButton()
        }

        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return

        lastDetectedText = collectVisibleText(root).take(MAX_CAPTURE_LENGTH)
    }

    /**
     * قراءة محدودة بطريقة BFS لتجنب التعمق الكبير في شجرة التطبيقات.
     * يتم تحرير كل AccessibilityNodeInfo بعد الانتهاء منه.
     */
    private fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val output = StringBuilder()
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        var visited = 0

        try {
            while (queue.isNotEmpty() &&
                visited < MAX_NODES &&
                output.length < MAX_CAPTURE_LENGTH
            ) {
                val (node, depth) = queue.removeFirst()
                visited++

                try {
                    if (!node.isVisibleToUser) continue

                    if (!node.isPassword) {
                        appendUnique(output, seen, node.text?.toString().orEmpty())
                        appendUnique(
                            output,
                            seen,
                            node.contentDescription?.toString().orEmpty()
                        )
                    }

                    if (depth >= MAX_DEPTH) continue

                    val childCount = minOf(node.childCount, MAX_CHILDREN_PER_NODE)
                    for (index in 0 until childCount) {
                        val child = try {
                            node.getChild(index)
                        } catch (_: Exception) {
                            null
                        }
                        if (child != null) {
                            queue.add(child to (depth + 1))
                        }
                    }
                } catch (_: Exception) {
                    // قد تتغير شجرة التطبيق أثناء القراءة؛ نتجاوز العنصر المتعذر.
                } finally {
                    try {
                        node.recycle()
                    } catch (_: Exception) {
                        // recycle غير مؤثر في إصدارات Android الحديثة.
                    }
                }
            }
        } finally {
            // تحرير أي عناصر بقيت في الطابور عند بلوغ الحد الأقصى.
            while (queue.isNotEmpty()) {
                try {
                    queue.removeFirst().first.recycle()
                } catch (_: Exception) {
                    // تجاهل أخطاء التحرير.
                }
            }
        }

        return output.toString().trim()
    }

    private fun appendUnique(
        output: StringBuilder,
        seen: MutableSet<String>,
        rawValue: String
    ) {
        val value = rawValue.trim().take(MAX_SINGLE_TEXT_LENGTH)
        if (value.isBlank() || !seen.add(value)) return

        val remaining = MAX_CAPTURE_LENGTH - output.length
        if (remaining <= 0) return

        if (output.isNotEmpty()) output.append('\n')
        output.append(value.take(remaining))
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
            dp(52),
            dp(52),
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
                        val maxY = (resources.displayMetrics.heightPixels - dp(60))
                            .coerceAtLeast(0)
                        params.y = (initialY + dy).coerceIn(0, maxY)
                        try {
                            wm.updateViewLayout(view, params)
                        } catch (_: Exception) {
                            // قد تُزال النافذة أثناء تغيير حالة الخدمة.
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

    override fun onInterrupt() {
        // لا نوقف الخدمة ولا نزيل الزر بسبب مقاطعة مؤقتة.
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(scanRunnable)
        scanScheduled = false

        floatingButton?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
                // قد تكون النافذة أزيلت مسبقاً.
            }
        }

        floatingButton = null
        windowManager = null
        lastDetectedText = ""
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val INITIAL_SCAN_DELAY_MS = 350L
        private const val WINDOW_CHANGE_DELAY_MS = 500L
        private const val SCROLL_DELAY_MS = 450L
        private const val CONTENT_CHANGE_DELAY_MS = 350L

        private const val MAX_NODES = 100
        private const val MAX_DEPTH = 10
        private const val MAX_CHILDREN_PER_NODE = 20
        private const val MAX_SINGLE_TEXT_LENGTH = 250
        private const val MAX_CAPTURE_LENGTH = 1200
    }
}
