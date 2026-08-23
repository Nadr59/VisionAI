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

    suspend fun search(query: String): List<SearchResult> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "https://html.duckduckgo.com/html/?q=$encoded"

                val request = Request.Builder()
                    .url(url)
                    .addHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    .addHeader("Accept", "text/html")
                    .build()

                val response = client.newCall(request).execute()
                val html = response.body?.string() ?: ""
                parseHtmlResults(html)
            } catch (e: Exception) {
                searchInstant(query)
            }
        }
    }

    private fun parseHtmlResults(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val blockPattern = Regex(
            """<div class="result results_links results_links_deep web-result".*?</div>\s*</div>""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (block in blockPattern.findAll(html).take(10)) {
            val b = block.value
            val rawUrl = Regex("""<a rel="nofollow" class="result__a" href="([^"]+)"""")
                .find(b)?.groupValues?.get(1) ?: continue
            val cleanUrl = extractRealUrl(rawUrl)
            val title = Regex(
                """<a rel="nofollow" class="result__a"[^>]*>(.+?)</a>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(b)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace("&amp;", "&")
                ?.trim() ?: ""
            val snippet = Regex(
                """<a class="result__snippet"[^>]*>(.+?)</a>""",
                RegexOption.DOT_MATCHES_ALL
            ).find(b)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.replace("&amp;", "&")
                ?.trim() ?: ""
            val source = Regex("""<span class="result__url__domain">([^<]+)</span>""")
                .find(b)?.groupValues?.get(1)?.trim() ?: extractDomain(cleanUrl)
            if (title.isNotBlank() && cleanUrl.isNotBlank()) {
                results.add(SearchResult(title.take(120), cleanUrl, snippet.take(300), source))
            }
        }
        return results
    }

    private fun extractRealUrl(raw: String): String {
        return try {
            if (raw.contains("uddg=")) {
                val enc = Regex("uddg=([^&]+)").find(raw)?.groupValues?.get(1) ?: return raw
                java.net.URLDecoder.decode(enc, "UTF-8")
            } else if (raw.startsWith("//")) "https:$raw" else raw
        } catch (_: Exception) { raw }
    }

    private fun extractDomain(url: String): String {
        return try {
            (java.net.URI(url).host ?: url).removePrefix("www.")
        } catch (_: Exception) { url }
    }

    private suspend fun searchInstant(query: String): List<SearchResult> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val request = Request.Builder()
                    .url("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
                    .addHeader("User-Agent", "VisionAI/2.0")
                    .build()
                val body = client.newCall(request).execute().body?.string() ?: ""
                val json = JSONObject(body)
                val results = mutableListOf<SearchResult>()
                val abs = json.optString("Abstract", "")
                val absUrl = json.optString("AbstractURL", "")
                if (abs.isNotBlank() && absUrl.isNotBlank()) {
                    results.add(
                        SearchResult(
                            json.optString("AbstractSource", ""),
                            absUrl,
                            abs.take(300),
                            "DuckDuckGo"
                        )
                    )
                }
                val topics = json.optJSONArray("RelatedTopics") ?: org.json.JSONArray()
                for (i in 0 until minOf(topics.length(), 8)) {
                    val t = topics.optJSONObject(i) ?: continue
                    val text = t.optString("Text", "")
                    val fUrl = t.optString("FirstURL", "")
                    if (text.isNotBlank() && fUrl.isNotBlank()) {
                        results.add(SearchResult(text.take(80), fUrl, text.take(300), extractDomain(fUrl)))
                    }
                }
                results
            } catch (_: Exception) { emptyList() }
        }
    }

    fun generateSearchQueries(keywords: List<String>): List<String> {
        if (keywords.isEmpty()) return emptyList()
        val queries = mutableListOf(keywords.take(3).joinToString(" "))
        keywords.take(5).filter { it.length > 3 }.forEach {
            queries.add("$it official")
        }
        return queries.distinct().take(5)
    }
                               }
