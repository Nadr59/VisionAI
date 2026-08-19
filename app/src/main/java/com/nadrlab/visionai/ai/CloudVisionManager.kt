package com.nadrlab.visionai.ai

import android.graphics.Bitmap
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

    private const val API_URL = "https://ai-key-manager.vercel.app/api/vision"
    private const val APP_ID = "vision-ai-01"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun analyze(bitmap: Bitmap, prompt: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val base64 = ImageProcessor.bitmapToBase64(bitmap)
                val dataUrl = "data:image/jpeg;base64,$base64"

                val body = JSONObject().apply {
                    put("appId", APP_ID)
                    put("prompt", prompt)
                    put("image", dataUrl)
                }

                val request = Request.Builder()
                    .url(API_URL)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        if (json.optBoolean("success")) {
                            Result.success(json.getString("response"))
                        } else {
                            Result.failure(Exception(json.optString("error", "Unknown error")))
                        }
                    }
                    501 -> Result.failure(Exception("ميزة الرؤية غير مفعّلة بعد. أرسل لي كود api/vision.js"))
                    else -> {
                        val error = try {
                            JSONObject(responseBody).getString("error")
                        } catch (_: Exception) {
                            "Error ${response.code}: ${responseBody.take(200)}"
                        }
                        Result.failure(Exception(error))
                    }
                }
            } catch (e: Exception) {
                Result.failure(Exception("فشل الاتصال: ${e.message}"))
            }
        }
    }
}
