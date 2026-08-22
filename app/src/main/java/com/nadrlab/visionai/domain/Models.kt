package com.nadrlab.visionai.domain

// ═══ أنواع التحليل ═══
enum class AnalysisType(val label: String, val labelAr: String, val prompt: String) {
    GENERAL("عام", "تحليل عام", "Analyze this image comprehensively."),
    TECHNICAL("تقني", "تحليل تقني", "Analyze technical details, devices, components, specifications."),
    TEXT("نصي", "استخراج النص", "Extract and organize all text visible in this image."),
    DOCUMENT("مستند", "تحليل مستند", "Analyze this document, extract tables, forms, data."),
    PRODUCT("منتج", "تحليل منتج", "Identify this product: name, specs, manufacturer, price range."),
    PLACE("مكان", "تحليل مكان", "Identify this location or landmark with confidence level."),
    EDUCATIONAL("تعليمي", "تحليل تعليمي", "Explain the educational content of this image."),
    SCIENTIFIC("علمي", "تحليل علمي", "Analyze the scientific content visible in this image."),
    HISTORIC("تاريخي", "تحليل تاريخي", "Analyze historical context with certainty level."),
    PHILOSOPHICAL("فلسفي", "تحليل فلسفي", "Provide philosophical interpretation of this image content.")
}

// ═══ وضع الذكاء الاصطناعي ═══
enum class AiMode(val label: String) {
    CLOUD("سحابي")
}

// ═══ مستوى الثقة ═══
enum class ConfidenceLevel(val label: String, val icon: String) {
    HIGH("مؤكد بدرجة عالية", "🟢"),
    MEDIUM("مرجح", "🟡"),
    LOW("محتمل", "🟠"),
    UNCERTAIN("غير مؤكد", "🔴")
}

// ═══ نتيجة التحليل ═══
data class AnalysisResult(
    val contentType: String = "",
    val description: String = "",
    val elements: List<String> = emptyList(),
    val extractedText: String = "",
    val keywords: List<String> = emptyList(),
    val confidence: ConfidenceLevel = ConfidenceLevel.MEDIUM,
    val additionalInfo: String = "",
    val fullText: String = ""
)

// ═══ نتيجة بحث ═══
data class SearchResult(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val source: String = ""
)

// ═══ حالة التحليل ═══
data class AnalysisState(
    val isLoading: Boolean = false,
    val isSearching: Boolean = false,
    val result: AnalysisResult? = null,
    val searchResults: List<SearchResult> = emptyList(),
    val error: String = "",
    val progress: String = "",
    val usedMode: AiMode = AiMode.CLOUD
)
