package com.nadrlab.visionai.ai

import com.nadrlab.visionai.domain.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object WebSearchEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ═══ بحث رئيسي — DuckDuckGo Instant Answer API ═══
    suspend fun search(query: String): List<SearchResult> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")

                // الطريقة 1: DuckDuckGo Instant Answer API
                val results = searchInstantApi(encoded)
                if (results.isNotEmpty()) return@withContext results

                // الطريقة 2: DuckDuckGo HTML
                val htmlResults = searchHtml(encoded)
                if (htmlResults.isNotEmpty()) return@withContext htmlResults

                // الطريقة 3: Wikipedia API
                val wikiResults = searchWikipedia(encoded)
                if (wikiResults.isNotEmpty()) return@withContext wikiResults

                // لا نتائج
                emptyList()
            } catch (e: Exception) {
                // محاولة أخيرة
                try {
                    searchWikipedia(URLEncoder.encode(query, "UTF-8"))
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }
    }

    // ═══ الطريقة 1: DuckDuckGo Instant Answer ═══
    private fun searchInstantApi(encoded: String): List<SearchResult> {
        return try {
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "VisionAI/2.0")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val json = JSONObject(body)

            val results = mutableListOf<SearchResult>()

            // النتيجة الرئيسية (Abstract)
            val abstract = json.optString("Abstract", "")
            val abstractUrl = json.optString("AbstractURL", "")
            val abstractSource = json.optString("AbstractSource", "")
            if (abstract.isNotBlank() && abstractUrl.isNotBlank()) {
                results.add(
                    SearchResult(
                        title = abstractSource.ifBlank { "DuckDuckGo" },
                        url = abstractUrl,
                        snippet = abstract.take(300),
                        source = extractDomain(abstractUrl)
                    )
                )
            }

            // Definition
            val definition = json.optString("Definition", "")
            val definitionUrl = json.optString("DefinitionURL", "")
            val definitionSource = json.optString("DefinitionSource", "")
            if (definition.isNotBlank() && definitionUrl.isNotBlank()) {
                results.add(
                    SearchResult(
                        title = definitionSource.ifBlank { "تعريف" },
                        url = definitionUrl,
                        snippet = definition.take(300),
                        source = extractDomain(definitionUrl)
                    )
                )
            }

            // Related Topics
            val topics = json.optJSONArray("RelatedTopics") ?: org.json.JSONArray()
            for (i in 0 until minOf(topics.length(), 8)) {
                val topic = topics.optJSONObject(i) ?: continue
                val text = topic.optString("Text", "")
                val firstUrl = topic.optString("FirstURL", "")
                if (text.isNotBlank() && firstUrl.isNotBlank()) {
                    results.add(
                        SearchResult(
                            title = text.take(80),
                            url = firstUrl,
                            snippet = text.take(300),
                            source = extractDomain(firstUrl)
                        )
                    )
                }
            }

            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ═══ الطريقة 2: DuckDuckGo HTML ═══
    private fun searchHtml(encoded: String): List<SearchResult> {
        return try {
            val url = "https://html.duckduckgo.com/html/?q=$encoded"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                .addHeader("Accept-Language", "ar,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: ""

            parseDuckDuckGoHtml(html)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseDuckDuckGoHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // نمط البحث عن النتائج
        val resultPattern = Regex(
            """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        val snippetPattern = Regex(
            """<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )

        val titleMatches = resultPattern.findAll(html).toList()
        val snippetMatches = snippetPattern.findAll(html).toList()

        for (i in titleMatches.indices.take(10)) {
            val titleMatch = titleMatches[i]
            val rawUrl = titleMatch.groupValues[1]
            val title = titleMatch.groupValues[2]
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim()

            val cleanUrl = extractRealUrl(rawUrl)

            val snippet = if (i < snippetMatches.size) {
                snippetMatches[i].groupValues[1]
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&#x27;", "'")
                    .trim()
            } else ""

            if (title.isNotBlank() && cleanUrl.isNotBlank()) {
                results.add(
                    SearchResult(
                        title = title.take(120),
                        url = cleanUrl,
                        snippet = snippet.take(300),
                        source = extractDomain(cleanUrl)
                    )
                )
            }
        }

        return results
    }

    // ═══ الطريقة 3: Wikipedia API ═══
    private fun searchWikipedia(encoded: String): List<SearchResult> {
        return try {
            val url = "https://ar.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&srlimit=5"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "VisionAI/2.0")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            val search = json.getJSONObject("query").optJSONArray("search") ?: org.json.JSONArray()

            val results = mutableListOf<SearchResult>()
            for (i in 0 until search.length()) {
                val item = search.getJSONObject(i)
                val title = item.optString("title", "")
                val snippet = item.optString("snippet", "")
                    .replace(Regex("<[^>]+>"), "")
                    .trim()
                val pageUrl = "https://ar.wikipedia.org/wiki/${URLEncoder.encode(title, "UTF-8").replace("+", "_")}"

                if (title.isNotBlank()) {
                    results.add(
                        SearchResult(
                            title = title,
                            url = pageUrl,
                            snippet = snippet.take(300),
                            source = "ويكيبيديا"
                        )
                    )
                }
            }

            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ═══ أدوات مساعدة ═══
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
            val host = java.net.URI(url).host ?: url
            host.removePrefix("www.")
        } catch (_: Exception) { url }
    }

    fun generateSearchQueries(keywords: List<String>): List<String> {
        if (keywords.isEmpty()) return emptyList()
        val queries = mutableListOf(keywords.take(3).joinToString(" "))
        keywords.take(5).filter { it.length > 3 }.forEach {
            queries.add(it)
        }
        return queries.distinct().take(5)
    }
}
