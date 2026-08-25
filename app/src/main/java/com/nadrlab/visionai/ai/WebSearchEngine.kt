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
import java.net.URLDecoder
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

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Mobile Safari/537.36"

    // ════════════════════════════════════════════════════
    //  HTTP Helper المركزي
    // ════════════════════════════════════════════════════

    private fun httpGet(
        url: String,
        accept: String = "*/*",
        lang: String? = "ar,en;q=0.9",
        customUA: String? = null
    ): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", customUA ?: UA)
                .addHeader("Accept", accept)
                .addHeader("Accept-Encoding", "identity")
                .apply {
                    if (!lang.isNullOrBlank()) addHeader("Accept-Language", lang)
                }
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                Log.d(TAG, "GET $url → HTTP ${response.code}, bodyLen=${body?.length ?: 0}")
                if (response.isSuccessful) body else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "httpGet FAILED: $url → ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ════════════════════════════════════════════════════
    //  بحث رئيسي مع fallback ذكي
    // ════════════════════════════════════════════════════

    suspend fun search(
        query: String,
        engine: SearchEngine = SearchEngine.SEARXNG
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        Log.i(TAG, "search() query='$q' engine=$engine")

        return withContext(Dispatchers.IO) {
            val primary = runCatching {
                when (engine) {
                    SearchEngine.SEARXNG     -> searchSearXNG(q)
                    SearchEngine.DUCKDUCKGO  -> searchDuckDuckGo(q)
                    SearchEngine.WIKIPEDIA   -> searchWikipedia(q)
                    SearchEngine.GOOGLE_LITE -> searchGoogleLite(q)
                    SearchEngine.ARCHIVE     -> searchArchive(q)
                    SearchEngine.MULTI       -> searchMulti(q)
                }
            }.onFailure {
                Log.e(TAG, "Primary engine $engine failed: ${it.message}")
            }.getOrDefault(emptyList())

            if (primary.isNotEmpty()) {
                Log.i(TAG, "Primary engine returned ${primary.size} results")
                return@withContext dedupe(primary, 25)
            }

            Log.w(TAG, "Primary engine $engine returned 0 results → trying fallbacks")

            val fallbackOrder = listOf(
                SearchEngine.SEARXNG,
                SearchEngine.DUCKDUCKGO,
                SearchEngine.ARCHIVE,
                SearchEngine.GOOGLE_LITE,
                SearchEngine.WIKIPEDIA
            ).filter { it != engine }

            for (fb in fallbackOrder) {
                val r = runCatching {
                    when (fb) {
                        SearchEngine.SEARXNG     -> searchSearXNG(q)
                        SearchEngine.DUCKDUCKGO  -> searchDuckDuckGo(q)
                        SearchEngine.WIKIPEDIA   -> searchWikipedia(q)
                        SearchEngine.GOOGLE_LITE -> searchGoogleLite(q)
                        SearchEngine.ARCHIVE     -> searchArchive(q)
                        SearchEngine.MULTI       -> emptyList()
                    }
                }.onFailure {
                    Log.e(TAG, "Fallback $fb failed: ${it.message}")
                }.getOrDefault(emptyList())

                if (r.isNotEmpty()) {
                    Log.i(TAG, "Fallback $fb returned ${r.size} results")
                    return@withContext dedupe(r, 25)
                }

                Log.w(TAG, "Fallback $fb returned 0 results")
            }

            Log.e(TAG, "ALL engines failed for query='$q'")
            emptyList()
        }
    }

    // ════════════════════════════════════════════════════
    //  1) SearXNG
    // ════════════════════════════════════════════════════

    private val searxngInstances = listOf(
        "https://search.inetol.net",
        "https://searx.be",
        "https://search.ononoki.org",
        "https://searx.tiekoetter.com",
        "https://search.hbubli.de",
        "https://search.datura.network",
        "https://searx.tuxcloud.net",
        "https://priv.au"
    )

    private fun searchSearXNG(query: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")

        for (instance in searxngInstances) {
            Log.d(TAG, "SearXNG trying: $instance")
            val url = "$instance/search?q=$encoded&format=json&categories=general&language=auto"
            val body = httpGet(url, accept = "application/json", lang = null) ?: continue

            if (!body.startsWith("{")) {
                Log.w(TAG, "SearXNG $instance: response not JSON (starts with '${body.take(30)}')")
                continue
            }
            if (!body.contains("\"results\"")) {
                Log.w(TAG, "SearXNG $instance: no 'results' key in JSON")
                continue
            }

            return try {
                val arr = JSONObject(body).optJSONArray("results") ?: JSONArray()
                val out = mutableListOf<SearchResult>()

                for (i in 0 until minOf(arr.length(), 15)) {
                    val item      = arr.optJSONObject(i) ?: continue
                    val title     = item.optString("title", "").trim()
                    val resultUrl = item.optString("url", "").trim()
                    val content   = item.optString("content", "").trim()
                    val eng       = item.optString("engine", "searxng").trim()

                    if (title.isNotBlank() && resultUrl.startsWith("http")) {
                        out.add(SearchResult(
                            title   = title.take(150),
                            url     = resultUrl,
                            snippet = content.take(400),
                            source  = "${extractDomain(resultUrl)} · $eng"
                        ))
                    }
                }

                Log.i(TAG, "SearXNG $instance → ${out.size} results")
                if (out.isNotEmpty()) return out else continue
            } catch (e: Exception) {
                Log.e(TAG, "SearXNG $instance parse error: ${e.message}")
                continue
            }
        }

        return emptyList()
    }

    // ════════════════════════════════════════════════════
    //  2) DuckDuckGo — 3 طرق بدون POST
    // ════════════════════════════════════════════════════

    private fun searchDuckDuckGo(query: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")

        // الطريقة 1: HTML GET
        Log.d(TAG, "DDG: trying html endpoint")
        val html1 = httpGet(
            url    = "https://html.duckduckgo.com/html/?q=$encoded",
            accept = "text/html"
        )
        if (!html1.isNullOrBlank() && html1.contains("result__a")) {
            val r = parseDuckDuckGoHtml(html1)
            Log.i(TAG, "DDG html → ${r.size} results")
            if (r.isNotEmpty()) return r
        }

        // الطريقة 2: lite
        Log.d(TAG, "DDG: trying lite endpoint")
        val html2 = httpGet(
            url    = "https://lite.duckduckgo.com/lite/?q=$encoded",
            accept = "text/html"
        )
        if (!html2.isNullOrBlank()) {
            val r = parseDuckDuckGoLite(html2)
            Log.i(TAG, "DDG lite → ${r.size} results")
            if (r.isNotEmpty()) return r
        }

        // الطريقة 3: Instant Answer API
        Log.d(TAG, "DDG: trying instant answer API")
        val apiBody = httpGet(
            url    = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1",
            accept = "application/json",
            lang   = null
        )

        if (!apiBody.isNullOrBlank() && apiBody.startsWith("{")) {
            val json    = runCatching { JSONObject(apiBody) }.getOrNull() ?: return emptyList()
            val results = mutableListOf<SearchResult>()

            val abs    = json.optString("Abstract", "")
            val absUrl = json.optString("AbstractURL", "")
            if (abs.isNotBlank() && absUrl.isNotBlank()) {
                results.add(SearchResult(
                    title   = json.optString("AbstractSource", "DuckDuckGo"),
                    url     = absUrl,
                    snippet = abs.take(400),
                    source  = extractDomain(absUrl)
                ))
            }

            val topics = json.optJSONArray("RelatedTopics") ?: JSONArray()
            for ((text, firstUrl) in flattenDDGTopics(topics).take(12)) {
                if (text.isNotBlank() && firstUrl.isNotBlank()) {
                    results.add(SearchResult(
                        title   = text.take(120),
                        url     = firstUrl,
                        snippet = text.take(400),
                        source  = extractDomain(firstUrl)
                    ))
                }
            }

            Log.i(TAG, "DDG instant answer → ${results.size} results")
            if (results.isNotEmpty()) return dedupe(results, 15)
        }

        return emptyList()
    }

    private fun parseDuckDuckGoHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val seen    = mutableSetOf<String>()

        val titlePattern = Regex(
            """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val snippetPattern = Regex(
            """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        val titles   = titlePattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()

        for (i in titles.indices.take(15)) {
            val rawUrl  = titles[i].groupValues[1]
            val title   = cleanHtml(titles[i].groupValues[2])
            val url     = extractDDGUrl(rawUrl)
            val snippet = if (i < snippets.size) cleanHtml(snippets[i].groupValues[1]) else ""

            if (title.isBlank() || url.isBlank() || !url.startsWith("http")) continue
            if (url in seen || isBlockedUrl(url)) continue

            seen.add(url)
            results.add(SearchResult(
                title   = title.take(150),
                url     = url,
                snippet = snippet.take(400),
                source  = extractDomain(url)
            ))
        }
        return results
    }

    private fun parseDuckDuckGoLite(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val seen    = mutableSetOf<String>()

        val linkPattern = Regex(
            """<a[^>]+href="(https?://[^"]+)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        for (m in linkPattern.findAll(html)) {
            val url   = m.groupValues[1].trim()
            val title = cleanHtml(m.groupValues[2]).take(150)

            if (title.length < 4 || !url.startsWith("http")) continue
            if (url in seen || isBlockedUrl(url)) continue

            seen.add(url)
            results.add(SearchResult(
                title   = title,
                url     = url,
                snippet = "",
                source  = extractDomain(url)
            ))
            if (results.size >= 15) break
        }
        return results
    }

    private fun flattenDDGTopics(topics: JSONArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()

        fun addObj(obj: JSONObject) {
            val text     = obj.optString("Text", "")
            val firstUrl = obj.optString("FirstURL", "")
            if (text.isNotBlank() && firstUrl.isNotBlank()) out.add(text to firstUrl)
        }

        for (i in 0 until topics.length()) {
            val obj = topics.optJSONObject(i) ?: continue
            if (obj.has("Topics")) {
                val nested = obj.optJSONArray("Topics") ?: JSONArray()
                for (j in 0 until nested.length()) nested.optJSONObject(j)?.let { addObj(it) }
            } else {
                addObj(obj)
            }
        }
        return out
    }

    // ════════════════════════════════════════════════════
    //  3) Wikipedia
    // ════════════════════════════════════════════════════

    private fun searchWikipedia(query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val encoded = URLEncoder.encode(query, "UTF-8")

        for ((lang, sourceName) in listOf("ar" to "ويكيبيديا عربي", "en" to "Wikipedia EN")) {
            val body = httpGet(
                url      = "https://$lang.wikipedia.org/w/api.php?action=query" +
                           "&list=search&srsearch=$encoded&format=json&srlimit=6&utf8=1",
                accept   = "application/json",
                lang     = null,
                customUA = "VisionAI/2.1 (Android; contact@example.com)"
            ) ?: continue

            if (!body.startsWith("{") || body.contains("\"error\"")) continue

            val arr = runCatching {
                JSONObject(body).optJSONObject("query")?.optJSONArray("search")
            }.getOrNull() ?: continue

            for (i in 0 until arr.length()) {
                val item    = arr.optJSONObject(i) ?: continue
                val title   = item.optString("title", "").trim()
                val snippet = cleanHtml(item.optString("snippet", "")).take(400)
                val pageUrl = "https://$lang.wikipedia.org/wiki/" +
                              URLEncoder.encode(title, "UTF-8").replace("+", "_")

                if (title.isNotBlank()) {
                    results.add(SearchResult(title, pageUrl, snippet, sourceName))
                }
            }
        }

        Log.i(TAG, "Wikipedia → ${results.size} results")
        return dedupe(results, 15)
    }

    // ════════════════════════════════════════════════════
    //  4) Google Lite
    // ════════════════════════════════════════════════════

    private fun searchGoogleLite(query: String): List<SearchResult> {
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val html = httpGet(
                url    = "https://www.google.com/search?q=$encoded&num=10&hl=ar&gl=sa",
                accept = "text/html"
            ) ?: return@runCatching emptyList()

            val r = parseGoogleHtml(html)
            Log.i(TAG, "Google Lite → ${r.size} results")
            r
        }.getOrDefault(emptyList())
    }

    private fun parseGoogleHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val seen    = mutableSetOf<String>()

        val pattern = Regex(
            """<a[^>]*href="/url\?q=([^&"]+)[^"]*"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        for (match in pattern.findAll(html).take(20)) {
            val rawUrl   = match.groupValues[1]
            val title    = cleanHtml(match.groupValues[2])
            val cleanUrl = runCatching {
                URLDecoder.decode(rawUrl, "UTF-8")
            }.getOrDefault(rawUrl)

            if (title.isBlank() || !cleanUrl.startsWith("http")) continue
            if (cleanUrl.contains("google.com", ignoreCase = true)) continue
            if (cleanUrl in seen) continue

            seen.add(cleanUrl)
            results.add(SearchResult(title.take(150), cleanUrl, "", extractDomain(cleanUrl)))
            if (results.size >= 10) break
        }
        return results
    }

    // ════════════════════════════════════════════════════
    //  5) Archive.org
    // ════════════════════════════════════════════════════

    private fun searchArchive(query: String): List<SearchResult> {
        return runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val body = httpGet(
                url      = "https://archive.org/advancedsearch.php?q=$encoded" +
                           "&fl[]=identifier&fl[]=title&fl[]=description&output=json&rows=10",
                accept   = "application/json",
                lang     = null,
                customUA = "VisionAI/2.1"
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

                // description يكون أحياناً String وأحياناً JSONArray
                val description = when (val d = item.opt("description")) {
                    is String    -> d
                    is JSONArray -> (0 until d.length()).joinToString(" ") { idx ->
                        d.optString(idx, "")
                    }
                    else -> ""
                }

                if (title.isNotBlank() && identifier.isNotBlank()) {
                    out.add(SearchResult(
                        title   = title.take(150),
                        url     = "https://archive.org/details/$identifier",
                        snippet = cleanHtml(description).take(400),
                        source  = "Archive.org"
                    ))
                }
            }

            Log.i(TAG, "Archive → ${out.size} results")
            out
        }.getOrDefault(emptyList())
    }

    // ════════════════════════════════════════════════════
    //  6) Multi — كل المحركات بالتوازي
    // ════════════════════════════════════════════════════

    private suspend fun searchMulti(query: String): List<SearchResult> = supervisorScope {
        val jobs = listOf(
            async(Dispatchers.IO) {
                runCatching { searchSearXNG(query) }.getOrDefault(emptyList())
            },
            async(Dispatchers.IO) {
                runCatching { searchDuckDuckGo(query) }.getOrDefault(emptyList())
            },
            async(Dispatchers.IO) {
                runCatching { searchWikipedia(query) }.getOrDefault(emptyList())
            },
            async(Dispatchers.IO) {
                runCatching { searchGoogleLite(query) }.getOrDefault(emptyList())
            },
            async(Dispatchers.IO) {
                runCatching { searchArchive(query) }.getOrDefault(emptyList())
            }
        )

        val all = jobs.awaitAll().flatten()
        Log.i(TAG, "Multi → ${all.size} raw results before dedupe")
        dedupe(all, 25)
    }

    // ════════════════════════════════════════════════════
    //  محرك مخصص
    // ════════════════════════════════════════════════════

    suspend fun searchCustom(
        query: String,
        engine: CustomSearchEngine
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            runCatching {
                val encoded   = URLEncoder.encode(query.trim(), "UTF-8")
                val searchUrl = engine.urlTemplate.replace("{query}", encoded)

                val html = httpGet(
                    url    = searchUrl,
                    accept = "text/html,application/xhtml+xml"
                ) ?: return@runCatching searchDuckDuckGo(query)

                val parsed = parseGenericHtml(html, engine.nameAr.ifBlank { engine.name })
                if (parsed.isEmpty()) searchDuckDuckGo(query) else parsed
            }.getOrElse {
                runCatching { searchDuckDuckGo(query) }.getOrDefault(emptyList())
            }
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
    //  أدوات مساعدة
    // ════════════════════════════════════════════════════

    private fun parseGenericHtml(html: String, engineName: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val seen    = mutableSetOf<String>()

        val linkPattern = Regex(
            """<a[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        val skipDomains = listOf(
            "google.com", "bing.com", "yandex.com",
            "facebook.com", "twitter.com", "x.com", "instagram.com",
            "accounts.google", "support.google", "policies.google",
            "fonts.googleapis", "ajax.googleapis",
            ".css", ".js", ".png", ".jpg", ".gif", ".svg",
            "javascript:", "mailto:", "tel:"
        )

        for (match in linkPattern.findAll(html)) {
            val url      = match.groupValues[1]
            val rawTitle = cleanHtml(match.groupValues[2])

            val shouldSkip = skipDomains.any { url.contains(it, ignoreCase = true) }
                || rawTitle.length < 5
                || url in seen

            if (!shouldSkip) {
                seen.add(url)
                results.add(SearchResult(
                    title   = rawTitle.take(150),
                    url     = url,
                    snippet = extractNearbyText(html, url).take(400),
                    source  = "$engineName · ${extractDomain(url)}"
                ))
                if (results.size >= 15) break
            }
        }
        return results
    }

    private fun cleanHtml(text: String): String = text
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#x2F;", "/")
        .replace("&nbsp;", " ")
        .replace("\\s+".toRegex(), " ")
        .trim()

    private fun extractDDGUrl(raw: String): String = runCatching {
        when {
            raw.contains("uddg=") -> {
                val enc = Regex("uddg=([^&]+)").find(raw)
                    ?.groupValues?.getOrNull(1) ?: return@runCatching raw
                URLDecoder.decode(enc, "UTF-8")
            }
            raw.startsWith("//")   -> "https:$raw"
            raw.startsWith("http") -> raw
            else -> ""
        }
    }.getOrDefault(raw)

    private fun isBlockedUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("duckduckgo.com")
            || lower.contains("google.com")
            || lower.startsWith("javascript:")
            || lower.startsWith("mailto:")
            || lower.startsWith("tel:")
    }

    private fun extractNearbyText(html: String, url: String): String = runCatching {
        val idx   = html.indexOf(url).takeIf { it >= 0 } ?: return@runCatching ""
        val start = minOf(idx + url.length, html.length)
        val end   = minOf(start + 500, html.length)
        val after = cleanHtml(html.substring(start, end))
        if (after.length > 20) after.take(300) else ""
    }.getOrDefault("")

    private fun extractDomain(url: String): String = runCatching {
        URI(url).host?.removePrefix("www.") ?: url
    }.getOrDefault(url)

    private fun dedupe(list: List<SearchResult>, limit: Int): List<SearchResult> {
        val seen = mutableSetOf<String>()
        return list.filter { r ->
            val key = r.url.removeSuffix("/")
            if (key in seen) false else { seen.add(key); true }
        }.take(limit)
    }

    fun generateSearchQueries(keywords: List<String>): List<String> {
        if (keywords.isEmpty()) return emptyList()
        val queries = mutableListOf(keywords.take(3).joinToString(" "))
        keywords.take(5).filter { it.length > 3 }.forEach { queries.add(it) }
        return queries.distinct().take(6)
    }
}
