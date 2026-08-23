package com.nadrlab.visionai.vm

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadrlab.visionai.ai.CloudVisionManager
import com.nadrlab.visionai.ai.OcrEngine
import com.nadrlab.visionai.ai.WebSearchEngine
import com.nadrlab.visionai.data.AnalysisEntity
import com.nadrlab.visionai.data.AppDatabase
import com.nadrlab.visionai.data.AppSettings
import com.nadrlab.visionai.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settings = AppSettings(app)
    private val db = AppDatabase.get(app)

    init {
        CloudVisionManager.init(settings)
    }

    // ═══ Image ═══
    private val _selectedImage = MutableStateFlow<Bitmap?>(null)
    val selectedImage: StateFlow<Bitmap?> = _selectedImage

    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri: StateFlow<Uri?> = _selectedUri

    // ═══ Analysis ═══
    private val _analysisType = MutableStateFlow(AnalysisType.GENERAL)
    val analysisType: StateFlow<AnalysisType> = _analysisType

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

    // ═══════════════════════════════════════════
    // ANALYSIS
    // ═══════════════════════════════════════════

    fun analyze() {
        val bitmap = _selectedImage.value ?: return
        val type = _analysisType.value

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

                // Prompt
                val prompt = buildCloudPrompt(type, ocrText)

                // Cloud analyze
                _state.value = _state.value.copy(progress = "جاري إرسال الصورة للمزود...")
                val resultText = CloudVisionManager.analyze(bitmap, prompt).getOrElse {
                    throw Exception("فشل التحليل: ${it.message}")
                }

                // Parse
                _state.value = _state.value.copy(progress = "جاري معالجة النتائج...")
                val parsed = parseResult(resultText)
                _lastAnalysisText.value = buildContextForChat(parsed, ocrText)

                // Search
                var searchResults = emptyList<SearchResult>()
                if (settings.searchEnabled && parsed.keywords.isNotEmpty()) {
                    _state.value = _state.value.copy(progress = "جاري البحث...")
                    try {
                        val queries = WebSearchEngine.generateSearchQueries(parsed.keywords)
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
                    usedMode = AiMode.CLOUD
                )

                // Save
                if (settings.saveHistory) {
                    saveToHistory(type, parsed, searchResults, _selectedUri.value?.toString() ?: "")
                }

            } catch (e: Exception) {
                _state.value = AnalysisState(
                    isLoading = false,
                    error = "خطأ: ${e.message}"
                )
            }
        }
    }

    // ═══════════════════════════════════════════
    // CHAT
    // ═══════════════════════════════════════════

    fun askQuestion(question: String) {
        if (question.isBlank()) return

        viewModelScope.launch {
            _isChatLoading.value = true

            val currentHistory = _chatHistory.value.toMutableList()
            currentHistory.add("USER: $question")
            _chatHistory.value = currentHistory

            try {
                val chatPrompt = buildChatPrompt(question, _lastAnalysisText.value)
                val bitmap = _selectedImage.value

                currentHistory.add("AI: 🤔 جاري التفكير...")
                _chatHistory.value = currentHistory

                val result = if (bitmap != null) {
                    CloudVisionManager.analyze(bitmap, chatPrompt)
                } else {
                    CloudVisionManager.analyze(createBlankBitmap(), chatPrompt)
                }

                val response = result.getOrElse { "❌ خطأ: ${it.message}" }

                val finalHistory = _chatHistory.value.toMutableList()
                finalHistory.removeLast()
                finalHistory.add("AI: $response")
                _chatHistory.value = finalHistory

            } catch (e: Exception) {
                val errorHistory = _chatHistory.value.toMutableList()
                errorHistory.removeAll { it.startsWith("AI:") && it.contains("جاري") }
                errorHistory.add("AI: ❌ خطأ: ${e.message}")
                _chatHistory.value = errorHistory
            }

            _isChatLoading.value = false
        }
    }

    private fun createBlankBitmap(): Bitmap {
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    fun clearChat() {
        _chatHistory.value = emptyList()
    }

    // ═══════════════════════════════════════════
    // PROMPTS
    // ═══════════════════════════════════════════

    private fun buildCloudPrompt(type: AnalysisType, ocrText: String = ""): String {
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

    private fun buildChatPrompt(question: String, context: String): String {
        return if (context.isNotBlank()) {
            """أنت مساعد ذكي يجيب بالعربية.

معلومات من تحليل صورة سابق:
$context

سؤال المستخدم: $question

أجب بإيجاز وبوضوح."""
        } else {
            """أنت مساعد ذكي يجيب بالعربية.

سؤال المستخدم: $question

أجب بإيجاز وبوضوح."""
        }
    }

    private fun buildContextForChat(result: AnalysisResult, ocr: String = ""): String {
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

    private fun saveToHistory(
        type: AnalysisType,
        result: AnalysisResult,
        search: List<SearchResult>,
        uri: String
    ) {
        viewModelScope.launch {
            try {
                db.analysisDao().insert(
                    AnalysisEntity(
                        analysisType = type.name,
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
                )
            } catch (_: Exception) {}
        }
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
        _selectedImage.value?.recycle()
    }
}
