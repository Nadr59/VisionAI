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
    private const val APP_ID = "vision-ai-01"

    // عميل عام — مهلة طويلة للتحليل
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // عميل خاص بالفحص — مهلة قصيرة
    private val statusClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private var settings: AppSettings? = null

    fun init(appSettings: AppSettings) {
        settings = appSettings
    }

    // ═══════════════════════════════════════════
    //  فحص حالة الخدمة — سريع
    // ═══════════════════════════════════════════

    data class ServiceStatus(
        val online: Boolean = false,
        val provider: String = "",
        val models: List<String> = emptyList(),
        val remaining: Int = -1,
        val error: String = ""
    )

    suspend fun checkStatus(): ServiceStatus = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("appId", APP_ID)
                put("prompt", "hi")
            }.toString()

            val request = Request.Builder()
                .url(ASK_URL)
                .addHeader("Content-Type", "application/json; charset=UTF-8")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = statusClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    try {
                        val json = JSONObject(responseBody)
                        if (json.optBoolean("success")) {
                            val provider = json.optString("provider", "VisionAI Cloud")
                            val remaining = json.optInt("remaining", -1)
                            val modelsArr = json.optJSONArray("models")
                            val models = mutableListOf<String>()
                            if (modelsArr != null) {
                                for (i in 0 until modelsArr.length()) {
                                    models.add(modelsArr.getString(i))
                                }
                            }
                            ServiceStatus(
                                online = true,
                                provider = provider,
                                models = models,
                                remaining = remaining
                            )
                        } else {
                            ServiceStatus(
                                online = false,
                                error = json.optString("error", "خطأ غير معروف")
                            )
                        }
                    } catch (e: Exception) {
                        // الاستجابة ليست JSON — السيرفر متصل لكن الاستجابة غريبة
                        ServiceStatus(online = true, provider = "متصل")
                    }
                }
                403 -> ServiceStatus(error = "التطبيق غير مصرح له بالوصول")
                429 -> ServiceStatus(error = "تم تجاوز الحد اليومي للطلبات")
                503 -> ServiceStatus(error = "لا توجد مفاتيح متاحة حالياً")
                else -> ServiceStatus(error = "خطأ ${response.code}")
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

                // مزود مخصص أولاً
                if (s.providerUrl.isNotBlank() && s.providerKey.isNotBlank()) {
                    val base64 = bitmapToBase64(bitmap, 70)
                    val result = callCustomProvider(
                        base64, prompt,
                        s.providerUrl, s.providerKey, s.providerModel
                    )
                    if (result.isSuccess) return@withContext result
                }

                // VisionAI Cloud
                val base64 = bitmapToBase64(bitmap, 70)
                callVisionCloud(base64, prompt)
            } catch (e: Exception) {
                Result.failure(Exception("خطأ في التحليل: ${e.message}"))
            }
        }

    // ═══════════════════════════════════════════
    //  محادثة نصية فقط
    // ═══════════════════════════════════════════

    suspend fun analyzeText(prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val s = settings
                    ?: return@withContext Result.failure(Exception("لم يتم تهيئة الإعدادات"))

                // مزود مخصص
                if (s.providerUrl.isNotBlank() && s.providerKey.isNotBlank()) {
                    val result = callCustomProviderText(
                        prompt,
                        s.providerUrl, s.providerKey, s.providerModel
                    )
                    if (result.isSuccess) return@withContext result
                }

                // VisionAI Cloud
                callVisionCloudText(prompt)
            } catch (e: Exception) {
                Result.failure(Exception("خطأ في المحادثة: ${e.message}"))
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

            parseCloudResponse(response.code, responseBody)
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

            parseCloudResponse(response.code, responseBody)
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
    //  تحليل استجابة السيرفر
    // ═══════════════════════════════════════════

    private fun parseCloudResponse(code: Int, body: String): Result<String> {
        return when (code) {
            200 -> {
                try {
                    val json = JSONObject(body)
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
                } catch (e: Exception) {
                    Result.failure(Exception("خطأ في قراءة الاستجابة"))
                }
            }
            403 -> Result.failure(Exception("التطبيق غير مصرح له بالوصول"))
            429 -> Result.failure(Exception("تم تجاوز الحد اليومي للطلبات"))
            503 -> Result.failure(Exception("لا توجد مفاتيح متاحة حالياً"))
            else -> Result.failure(Exception("خطأ ${code}"))
        }
    }

    // ═══════════════════════════════════════════
    //  مزود مخصص — صورة
    // ═══════════════════════════════════════════

    private fun callCustomProvider(
        base64: String, prompt: String,
        url: String, apiKey: String, model: String
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
                return Result.failure(Exception("مزود مخصص ${response.code}"))
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
        prompt: String, url: String, apiKey: String, model: String
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
                return Result.failure(Exception("مزود مخصص ${response.code}"))
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
