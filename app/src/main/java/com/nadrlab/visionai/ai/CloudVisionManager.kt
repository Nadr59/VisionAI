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

// ═══ استجابة السيرفر — مطابقة لـ SiteManager ═══
data class ApiResponse(
    val success: Boolean = false,
    val response: String = "",
    val remaining: Int = 0,
    val provider: String = "",
    val error: String = ""
)

object CloudVisionManager {

    private const val BASE_URL = "https://ai-key-manager.vercel.app/api"
    private const val ASK_URL = "$BASE_URL/ask"
    private const val APP_ID = "vision-ai-01"

    // ═══ عميل واحد — نفس إعدادات SiteManager ═══
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
    //  فحص حالة الخدمة — طلب بسيط عبر /api/ask
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
            val response = doAsk("ping")
            if (response.success) {
                ServiceStatus(
                    online = true,
                    provider = response.provider,
                    remaining = response.remaining
                )
            } else {
                ServiceStatus(error = response.error.ifBlank { "خطأ غير معروف" })
            }
        } catch (e: Exception) {
            ServiceStatus(error = e.message ?: "خطأ غير معروف")
        }
    }

    // ═══════════════════════════════════════════
    //  تحليل صورة — عبر /api/ask مع image
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
                    // لا نعود بصمت لـ VisionAI Cloud: يجب أن يظهر خطأ المزود المخصص الحقيقي
                    return@withContext result
                }

                // VisionAI Cloud — /api/ask مع صورة
                val base64 = bitmapToBase64(bitmap, 70)
                val response = doAskWithImage(prompt, base64)

                if (response.success && response.response.isNotBlank()) {
                    Result.success(response.response)
                } else {
                    Result.failure(Exception(response.error.ifBlank { "لا يوجد محتوى" }))
                }
            } catch (e: Exception) {
                Result.failure(Exception("خطأ في التحليل: ${e.message}"))
            }
        }

    // ═══════════════════════════════════════════
    //  محادثة نصية فقط — عبر /api/ask
    // ═══════════════════════════════════════════


                    // ═══════════════════════════════════════════
    //  محادثة نصية فقط — مع معالجة أخطاء كاملة
    // ═══════════════════════════════════════════

    suspend fun analyzeText(prompt: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val s = settings
                    ?: return@withContext Result.failure(Exception("لم يتم تهيئة الإعدادات"))

                // مزود مخصص أولاً
                if (s.providerUrl.isNotBlank() && s.providerKey.isNotBlank()) {
                    val result = callCustomProviderText(
                        prompt,
                        s.providerUrl, s.providerKey, s.providerModel
                    )
                    // لا نعود بصمت لـ VisionAI Cloud: يجب أن يظهر خطأ المزود المخصص الحقيقي
                    return@withContext result
                }

                // VisionAI Cloud — /api/ask
                val response = doAsk(prompt)

                if (response.success && response.response.isNotBlank()) {
                    Result.success(response.response)
                } else {
                    val errorMsg = response.error.ifBlank {
                        if (!response.success) "الخدمة غير متاحة — تأكد من تسجيل التطبيق"
                        else "لا يوجد محتوى"
                    }
                    Result.failure(Exception(errorMsg))
                }
            } catch (e: Exception) {
                Result.failure(Exception("خطأ في المحادثة: ${e.message ?: "غير معروف"}"))
            }
        }

    // ═══════════════════════════════════════════
    //  /api/ask — نص فقط (مطابق لـ SiteManager)
    // ═══════════════════════════════════════════

    private fun doAsk(prompt: String): ApiResponse {
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
                    try {
                        val json = JSONObject(responseBody)
                        ApiResponse(
                            success = json.optBoolean("success", false),
                            response = json.optString("response", ""),
                            remaining = json.optInt("remaining", 0),
                            provider = json.optString("provider", ""),
                            error = json.optString("error", "")
                        )
                    } catch (e: Exception) {
                        ApiResponse(error = "خطأ في قراءة الاستجابة")
                    }
                }
                403 -> ApiResponse(error = "التطبيق غير مصرح له بالوصول")
                429 -> ApiResponse(error = "تم تجاوز الحد اليومي للطلبات")
                503 -> ApiResponse(error = "لا توجد مفاتيح متاحة حالياً")
                else -> ApiResponse(error = "خطأ ${response.code}: ${responseBody.take(150)}")
            }
        } catch (e: java.net.UnknownHostException) {
            ApiResponse(error = "لا يوجد اتصال بالإنترنت")
        } catch (e: java.net.SocketTimeoutException) {
            ApiResponse(error = "انتهت مهلة الاتصال — حاول مرة أخرى")
        } catch (e: java.net.ConnectException) {
            ApiResponse(error = "فشل الاتصال بالخادم")
        } catch (e: Exception) {
            ApiResponse(error = e.message ?: "خطأ غير معروف")
        }
    }

    // ═══════════════════════════════════════════
    //  /api/ask — مع صورة
    // ═══════════════════════════════════════════

    private fun doAskWithImage(prompt: String, base64: String): ApiResponse {
        return try {
            val body = JSONObject().apply {
                put("appId", APP_ID)
                put("prompt", prompt)
                put("image", "data:image/jpeg;base64,$base64")
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
                    try {
                        val json = JSONObject(responseBody)
                        ApiResponse(
                            success = json.optBoolean("success", false),
                            response = json.optString("response", ""),
                            remaining = json.optInt("remaining", 0),
                            provider = json.optString("provider", ""),
                            error = json.optString("error", "")
                        )
                    } catch (e: Exception) {
                        ApiResponse(error = "خطأ في قراءة الاستجابة")
                    }
                }
                403 -> ApiResponse(error = "التطبيق غير مصرح له بالوصول")
                429 -> ApiResponse(error = "تم تجاوز الحد اليومي للطلبات")
                503 -> ApiResponse(error = "لا توجد مفاتيح متاحة حالياً")
                else -> ApiResponse(error = "خطأ ${response.code}: ${responseBody.take(150)}")
            }
        } catch (e: java.net.UnknownHostException) {
            ApiResponse(error = "لا يوجد اتصال بالإنترنت")
        } catch (e: java.net.SocketTimeoutException) {
            ApiResponse(error = "انتهت مهلة الاتصال — حاول مرة أخرى")
        } catch (e: java.net.ConnectException) {
            ApiResponse(error = "فشل الاتصال بالخادم")
        } catch (e: Exception) {
            ApiResponse(error = e.message ?: "خطأ غير معروف")
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
                put("model", model.trim().ifBlank { "gpt-4o" })
                put("messages", messages)
                put("max_tokens", 1500)
            }.toString()

            val request = Request.Builder()
                .url(normalizeEndpoint(url))
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return Result.failure(Exception(providerError(response.code, responseBody)))
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
                put("model", model.trim().ifBlank { "gpt-4o" })
                put("messages", messages)
                put("max_tokens", 3000)
            }.toString()

            val request = Request.Builder()
                .url(normalizeEndpoint(url))
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return Result.failure(Exception(providerError(response.code, responseBody)))
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
    //  أدوات المزود المخصص
    // ═══════════════════════════════════════════

    /** يقبل https://api.mistral.ai/v1 ويحوّله إلى .../v1/chat/completions. */
    private fun normalizeEndpoint(raw: String): String {
        val url = raw.trim().trimEnd('/')
        return if (url.endsWith("/chat/completions") || url.endsWith("/completions")) url
        else "$url/chat/completions"
    }

    private fun providerError(code: Int, body: String): String {
        val rawDetail = try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("detail").takeIf { it.isNotBlank() }
                ?: body
        } catch (_: Exception) {
            body
        }
        val detail = rawDetail.take(200)
        val hint = when (code) {
            401, 403 -> "تحقق من مفتاح API"
            404 -> "تحقق من الرابط واسم النموذج"
            400, 422 -> "تحقق من اسم النموذج"
            429 -> "تم تجاوز حد الطلبات"
            else -> ""
        }
        return "مزود مخصص $code" + (if (hint.isNotEmpty()) " ($hint)" else "") +
            (if (detail.isNotBlank()) ": $detail" else "")
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
