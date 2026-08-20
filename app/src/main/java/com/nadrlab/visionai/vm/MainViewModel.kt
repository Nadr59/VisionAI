package com.nadrlab.visionai.vm

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadrlab.visionai.ai.CloudVisionManager
import com.nadrlab.visionai.ai.ImageProcessor
import com.nadrlab.visionai.ai.LocalLlmManager
import com.nadrlab.visionai.ai.ModelDownloader
import com.nadrlab.visionai.ai.OcrEngine
import com.nadrlab.visionai.ai.WebSearchEngine
import com.nadrlab.visionai.data.AnalysisEntity
import com.nadrlab.visionai.data.AppDatabase
import com.nadrlab.visionai.data.AppSettings
import com.nadrlab.visionai.domain.AiMode
import com.nadrlab.visionai.domain.AnalysisResult
import com.nadrlab.visionai.domain.AnalysisState
import com.nadrlab.visionai.domain.AnalysisType
import com.nadrlab.visionai.domain.ConfidenceLevel
import com.nadrlab.visionai.domain.SearchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settings = AppSettings(app)
    val modelDownloader = ModelDownloader(app, settings)
    val localLlm = LocalLlmManager(app, settings)
    private val db = AppDatabase.get(app)

    // ═══ Image ═══
    private val _selectedImage = MutableStateFlow<Bitmap?>(null)
    val selectedImage: StateFlow<Bitmap?> = _selectedImage

    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri: StateFlow<Uri?> = _selectedUri

    // ═══ Analysis ═══
    private val _analysisType = MutableStateFlow(AnalysisType.GENERAL)
    val analysisType: StateFlow<AnalysisType> = _analysisType

    private val _aiMode = MutableStateFlow(AiMode.AUTO)
    val aiMode: StateFlow<AiMode> = _aiMode

    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state

    // ═══ Chat ═══
    private val _chatHistory = MutableStateFlow<List<String>>(emptyList())
    val chatHistory: StateFlow<List<String>> = _chatHistory

    private val _isChatLoading = MutableStateFlow(false)
    val isChatLoading: StateFlow<Boolean> = _isChatLoading

    private val _lastAnalysisText = MutableStateFlow("")
    val lastAnalysisText: StateFlow<String> = _lastAnalysisText

    // ═══ History ═══
    private val _history = MutableStateFlow<List<AnalysisEntity>>(emptyList())
    val history: StateFlow<List<AnalysisEntity>> = _history

        init {
        _aiMode.value = AiMode.valueOf(settings.aiMode)
        CloudVisionManager.init(settings)
        }

    // ═══════════════════════════════════════════
    // IMAGE
    // ═══════════════════════════════════════════

    fun selectImage(bitmap: Bitmap, uri: Uri) {
        _selectedImage.value?.recycle()
        _selectedImage.value = bitmap
        _selectedUri.value = uri
        _state.value = AnalysisState()
        _chatHistory.value = emptyList()
        _lastAnalysisText.value = ""
    }

    fun clearImage() {
        _selectedImage.value?.recycle()
        _selectedImage.value = null
        _selectedUri.value = null
        _state.value = AnalysisState()
        _chatHistory.value = emptyList()
        _lastAnalysisText.value = ""
    }

    fun setAnalysisType(type: AnalysisType) {
        _analysisType.value = type
    }

    fun setAiMode(mode: AiMode) {
        _aiMode.value = mode
        settings.aiMode = mode.name
    }

    // ═══════════════════════════════════════════
    // ANALYSIS
    // ═══════════════════════════════════════════

    fun analyze() {
        val bitmap = _selectedImage.value ?: return
        val type = _analysisType.value
        val mode = _aiMode.value

        viewModelScope.launch {
            _state.value = AnalysisState(isLoading = true, progress = "جاري التحليل...")
            _chatHistory.value = emptyList()
            _lastAnalysisText.value = ""

            try {
                // OCR
                var ocrText = ""
                if (settings.ocrEnabled) {
                    _state.value = _state.value.copy(progress = "استخراج النصوص...")
                    ocrText = OcrEngine.recognize(bitmap)
                }

                // Effective mode
                val effectiveMode = when (mode) {
                    AiMode.AUTO -> AiMode.CLOUD
                    AiMode.LOCAL -> {
                        if (!settings.modelDownloaded) {
                            _state.value = AnalysisState(
                                isLoading = false,
                                error = "النموذج المحلي غير مثبت. اذهب لصفحة 'النموذج' أو استخدم الوضع السحابي."
                            )
                            return@launch
                        }
                        AiMode.LOCAL
                    }
                    AiMode.CLOUD -> AiMode.CLOUD
                }

                // Analyze
                val resultText: String
                if (effectiveMode == AiMode.LOCAL) {
                    _state.value = _state.value.copy(progress = "التحليل المحلي...")
                    val localPrompt = buildLocalPrompt(type, ocrText)
                    resultText = localLlm.generate(localPrompt)
                } else {
                    _state.value = _state.value.copy(progress = "التحليل السحابي...")
                    val cloudPrompt = buildCloudPrompt(type, ocrText)
                    resultText = CloudVisionManager.analyze(bitmap, cloudPrompt).getOrElse {
                        "فشل التحليل السحابي: ${it.message}"
                    }
                }

                // Parse
                val parsed = parseResult(resultText)
                _lastAnalysisText.value = buildContextForChat(parsed, ocrText)

                // Search
                var searchResults = emptyList<SearchResult>()
                if (settings.searchEnabled && parsed.keywords.isNotEmpty()) {
                    _state.value = _state.value.copy(progress = "البحث...")
                    try {
                        val queries = WebSearchEngine.generateSearchQueries(parsed.keywords, parsed.contentType)
                        val allResults = mutableListOf<SearchResult>()
                        for (q in queries.take(3)) {
                            allResults.addAll(WebSearchEngine.search(q))
                        }
                        searchResults = allResults.distinctBy { it.url }.take(10)
                    } catch (_: Exception) {}
                }

                // Update
                _state.value = AnalysisState(
                    isLoading = false,
                    result = parsed,
                    searchResults = searchResults,
                    usedMode = effectiveMode
                )

                // Save
                if (settings.saveHistory) {
                    saveToHistory(type, effectiveMode, parsed, searchResults, _selectedUri.value?.toString() ?: "")
                }

            } catch (e: Exception) {
                _state.value = AnalysisState(isLoading = false, error = "خطأ: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════
    // CHAT WITH LOCAL MODEL
    // ═══════════════════════════════════════════

    fun askLocalModel(question: String) {
        viewModelScope.launch {
            _isChatLoading.value = true

            val currentHistory = _chatHistory.value.toMutableList()
            currentHistory.add("USER: $question")
            _chatHistory.value = currentHistory

            try {
                // Refresh state
                localLlm.refreshState()

                // Check file
                val path = settings.modelPath
                val file = File(path)
                val fileSizeMb = if (file.exists()) file.length() / (1024 * 1024) else 0

                // Get RAM info
                val am = getApplication<Application>().getSystemService(
                    android.content.Context.ACTIVITY_SERVICE
                ) as android.app.ActivityManager
                val memInfo = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                val ramAvail = memInfo.availMem / (1024 * 1024)
                val ramTotal = memInfo.totalMem / (1024 * 1024)

                // If model not loaded, try loading
                if (!localLlm.isLoaded()) {
                    // Pre-checks
                    if (!file.exists() || fileSizeMb < 100) {
                        currentHistory.add(
                            "AI: ⚠️ ملف النموذج غير موجود أو ناقص.\n" +
                            "المسار: $path\n" +
                            "الحجم: ${fileSizeMb} MB\n\n" +
                            "اذهب لصفحة 'النموذج' وأعد الاستيراد."
                        )
                        _chatHistory.value = currentHistory
                        _isChatLoading.value = false
                        return@launch
                    }

                    // Show loading message
                    currentHistory.add(
                        "AI: ⏳ جاري تحميل النموذج (${fileSizeMb}MB)...\n" +
                        "ذاكرة متاحة: ${ramAvail}MB\n" +
                        "قد يستغرق 1-2 دقيقة. لا تغلق التطبيق."
                    )
                    _chatHistory.value = currentHistory

                    // Load
                    val loaded = localLlm.loadModel()

                    if (!loaded) {
                        val logContent = localLlm.readLog()
                        val updated = _chatHistory.value.toMutableList()
                        updated.removeLast()
                        updated.add(
                            "AI: ❌ فشل تحميل النموذج.\n\n" +
                            "📋 معلومات التشخيص:\n" +
                            "المسار: $path\n" +
                            "حجم الملف: ${fileSizeMb} MB\n" +
                            "ذاكرة متاحة: ${ramAvail}MB / ${ramTotal}MB\n" +
                            "حالة النموذج: ${localLlm.state.value}\n\n" +
                            "📋 سجل مفصل:\n$logContent\n\n" +
                            "💡 نصائح:\n" +
                            "• جرّب نموذج أصغر (Qwen3-0.6B)\n" +
                            "• أغلق التطبيقات الأخرى\n" +
                            "• أعد تشغيل الجهاز"
                        )
                        _chatHistory.value = updated
                        _isChatLoading.value = false
                        return@launch
                    }

                    // Remove loading message
                    val updated = _chatHistory.value.toMutableList()
                    updated.removeLast()
                    _chatHistory.value = updated
                }

                // Generate
                currentHistory.add("AI: 🤔 جاري التفكير...")
                _chatHistory.value = currentHistory

                val prompt = buildChatPrompt(question, _lastAnalysisText.value)

                val response = withTimeoutOrNull(180_000L) {
                    localLlm.generate(prompt)
                } ?: "⏰ انتهت المهلة (3 دقائق)"

                val finalHistory = _chatHistory.value.toMutableList()
                finalHistory.removeLast()
                finalHistory.add("AI: $response")
                _chatHistory.value = finalHistory

            } catch (e: Throwable) {
                val logContent = localLlm.readLog()
                val errorHistory = _chatHistory.value.toMutableList()
                errorHistory.removeAll { it.startsWith("AI:") && it.contains("جاري") }
                errorHistory.add(
                    "AI: ❌ خطأ: ${e.message}\n\n📋 سجل:\n$logContent"
                )
                _chatHistory.value = errorHistory
            }

            _isChatLoading.value = false
        }
    }

    // ═══════════════════════════════════════════
    // PROCESS RESULTS (Quick Actions)
    // ═══════════════════════════════════════════

    fun processResults(fullPrompt: String, shortLabel: String) {
        viewModelScope.launch {
            _isChatLoading.value = true

            val currentHistory = _chatHistory.value.toMutableList()
            currentHistory.add("USER: $shortLabel")
            _chatHistory.value = currentHistory

            try {
                localLlm.refreshState()

                if (!localLlm.isLoaded()) {
                    val path = settings.modelPath
                    val file = File(path)
                    val fileSizeMb = if (file.exists()) file.length() / (1024 * 1024) else 0

                    if (!file.exists() || fileSizeMb < 100) {
                        currentHistory.add("AI: ⚠️ النموذج غير مثبت")
                        _chatHistory.value = currentHistory
                        _isChatLoading.value = false
                        return@launch
                    }

                    currentHistory.add("AI: ⏳ جاري تحميل النموذج...")
                    _chatHistory.value = currentHistory

                    val loaded = localLlm.loadModel()
                    if (!loaded) {
                        val logContent = localLlm.readLog()
                        val updated = _chatHistory.value.toMutableList()
                        updated.removeLast()
                        updated.add("AI: ❌ فشل التحميل.\n📋 سجل:\n$logContent")
                        _chatHistory.value = updated
                        _isChatLoading.value = false
                        return@launch
                    }

                    val updated = _chatHistory.value.toMutableList()
                    updated.removeLast()
                    _chatHistory.value = updated
                }

                currentHistory.add("AI: 🤔 جاري المعالجة...")
                _chatHistory.value = currentHistory

                val response = withTimeoutOrNull(180_000L) {
                    localLlm.generate(fullPrompt)
                } ?: "⏰ انتهت المهلة"

                val finalHistory = _chatHistory.value.toMutableList()
                finalHistory.removeLast()
                finalHistory.add("AI: $response")
                _chatHistory.value = finalHistory

            } catch (e: Throwable) {
                val logContent = localLlm.readLog()
                val errorHistory = _chatHistory.value.toMutableList()
                errorHistory.removeAll { it.startsWith("AI:") && it.contains("جاري") }
                errorHistory.add("AI: ❌ خطأ: ${e.message}\n📋 سجل:\n$logContent")
                _chatHistory.value = errorHistory
            }

            _isChatLoading.value = false
        }
    }

    // ═══════════════════════════════════════════
    // PROMPTS
    // ═══════════════════════════════════════════

    private fun buildChatPrompt(question: String, context: String): String {
        return "أنت مساعد ذكي يجيب بالعربية.\n\n" +
            "معلومات من تحليل صورة:\n$context\n\n" +
            "سؤال المستخدم: $question\n\n" +
            "أجب بإيجاز وبوضوح:"
    }

    private fun buildContextForChat(result: AnalysisResult, ocr: String): String {
        val sb = StringBuilder()
        sb.append("نوع المحتوى: ${result.contentType}\n")
        sb.append("الوصف: ${result.description}\n")
        sb.append("العناصر: ${result.elements.joinToString("،")}\n")
        sb.append("النص المستخرج: ${result.extractedText.ifBlank { ocr.ifBlank { "لا يوجد" } }}\n")
        sb.append("الكلمات المفتاحية: ${result.keywords.joinToString("،")}\n")
        sb.append("الثقة: ${result.confidence.label}\n")
        sb.append("معلومات إضافية: ${result.additionalInfo}")
        return sb.toString()
    }

    private fun buildCloudPrompt(type: AnalysisType, ocrText: String): String {
        val base = """${type.prompt}

Respond in ARABIC. Format your response EXACTLY as follows:

[المحتوى]: (نوع المحتوى بكلمة واحدة: منتج/مكان/شخص/مستند/نص/فني/تقني/طبيعي/أخرى)
[الوصف]: (وصف تفصيلي 2-3 جمل)
[العناصر]: (قائمة العناصر المكتشفة، سطر لكل عنصر)
[النص]: (النص المستخرج من الصورة إن وجد)
[الكلمات]: (كلمات مفتاحية مفصولة بفاصلة)
[الثقة]: (عالي/متوسط/منخفض/غير مؤكد)
[معلومات]: (معلومات إضافية مهمة)
[نهاية]"""

        return if (ocrText.isNotBlank()) {
            "$base\n\nالنص المستخرج بالـ OCR:\n$ocrText"
        } else {
            base
        }
    }

    private fun buildLocalPrompt(type: AnalysisType, ocrText: String): String {
        return """أنت مساعد تحليل. النص المستخرج من صورة بواسطة OCR:

${ocrText.ifBlank { "لا يوجد نص في الصورة" }}

المطلوب: ${type.prompt}

أجب بالعربية مع:
[المحتوى]: نوع المحتوى
[الوصف]: وصف مختصر
[العناصر]: العناصر المكتشفة
[الكلمات]: كلمات مفتاحية
[الثقة]: مستوى الثقة
[معلومات]: معلومات إضافية
[نهاية]"""
    }

    // ═══════════════════════════════════════════
    // PARSE RESULT
    // ═══════════════════════════════════════════

    private fun parseResult(text: String): AnalysisResult {
        fun extract(tag: String): String {
            val regex = Regex("\\[$tag]:\\s*(.+?)(?=\\[|$)", RegexOption.DOT_MATCHES_ALL)
            return regex.find(text)?.groupValues?.get(1)?.trim() ?: ""
        }

        val confStr = extract("الثقة")
        val confidence = when {
            confStr.contains("عالي") -> ConfidenceLevel.HIGH
            confStr.contains("متوسط") -> ConfidenceLevel.MEDIUM
            confStr.contains("منخفض") -> ConfidenceLevel.LOW
            else -> ConfidenceLevel.UNCERTAIN
        }

        val elementsText = extract("العناصر")
        val elements = elementsText.split("\n", "•", "-", "،")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 2 }

        val keywordsText = extract("الكلمات")
        val keywords = keywordsText.split(",", "،", "\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return AnalysisResult(
            contentType = extract("المحتوى"),
            description = extract("الوصف"),
            elements = elements,
            extractedText = extract("النص"),
            keywords = keywords,
            confidence = confidence,
            additionalInfo = extract("معلومات"),
            fullText = text
        )
    }

    // ═══════════════════════════════════════════
    // HISTORY
    // ═══════════════════════════════════════════

    private suspend fun saveToHistory(
        type: AnalysisType,
        mode: AiMode,
        result: AnalysisResult,
        search: List<SearchResult>,
        uri: String
    ) {
        try {
            val entity = AnalysisEntity(
                analysisType = type.name,
                aiMode = mode.name,
                contentType = result.contentType,
                description = result.description,
                elements = result.elements.joinToString("،"),
                extractedText = result.extractedText,
                keywords = result.keywords.joinToString("،"),
                confidence = result.confidence.name,
                fullResult = result.fullText,
                searchResults = search.joinToString("\n") { "${it.title}|${it.url}" },
                imageUri = uri
            )
            db.analysisDao().insert(entity)
        } catch (_: Exception) {}
    }

    fun loadHistory() {
        viewModelScope.launch {
            try {
                _history.value = db.analysisDao().getAll()
            } catch (_: Exception) {}
        }
    }

    fun deleteHistoryItem(entity: AnalysisEntity) {
        viewModelScope.launch {
            try {
                db.analysisDao().delete(entity)
                loadHistory()
            } catch (_: Exception) {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        localLlm.unloadModel()
        _selectedImage.value?.recycle()
    }
}
