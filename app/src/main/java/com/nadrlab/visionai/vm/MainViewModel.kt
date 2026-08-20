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

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settings = AppSettings(app)
    val modelDownloader = ModelDownloader(app, settings)
    val localLlm = LocalLlmManager(app, settings)
    private val db = AppDatabase.get(app)

    private val _selectedImage = MutableStateFlow<Bitmap?>(null)
    val selectedImage: StateFlow<Bitmap?> = _selectedImage

    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri: StateFlow<Uri?> = _selectedUri

    private val _analysisType = MutableStateFlow(AnalysisType.GENERAL)
    val analysisType: StateFlow<AnalysisType> = _analysisType

    private val _aiMode = MutableStateFlow(AiMode.AUTO)
    val aiMode: StateFlow<AiMode> = _aiMode

    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state

    private val _chatHistory = MutableStateFlow<List<String>>(emptyList())
    val chatHistory: StateFlow<List<String>> = _chatHistory

    private val _isChatLoading = MutableStateFlow(false)
    val isChatLoading: StateFlow<Boolean> = _isChatLoading

    private val _lastAnalysisText = MutableStateFlow("")
    val lastAnalysisText: StateFlow<String> = _lastAnalysisText

    private val _history = MutableStateFlow<List<AnalysisEntity>>(emptyList())
    val history: StateFlow<List<AnalysisEntity>> = _history

    init {
        _aiMode.value = AiMode.valueOf(settings.aiMode)
    }

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

    // ═══ التحليل الرئيسي ═══
    fun analyze() {
        val bitmap = _selectedImage.value ?: return
        val type = _analysisType.value
        val mode = _aiMode.value

        viewModelScope.launch {
            _state.value = AnalysisState(isLoading = true, progress = "جاري التحليل...")
            _chatHistory.value = emptyList()
            _lastAnalysisText.value = ""

            try {
                // 1. OCR
                var ocrText = ""
                if (settings.ocrEnabled) {
                    _state.value = _state.value.copy(progress = "استخراج النصوص...")
                    ocrText = OcrEngine.recognize(bitmap)
                }

                // 2. Determine effective mode
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

                // 3. Analyze
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

                // 4. Parse
                val parsed = parseResult(resultText)
                _lastAnalysisText.value = buildContextForChat(parsed, ocrText)

                // 5. Search
                var searchResults = emptyList<SearchResult>()
                if (settings.searchEnabled && parsed.keywords.isNotEmpty()) {
                    _state.value = _state.value.copy(progress = "البحث في الإنترنت...")
                    try {
                        val queries = WebSearchEngine.generateSearchQueries(parsed.keywords, parsed.contentType)
                        val allResults = mutableListOf<SearchResult>()
                        for (q in queries.take(3)) {
                            allResults.addAll(WebSearchEngine.search(q))
                        }
                        searchResults = allResults.distinctBy { it.url }.take(10)
                    } catch (_: Exception) {}
                }

                // 6. Update state
                _state.value = AnalysisState(
                    isLoading = false,
                    result = parsed,
                    searchResults = searchResults,
                    usedMode = effectiveMode
                )

                // 7. Save history
                if (settings.saveHistory) {
                    saveToHistory(type, effectiveMode, parsed, searchResults, _selectedUri.value?.toString() ?: "")
                }

            } catch (e: Exception) {
                _state.value = AnalysisState(isLoading = false, error = "خطأ: ${e.message}")
            }
        }
    }

    // ═══ المحادثة مع النموذج المحلي ═══
        fun askLocalModel(question: String) {
        viewModelScope.launch {
            _isChatLoading.value = true

            val currentHistory = _chatHistory.value.toMutableList()
            currentHistory.add("USER: $question")
            _chatHistory.value = currentHistory

            try {
                // تحميل النموذج
                if (!localLlm.isLoaded()) {
                    if (!settings.modelDownloaded) {
                        currentHistory.add("AI: ⚠️ النموذج غير مثبت")
                        _chatHistory.value = currentHistory
                        _isChatLoading.value = false
                        return@launch
                    }
                    if (!localLlm.hasEnoughRam()) {
                        currentHistory.add("AI: ⚠️ ذاكرة غير كافية. أغلق التطبيقات الأخرى")
                        _chatHistory.value = currentHistory
                        _isChatLoading.value = false
                        return@launch
                    }

                    // تحديث: جاري تحميل النموذج
                    currentHistory.add("AI: ⏳ جاري تحميل النموذج... قد يستغرق دقيقة")
                    _chatHistory.value = currentHistory

                    val loaded = localLlm.loadModel()
                    if (!loaded) {
                        // استبدل رسالة "جاري التحميل" برسالة الخطأ
                        val updated = _chatHistory.value.toMutableList()
                        updated.removeLast()
                        updated.add("AI: ⚠️ فشل تحميل النموذج")
                        _chatHistory.value = updated
                        _isChatLoading.value = false
                        return@launch
                    }

                    // استبدل رسالة "جاري التحميل" بنجاح
                    val updated = _chatHistory.value.toMutableList()
                    updated.removeLast()
                    _chatHistory.value = updated
                }

                // إضافة رسالة "جاري التفكير"
                currentHistory.add("AI: 🤔 جاري التفكير...")
                _chatHistory.value = currentHistory

                // توليد الرد مع timeout
                val prompt = buildChatPrompt(question, _lastAnalysisText.value)
                val response = withTimeoutOrNull(180_000L) { // 3 دقائق timeout
                    localLlm.generate(prompt)
                } ?: "⏰ انتهت المهلة. النموذج بطيء جداً على هذا الجهاز."

                // استبدل رسالة "جاري التفكير" بالرد
                val finalHistory = _chatHistory.value.toMutableList()
                finalHistory.removeLast()
                finalHistory.add("AI: $response")
                _chatHistory.value = finalHistory

            } catch (e: Exception) {
                val errorHistory = _chatHistory.value.toMutableList()
                if (errorHistory.lastOrNull()?.contains("جاري") == true) {
                    errorHistory.removeLast()
                }
                errorHistory.add("AI: خطأ: ${e.message}")
                _chatHistory.value = errorHistory
            }

            _isChatLoading.value = false
        }
        }

    // ═══ معالجة النتائج مباشرة ═══
        fun processResults(fullPrompt: String, shortLabel: String) {
        viewModelScope.launch {
            _isChatLoading.value = true

            val currentHistory = _chatHistory.value.toMutableList()
            currentHistory.add("USER: $shortLabel")
            _chatHistory.value = currentHistory

            try {
                if (!localLlm.isLoaded()) {
                    if (!settings.modelDownloaded) {
                        currentHistory.add("AI: ⚠️ النموذج غير مثبت")
                        _chatHistory.value = currentHistory
                        _isChatLoading.value = false
                        return@launch
                    }
                    if (!localLlm.hasEnoughRam()) {
                        currentHistory.add("AI: ⚠️ ذاكرة غير كافية")
                        _chatHistory.value = currentHistory
                        _isChatLoading.value = false
                        return@launch
                    }

                    currentHistory.add("AI: ⏳ جاري تحميل النموذج...")
                    _chatHistory.value = currentHistory

                    val loaded = localLlm.loadModel()
                    if (!loaded) {
                        val updated = _chatHistory.value.toMutableList()
                        updated.removeLast()
                        updated.add("AI: ⚠️ فشل تحميل النموذج")
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
                } ?: "⏰ انتهت المهلة. جرّب نصاً أقصر."

                val finalHistory = _chatHistory.value.toMutableList()
                finalHistory.removeLast()
                finalHistory.add("AI: $response")
                _chatHistory.value = finalHistory

            } catch (e: Exception) {
                val errorHistory = _chatHistory.value.toMutableList()
                if (errorHistory.lastOrNull()?.contains("جاري") == true) {
                    errorHistory.removeLast()
                }
                errorHistory.add("AI: خطأ: ${e.message}")
                _chatHistory.value = errorHistory
            }

            _isChatLoading.value = false
        }
        }

    // ═══ بناء برومبتات ═══
    private fun buildChatPrompt(question: String, context: String): String {
        return """أنت مساعد ذكي يجيب بالعربية. لديك نتائج تحليل صورة.

$context

سؤال المستخدم: $question

أجب بإيجاز وبوضوح:"""
    }

    private fun buildContextForChat(result: AnalysisResult, ocr: String): String {
        return """نتائج التحليل:
نوع المحتوى: ${result.contentType}
الوصف: ${result.description}
العناصر: ${result.elements.joinToString("،")}
النص المستخرج: ${result.extractedText.ifBlank { ocr.ifBlank { "لا يوجد" } }}
الكلمات المفتاحية: ${result.keywords.joinToString("،")}
الثقة: ${result.confidence.label}
معلومات إضافية: ${result.additionalInfo}"""
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

    // ═══ تحليل النتيجة ═══
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

    // ═══ الحفظ ═══
    private suspend fun saveToHistory(
        type: AnalysisType, mode: AiMode,
        result: AnalysisResult, search: List<SearchResult>, uri: String
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
