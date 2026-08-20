package com.nadrlab.visionai.data

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var aiMode: String
        get() = prefs.getString("ai_mode", "AUTO") ?: "AUTO"
        set(v) = prefs.edit().putString("ai_mode", v).apply()

    var defaultAnalysisType: String
        get() = prefs.getString("analysis_type", "GENERAL") ?: "GENERAL"
        set(v) = prefs.edit().putString("analysis_type", v).apply()

    var searchEnabled: Boolean
        get() = prefs.getBoolean("search_enabled", true)
        set(v) = prefs.edit().putBoolean("search_enabled", v).apply()

    var ocrEnabled: Boolean
        get() = prefs.getBoolean("ocr_enabled", true)
        set(v) = prefs.edit().putBoolean("ocr_enabled", v).apply()

    var saveHistory: Boolean
        get() = prefs.getBoolean("save_history", true)
        set(v) = prefs.edit().putBoolean("save_history", v).apply()

    // Model settings
    var contextSize: Int
        get() = prefs.getInt("context_size", 512)
        set(v) = prefs.edit().putInt("context_size", v).apply()

    var threads: Int
        get() = prefs.getInt("threads", 3)
        set(v) = prefs.edit().putInt("threads", v).apply()

    var temperature: Float
        get() = prefs.getFloat("temperature", 0.6f)
        set(v) = prefs.edit().putFloat("temperature", v).apply()

    var topP: Float
        get() = prefs.getFloat("top_p", 0.8f)
        set(v) = prefs.edit().putFloat("top_p", v).apply()

    var topK: Int
        get() = prefs.getInt("top_k", 20)
        set(v) = prefs.edit().putInt("top_k", v).apply()

    var maxTokens: Int
        get() = prefs.getInt("max_tokens", 150)
        set(v) = prefs.edit().putInt("max_tokens", v).apply()

    var modelPath: String
        get() = prefs.getString("model_path", "") ?: ""
        set(v) = prefs.edit().putString("model_path", v).apply()

    var modelDownloaded: Boolean
        get() = prefs.getBoolean("model_downloaded", false)
        set(v) = prefs.edit().putBoolean("model_downloaded", v).apply()
}
