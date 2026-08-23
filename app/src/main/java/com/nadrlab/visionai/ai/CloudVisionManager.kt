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
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var settings: AppSettings? = null

    fun init(appSettings: AppSettings) {
        settings = appSettings
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val maxSize = 1024
        val scale = minOf(
            maxSize.toFloat() / bitmap.width,
            maxSize.toFloat() / bitmap.height,
            1f
        )
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else bitmap

        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    suspend fun analyze(bitmap: Bitmap, prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val base64 = bitmapToBase64(bitmap, 70)
                val s = settings
                    ?: return@withContext Result.failure(Exception("لم يتم تهيئة الإعدادات"))

                // ═══ 1. المزود المخصص أولاً ═══
                val providerUrl = s.providerUrl
                val providerKey = s.providerKey
                val providerModel = s.providerModel

                if (providerUrl.isNotBlank() && providerKey.isNotBlank()) {
                    val result = callCustomProvider(base64, prompt, providerUrl, providerKey, providerModel)
                    if (result.isSuccess) return@withContext result
                }

                // ═══ 2. ai-key-manager (مجاني) ═══
                val result = callKeyManager(base64, prompt)
                if (result.isSuccess) return@withContext result

                result
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun callCustomProvider(
        base64: String,
        prompt: String,
        url: String,
        apiKey: String,
        model: String
    ): Result<String> {
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
                put("model", model.ifBlank { "gpt-4o" })
                put("messages", messages)
                put("max_tokens", 1500)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return Result.failure(
                    Exception("مزود مخصص ${response.code}: ${responseBody.take(200)}")
                )
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

    private fun callKeyManager(base64: String, prompt: String): Result<String> {
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
                return Result.failure(
                    Exception("KeyManager ${response.code}: ${responseBody.take(200)}")
                )
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
