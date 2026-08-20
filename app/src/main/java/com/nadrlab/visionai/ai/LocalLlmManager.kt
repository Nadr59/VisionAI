package com.nadrlab.visionai.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.nadrlab.visionai.data.AppSettings
import com.nadrlab.visionai.domain.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class LocalLlmManager(private val context: Context, private val settings: AppSettings) {

    private val _state = MutableStateFlow(ModelState.NOT_DOWNLOADED)
    val state: StateFlow<ModelState> = _state

    private val _memUsage = MutableStateFlow(0L)
    val memUsage: StateFlow<Long> = _memUsage

    init {
        System.loadLibrary("visionai")
        if (settings.modelDownloaded && settings.modelPath.isNotBlank()) {
            _state.value = ModelState.READY
        }
    }

        fun hasEnoughRam(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availableMb = memInfo.availMem / (1024 * 1024)
        return availableMb > 800 // Lowered for Redmi 8
        }

    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        if (_state.value == ModelState.LOADED) return@withContext true
        if (!settings.modelDownloaded || settings.modelPath.isBlank()) {
            _state.value = ModelState.NOT_DOWNLOADED
            return@withContext false
        }
        if (!hasEnoughRam()) {
            _state.value = ModelState.ERROR
            return@withContext false
        }

        _state.value = ModelState.LOADING

        val success = nativeLoadModel(
            settings.modelPath,
            settings.contextSize,
            settings.threads
        )

        _state.value = if (success) {
            _memUsage.value = nativeGetMemUsage()
            ModelState.LOADED
        } else {
            ModelState.ERROR
        }

        success
    }

    suspend fun generate(prompt: String, onToken: ((String) -> Unit)? = null): String {
        if (_state.value != ModelState.LOADED) {
            val loaded = loadModel()
            if (!loaded) return "النموذج غير جاهز. تحقق من التنزيل."
        }

        return withContext(Dispatchers.IO) {
            val callback = object : TokenCallback {
                override fun onToken(token: String) {
                    onToken?.invoke(token)
                }
            }

            nativeGenerate(
                prompt,
                settings.maxTokens,
                settings.temperature,
                settings.topP,
                settings.topK,
                callback
            )
        }
    }

    fun unloadModel() {
        if (_state.value == ModelState.LOADED) {
            nativeFreeModel()
            _state.value = ModelState.READY
            _memUsage.value = 0
        }
    }

    fun isLoaded(): Boolean = nativeIsLoaded()

    // ═══ JNI ═══
    private external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Boolean
    private external fun nativeGenerate(
        prompt: String, maxTokens: Int, temperature: Float,
        topP: Float, topK: Int, callback: TokenCallback
    ): String
    private external fun nativeFreeModel()
    private external fun nativeIsLoaded(): Boolean
    private external fun nativeGetMemUsage(): Long

    interface TokenCallback {
        fun onToken(token: String)
    }
}
