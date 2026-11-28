package com.nadrlab.visionai.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.nadrlab.visionai.ai.OcrEngine
import com.nadrlab.visionai.ai.ScreenTranslationEngine
import com.nadrlab.visionai.data.AppSettings
import kotlinx.coroutines.runBlocking
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/** Reads accessible screen text on demand, translates it and draws translations in an overlay. */
class ScreenReaderService : AccessibilityService() {

    private data class ScreenItem(val text: String, val bounds: Rect)

    private var windowManager: WindowManager? = null
    private var floatingButton: TextView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var translationOverlay: FrameLayout? = null
    private lateinit var appSettings: AppSettings

    @Volatile
    private var destroyed = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var translationExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val positionPrefs by lazy { getSharedPreferences(POSITION_PREFS, MODE_PRIVATE) }

    // The fields below are only touched on the main thread.
    private var requestId = 0
    private var busy = false
    private var cancelled = false
    private var buttonRetryPending = false
    private var lastValidation = 0L

    private val buttonRetryRunnable = Runnable {
        buttonRetryPending = false
        if (!destroyed && floatingButton == null) showFloatingButton()
    }

    // ───────────────────────── lifecycle ─────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "onServiceConnected")
        destroyed = false
        isConnected = true
        instance = this
        if (scanExecutor.isShutdown) scanExecutor = Executors.newSingleThreadExecutor()
        if (translationExecutor.isShutdown) translationExecutor = Executors.newSingleThreadExecutor()
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            appSettings = AppSettings(applicationContext)
            // Always start from a clean state: a leftover view that still reports "attached" would make
            // showFloatingButton() return early and the bubble would never come back.
            rebuildWindows()
            toast("مترجم الشاشة يعمل. اضغط الفقاعة لترجمة الشاشة.")
        } catch (error: Exception) {
            Log.e(TAG, "onServiceConnected failed", error)
            toast("تعذر تشغيل الفقاعة: ${error.javaClass.simpleName}: ${error.message}", long = true)
            scheduleButtonRetry()
        }
    }

    /** Removes both overlay windows and creates them again from scratch. */
    private fun rebuildWindows() {
        floatingButton?.let { removeViewQuietly(it) }
        translationOverlay?.let { removeViewQuietly(it) }
        floatingButton = null
        buttonParams = null
        translationOverlay = null
        busy = false
        addOverlay()
        showFloatingButton()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        isConnected = false
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        clearOverlay(cancelPending = true)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        isConnected = false
        cleanup()
        windowManager = null
        super.onDestroy()
    }

    private fun cleanup() {
        destroyed = true
        if (instance === this) instance = null
        mainHandler.removeCallbacksAndMessages(null)
        floatingButton?.let { removeViewQuietly(it) }
        translationOverlay?.let { removeViewQuietly(it) }
        floatingButton = null
        buttonParams = null
        translationOverlay = null
        busy = false
        cancelled = false
        buttonRetryPending = false
        scanExecutor.shutdownNow()
        translationExecutor.shutdownNow()
        ScreenTranslationEngine.close()
    }

    // ───────────────────────── events ─────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || destroyed) return
        val eventPackage = event.packageName?.toString() ?: return

        if (eventPackage == packageName) {
            // Events from our own bubble/overlay must NEVER clear the translation: drawing the overlay
            // itself raises a content-changed event, which used to erase the result immediately.
            ensureFloatingButton()
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.className?.toString() == MAIN_ACTIVITY_CLASS
            ) {
                clearOverlay(cancelPending = true)
            }
            return
        }
        if (eventPackage == SYSTEM_UI_PACKAGE) return

        // Recover the button if the system dropped its window while the service stayed enabled.
        ensureFloatingButton()

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> clearOverlay(cancelPending = true)
        }
    }

    // ───────────────────────── floating button ─────────────────────────

    private fun ensureFloatingButton() {
        if (destroyed) return
        val button = floatingButton
        val params = buttonParams
        if (button == null || params == null || !button.isAttachedToWindow) {
            floatingButton = null
            showFloatingButton()
            return
        }
        // isAttachedToWindow can stay true after the system killed the overlay window,
        // so probe it from time to time and rebuild the button if the window is dead.
        val now = SystemClock.uptimeMillis()
        if (now - lastValidation < VALIDATE_INTERVAL_MS) return
        lastValidation = now
        try {
            windowManager?.updateViewLayout(button, params)
        } catch (error: Exception) {
            Log.w(TAG, "Floating button window is dead, recreating it", error)
            removeViewQuietly(button)
            floatingButton = null
            showFloatingButton()
        }
    }

    private fun showFloatingButton() {
        if (destroyed) return
        val current = floatingButton
        if (current != null && current.isAttachedToWindow) return
        floatingButton = null

        val wm = windowManager ?: return
        val metrics = resources.displayMetrics
        val size = dp(52)
        val maxX = (metrics.widthPixels - size).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - size).coerceAtLeast(0)

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
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // TOP|START so that x/y are plain screen coordinates and dragging can never push
            // the button outside the screen (the old CENTER_VERTICAL gravity could).
            gravity = Gravity.TOP or Gravity.START
            x = positionPrefs.getInt(KEY_X, metrics.widthPixels - size - dp(12)).coerceIn(0, maxX)
            y = positionPrefs.getInt(KEY_Y, metrics.heightPixels / 2).coerceIn(0, maxY)
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
                    val dx = (event.rawX - startX).toInt()
                    val dy = (event.rawY - startY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        val m = resources.displayMetrics
                        params.x = (initialX + dx).coerceIn(0, (m.widthPixels - size).coerceAtLeast(0))
                        params.y = (initialY + dy).coerceIn(0, (m.heightPixels - size).coerceAtLeast(0))
                        try {
                            wm.updateViewLayout(view, params)
                        } catch (error: Exception) {
                            Log.w(TAG, "Unable to move floating button", error)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        positionPrefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else {
                        translateCurrentScreen()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        // Used by TalkBack / accessibility "click" actions; touch taps are handled above.
        button.setOnClickListener { translateCurrentScreen() }

        try {
            wm.addView(button, params)
            floatingButton = button
            buttonParams = params
            buttonRetryPending = false
            mainHandler.removeCallbacks(buttonRetryRunnable)
            lastValidation = SystemClock.uptimeMillis()
            Log.i(TAG, "Floating button added")
        } catch (error: Exception) {
            floatingButton = null
            buttonParams = null
            Log.e(TAG, "Unable to create accessibility floating button", error)
            toast("تعذر إنشاء الفقاعة: ${error.javaClass.simpleName}: ${error.message}", long = true)
            scheduleButtonRetry()
        }
    }

    private fun scheduleButtonRetry() {
        if (destroyed || buttonRetryPending) return
        buttonRetryPending = true
        mainHandler.postDelayed(buttonRetryRunnable, BUTTON_RETRY_DELAY_MS)
    }

    private fun setBusyLook(isBusy: Boolean) {
        floatingButton?.alpha = if (isBusy) 0.55f else 1f
    }

    // ───────────────────────── translate on tap ─────────────────────────

    private fun translateCurrentScreen() {
        if (destroyed) return
        if (busy && !cancelled) {
            toast("الترجمة جارية، انتظر قليلاً...")
            return
        }

        val id = ++requestId
        busy = true
        cancelled = false
        setBusyLook(true)
        translationOverlay?.removeAllViews()
        toast("جاري قراءة نصوص الشاشة...")

        scanScreen { items ->
            if (id != requestId) return@scanScreen
            if (cancelled) {
                finishRequest()
                toast(SCREEN_CHANGED_MESSAGE, long = true)
                return@scanScreen
            }
            when {
                // The app exposes enough text through accessibility.
                items.size >= MIN_ITEMS_WITHOUT_OCR -> startTranslation(id, items)
                // Little or no text: read the screen pixels with OCR if screen capture is allowed.
                ScreenCaptureService.isReady -> captureAndOcr(id, items)
                items.isEmpty() -> {
                    finishRequest()
                    askForCapturePermission()
                }
                else -> startTranslation(id, items)
            }
        }
    }

    private fun startTranslation(id: Int, items: List<ScreenItem>) {
        if (items.isEmpty()) {
            finishRequest()
            toast("لم يتمكن التطبيق من قراءة نصوص هذه الشاشة.", long = true)
            return
        }

        toast("جاري ترجمة ${items.size} مقطعاً... (أول مرة قد يُنزَّل نموذج اللغة)")
        val texts = items.map { it.text }
        try {
            translationExecutor.execute {
                try {
                    val translations = runBlocking {
                        ScreenTranslationEngine.translateBatch(texts, appSettings)
                    }
                    val translatedItems = items.mapIndexedNotNull { index, item ->
                        val translated = translations.getOrNull(index)?.trim().orEmpty()
                        if (translated.isBlank() || translated == item.text) null
                        else item to translated
                    }
                    mainHandler.post { onTranslationFinished(id, translatedItems, null) }
                } catch (error: Exception) {
                    Log.e(TAG, "Translation failed", error)
                    mainHandler.post { onTranslationFinished(id, emptyList(), error) }
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to schedule translation", error)
            finishRequest()
            toast("تعذر بدء الترجمة.", long = true)
        }
    }

    // ───────────────────────── OCR fallback (screenshot via MediaProjection) ─────────────────────────

    private fun askForCapturePermission() {
        toast(
            "لا توجد نصوص قابلة للقراءة هنا. اسمح بالتقاط الشاشة لتفعيل OCR ثم اضغط الفقاعة مرة أخرى " +
                "(أو افتح إعدادات VisionAI واضغط «تشغيل OCR»).",
            long = true
        )
        try {
            startActivity(
                Intent(this, ScreenCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (error: Exception) {
            Log.w(TAG, "Unable to open capture permission screen", error)
        }
    }

    private fun captureAndOcr(id: Int, accessibilityItems: List<ScreenItem>) {
        // Our own bubble, toast and previous translations must not end up in the screenshot.
        currentToast?.cancel()
        floatingButton?.visibility = View.INVISIBLE
        translationOverlay?.removeAllViews()

        mainHandler.postDelayed({
            if (destroyed) return@postDelayed
            try {
                scanExecutor.execute {
                    var ocrItems = emptyList<ScreenItem>()
                    var failed = false
                    try {
                        val bitmap = ScreenCaptureService.captureBlocking()
                        mainHandler.post { floatingButton?.visibility = View.VISIBLE }
                        if (bitmap == null) {
                            failed = true
                        } else {
                            try {
                                ocrItems = OcrEngine.recognizeBlocks(bitmap).mapNotNull { block ->
                                    val text = block.text.replace('\n', ' ').trim()
                                    if (text.length < MIN_OCR_TEXT_LENGTH || text.count { it.isLetter() } < 2) null
                                    else ScreenItem(text.take(MAX_TEXT_LENGTH), block.bounds)
                                }.take(MAX_ITEMS)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    } catch (error: Exception) {
                        Log.w(TAG, "OCR failed", error)
                        mainHandler.post { floatingButton?.visibility = View.VISIBLE }
                        failed = true
                    }
                    val didFail = failed
                    mainHandler.post { onOcrFinished(id, accessibilityItems, ocrItems, didFail) }
                }
            } catch (error: Exception) {
                Log.e(TAG, "Unable to schedule OCR", error)
                floatingButton?.visibility = View.VISIBLE
                finishRequest()
                toast("تعذر بدء OCR.", long = true)
            }
        }, CAPTURE_SETTLE_DELAY_MS)
    }

    private fun onOcrFinished(
        id: Int,
        accessibilityItems: List<ScreenItem>,
        ocrItems: List<ScreenItem>,
        failed: Boolean
    ) {
        if (destroyed || id != requestId) return
        if (cancelled) {
            finishRequest()
            toast(SCREEN_CHANGED_MESSAGE, long = true)
            return
        }
        // OCR boxes that fall inside a node accessibility already gave us would be translated twice.
        val extra = ocrItems.filter { ocr ->
            accessibilityItems.none { it.bounds.contains(ocr.bounds.centerX(), ocr.bounds.centerY()) }
        }
        val merged = (accessibilityItems + extra)
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .take(MAX_ITEMS)

        when {
            merged.isNotEmpty() -> startTranslation(id, merged)
            failed -> {
                finishRequest()
                toast("تعذر التقاط الشاشة. أعد تشغيل OCR من إعدادات التطبيق.", long = true)
            }
            else -> {
                finishRequest()
                toast("لم أعثر على نص إنجليزي في الشاشة.", long = true)
            }
        }
    }

    private fun finishRequest() {
        busy = false
        setBusyLook(false)
    }

    private fun onTranslationFinished(
        id: Int,
        translatedItems: List<Pair<ScreenItem, String>>,
        error: Exception?
    ) {
        if (destroyed || id != requestId) return
        finishRequest()
        when {
            cancelled -> toast(SCREEN_CHANGED_MESSAGE, long = true)
            error != null -> toast("تعذرت الترجمة: ${error.message ?: "خطأ غير معروف"}", long = true)
            translatedItems.isEmpty() ->
                toast("لم يعثر محرك الترجمة على نص يحتاج إلى ترجمة.", long = true)
            else -> {
                renderTranslations(translatedItems)
                toast("تمت ترجمة ${translatedItems.size} مقطعاً.")
            }
        }
    }

    // ───────────────────────── screen reading ─────────────────────────

    /** Accessibility tree traversal runs on a worker so it cannot freeze the main thread. */
    private fun scanScreen(onComplete: (List<ScreenItem>) -> Unit) {
        if (destroyed) return
        try {
            scanExecutor.execute {
                val result = try {
                    readScreen()
                } catch (error: Exception) {
                    Log.w(TAG, "Screen scan failed", error)
                    emptyList()
                }
                if (!destroyed) {
                    mainHandler.post { if (!destroyed) onComplete(result) }
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to schedule screen scan", error)
            onComplete(emptyList())
        }
    }

    private fun readScreen(): List<ScreenItem> {
        val roots = mutableListOf<AccessibilityNodeInfo>()

        val active = rootInActiveWindow
        if (active != null) {
            if (active.packageName?.toString() == packageName) active.recycle() else roots.add(active)
        }

        // rootInActiveWindow is sometimes null (split screen, right after a tap...): use the app windows.
        if (roots.isEmpty()) {
            val appWindows = try { windows } catch (_: Exception) { emptyList<AccessibilityWindowInfo>() }
            appWindows
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { it.layer }
                .forEach { window ->
                    val root = try { window.root } catch (_: Exception) { null }
                    if (root != null) {
                        if (root.packageName?.toString() == packageName) root.recycle() else roots.add(root)
                    }
                }
        }

        val result = mutableListOf<ScreenItem>()
        val seen = HashSet<String>()
        roots.forEach { collectVisibleText(it, seen, result) }
        Log.i(TAG, "Screen scan: ${roots.size} root(s), ${result.size} text item(s)")
        return result.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun collectVisibleText(
        root: AccessibilityNodeInfo,
        seen: HashSet<String>,
        result: MutableList<ScreenItem>
    ) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            visited++
            try {
                val limitReached = visited > MAX_NODES || result.size >= MAX_ITEMS
                if (!limitReached) {
                    val visible = node.isVisibleToUser
                    if (visible && !node.isEditable && !node.isPassword) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        if (bounds.width() >= MIN_WIDTH_PX && bounds.height() >= MIN_HEIGHT_PX) {
                            val text = node.text?.toString()?.trim().orEmpty()
                            addItem(text, bounds, seen, result)
                            val description = node.contentDescription?.toString()?.trim().orEmpty()
                            if (description != text) addItem(description, bounds, seen, result)
                        }
                    }

                    // Off-screen subtrees (e.g. recycled list rows) are skipped, which is what keeps
                    // the traversal inside the node budget on big screens.
                    if (depth < MAX_DEPTH && (visible || depth < 2)) {
                        val count = minOf(node.childCount, MAX_CHILDREN_PER_NODE)
                        for (index in 0 until count) {
                            val child = try { node.getChild(index) } catch (_: Exception) { null }
                            if (child != null) queue.add(child to (depth + 1))
                        }
                    }
                }
            } catch (error: Exception) {
                Log.v(TAG, "Skipping inaccessible accessibility node", error)
            } finally {
                try { node.recycle() } catch (_: Exception) { }
            }
        }
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

    // ───────────────────────── translation overlay ─────────────────────────

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

        // Node bounds are in screen coordinates; the overlay window may start below a cutout/status bar.
        val location = IntArray(2)
        overlay.getLocationOnScreen(location)
        val metrics = resources.displayMetrics
        val screenWidth = if (overlay.width > 0) overlay.width else metrics.widthPixels
        val screenHeight = if (overlay.height > 0) overlay.height else metrics.heightPixels
        val rtl = appSettings.screenTranslationTarget in setOf("ar", "fa", "ur", "he")

        items.forEach { (item, translation) ->
            val bounds = Rect(item.bounds)
            bounds.offset(-location[0], -location[1])
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

    private fun clearOverlay(cancelPending: Boolean = false) {
        if (cancelPending && busy) cancelled = true
        translationOverlay?.removeAllViews()
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun removeViewQuietly(view: View) {
        try { windowManager?.removeView(view) } catch (_: Exception) { }
    }

    private var currentToast: Toast? = null

    private fun toast(message: String, long: Boolean = false) {
        try {
            currentToast?.cancel()
            val toast = Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT)
            currentToast = toast
            toast.show()
        } catch (_: Exception) { }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "ScreenReaderService"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val MAIN_ACTIVITY_CLASS = "com.nadrlab.visionai.MainActivity"
        private const val POSITION_PREFS = "screen_button"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val SCREEN_CHANGED_MESSAGE = "تغيّرت الشاشة أثناء الترجمة. اضغط الفقاعة مرة أخرى."
        private const val BUTTON_RETRY_DELAY_MS = 1500L
        private const val VALIDATE_INTERVAL_MS = 2000L
        private const val MAX_NODES = 1500
        private const val MAX_DEPTH = 40
        private const val MAX_CHILDREN_PER_NODE = 200
        private const val MAX_ITEMS = 60
        private const val MAX_TEXT_LENGTH = 700
        private const val MIN_TEXT_LENGTH = 2
        private const val MIN_OCR_TEXT_LENGTH = 3
        private const val MIN_ITEMS_WITHOUT_OCR = 3
        private const val CAPTURE_SETTLE_DELAY_MS = 300L
        private const val MIN_WIDTH_PX = 20
        private const val MIN_HEIGHT_PX = 10

        @Volatile
        private var instance: ScreenReaderService? = null

        /**
         * Called from the app UI: rebuilds the bubble and resets its saved position.
         * Returns a message that tells the user what actually happened.
         */
        fun recreateButtonFromApp(): String {
            val service = instance
            if (service == null || !isConnected) {
                return "الخدمة غير متصلة فعلياً. افتح إعدادات إمكانية الوصول، أوقف VisionAI ثم فعّلها."
            }
            service.mainHandler.post {
                if (service.destroyed) return@post
                service.positionPrefs.edit().clear().apply()
                service.rebuildWindows()
            }
            return "تمت إعادة إنشاء الفقاعة. إن لم تظهر ستظهر رسالة بسبب الخطأ."
        }

        /** Called by ScreenCaptureService once the screen capture session is running. */
        fun onCaptureReady() {
            val service = instance ?: return
            service.mainHandler.post {
                if (!service.destroyed) {
                    service.toast("OCR جاهز. اضغط الفقاعة مرة أخرى لترجمة الشاشة.", long = true)
                }
            }
        }

        /** Called when the user refused or capture could not start. [message] may be null (plain refusal). */
        fun onCaptureUnavailable(message: String?) {
            val service = instance ?: return
            service.mainHandler.post {
                if (!service.destroyed) {
                    service.toast(message ?: "لم يتم السماح بالتقاط الشاشة، لذلك OCR غير مفعّل.", long = true)
                }
            }
        }

        /** True only while the system really has this service bound and running. */
        @Volatile
        var isConnected = false
            private set

        /** True when the user enabled the service in Accessibility settings (it may still be dead). */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = ComponentName(context, ScreenReaderService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
