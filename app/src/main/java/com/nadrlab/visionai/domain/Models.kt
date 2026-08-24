package com.nadrlab.visionai.domain

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

enum class AiMode(val label: String) {
    CLOUD("سحابي")
}

enum class ConfidenceLevel(val label: String, val icon: String) {
    HIGH("مؤكد بدرجة عالية", "🟢"),
    MEDIUM("مرجح", "🟡"),
    LOW("محتمل", "🟠"),
    UNCERTAIN("غير مؤكد", "🔴")
}
enum class SearchEngine(
    val label: String,
    val labelAr: String,
    val icon: String,
    val description: String
) {
    SEARXNG("SearXNG", "SearXNG (موصى)", "🌐", "يجمع Google + Bing + DuckDuckGo + أكثر"),
    DUCKDUCKGO("DuckDuckGo", "DuckDuckGo", "🦆", "بحث خصوصي بدون تتبع"),
    WIKIPEDIA("Wikipedia", "ويكيبيديا", "📚", "موسوعة شاملة"),
    GOOGLE_LITE("Google Lite", "جوجل خفيف", "🔍", "نتائج جوجل الأساسية"),
    ARCHIVE("Archive.org", "أرشيف الإنترنت", "📦", "أرشيف الصفحات والمحتوى القديم"),
    MULTI("Multi", "بحث شامل", "🚀", "يجمع كل المحركات معاً")
}

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

data class SearchResult(
    val title: String = "",
    val url: String = "",
    val snippet: String = "",
    val source: String = ""
)

data class AnalysisState(
    val isLoading: Boolean = false,
    val isSearching: Boolean = false,
    val result: AnalysisResult? = null,
    val searchResults: List<SearchResult> = emptyList(),
    val error: String = "",
    val progress: String = "",
    val usedMode: AiMode = AiMode.CLOUD
)
data class WebSearchState(
    val isLoading: Boolean = false,
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val error: String = ""
)
data class CustomSearchEngine(
    val id: String = System.currentTimeMillis().toString(),
    val name: String = "",
    val nameAr: String = "",
    val urlTemplate: String = "",
    val icon: String = "🔍"
) {
    companion object {
        val EXAMPLES = listOf(
            CustomSearchEngine(
                name = "Yandex",
                nameAr = "ياندكس",
                urlTemplate = "https://yandex.com/search/?text={query}",
                icon = "🔴"
            ),
            CustomSearchEngine(
                name = "Bing",
                nameAr = "بينغ",
                urlTemplate = "https://www.bing.com/search?q={query}",
                icon = "🔵"
            ),
            CustomSearchEngine(
                name = "Baidu",
                nameAr = "بايدو",
                urlTemplate = "https://www.baidu.com/s?wd={query}",
                icon = "🇨🇳"
            ),
            CustomSearchEngine(
                name = "Ecosia",
                nameAr = "إيكوسيا",
                urlTemplate = "https://www.ecosia.org/search?q={query}",
                icon = "🌳"
            ),
            CustomSearchEngine(
                name = "Brave",
                nameAr = "بريف",
                urlTemplate = "https://search.brave.com/search?q={query}",
                icon = "🦁"
            ),
            CustomSearchEngine(
                name = "Startpage",
                nameAr = "ستارت بيج",
                urlTemplate = "https://www.startpage.com/do/search?q={query}",
                icon = "👁️"
            )
        )
    }
}
