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

    private const val BASE_URL = "https://ai-key-manager.vercel.app/api"
    private const val VISION_URL = "$BASE_URL/vision"
    private const val ASK_URL = "$BASE_URL/ask"
    private const val STATUS_URL = "$BASE_URL/status"
    private const val APP_ID = "vision-ai-01"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var settings: AppSettings? = null

    fun init(appSettings: AppSettings) {
        settings = appSettings
    }

    // ═══════════════════════════════════════════
    //  فحص حالة الخدمة
    // ═══════════════════════════════════════════

    data class ServiceStatus(
        val online: Boolean = false,
        val provider: String = "",
        val models: List<String> = emptyList(),
        val remaining: Int = 0,
        val error: String = ""
    )

    suspend fun checkStatus(): ServiceStatus = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("appId", APP_ID)
            }.toString()

            val request = Request.Builder()
                .url(STATUS_URL)
                .addHeader("Content-Type", "application/json; charset=UTF-8")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.code == 200) {
                val json = JSONObject(responseBody)
                val modelsArray = json.optJSONArray("models") ?: org.json.JSONArray()
                val models = mutableListOf<String>()
                for (i in 0 until modelsArray.length()) {
                    models.add(modelsArray.getString(i))
                }
                ServiceStatus(
                    online = json.optBoolean("online", true),
                    provider = json.optString("provider", "unknown"),
                    models = models,
                    remaining = json.optInt("remaining", -1)
                )
            } else {
                ServiceStatus(error = "خطأ ${response.code}")
            }
        } catch (e: java.net.UnknownHostException) {
            ServiceStatus(error = "لا يوجد اتصال بالإنترنت")
        } catch (e: java.net.SocketTimeoutException) {
            ServiceStatus(error = "انتهت مهلة الاتصال")
        } catch (e: java.net.ConnectException) {
            ServiceStatus(error = "فشل الاتصال بالخادم")
        } catch (e: Exception) {
            ServiceStatus(error = e.message ?: "خطأ غير معروف")
        }
    }

    // ═══════════════════════════════════════════
    //  تحليل صورة
    // ═══════════════════════════════════════════

    suspend fun analyze(bitmap: Bitmap, prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val s = settings
                    ?: return@withContext Result.failure(Exception("لم يتم تهيئة الإعدادات"))

                // ═══ 1. مزود مخصص أولاً ═══
                if (s.providerUrl.isNotBlank() && s.providerKey.isNotBlank()) {
                    val base64 = bitmapToBase64(bitmap, 70)
                    val result = callCustomProvider(base64, prompt, s.providerUrl, s.providerKey, s.providerModel)
                    if (result.isSuccess) return@withContext result
                }

                // ═══ 2. VisionAI Cloud (مجاني) ═══
                val base64 = bitmapToBase64(bitmap, 70)
                val result = callVisionCloud(base64, prompt)
                if (result.isSuccess) return@withContext result

                result
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ═══════════════════════════════════════════
    //  محادثة نصية فقط (بدون صورة)
    // ═══════════════════════════════════════════

    suspend fun analyzeText(prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val s = settings
                    ?: return@withContext Result.failure(Exception("لم يتم تهيئة الإعدادات"))

                // ═══ 1. مزود مخصص ═══
                if (s.providerUrl.isNotBlank() && s.providerKey.isNotBlank()) {
                    val result = callCustomProviderText(prompt, s.providerUrl, s.providerKey, s.providerModel)
                    if (result.isSuccess) return@withContext result
                }

                // ═══ 2. VisionAI Cloud — ask endpoint ═══
                val result = callVisionCloudText(prompt)
                if (result.isSuccess) return@withContext result

                result
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ═══════════════════════════════════════════
    //  VisionAI Cloud — صورة
    // ═══════════════════════════════════════════

    private fun callVisionCloud(base64: String, prompt: String): Result<String> {
        return try {
            val body = JSONObject().apply {
                put("appId", APP_ID)
                put("prompt", prompt)
                put("image", "data:image/jpeg;base64,$base64")
            }.toString()

            val request = Request.Builder()
                .url(VISION_URL)
                .addHeader("Content-Type", "application/json; charset=UTF-8")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    if (json.optBoolean("success")) {
                        val text = json.optString("response", "")
                        if (text.isNotBlank()) {
                            Result.success(text)
                        } else {
                            Result.failure(Exception("لا يوجد محتوى في الاستجابة"))
                        }
                    } else {
                        Result.failure(Exception(json.optString("error", "خطأ غير معروف")))
                    }
                }
                403 -> Result.failure(Exception("التطبيق غير مصرح له بالوصول"))
                429 -> Result.failure(Exception("تم تجاوز الحد اليومي للطلبات"))
                503 -> Result.failure(Exception("لا توجد مفاتيح متاحة حالياً"))
                else -> Result.failure(Exception("خطأ ${response.code}: ${responseBody.take(200)}"))
            }
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception("لا يوجد اتصال بالإنترنت"))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(Exception("انتهت مهلة الاتصال — حاول مرة أخرى"))
        } catch (e: java.net.ConnectException) {
            Result.failure(Exception("فشل الاتصال بالخادم"))
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "خطأ غير معروف"))
        }
    }

    // ═══════════════════════════════════════════
    //  VisionAI Cloud — نص فقط
    // ═══════════════════════════════════════════

    private fun callVisionCloudText(prompt: String): Result<String> {
        return try {
            val body = JSONObject().apply {
                put("appId", APP_ID)
                put("prompt", prompt)
            }.toString()

            val request = Request.Builder()
                .url(ASK_URL)
                .addHeader("Content-Type", "application/json; charset=UTF-8")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    val json = JSONObject(responseBody)
                    if (json.optBoolean("success")) {
                        val text = json.optString("response", "")
                        if (text.isNotBlank()) {
                            Result.success(text)
                        } else {
                            Result.failure(Exception("لا يوجد محتوى"))
                        }
                    } else {
                        Result.failure(Exception(json.optString("error", "خطأ غير معروف")))
                    }
                }
                403 -> Result.failure(Exception("التطبيق غير مصرح له بالوصول"))
                429 -> Result.failure(Exception("تم تجاوز الحد اليومي للطلبات"))
                503 -> Result.failure(Exception("لا توجد مفاتيح متاحة حالياً"))
                else -> Result.failure(Exception("خطأ ${response.code}: ${responseBody.take(200)}"))
            }
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception("لا يوجد اتصال بالإنترنت"))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(Exception("انتهت مهلة الاتصال"))
        } catch (e: java.net.ConnectException) {
            Result.failure(Exception("فشل الاتصال بالخادم"))
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "خطأ غير معروف"))
        }
    }

    // ═══════════════════════════════════════════
    //  مزود مخصص — صورة
    // ═══════════════════════════════════════════

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
            }.toString()

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
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

    // ═══════════════════════════════════════════
    //  مزود مخصص — نص فقط
    // ═══════════════════════════════════════════

    private fun callCustomProviderText(
        prompt: String,
        url: String,
        apiKey: String,
        model: String
    ): Result<String> {
        return try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }

            val body = JSONObject().apply {
                put("model", model.ifBlank { "gpt-4o" })
                put("messages", messages)
                put("max_tokens", 1500)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
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

    // ═══════════════════════════════════════════
    //  Bitmap → Base64
    // ═══════════════════════════════════════════

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
}
