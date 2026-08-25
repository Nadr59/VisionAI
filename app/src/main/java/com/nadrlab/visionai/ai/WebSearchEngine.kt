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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object WebSearchEngine {

    private const val TAG = "WebSearchEngine"

    // ══════════════════════════════════════════════════════
    //  🔑 Brave Search API Key
    // ══════════════════════════════════════════════════════
    private const val BRAVE_API_KEY = "BSA_YOUR_KEY_HERE"   // ← ضع مفتاحك هنا
    private const val BRAVE_BASE_URL = "https://api.search.brave.com/res/v1/web/search"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ════════════════════════════════════════════════════
    //  HTTP Helper
    // ════════════════════════════════════════════════════

    private fun httpGet(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addHeader("Accept-Encoding", "identity")
                .addHeader("X-Subscription-Token", BRAVE_API_KEY)
                .build()

            client.newCall(req).execute().use { response ->
                val body = response.body?.string()
                Log.d(TAG, "GET ${url.take(80)} → ${response.code}, len=${body?.length ?: 0}")
                if (response.isSuccessful) body else {
                    Log.w(TAG, "HTTP ${response.code} → ${url.take(60)}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "httpGet FAILED: ${e.javaClass.simpleName}: ${e.message}")
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
            searchBrave(q)
        }
    }

    // ════════════════════════════════════════════════════
    //  ★ Brave Search API — المحرك الوحيد
    //  يُعيد مواقع ويب حقيقية — 2,000 طلب/شهر مجاناً
    //  endpoint: GET https://api.search.brave.com/res/v1/web/search
    // ════════════════════════════════════════════════════

    fun searchBrave(query: String, count: Int = 10): List<SearchResult> {
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$BRAVE_BASE_URL" +
                      "?q=$encoded" +
                      "&count=$count" +
                      "&search_lang=ar" +
                      "&safesearch=moderate" +
                      "&extra_snippets=1"

            val body = httpGet(url) ?: return@runCatching emptyList()
            if (!body.startsWith("{")) return@runCatching emptyList()

            val json = JSONObject(body)
            val webResults = json
                .optJSONObject("web")
                ?.optJSONArray("results")
                ?: return@runCatching emptyList()

            val out = mutableListOf<SearchResult>()

            for (i in 0 until webResults.length()) {
                val item = webResults.optJSONObject(i) ?: continue

                val title       = item.optString("title", "").trim()
                val pageUrl     = item.optString("url", "").trim()
                val description = item.optString("description", "").trim()
                val age         = item.optString("age", "")

                // extra_snippets — مقاطع إضافية إن وُجدت
                val extraArr = item.optJSONArray("extra_snippets")
                val extra = if (extraArr != null && extraArr.length() > 0)
                    (0 until minOf(extraArr.length(), 2))
                        .joinToString(" … ") { extraArr.optString(it, "") }
                else ""

                val snippet = when {
                    description.isNotBlank() && extra.isNotBlank() ->
                        "$description … $extra".take(500)
                    description.isNotBlank() -> description.take(400)
                    extra.isNotBlank()       -> extra.take(400)
                    else -> ""
                }

                // اسم النطاق كمصدر
                val domain = extractDomain(pageUrl)
                val source = if (age.isNotBlank()) "Brave · $domain · $age"
                             else "Brave · $domain"

                if (title.isNotBlank() && pageUrl.isNotBlank()) {
                    out.add(SearchResult(
                        title   = title,
                        url     = pageUrl,
                        snippet = snippet,
                        source  = source
                    ))
                }
            }

            Log.i(TAG, "Brave Search → ${out.size} results")
            out
        }.getOrElse {
            Log.e(TAG, "Brave Search error: ${it.message}")
            emptyList()
        }
    }

    // دالة قديمة — تحول إلى Brave
    fun searchWikipedia(query: String): List<SearchResult> = searchBrave(query)
    fun searchArchive(query: String): List<SearchResult>   = searchBrave(query)
    fun searchDDGInstant(query: String): List<SearchResult> = searchBrave(query)

    // ════════════════════════════════════════════════════
    //  جلب محتوى مقالة (يُبقي التوافق مع الكود القديم)
    // ════════════════════════════════════════════════════

    fun fetchWikipediaContent(title: String, lang: String = "ar"): String {
        // استخدام Brave للبحث عن المحتوى بدلاً من Wikipedia API
        val results = searchBrave("$title site:wikipedia.org", count = 3)
        return results.joinToString("\n\n") { it.snippet }.take(2000)
    }

    // ════════════════════════════════════════════════════
    //  Multi — يمر عبر Brave مباشرة
    // ════════════════════════════════════════════════════

    private suspend fun searchMulti(query: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            searchBrave(query, count = 20)
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
            searchBrave(query)
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
                runCatching { searchBrave(query, count = 20) }.getOrDefault(emptyList())
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
            val results = searchBrave(query, count = 10)
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

    private fun extractDomain(url: String): String = runCatching {
        java.net.URI(url).host?.removePrefix("www.") ?: url
    }.getOrDefault(url)

    private fun dedupe(list: List<SearchResult>, limit: Int): List<SearchResult> {
        val seen = mutableSetOf<String>()
        return list.filter { r ->
            val k = r.url.removeSuffix("/")
            if (k in seen) false else { seen.add(k); true }
        }.take(limit)
    }

    fun generateSearchQueries(keywords: List<String>): List<String> {
        if (keywords.isEmpty()) return emptyList()
        val q = mutableListOf(keywords.take(3).joinToString(" "))
        keywords.take(5).filter { it.length > 3 }.forEach { q.add(it) }
        return q.distinct().take(6)
    }
}
