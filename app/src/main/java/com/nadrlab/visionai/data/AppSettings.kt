package com.nadrlab.visionai.data

import android.content.Context

class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════
    //  المزود المخصص (عام لأي API)
    // ═══════════════════════════════════════════

    // رابط API الكامل
    // مثال: https://api.cometapi.com/v1/chat/completions
    // مثال: https://zenmux.ai/api/v1/chat/completions
    // مثال: https://openrouter.ai/api/v1/chat/completions
    var providerUrl: String
        get() = prefs.getString("provider_url", "") ?: ""
        set(v) = prefs.edit().putString("provider_url", v).apply()

    // مفتاح API
    var providerKey: String
        get() = prefs.getString("provider_key", "") ?: ""
        set(v) = prefs.edit().putString("provider_key", v).apply()

    // اسم النموذج
    // مثال: glm-5.3
    // مثال: gpt-4o
    // مثال: deepseek/deepseek-chat-v3-0324:free
    var providerModel: String
        get() = prefs.getString("provider_model", "glm-5.3") ?: "glm-5.3"
        set(v) = prefs.edit().putString("provider_model", v).apply()

    // اسم المزود (للعرض فقط)
    var providerName: String
        get() = prefs.getString("provider_name", "") ?: ""
        set(v) = prefs.edit().putString("provider_name", v).apply()

    // ═══════════════════════════════════════════
    //  إعدادات عامة
    // ═══════════════════════════════════════════

    var searchEnabled: Boolean
        get() = prefs.getBoolean("search_enabled", true)
        set(v) = prefs.edit().putBoolean("search_enabled", v).apply()

    var ocrEnabled: Boolean
        get() = prefs.getBoolean("ocr_enabled", true)
        set(v) = prefs.edit().putBoolean("ocr_enabled", v).apply()

    var saveHistory: Boolean
        get() = prefs.getBoolean("save_history", true)
        set(v) = prefs.edit().putBoolean("save_history", v).apply()

    var defaultAnalysisType: String
        get() = prefs.getString("analysis_type", "GENERAL") ?: "GENERAL"
        set(v) = prefs.edit().putString("analysis_type", v).apply()

    // ═══════════════════════════════════════════
    //  هل المزود المخصص مُعد؟
    // ═══════════════════════════════════════════
    fun isCustomProviderConfigured(): Boolean {
        return providerUrl.isNotBlank() && providerKey.isNotBlank()
    }

    // ═══════════════════════════════════════════
    //  حفظ مزود كامل دفعة واحدة
    // ═══════════════════════════════════════════
    fun saveProvider(name: String, url: String, key: String, model: String) {
        prefs.edit()
            .putString("provider_name", name)
            .putString("provider_url", url)
            .putString("provider_key", key)
            .putString("provider_model", model)
            .apply()
    }

    fun clearProvider() {
        prefs.edit()
            .remove("provider_name")
            .remove("provider_url")
            .remove("provider_key")
            .remove("provider_model")
            .apply()
    }
}
