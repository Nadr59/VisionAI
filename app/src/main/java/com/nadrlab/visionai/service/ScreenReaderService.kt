package com.nadrlab.visionai.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/**
 * المرحلة الأولى: يقرأ النصوص المتاحة في شجرة إمكانية الوصول ويعرض زرّاً عائماً.
 * لا يلتقط الشاشة ولا يرسل أي نص إلى الإنترنت في هذه المرحلة.
 */
class ScreenReaderService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatingButton: TextView? = null
    private var lastDetectedText: String = ""
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() == packageName) return
        val root = rootInActiveWindow ?: return
        val collected = StringBuilder()
        collectVisibleText(root, collected)
        lastDetectedText = collected.toString().trim().take(MAX_CAPTURE_LENGTH)
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, output: StringBuilder) {
        if (node == null || output.length >= MAX_CAPTURE_LENGTH) return
        val text = node.text?.toString()?.trim().orEmpty()
        val description = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) appendUnique(output, text)
        if (description.isNotEmpty()) appendUnique(output, description)
        for (index in 0 until node.childCount) {
            if (output.length >= MAX_CAPTURE_LENGTH) break
            collectVisibleText(node.getChild(index), output)
        }
    }

    private fun appendUnique(output: StringBuilder, value: String) {
        if (value.isBlank() || output.contains(value)) return
        if (output.isNotEmpty()) output.append('\n')
        output.append(value)
    }

    private fun showFloatingButton() {
        if (floatingButton != null) return
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
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) moved = true
                    if (moved) {
                        params.x = (initialX + dx).coerceAtLeast(0)
                        params.y = initialY + dy
                        windowManager?.updateViewLayout(view, params)
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
            val message = if (lastDetectedText.isBlank()) {
                "لم يُكتشف نص بعد. افتح تطبيقاً يحتوي على نصوص ثم اضغط مجدداً."
            } else {
                "تم رصد نصوص من الشاشة:\n" + lastDetectedText.take(350)
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

        try {
            windowManager?.addView(button, params)
            floatingButton = button
            layoutParams = params
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
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_CAPTURE_LENGTH = 4000
    }
}
