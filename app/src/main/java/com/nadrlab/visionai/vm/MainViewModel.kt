package com.nadrlab.visionai.vm

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadrlab.visionai.ai.*
import com.nadrlab.visionai.data.*
import com.nadrlab.visionai.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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

    private val _ocrResult = MutableStateFlow("")
    val ocrResult: StateFlow<String> = _ocrResult

    private val _history = MutableStateFlow<List<AnalysisEntity>>(emptyList())
    val history: StateFlow<List<AnalysisEntity>> = _history

    init {
        _aiMode.value = AiMode.valueOf(settings.aiMode)
    }

    fun selectImage(bitmap: Bitmap, uri: Uri) {
        _selectedImage.value = bitmap
        _selectedUri.value = uri
        _state.value = AnalysisState()
        _ocrResult.value = ""
    }

    fun clearImage() {
        _selectedImage.value?.recycle()
        _selectedImage.value = null
        _selectedUri.value = null
        _state.value = AnalysisState()
        _ocrResult.value = ""
    }

    fun setAnalysisType(type: AnalysisType) { _analysisType.value = type }

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

            try {
                // 1. OCR
                if (settings.ocrEnabled) {
                    _state.value = _state.value.copy(progress = "استخراج النصوص...")
                    val ocr = OcrEngine.recognize(bitmap)
                    _ocrResult.value = ocr
                }

                // 2. Determine effective mode
                val effectiveMode = when (mode) {
                    AiMode.AUTO -> if (localLlm.hasEnoughRam() && settings.modelDownloaded) {
                        AiMode.LOCAL
                    } else {
                        AiMode.CLOUD
                    }
                    else -> mode
                }

                // 3. Analyze
                val analysisPrompt = buildAnalysisPrompt(type, _ocrResult.value)
                val resultText = when (effectiveMode) {
                    AiMode.LOCAL -> {
                        _state.value = _state.value.copy(progress = "التحليل المحلي...")
                        localLlm.generate(analysisPrompt)
                    }
                    AiMode.CLOUD -> {
                        _state.value = _state.value.copy(progress = "التحليل السحابي...")
                        CloudVisionManager.analyze(bitmap, analysisPrompt).getOrElse {
                            "فشل التحليل السحابي: ${it.message}"
                        }
                    }
                    AiMode.AUTO -> {
                        CloudVisionManager.analyze(bitmap, analysisPrompt).getOrElse {
                            localLlm.generate(analysisPrompt)
                        }
                    }
                }

                // 4. Parse result
                val parsed = parseResult(resultText)

                // 5. Web search
                var searchResults = emptyList<SearchResult>()
                if (settings.searchEnabled && parsed.keywords.isNotEmpty()) {
                    _state.value = _state.value.copy(progress = "البحث في الإنترنت...")
                    val queries = WebSearchEngine.generateSearchQueries(parsed.keywords, parsed.contentType)
                    val allResults = mutableListOf<SearchResult>()
                    for (q in queries.take(3)) {
                        allResults.addAll(WebSearchEngine.search(q))
                    }
                    searchResults = allResults.distinctBy { it.url }.take(10)
                }

                // 6. Save
                _state.value = AnalysisState(
                    isLoading = false,
                    result = parsed,
                    searchResults = searchResults,
                    usedMode = effectiveMode
                )

                if (settings.saveHistory) {
                    saveToHistory(type, effectiveMode, parsed, searchResults, _selectedUri.value?.toString() ?: "")
                }

            } catch (e: Exception) {
                _state.value = AnalysisState(
                    isLoading = false,
                    error = "خطأ: ${e.message}"
                )
            }
        }
    }

    // ═══ بناء البرومت ═══
    private fun buildAnalysisPrompt(type: AnalysisType, ocrText: String): String {
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
    }

    fun loadHistory() {
        viewModelScope.launch {
            _history.value = db.analysisDao().getAll()
        }
    }

    fun deleteHistoryItem(entity: AnalysisEntity) {
        viewModelScope.launch {
            db.analysisDao().delete(entity)
            loadHistory()
        }
    }

    override fun onCleared() {
        super.onCleared()
        localLlm.unloadModel()
        _selectedImage.value?.recycle()
    }
}
