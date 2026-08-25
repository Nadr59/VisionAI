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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object WebSearchEngine {

    private const val TAG = "WebSearchEngine"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val WIKI_UA = "VisionAI/2.1 (Android; contact@example.com)"

    // ════════════════════════════════════════════════════
    //  HTTP Helper
    // ════════════════════════════════════════════════════

    private fun httpGet(
        url: String,
        accept: String = "application/json",
        customUA: String? = null
    ): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", customUA ?: WIKI_UA)
                .addHeader("Accept", accept)
                .addHeader("Accept-Encoding", "identity")
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
            when (engine) {
                SearchEngine.WIKIPEDIA   -> searchWikipedia(q)
                SearchEngine.ARCHIVE     -> searchArchive(q)
                SearchEngine.DUCKDUCKGO  -> searchDDGInstant(q)
                SearchEngine.MULTI,
                SearchEngine.SEARXNG,
                SearchEngine.GOOGLE_LITE -> searchMulti(q)
            }
        }
    }

    // ════════════════════════════════════════════════════
    //  ★ 1) Wikipedia API — بلا حدود تماماً
    //  أفضل مصدر للمعلومات والحقائق لتطبيقات AI
    // ════════════════════════════════════════════════════

    fun searchWikipedia(query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val encoded = URLEncoder.encode(query, "UTF-8")

        // البحث بالعربي أولاً ثم الإنجليزي
        for ((lang, src) in listOf("ar" to "ويكيبيديا", "en" to "Wikipedia")) {
            runCatching {
                val body = httpGet(
                    url = "https://$lang.wikipedia.org/w/api.php" +
                          "?action=query&list=search" +
                          "&srsearch=$encoded" +
                          "&format=json&srlimit=8&utf8=1" +
                          "&srnamespace=0"
                ) ?: return@runCatching

                if (!body.startsWith("{")) return@runCatching

                val arr = JSONObject(body)
                    .optJSONObject("query")
                    ?.optJSONArray("search")
                    ?: return@runCatching

                for (i in 0 until arr.length()) {
                    val item    = arr.optJSONObject(i) ?: continue
                    val title   = item.optString("title", "").trim()
                    val snippet = cleanHtml(item.optString("snippet", "")).take(400)
                    val wordCount = item.optInt("wordcount", 0)
                    val pageUrl = "https://$lang.wikipedia.org/wiki/" +
                                  URLEncoder.encode(title, "UTF-8").replace("+", "_")

                    if (title.isNotBlank()) {
                        results.add(SearchResult(
                            title   = title,
                            url     = pageUrl,
                            snippet = snippet,
                            source  = "$src · $wordCount كلمة"
                        ))
                    }
                }
            }.onFailure { Log.e(TAG, "Wikipedia $lang error: ${it.message}") }
        }

        Log.i(TAG, "Wikipedia → ${results.size} results")
        return dedupe(results, 16)
    }

    // ════════════════════════════════════════════════════
    //  جلب محتوى مقالة Wikipedia كاملة (للـ AI context)
    // ════════════════════════════════════════════════════

    fun fetchWikipediaContent(title: String, lang: String = "ar"): String {
        return runCatching {
            val encoded = URLEncoder.encode(title, "UTF-8")
            val body = httpGet(
                url = "https://$lang.wikipedia.org/w/api.php" +
                      "?action=query&prop=extracts&exintro=true" +
                      "&explaintext=true&titles=$encoded" +
                      "&format=json&utf8=1"
            ) ?: return@runCatching ""

            val pages = JSONObject(body)
                .optJSONObject("query")
                ?.optJSONObject("pages")
                ?: return@runCatching ""

            // Wikipedia يرجع page id كـ key
            val pageKey = pages.keys().asSequence().firstOrNull() ?: return@runCatching ""
            pages.optJSONObject(pageKey)
                ?.optString("extract", "")
                ?.take(2000)
                ?: ""
        }.getOrDefault("")
    }

    // ════════════════════════════════════════════════════
    //  ★ 2) Archive.org — بلا حدود
    //  مفيد للمراجع والوثائق والكتب
    // ════════════════════════════════════════════════════

    fun searchArchive(query: String): List<SearchResult> {
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val body = httpGet(
                url = "https://archive.org/advancedsearch.php" +
                      "?q=$encoded" +
                      "&fl[]=identifier&fl[]=title&fl[]=description&fl[]=mediatype" +
                      "&output=json&rows=10&sort[]=downloads+desc"
            ) ?: return@runCatching emptyList()

            if (!body.startsWith("{")) return@runCatching emptyList()

            val docs = JSONObject(body)
                .optJSONObject("response")
                ?.optJSONArray("docs")
                ?: return@runCatching emptyList()

            val out = mutableListOf<SearchResult>()
            for (i in 0 until docs.length()) {
                val item       = docs.optJSONObject(i) ?: continue
                val title      = item.optString("title", "").trim()
                val identifier = item.optString("identifier", "").trim()
                val mediatype  = item.optString("mediatype", "").trim()
                val description = when (val d = item.opt("description")) {
                    is String    -> d
                    is JSONArray -> (0 until d.length())
                        .joinToString(" ") { idx -> d.optString(idx, "") }
                    else -> ""
                }

                if (title.isNotBlank() && identifier.isNotBlank()) {
                    out.add(SearchResult(
                        title   = title.take(150),
                        url     = "https://archive.org/details/$identifier",
                        snippet = cleanHtml(description).take(400),
                        source  = "Archive.org · $mediatype"
                    ))
                }
            }

            Log.i(TAG, "Archive → ${out.size} results")
            out
        }.getOrDefault(emptyList())
    }

    // ════════════════════════════════════════════════════
    //  ★ 3) DuckDuckGo Instant Answer — بلا حدود
    //  مفيد للإجابات السريعة والتعريفات
    // ════════════════════════════════════════════════════

    fun searchDDGInstant(query: String): List<SearchResult> {
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val body = httpGet(
                url = "https://api.duckduckgo.com/" +
                      "?q=$encoded&format=json&no_html=1&skip_disambig=1"
            ) ?: return@runCatching emptyList()

            if (!body.startsWith("{")) return@runCatching emptyList()

            val json    = JSONObject(body)
            val results = mutableListOf<SearchResult>()

            // Abstract (الإجابة المباشرة)
            val abs    = json.optString("Abstract", "")
            val absUrl = json.optString("AbstractURL", "")
            val absSrc = json.optString("AbstractSource", "")
            if (abs.isNotBlank() && absUrl.isNotBlank()) {
                results.add(SearchResult(
                    title   = absSrc.ifBlank { query },
                    url     = absUrl,
                    snippet = abs.take(400),
                    source  = "DDG · $absSrc"
                ))
            }

            // Answer (إجابة مباشرة قصيرة)
            val answer = json.optString("Answer", "")
            if (answer.isNotBlank()) {
                results.add(SearchResult(
                    title   = query,
                    url     = absUrl.ifBlank { "https://duckduckgo.com/?q=$encoded" },
                    snippet = answer.take(400),
                    source  = "DDG Instant Answer"
                ))
            }

            // Definition
            val definition    = json.optString("Definition", "")
            val definitionUrl = json.optString("DefinitionURL", "")
            val definitionSrc = json.optString("DefinitionSource", "")
            if (definition.isNotBlank() && definitionUrl.isNotBlank()) {
                results.add(SearchResult(
                    title   = "تعريف: $query",
                    url     = definitionUrl,
                    snippet = definition.take(400),
                    source  = "DDG · $definitionSrc"
                ))
            }

            // Related Topics
            val topics = json.optJSONArray("RelatedTopics") ?: JSONArray()
            for ((text, url) in flattenDDGTopics(topics).take(8)) {
                if (text.isNotBlank() && url.isNotBlank()) {
                    results.add(SearchResult(
                        title   = text.take(120),
                        url     = url,
                        snippet = text.take(400),
                        source  = "DDG Related"
                    ))
                }
            }

            Log.i(TAG, "DDG Instant → ${results.size} results")
            dedupe(results, 12)
        }.getOrDefault(emptyList())
    }

    private fun flattenDDGTopics(topics: JSONArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()

        fun add(obj: JSONObject) {
            val t = obj.optString("Text", "")
            val u = obj.optString("FirstURL", "")
            if (t.isNotBlank() && u.isNotBlank()) out.add(t to u)
        }

        for (i in 0 until topics.length()) {
            val obj = topics.optJSONObject(i) ?: continue
            if (obj.has("Topics")) {
                val nested = obj.optJSONArray("Topics") ?: JSONArray()
                for (j in 0 until nested.length()) nested.optJSONObject(j)?.let { add(it) }
            } else {
                add(obj)
            }
        }
        return out
    }

    // ════════════════════════════════════════════════════
    //  Multi — كل المحركات الثلاثة بالتوازي
    // ════════════════════════════════════════════════════

    private suspend fun searchMulti(query: String): List<SearchResult> = supervisorScope {
        val jobs = listOf(
            async(Dispatchers.IO) {
                runCatching { searchWikipedia(query) }.getOrDefault(emptyList())
            },
            async(Dispatchers.IO) {
                runCatching { searchDDGInstant(query) }.getOrDefault(emptyList())
            },
            async(Dispatchers.IO) {
                runCatching { searchArchive(query) }.getOrDefault(emptyList())
            }
        )

        val all = jobs.awaitAll().flatten()
        Log.i(TAG, "Multi → ${all.size} total results")
        dedupe(all, 25)
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
            // نستخدم Wikipedia + DDG كـ fallback للمحركات المخصصة
            runCatching {
                searchWikipedia(query).ifEmpty { searchDDGInstant(query) }
            }.getOrDefault(emptyList())
        }
    }

    suspend fun searchWithCustom(
        query: String,
        engine: SearchEngine,
        customEngineList: List<CustomSearchEngine> = emptyList()
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        return when (engine) {
            SearchEngine.MULTI -> supervisorScope {
                val standard = async(Dispatchers.IO) {
                    runCatching { searchMulti(query) }.getOrDefault(emptyList())
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
            else -> search(query, engine)
        }
    }

    // ════════════════════════════════════════════════════
    //  دالة مخصصة للـ AI — ترجع نصاً جاهزاً كـ context
    // ════════════════════════════════════════════════════

    suspend fun searchForAIContext(query: String): String {
        return withContext(Dispatchers.IO) {
            val results = searchMulti(query)

            if (results.isEmpty()) return@withContext ""

            buildString {
                appendLine("=== نتائج البحث عن: $query ===")
                appendLine()

                results.take(5).forEachIndexed { index, result ->
                    appendLine("${index + 1}. ${result.title}")
                    appendLine("   المصدر: ${result.source}")
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

    private fun cleanHtml(text: String): String = text
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("\\s+".toRegex(), " ").trim()

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

    fun generateSearchQueries(keywords: List<String>): List<String> {
        if (keywords.isEmpty()) return emptyList()
        val q = mutableListOf(keywords.take(3).joinToString(" "))
        keywords.take(5).filter { it.length > 3 }.forEach { q.add(it) }
        return q.distinct().take(6)
    }
}
