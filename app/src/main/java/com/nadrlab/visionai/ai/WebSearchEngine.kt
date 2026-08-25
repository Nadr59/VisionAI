package com.nadrlab.visionai.ai

import android.util.Log
import com.nadrlab.visionai.domain.CustomSearchEngine
import com.nadrlab.visionai.domain.SearchEngine
import com.nadrlab.visionai.domain.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

object WebSearchEngine {

    private const val TAG = "WebSearchEngine"

    // ══════════════════════════════════════════════════════
    //  🔑 Serper.dev API Key
    //  سجّل مجاناً على https://serper.dev → 2,500 طلب مجاني
    // ══════════════════════════════════════════════════════
    private const val SERPER_API_KEY  = "d30d311608cc2fd59897d1d9920b4433fed55de9"   // ← ضع مفتاحك هنا
    private const val SERPER_BASE_URL = "https://google.serper.dev/search"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ════════════════════════════════════════════════════
    //  HTTP Helper — POST
    // ════════════════════════════════════════════════════

    private fun httpPost(url: String, jsonBody: String): String? {
        return try {
            val body = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
            val req = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("X-API-KEY", SERPER_API_KEY)
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(req).execute().use { response ->
                val responseBody = response.body?.string()
                Log.d(TAG, "POST ${url.take(80)} → ${response.code}, len=${responseBody?.length ?: 0}")
                if (response.isSuccessful) responseBody else {
                    Log.w(TAG, "HTTP ${response.code} → ${url.take(60)}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "httpPost FAILED: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ════════════════════════════════════════════════════
    //  البحث الرئيسي
    // ════════════════════════════════════════════════════

    suspend fun search(
        query: String,
        engine: SearchEngine = SearchEngine.MULTI
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        Log.i(TAG, "search() → '$q'  engine=$engine")
        return withContext(Dispatchers.IO) {
            searchSerper(q)
        }
    }

    // ════════════════════════════════════════════════════
    //  ★ Serper.dev — المحرك الوحيد
    //  نتائج Google حقيقية — 2,500 طلب مجاني بدون بطاقة
    //  endpoint: POST https://google.serper.dev/search
    // ════════════════════════════════════════════════════

    fun searchSerper(query: String, count: Int = 10): List<SearchResult> {
        return runCatching {
            // بناء جسم الطلب JSON
            val requestJson = JSONObject().apply {
                put("q", query)
                put("num", count.coerceIn(1, 10))   // الحد الأقصى 10 لكل طلب
                put("hl", "ar")                      // لغة الواجهة عربي
                put("gl", "us")                      // منطقة البحث
            }.toString()

            val body = httpPost(SERPER_BASE_URL, requestJson)
                ?: return@runCatching emptyList()

            if (!body.startsWith("{")) return@runCatching emptyList()

            val json    = JSONObject(body)
            val results = mutableListOf<SearchResult>()

            // ── 1) Answer Box — الإجابة المباشرة من Google ──
            val answerBox = json.optJSONObject("answerBox")
            if (answerBox != null) {
                val title   = answerBox.optString("title", "").trim()
                val answer  = answerBox.optString("answer", "")
                    .ifBlank { answerBox.optString("snippet", "") }.trim()
                val link    = answerBox.optString("link", "").trim()
                if (answer.isNotBlank()) {
                    results.add(SearchResult(
                        title   = title.ifBlank { query },
                        url     = link.ifBlank { "https://www.google.com/search?q=${query}" },
                        snippet = answer.take(400),
                        source  = "Google · Answer Box"
                    ))
                }
            }

            // ── 2) Knowledge Graph — معلومات المعرفة ──
            val kg = json.optJSONObject("knowledgeGraph")
            if (kg != null) {
                val kgTitle       = kg.optString("title", "").trim()
                val kgDescription = kg.optString("description", "").trim()
                val kgUrl         = kg.optString("descriptionLink", "").trim()
                val kgType        = kg.optString("type", "").trim()
                if (kgTitle.isNotBlank() && kgDescription.isNotBlank()) {
                    results.add(SearchResult(
                        title   = kgTitle,
                        url     = kgUrl.ifBlank { "https://www.google.com/search?q=${query}" },
                        snippet = kgDescription.take(400),
                        source  = "Google · Knowledge Graph" + if (kgType.isNotBlank()) " · $kgType" else ""
                    ))
                }
            }

            // ── 3) Organic Results — النتائج العضوية الرئيسية ──
            val organic = json.optJSONArray("organic") ?: run {
                Log.w(TAG, "Serper: لا توجد نتائج عضوية")
                return@runCatching results
            }

            for (i in 0 until organic.length()) {
                val item    = organic.optJSONObject(i) ?: continue
                val title   = item.optString("title", "").trim()
                val link    = item.optString("link", "").trim()
                val snippet = item.optString("snippet", "").trim()
                val date    = item.optString("date", "").trim()
                val pos     = item.optInt("position", i + 1)

                // مقاطع إضافية sitelinks إن وُجدت
                val sitelinks = item.optJSONArray("sitelinks")
                val extra = if (sitelinks != null && sitelinks.length() > 0) {
                    (0 until minOf(sitelinks.length(), 2)).joinToString(" | ") { idx ->
                        sitelinks.optJSONObject(idx)?.optString("title", "") ?: ""
                    }.trim()
                } else ""

                val fullSnippet = when {
                    snippet.isNotBlank() && extra.isNotBlank() -> "$snippet | $extra".take(500)
                    snippet.isNotBlank() -> snippet.take(400)
                    extra.isNotBlank()   -> extra.take(400)
                    else -> ""
                }

                val domain = extractDomain(link)
                val source = buildString {
                    append("Google #$pos · $domain")
                    if (date.isNotBlank()) append(" · $date")
                }

                if (title.isNotBlank() && link.isNotBlank()) {
                    results.add(SearchResult(
                        title   = title,
                        url     = link,
                        snippet = fullSnippet,
                        source  = source
                    ))
                }
            }

            // ── 4) People Also Ask — أسئلة ذات صلة ──
            val paa = json.optJSONArray("peopleAlsoAsk")
            if (paa != null) {
                for (i in 0 until minOf(paa.length(), 3)) {
                    val item     = paa.optJSONObject(i) ?: continue
                    val question = item.optString("question", "").trim()
                    val answer   = item.optString("snippet", "").trim()
                    val link     = item.optString("link", "").trim()
                    if (question.isNotBlank() && answer.isNotBlank()) {
                        results.add(SearchResult(
                            title   = question,
                            url     = link.ifBlank { "https://www.google.com/search?q=${question}" },
                            snippet = answer.take(400),
                            source  = "Google · People Also Ask"
                        ))
                    }
                }
            }

            Log.i(TAG, "Serper → ${results.size} results (organic=${organic.length()})")
            dedupe(results, 20)

        }.getOrElse {
            Log.e(TAG, "Serper Search error: ${it.message}")
            emptyList()
        }
    }

    // ════════════════════════════════════════════════════
    //  توافق مع الكود القديم — كل الدوال تمر عبر Serper
    // ════════════════════════════════════════════════════

    fun searchWikipedia(query: String): List<SearchResult>  = searchSerper(query)
    fun searchArchive(query: String): List<SearchResult>    = searchSerper(query)
    fun searchDDGInstant(query: String): List<SearchResult> = searchSerper(query)

    fun fetchWikipediaContent(title: String, lang: String = "ar"): String {
        val results = searchSerper("$title site:wikipedia.org", count = 3)
        return results.joinToString("\n\n") { it.snippet }.take(2000)
    }

    // ════════════════════════════════════════════════════
    //  Multi — يمر عبر Serper مباشرة
    // ════════════════════════════════════════════════════

    private suspend fun searchMulti(query: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            searchSerper(query, count = 10)
        }

    // ════════════════════════════════════════════════════
    //  دعم المحركات المخصصة
    // ════════════════════════════════════════════════════

    suspend fun searchCustom(
        query: String,
        engine: CustomSearchEngine
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            searchSerper(query)
        }
    }

    suspend fun searchWithCustom(
        query: String,
        engine: SearchEngine,
        customEngineList: List<CustomSearchEngine> = emptyList()
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        return supervisorScope {
            val standard = async(Dispatchers.IO) {
                runCatching { searchSerper(query, count = 10) }.getOrDefault(emptyList())
            }
            val custom = async(Dispatchers.IO) {
                customEngineList.map { ce ->
                    async(Dispatchers.IO) {
                        runCatching { searchCustom(query, ce) }.getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
            dedupe(standard.await() + custom.await(), 25)
        }
    }

    // ════════════════════════════════════════════════════
    //  دالة مخصصة للـ AI — ترجع نصاً جاهزاً كـ context
    // ════════════════════════════════════════════════════

    suspend fun searchForAIContext(query: String): String {
        return withContext(Dispatchers.IO) {
            val results = searchSerper(query, count = 10)
            if (results.isEmpty()) return@withContext ""

            buildString {
                appendLine("=== نتائج البحث عن: $query ===")
                appendLine()
                results.take(5).forEachIndexed { index, result ->
                    appendLine("${index + 1}. ${result.title}")
                    appendLine("   المصدر: ${result.source}")
                    appendLine("   الرابط: ${result.url}")
                    if (result.snippet.isNotBlank()) {
                        appendLine("   ${result.snippet}")
                    }
                    appendLine()
                }
            }.trim()
        }
    }

    // ════════════════════════════════════════════════════
    //  أدوات مساعدة
    // ════════════════════════════════════════════════════

    fun generateSearchQueries(keywords: List<String>): List<String> {
        if (keywords.isEmpty()) return emptyList()
        val q = mutableListOf(keywords.take(3).joinToString(" "))
        keywords.take(5).filter { it.length > 3 }.forEach { q.add(it) }
        return q.distinct().take(6)
    }

    private fun extractDomain(url: String): String = runCatching {
        URI(url).host?.removePrefix("www.") ?: url
    }.getOrDefault(url)

    private fun dedupe(list: List<SearchResult>, limit: Int): List<SearchResult> {
        val seen = mutableSetOf<String>()
        return list.filter { r ->
            val k = r.url.removeSuffix("/")
            if (k in seen) false else { seen.add(k); true }
        }.take(limit)
    }
}
