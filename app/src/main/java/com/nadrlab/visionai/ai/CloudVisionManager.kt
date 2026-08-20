package com.nadrlab.visionai.ai

import android.graphics.Bitmap
import android.util.Base64
import com.nadrlab.visionai.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
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

    // ═══ Bitmap → Base64 ═══
    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    suspend fun analyze(bitmap: Bitmap, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val base64 = bitmapToBase64(bitmap, 70)
            val s = settings ?: return@withContext Result.failure(Exception("لم يتم تهيئة المزود"))

            // ═══ Try ZenMux direct first ═══
            if (s.zenmuxKey.isNotBlank()) {
                val result = callZenMux(base64, prompt, s)
                if (result.isSuccess) return@withContext result
            }

            // ═══ Try ai-key-manager ═══
            val result = callKeyManager(base64, prompt, s)
            if (result.isSuccess) return@withContext result

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
    private fun callKeyManager(base64: String, prompt: String, s: AppSettings): Result<String> {
        return try {
            val body = JSONObject().apply {
                put("appId", "vision-ai-01")
                put("prompt", prompt)
                put("image", "data:image/jpeg;base64,$base64")
            }

            val request = Request.Builder()
                .url("https://ai-key-manager.vercel.app/api/vision")
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
