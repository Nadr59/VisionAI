   

package com.nadrlab.visionai.ai

import com.nadrlab.visionai.domain.CustomSearchEngine
import com.nadrlab.visionai.domain.SearchEngine
import com.nadrlab.visionai.domain.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope          // ← أضف هذا
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType    // ← أضف هذا
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody // ← أضف هذا
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
object WebSearchEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    // ═══════════════════════════════════════════
    //  بحث رئيسي
    // ═══════════════════════════════════════════

    suspend fun search(
        query: String,
        engine: SearchEngine = SearchEngine.SEARXNG
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()

        return withContext(Dispatchers.IO) {
            try {
                when (engine) {
                    SearchEngine.SEARXNG -> searchSearXNG(q)
                    SearchEngine.DUCKDUCKGO -> searchDuckDuckGo(q)
                    SearchEngine.WIKIPEDIA -> searchWikipedia(q)
                    SearchEngine.GOOGLE_LITE -> searchGoogleLite(q)
                    SearchEngine.ARCHIVE -> searchArchive(q)
                    SearchEngine.MULTI -> searchMulti(q)
                }
            } catch (_: Exception) {
                try { searchWikipedia(q) } catch (_: Exception) { emptyList() }
            }
        }
    }

    // ═══════════════════════════════════════════
    //  1. SearXNG — حالات محدثة
    // ═══════════════════════════════════════════

    private val searxngInstances = listOf(
        "https://search.inetol.net",
        "https://searx.tuxcloud.net",
        "https://search.mdosch.de",
        "https://searx.be",
        "https://search.sapti.me",
        "https://searxng.site",
        "https://search.bus-hit.me",
        "https://priv.au",
        "https://search.ononoki.org",
        "https://searx.tiekoetter.com",
        "https://search.hbubli.de",
        "https://search.datura.network"
    )

    private fun searchSearXNG(query: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")

        for (instance in searxngInstances) {
            try {
                val url = "$instance/search?q=$encoded&format=json&categories=general&language=auto"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", UA)
                    .addHeader("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.code == 200 && body.startsWith("{")) {
                    val json = JSONObject(body)
                    val arr = json.optJSONArray("results") ?: JSONArray()
                    val parsed = mutableListOf<SearchResult>()

                    for (i in 0 until minOf(arr.length(), 15)) {
                        val item = arr.getJSONObject(i)
                        val title = item.optString("title", "")
                        val resultUrl = item.optString("url", "")
                        val content = item.optString("content", "")
                        val eng = item.optString("engine", "")

                        if (title.isNotBlank() && resultUrl.startsWith("http")) {
                            parsed.add(
                                SearchResult(
                                    title = title.take(150),
                                    url = resultUrl,
                                    snippet = content.take(400),
                                    source = "${extractDomain(resultUrl)} · $eng"
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
    //  2. DuckDuckGo — HTML فقط (الوحيد الموثوق)
    // ═══════════════════════════════════════════

    private fun searchDuckDuckGo(query: String): List<SearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")

        // ═══ الطريقة 1: DuckDuckGo HTML (بحث ويب حقيقي) ═══
        try {
            val url = "https://html.duckduckgo.com/html/"
            val formBody = "q=$encoded".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", UA)
                .addHeader("Accept", "text/html")
                .addHeader("Accept-Language", "ar,en;q=0.9")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""

            if (html.isNotBlank() && html.contains("result__a")) {
                val results = parseDuckDuckGoHtml(html)
                if (results.isNotEmpty()) return results
            }
        } catch (_: Exception) {}

        // ═══ الطريقة 2: DuckDuckGo GET ═══
        try {
            val url = "https://html.duckduckgo.com/html/?q=$encoded"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", UA)
                .addHeader("Accept", "text/html")
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""

            if (html.isNotBlank() && html.contains("result__a")) {
                val results = parseDuckDuckGoHtml(html)
                if (results.isNotEmpty()) return results
            }
        } catch (_: Exception) {}

        // ═══ الطريقة 3: Instant Answer API (احتياطي) ═══
        try {
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", UA)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (body.startsWith("{")) {
                val json = JSONObject(body)
                val results = mutableListOf<SearchResult>()

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

                if (results.isNotEmpty()) return results
            }
        } catch (_: Exception) {}

        return emptyList()
    }

    private fun parseDuckDuckGoHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // البحث عن كل result block
        val resultBlockPattern = Regex(
            """<div[^>]*class="[^"]*result[^"]*web-result[^"]*"[^>]*>(.*?)</div>\s*</div>""",
            RegexOption.DOT_MATCHES_ALL
        )

        // نمط العنوان
        val titlePattern = Regex(
            """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        // نمط المقتطف
        val snippetPattern = Regex(
            """<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        val titles = titlePattern.findAll(html).toList()
        val snippets = snippetPattern.findAll(html).toList()

        for (i in titles.indices.take(15)) {
            val rawUrl = titles[i].groupValues[1]
            val title = cleanHtml(titles[i].groupValues[2])
            val cleanUrl = extractDDGUrl(rawUrl)
            val snippet = if (i < snippets.size) cleanHtml(snippets[i].groupValues[1]) else ""

            if (title.isNotBlank() && cleanUrl.isNotBlank() && cleanUrl.startsWith("http")) {
                results.add(
                    SearchResult(
                        title = title.take(120),
                        url = cleanUrl,
                        snippet = snippet.take(400),
                        source = extractDomain(cleanUrl)
                    )
                )
            }
        }

        return results
    }

    private fun cleanHtml(text: String): String {
        return text
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#x2F;", "/")
            .replace("&nbsp;", " ")
            .trim()
    }

    private fun extractDDGUrl(raw: String): String {
        return try {
            when {
                raw.contains("uddg=") -> {
                    val enc = Regex("uddg=([^&]+)").find(raw)?.groupValues?.get(1) ?: return raw
                    URLDecoder.decode(enc, "UTF-8")
                }
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http") -> raw
                else -> ""
            }
        } catch (_: Exception) { raw }
    }

    // ═══════════════════════════════════════════
    //  3. Wikipedia — عربي + إنجليزي
    // ═══════════════════════════════════════════

    private fun searchWikipedia(query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val encoded = URLEncoder.encode(query, "UTF-8")

        // عربي
        try {
            val url = "https://ar.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&srlimit=5&utf8=1"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "VisionAI/2.0 (Android; contact@example.com)")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (body.startsWith("{") && !body.contains("\"error\"")) {
                val json = JSONObject(body)
                val queryObj = json.optJSONObject("query")
                val searchArr = queryObj?.optJSONArray("search") ?: JSONArray()

                for (i in 0 until searchArr.length()) {
                    val item = searchArr.getJSONObject(i)
                    val title = item.optString("title", "")
                    val snippet = item.optString("snippet", "").replace(Regex("<[^>]+>"), "").trim()
                    val pageUrl = "https://ar.wikipedia.org/wiki/${URLEncoder.encode(title, "UTF-8").replace("+", "_")}"

                    if (title.isNotBlank()) {
                        results.add(SearchResult(title, pageUrl, snippet.take(400), "ويكيبيديا عربي"))
                    }
                }
            }
        } catch (_: Exception) {}

        // إنجليزي
        try {
            val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&srlimit=5&utf8=1"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "VisionAI/2.0 (Android; contact@example.com)")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (body.startsWith("{") && !body.contains("\"error\"")) {
                val json = JSONObject(body)
                val queryObj = json.optJSONObject("query")
                val searchArr = queryObj?.optJSONArray("search") ?: JSONArray()

                for (i in 0 until searchArr.length()) {
                    val item = searchArr.getJSONObject(i)
                    val title = item.optString("title", "")
                    val snippet = item.optString("snippet", "").replace(Regex("<[^>]+>"), "").trim()
                    val pageUrl = "https://en.wikipedia.org/wiki/${URLEncoder.encode(title, "UTF-8").replace("+", "_")}"

                    if (title.isNotBlank()) {
                        results.add(SearchResult(title, pageUrl, snippet.take(400), "ويكيبيديا EN"))
                    }
                }
            }
        } catch (_: Exception) {}

        return results
    }

    // ═══════════════════════════════════════════
    //  4. Google Lite
    // ═══════════════════════════════════════════

    private fun searchGoogleLite(query: String): List<SearchResult> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.google.com/search?q=$encoded&num=10&hl=ar&gl=sa"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", UA)
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
        val seen = mutableSetOf<String>()

        val linkPattern = Regex(
            """<a[^>]*href="/url\?q=([^&"]+)[^"]*"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        for (match in linkPattern.findAll(html).take(10)) {
            val rawUrl = match.groupValues[1]
            val title = cleanHtml(match.groupValues[2])
            val cleanUrl = try { URLDecoder.decode(rawUrl, "UTF-8") } catch (_: Exception) { rawUrl }

            if (title.isNotBlank() && cleanUrl.startsWith("http") &&
                !cleanUrl.contains("google.com") && cleanUrl !in seen) {
                seen.add(cleanUrl)
                results.add(SearchResult(title.take(150), cleanUrl, "", extractDomain(cleanUrl)))
            }
        }

        return results
    }

    // ═══════════════════════════════════════════
    //  5. Archive.org
    // ═══════════════════════════════════════════

    private fun searchArchive(query: String): List<SearchResult> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "https://archive.org/advancedsearch.php?q=$encoded&fl[]=identifier&fl[]=title&fl[]=description&output=json&rows=10"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "VisionAI/2.0")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (body.startsWith("{")) {
                val json = JSONObject(body)
                val responseObj = json.optJSONObject("response")
                val docs = responseObj?.optJSONArray("docs") ?: JSONArray()
                val results = mutableListOf<SearchResult>()

                for (i in 0 until docs.length()) {
                    val item = docs.getJSONObject(i)
                    val title = item.optString("title", "")
                    val identifier = item.optString("identifier", "")
                    val description = item.optString("description", "")

                    if (title.isNotBlank() && identifier.isNotBlank()) {
                        results.add(
                            SearchResult(
                                title = title.take(150),
                                url = "https://archive.org/details/$identifier",
                                snippet = description.take(400),
                                source = "Archive.org"
                            )
                        )
                    }
                }

                return results
            }

            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ═══════════════════════════════════════════
    //  6. بحث شامل
    // ═══════════════════════════════════════════

    private suspend fun searchMulti(query: String): List<SearchResult> {
        return coroutineScope {
            val deferreds = listOf(
                async(Dispatchers.IO) { try { searchSearXNG(query) } catch (_: Exception) { emptyList() } },
                async(Dispatchers.IO) { try { searchDuckDuckGo(query) } catch (_: Exception) { emptyList() } },
                async(Dispatchers.IO) { try { searchWikipedia(query) } catch (_: Exception) { emptyList() } },
                async(Dispatchers.IO) { try { searchGoogleLite(query) } catch (_: Exception) { emptyList() } },
                async(Dispatchers.IO) { try { searchArchive(query) } catch (_: Exception) { emptyList() } }
            )

            val allResults = deferreds.awaitAll().flatten()
            val seen = mutableSetOf<String>()
            allResults.filter { r ->
                val key = r.url.removeSuffix("/")
                if (key in seen) false else { seen.add(key); true }
            }.take(25)
        }
    }

    // ═══════════════════════════════════════════
    //  محرك مخصص — مُصحح
    // ═══════════════════════════════════════════

    suspend fun searchCustom(
        query: String,
        engine: CustomSearchEngine
    ): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query.trim(), "UTF-8")
                val searchUrl = engine.urlTemplate.replace("{query}", encoded)

                val request = Request.Builder()
                    .url(searchUrl)
                    .addHeader("User-Agent", UA)
                    .addHeader("Accept-Language", "ar,en;q=0.9")
                    .addHeader("Accept", "text/html,application/xhtml+xml")
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: ""
                val code = response.code

                if (html.isBlank() || code != 200) {
                    return@withContext emptyList<SearchResult>()
                }

                // تحليل HTML
                val results = parseGenericHtml(html, engine.nameAr.ifBlank { engine.name })

                // إذا لم نجد نتائج، جرب البحث في DuckDuckGo بدل ذلك
                if (results.isEmpty()) {
                    return@withContext searchDuckDuckGo(query)
                }

                results
            } catch (_: Exception) {
                // في حالة الخطأ، جرب DuckDuckGo
                try { searchDuckDuckGo(query) } catch (_: Exception) { emptyList() }
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

        val skipDomains = listOf(
            "google.com", "bing.com", "yandex.com",
            "facebook.com", "twitter.com", "instagram.com",
            "play.google.com", "apple.com/itunes",
            "accounts.google", "support.google", "policies.google",
            "fonts.googleapis", "ajax.googleapis",
            ".css", ".js", ".png", ".jpg", ".gif", ".svg",
            "javascript:", "mailto:", "tel:"
        )

        for (match in linkPattern.findAll(html)) {
            val url = match.groupValues[1]
            val rawTitle = cleanHtml(match.groupValues[2])

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
            val textAfter = after.replace(Regex("<[^>]+>"), " ")
                .replace("\\s+".toRegex(), " ").trim()

            if (textAfter.length > 20) {
                textAfter.take(300)
            } else {
                val beforeStart = maxOf(0, idx - 500)
                val before = html.substring(beforeStart, idx)
                val textBefore = before.replace(Regex("<[^>]+>"), " ")
                    .replace("\\s+".toRegex(), " ").trim()
                textBefore.takeLast(300)
            }
        } catch (_: Exception) { "" }
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
                } else emptyList()

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
