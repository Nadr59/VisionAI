package com.nadrlab.visionai.ai

import com.nadrlab.visionai.domain.CustomSearchEngine
import com.nadrlab.visionai.domain.SearchEngine
import com.nadrlab.visionai.domain.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object WebSearchEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ═══════════════════════════════════════════
    //  بحث رئيسي
    // ═══════════════════════════════════════════

    suspend fun search(
        query: String,
        engine: SearchEngine = SearchEngine.SEARXNG
    ): List<SearchResult> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")

                when (engine) {
                    SearchEngine.SEARXNG -> searchSearXNG(query)
                    SearchEngine.DUCKDUCKGO -> searchDuckDuckGo(encoded)
                    SearchEngine.WIKIPEDIA -> searchWikipedia(encoded)
                    SearchEngine.GOOGLE_LITE -> searchGoogleLite(encoded)
                    SearchEngine.ARCHIVE -> searchArchive(encoded)
                    SearchEngine.MULTI -> searchMulti(query)
                }
            } catch (e: Exception) {
                try {
                    searchWikipedia(URLEncoder.encode(query, "UTF-8"))
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    //  1. SearXNG
    // ═══════════════════════════════════════════

    private val searxngInstances = listOf(
        "https://searx.be",
        "https://search.sapti.me",
        "https://searxng.site",
        "https://search.bus-hit.me",
        "https://priv.au",
        "https://search.ononoki.org"
    )

    private fun searchSearXNG(query: String): List<SearchResult> {
        for (instance in searxngInstances) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "$instance/search?q=$encoded&format=json&categories=general&language=ar&pageno=1"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "VisionAI/2.0")
                    .addHeader("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.code == 200 && body.isNotBlank()) {
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results") ?: JSONArray()
                    val parsed = mutableListOf<SearchResult>()

                    for (i in 0 until minOf(results.length(), 15)) {
                        val item = results.getJSONObject(i)
                        val title = item.optString("title", "")
                        val urlStr = item.optString("url", "")
                        val snippet = item.optString("content", "")
                        val eng = item.optString("engine", "")

                        if (title.isNotBlank() && urlStr.isNotBlank()) {
                            parsed.add(
                                SearchResult(
                                    title = title.take(150),
                                    url = urlStr,
                                    snippet = snippet.take(400),
                                    source = "${extractDomain(urlStr)} · $eng"
                                )
                            )
                        }
                    }

                    if (parsed.isNotEmpty()) return parsed
                }
            } catch (_: Exception) {
                continue
            }
        }
        return emptyList()
    }

    // ═══════════════════════════════════════════
    //  2. DuckDuckGo
    // ═══════════════════════════════════════════

    private fun searchDuckDuckGo(encoded: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        try {
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val request = Request.Builder().url(url)
                .addHeader("User-Agent", "VisionAI/2.0").build()
            val body = client.newCall(request).execute().body?.string() ?: ""
            val json = JSONObject(body)

            val abstract = json.optString("Abstract", "")
            val abstractUrl = json.optString("AbstractURL", "")
            if (abstract.isNotBlank() && abstractUrl.isNotBlank()) {
                results.add(
                    SearchResult(
                        title = json.optString("AbstractSource", "DuckDuckGo"),
                        url = abstractUrl,
                        snippet = abstract.take(400),
                        source = extractDomain(abstractUrl)
                    )
                )
            }

            val topics = json.optJSONArray("RelatedTopics") ?: JSONArray()
            for (i in 0 until minOf(topics.length(), 10)) {
                val t = topics.optJSONObject(i) ?: continue
                val text = t.optString("Text", "")
                val firstUrl = t.optString("FirstURL", "")
                if (text.isNotBlank() && firstUrl.isNotBlank()) {
                    results.add(
                        SearchResult(
                            title = text.take(100),
                            url = firstUrl,
                            snippet = text.take(400),
                            source = extractDomain(firstUrl)
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        if (results.isEmpty()) {
            try {
                val url = "https://html.duckduckgo.com/html/?q=$encoded"
                val request = Request.Builder().url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                    .build()
                val html = client.newCall(request).execute().body?.string() ?: ""
                results.addAll(parseDuckDuckGoHtml(html))
            } catch (_: Exception) {}
        }

        return results
    }

    private fun parseDuckDuckGoHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val titlePattern = Regex(
            """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val snippetPattern = Regex(
            """<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        val titles = titlePattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()

        for (i in titles.indices.take(10)) {
            val rawUrl = titles[i].groupValues[1]
            val title = titles[i].groupValues[2]
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .trim()
            val cleanUrl = extractRealUrl(rawUrl)
            val snippet = if (i < snippets.size) {
                snippets[i].groupValues[1]
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&amp;", "&")
                    .trim()
            } else ""

            if (title.isNotBlank() && cleanUrl.isNotBlank()) {
                results.add(
                    SearchResult(
                        title.take(120), cleanUrl, snippet.take(400), extractDomain(cleanUrl)
                    )
                )
            }
        }
        return results
    }

    // ═══════════════════════════════════════════
    //  3. Wikipedia
    // ═══════════════════════════════════════════

    private fun searchWikipedia(encoded: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // عربي
        try {
            val url = "https://ar.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&srlimit=5"
            val request = Request.Builder().url(url)
                .addHeader("User-Agent", "VisionAI/2.0").build()
            val body = client.newCall(request).execute().body?.string() ?: ""
            val search = JSONObject(body).getJSONObject("query").optJSONArray("search") ?: JSONArray()

            for (i in 0 until search.length()) {
                val item = search.getJSONObject(i)
                val title = item.optString("title", "")
                val snippet = item.optString("snippet", "").replace(Regex("<[^>]+>"), "").trim()
                val pageUrl = "https://ar.wikipedia.org/wiki/${URLEncoder.encode(title, "UTF-8").replace("+", "_")}"
                if (title.isNotBlank()) {
                    results.add(SearchResult(title, pageUrl, snippet.take(400), "ويكيبيديا عربي"))
                }
            }
        } catch (_: Exception) {}

        // إنجليزي
        try {
            val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&srlimit=5"
            val request = Request.Builder().url(url)
                .addHeader("User-Agent", "VisionAI/2.0").build()
            val body = client.newCall(request).execute().body?.string() ?: ""
            val search = JSONObject(body).getJSONObject("query").optJSONArray("search") ?: JSONArray()

            for (i in 0 until search.length()) {
                val item = search.getJSONObject(i)
                val title = item.optString("title", "")
                val snippet = item.optString("snippet", "").replace(Regex("<[^>]+>"), "").trim()
                val pageUrl = "https://en.wikipedia.org/wiki/${URLEncoder.encode(title, "UTF-8").replace("+", "_")}"
                if (title.isNotBlank()) {
                    results.add(SearchResult(title, pageUrl, snippet.take(400), "ويكيبيديا EN"))
                }
            }
        } catch (_: Exception) {}

        return results
    }

    // ═══════════════════════════════════════════
    //  4. Google Lite
    // ═══════════════════════════════════════════

    private fun searchGoogleLite(encoded: String): List<SearchResult> {
        return try {
            val url = "https://www.google.com/search?q=$encoded&num=10&hl=ar"
            val request = Request.Builder().url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                .addHeader("Accept-Language", "ar,en;q=0.9")
                .build()
            val html = client.newCall(request).execute().body?.string() ?: ""
            parseGoogleHtml(html)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseGoogleHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val linkPattern = Regex(
            """<a[^>]*href="/url\?q=([^&"]+)[^"]*"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val matches = linkPattern.findAll(html).toList()

        for (match in matches.take(10)) {
            val rawUrl = match.groupValues[1]
            val title = match.groupValues[2]
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .trim()

            val cleanUrl = try {
                java.net.URLDecoder.decode(rawUrl, "UTF-8")
            } catch (_: Exception) { rawUrl }

            if (title.isNotBlank() && cleanUrl.isNotBlank() && !cleanUrl.contains("google.com")) {
                results.add(
                    SearchResult(
                        title = title.take(150),
                        url = cleanUrl,
                        snippet = "",
                        source = extractDomain(cleanUrl)
                    )
                )
            }
        }

        return results
    }

    // ═══════════════════════════════════════════
    //  5. Archive.org
    // ═══════════════════════════════════════════

    private fun searchArchive(encoded: String): List<SearchResult> {
        return try {
            val cdxUrl = "https://web.archive.org/cdx/search/cdx?url=*$encoded*&output=json&limit=10&fl=original,timestamp,statuscode"
            val request = Request.Builder().url(cdxUrl)
                .addHeader("User-Agent", "VisionAI/2.0").build()
            val body = client.newCall(request).execute().body?.string() ?: ""

            if (body.isBlank() || body == "[]") return emptyList()

            val arr = JSONArray(body)
            val results = mutableListOf<SearchResult>()
            val seen = mutableSetOf<String>()

            for (i in 1 until arr.length()) {
                val row = arr.getJSONArray(i)
                val original = row.optString(0, "")
                val timestamp = row.optString(1, "")

                if (original.isNotBlank() && original !in seen) {
                    seen.add(original)
                    val archiveUrl = "https://web.archive.org/web/$timestamp/$original"
                    results.add(
                        SearchResult(
                            title = original.take(120),
                            url = archiveUrl,
                            snippet = "أرشيف من ${timestamp.take(4)}-${timestamp.drop(4).take(2)}-${timestamp.drop(6).take(2)}",
                            source = "Archive.org"
                        )
                    )
                }
            }

            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ═══════════════════════════════════════════
    //  6. بحث شامل — كل المحركات بالتوازي
    // ═══════════════════════════════════════════

    private suspend fun searchMulti(query: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")

        return coroutineScope {
            val deferreds = listOf(
                async(Dispatchers.IO) {
                    try { searchSearXNG(query) } catch (_: Exception) { emptyList() }
                },
                async(Dispatchers.IO) {
                    try { searchDuckDuckGo(encoded) } catch (_: Exception) { emptyList() }
                },
                async(Dispatchers.IO) {
                    try { searchWikipedia(encoded) } catch (_: Exception) { emptyList() }
                },
                async(Dispatchers.IO) {
                    try { searchGoogleLite(encoded) } catch (_: Exception) { emptyList() }
                }
            )

            val allResults = deferreds.awaitAll().flatten()

            val seen = mutableSetOf<String>()
            allResults.filter { result ->
                val key = result.url.removeSuffix("/")
                if (key in seen) false else { seen.add(key); true }
            }.take(20)
        }
    }

    // ═══════════════════════════════════════════
    //  محرك مخصص
    // ═══════════════════════════════════════════

    suspend fun searchCustom(
        query: String,
        engine: CustomSearchEngine
    ): List<SearchResult> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val searchUrl = engine.urlTemplate.replace("{query}", encoded)

                val request = Request.Builder()
                    .url(searchUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                    .addHeader("Accept-Language", "ar,en;q=0.9")
                    .addHeader("Accept", "text/html")
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: ""

                if (html.isBlank()) return@withContext emptyList()

                parseGenericHtml(html, engine.name)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private fun parseGenericHtml(html: String, engineName: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val seen = mutableSetOf<String>()

        val linkPattern = Regex(
            """<a[^>]*href="(https?://[^"]+)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        val matches = linkPattern.findAll(html)

        for (match in matches) {
            val url = match.groupValues[1]
            val rawTitle = match.groupValues[2]
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#x2F;", "/")
                .replace("&nbsp;", " ")
                .trim()

            val skipDomains = listOf(
                "google.com", "bing.com", "yandex.com",
                "facebook.com", "twitter.com", "instagram.com",
                "youtube.com/subscribe", "play.google.com",
                "apple.com/itunes", "accounts.google",
                "support.google", "policies.google",
                "fonts.googleapis", "ajax.googleapis",
                ".css", ".js", ".png", ".jpg", ".gif", ".svg",
                "javascript:", "mailto:", "tel:"
            )

            val shouldSkip = skipDomains.any { url.contains(it, ignoreCase = true) } ||
                    rawTitle.length < 5 ||
                    url in seen

            if (!shouldSkip && rawTitle.isNotBlank()) {
                seen.add(url)

                val snippet = extractNearbyText(html, url)

                results.add(
                    SearchResult(
                        title = rawTitle.take(150),
                        url = url,
                        snippet = snippet.take(400),
                        source = "$engineName · ${extractDomain(url)}"
                    )
                )

                if (results.size >= 15) break
            }
        }

        return results
    }

    private fun extractNearbyText(html: String, url: String): String {
        return try {
            val idx = html.indexOf(url)
            if (idx < 0) return ""

            val afterEnd = minOf(idx + url.length + 500, html.length)
            val after = html.substring(minOf(idx + url.length, html.length), afterEnd)
            val textAfter = after.replace(Regex("<[^>]+>"), " ").replace("\\s+".toRegex(), " ").trim()

            if (textAfter.length > 20) {
                textAfter.take(300)
            } else {
                val beforeStart = maxOf(0, idx - 500)
                val before = html.substring(beforeStart, idx)
                val textBefore = before.replace(Regex("<[^>]+>"), " ").replace("\\s+".toRegex(), " ").trim()
                textBefore.takeLast(300)
            }
        } catch (_: Exception) {
            ""
        }
    }

    // ═══════════════════════════════════════════
    //  بحث شامل مع محركات مخصصة
    // ═══════════════════════════════════════════

    suspend fun searchWithCustom(
        query: String,
        engine: SearchEngine,
        customEngineList: List<CustomSearchEngine> = emptyList()
    ): List<SearchResult> {
        return when (engine) {
            SearchEngine.MULTI -> {
                val standardResults = searchMulti(query)

                val customResults = if (customEngineList.isNotEmpty()) {
                    coroutineScope {
                        customEngineList.map { ce ->
                            async(Dispatchers.IO) {
                                try { searchCustom(query, ce) } catch (_: Exception) { emptyList() }
                            }
                        }.awaitAll().flatten()
                    }
                } else {
                    emptyList()
                }

                val all = standardResults + customResults
                val seen = mutableSetOf<String>()
                all.filter { r ->
                    val key = r.url.removeSuffix("/")
                    if (key in seen) false else { seen.add(key); true }
                }.take(25)
            }
            else -> search(query, engine)
        }
    }

    // ═══════════════════════════════════════════
    //  أدوات مساعدة
    // ═══════════════════════════════════════════

    private fun extractRealUrl(raw: String): String {
        return try {
            when {
                raw.contains("uddg=") -> {
                    val enc = Regex("uddg=([^&]+)").find(raw)?.groupValues?.get(1) ?: return raw
                    java.net.URLDecoder.decode(enc, "UTF-8")
                }
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("/") -> ""
                else -> raw
            }
        } catch (_: Exception) { raw }
    }

    private fun extractDomain(url: String): String {
        return try {
            java.net.URI(url).host?.removePrefix("www.") ?: url
        } catch (_: Exception) { url }
    }

    fun generateSearchQueries(keywords: List<String>): List<String> {
        if (keywords.isEmpty()) return emptyList()
        val queries = mutableListOf(keywords.take(3).joinToString(" "))
        keywords.take(5).filter { it.length > 3 }.forEach { queries.add(it) }
        return queries.distinct().take(5)
    }
}
