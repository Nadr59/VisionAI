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
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): List<SearchResult> {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "VisionAI/1.0")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)

                val results = mutableListOf<SearchResult>()

                // Abstract
                val abstract = json.optString("Abstract", "")
                val abstractUrl = json.optString("AbstractURL", "")
                val abstractSource = json.optString("AbstractSource", "")
                if (abstract.isNotBlank() && abstractUrl.isNotBlank()) {
                    results.add(SearchResult(
                        title = abstractSource,
                        url = abstractUrl,
                        snippet = abstract.take(200),
                        source = "DuckDuckGo"
                    ))
                }

                // Related Topics
                val topics = json.optJSONArray("RelatedTopics") ?: org.json.JSONArray()
                for (i in 0 until minOf(topics.length(), 8)) {
                    val topic = topics.optJSONObject(i) ?: continue
                    val text = topic.optString("Text", "")
                    val firstUrl = topic.optString("FirstURL", "")
                    if (text.isNotBlank() && firstUrl.isNotBlank()) {
                        results.add(SearchResult(
                            title = text.take(80),
                            url = firstUrl,
                            snippet = text.take(200),
                            source = "DuckDuckGo"
                        ))
                    }
                }

                results
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    // ═══ استخراج الكلمات المفتاحية وإنشاء استعلامات ═══
    fun generateSearchQueries(keywords: List<String>, contentType: String): List<String> {
        if (keywords.isEmpty()) return emptyList()

        val queries = mutableListOf<String>()

        // Main query with top keywords
        val topKeywords = keywords.take(3).joinToString(" ")
        queries.add(topKeywords)

        // Individual keyword queries
        for (kw in keywords.take(5)) {
            if (kw.length > 3) {
                queries.add("$kw official")
                queries.add("$kw specifications")
            }
        }

        return queries.distinct().take(5)
    }
}
