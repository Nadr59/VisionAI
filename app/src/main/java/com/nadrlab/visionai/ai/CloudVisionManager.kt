package com.nadrlab.visionai.ai

import android.graphics.Bitmap
import com.nadrlab.visionai.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object CloudVisionManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var settings: AppSettings? = null

    fun init(appSettings: AppSettings) {
        settings = appSettings
    }

    suspend fun analyze(bitmap: Bitmap, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val base64 = ImageProcessor.toBase64(bitmap, 70)
            val s = settings ?: return@withContext Result.failure(Exception("لم يتم تهيئة المزود"))

            // ═══ Try ZenMux direct first ═══
            if (s.zenmuxKey.isNotBlank()) {
                val result = callZenMux(base64, prompt, s)
                if (result.isSuccess) return@withContext result
            }

            // ═══ Try ai-key-manager ═══
            val result = callKeyManager(base64, prompt)
            if (result.isSuccess) return@withContext result

            // ═══ Return last error ═══
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══ ZenMux Direct ═══
    private fun callZenMux(base64: String, prompt: String, s: AppSettings): Result<String> {
        return try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64")
                            })
                        })
                    })
                })
            }

            val body = JSONObject().apply {
                put("model", s.zenmuxModel)
                put("messages", messages)
                put("max_tokens", 1000)
            }

            val request = Request.Builder()
                .url(s.zenmuxUrl)
                .addHeader("Authorization", "Bearer ${s.zenmuxKey}")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return Result.failure(Exception("ZenMux ${response.code}: ${responseBody.take(200)}"))
            }

            val json = JSONObject(responseBody)
            val content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            Result.success(content)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ═══ ai-key-manager ═══
    private fun callKeyManager(base64: String, prompt: String): Result<String> {
        return try {
            val s = settings ?: return Result.failure(Exception("لم يتم التهيئة"))

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64")
                            })
                        })
                    })
                })
            }

            val body = JSONObject().apply {
                put("appId", s.appId)
                put("prompt", prompt)
                put("image", "data:image/jpeg;base64,$base64")
                put("messages", messages)
            }

            val request = Request.Builder()
                .url("${s.serverUrl}/api/vision")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return Result.failure(Exception("KeyManager ${response.code}: ${responseBody.take(200)}"))
            }

            val json = JSONObject(responseBody)

            if (json.has("error")) {
                return Result.failure(Exception(json.getString("error")))
            }

            val content = json.optString("content", "")
            if (content.isBlank()) {
                return Result.failure(Exception("لا يوجد محتوى"))
            }

            Result.success(content)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
