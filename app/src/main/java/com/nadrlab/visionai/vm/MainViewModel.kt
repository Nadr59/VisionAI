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
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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

    // ═══ Service Status ═══
    private val _serviceStatus = MutableStateFlow(CloudVisionManager.ServiceStatus())
    val serviceStatus: StateFlow<CloudVisionManager.ServiceStatus> = _serviceStatus

    private val _isCheckingStatus = MutableStateFlow(false)
    val isCheckingStatus: StateFlow<Boolean> = _isCheckingStatus

    private var statusJob: Job? = null
    private var chatJob: Job? = null
    private var analysisJob: Job? = null

    // ═══════════════════════════════════════════
    // SERVICE STATUS — سريع ومنع التكرار
    // ═══════════════════════════════════════════

    fun checkServiceStatus() {
        // إلغاء أي فحص سابق
        statusJob?.cancel()

        statusJob = viewModelScope.launch {
            _isCheckingStatus.value = true
            try {
                val result = withTimeout(15_000) {
                    CloudVisionManager.checkStatus()
                }
                _serviceStatus.value = result
            } catch (e: TimeoutCancellationException) {
                _serviceStatus.value = CloudVisionManager.ServiceStatus(
                    error = "انتهت مهلة الفحص"
                )
            } catch (e: Exception) {
                _serviceStatus.value = CloudVisionManager.ServiceStatus(
                    error = e.message ?: "خطأ غير معروف"
                )
            }
            _isCheckingStatus.value = false
        }
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

    // ═══════════════════════════════════════════
    // ANALYSIS — مع timeout
    // ═══════════════════════════════════════════

    fun analyze() {
        val bitmap = _selectedImage.value ?: return
        val type = _analysisType.value

        analysisJob?.cancel()

        analysisJob = viewModelScope.launch {
            _state.value = AnalysisState(isLoading = true, progress = "جاري التحليل...")
            _chatHistory.value = emptyList()
            _lastAnalysisText.value = ""

            try {
                // OCR
                var ocrText = ""
                if (settings.ocrEnabled) {
                    _state.value = _state.value.copy(progress = "استخراج النصوص...")
                    try {
                        ocrText = OcrEngine.recognize(bitmap)
                    } catch (_: Exception) {}
                }

                // Prompt
                val prompt = buildCloudPrompt(type, ocrText)

                // Cloud analyze — مع timeout
                _state.value = _state.value.copy(progress = "جاري إرسال الصورة للمزود...")
                val resultText = withTimeout(90_000) {
                    CloudVisionManager.analyze(bitmap, prompt)
                }.getOrElse {
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

                _state.value = AnalysisState(
                    isLoading = false,
                    result = parsed,
                    searchResults = searchResults,
                    usedMode = AiMode.CLOUD
                )

                if (settings.saveHistory) {
                    saveToHistory(type, parsed, searchResults, _selectedUri.value?.toString() ?: "")
                }

            } catch (e: TimeoutCancellationException) {
                _state.value = AnalysisState(
                    isLoading = false,
                    error = "انتهت مهلة التحليل — حاول مرة أخرى"
                )
            } catch (e: Exception) {
                _state.value = AnalysisState(
                    isLoading = false,
                    error = "خطأ: ${e.message ?: "غير معروف"}"
                )
            }
        }
    }

    // ═══════════════════════════════════════════
    // CHAT — مُصحح بالكامل: لا ينهار أبداً
    // ═══════════════════════════════════════════

    

                // ═══════════════════════════════════════════
    // CHAT — مُصحح: يرسل الصورة دائماً إن وُجدت
    // ═══════════════════════════════════════════

    fun askQuestion(question: String) {
        if (question.isBlank()) return
        if (_isChatLoading.value) return

        chatJob?.cancel()

        chatJob = viewModelScope.launch {
            _isChatLoading.value = true

            try {
                // 1. إضافة رسالة المستخدم
                val withUserMsg = _chatHistory.value + "USER: $question"
                _chatHistory.value = withUserMsg

                // 2. إضافة مؤشر التفكير
                _chatHistory.value = withUserMsg + "AI: 🤔 جاري التفكير..."

                // 3. بناء الطلب
                val chatPrompt = buildChatPrompt(question, _lastAnalysisText.value)
                val bitmap = _selectedImage.value

                // 4. إرسال الطلب — دائماً مع صورة إن وُجدت
                val result: Result<String> = try {
                    withTimeout(90_000) {
                        if (bitmap != null) {
                            // مع صورة — نفس طريقة التحليل
                            CloudVisionManager.analyze(bitmap, chatPrompt)
                        } else {
                            // بدون صورة — نص فقط
                            CloudVisionManager.analyzeText(chatPrompt)
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    Result.failure(Exception("انتهت مهلة الاتصال — حاول مرة أخرى"))
                } catch (e: Exception) {
                    Result.failure(Exception("خطأ: ${e.message ?: "غير معروف"}"))
                }

                // 5. تحديث المحادثة
                val response = result.getOrElse { "❌ ${it.message}" }

                _chatHistory.value = withUserMsg + "AI: $response"

            } catch (e: Exception) {
                try {
                    _chatHistory.value = _chatHistory.value
                        .filterNot { it.contains("جاري التفكير") }
                        .plus("AI: ❌ خطأ: ${e.message ?: "غير معروف"}")
                } catch (_: Exception) {
                    _chatHistory.value = listOf("AI: ❌ حدث خطأ غير متوقع")
                }
            }

            _isChatLoading.value = false
        }
    }
        fun clearChat() {
        chatJob?.cancel()
        _chatHistory.value = emptyList()
        _isChatLoading.value = false
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
        // ═══ Web Search ═══
    private val _webSearch = MutableStateFlow(WebSearchState())
    val webSearch: StateFlow<WebSearchState> = _webSearch

    fun searchWeb(query: String) {
        if (query.isBlank()) return

        viewModelScope.launch {
            _webSearch.value = WebSearchState(isLoading = true, query = query)

            try {
                val results = withTimeout(30_000) {
                    WebSearchEngine.search(query)
                }

                _webSearch.value = WebSearchState(
                    isLoading = false,
                    query = query,
                    results = results
                )
            } catch (e: Exception) {
                _webSearch.value = WebSearchState(
                    isLoading = false,
                    query = query,
                    error = "خطأ: ${e.message ?: "فشل البحث"}"
                )
            }
        }
    }

    fun clearSearch() {
        _webSearch.value = WebSearchState()
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
        statusJob?.cancel()
        chatJob?.cancel()
        analysisJob?.cancel()
        _selectedImage.value?.recycle()
    }
}
