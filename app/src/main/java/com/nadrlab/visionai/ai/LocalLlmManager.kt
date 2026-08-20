package com.nadrlab.visionai.ai

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.nadrlab.visionai.data.AppSettings
import com.nadrlab.visionai.domain.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class LocalLlmManager(private val context: Context, private val settings: AppSettings) {

    companion object {
        private const val TAG = "VisionAI_LLM"

        init {
            try {
                System.loadLibrary("llama_jni")
                Log.i(TAG, "✅ llama_jni library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "❌ Failed to load llama_jni: ${e.message}")
            }
        }
    }

    private val _state = MutableStateFlow(ModelState.NOT_DOWNLOADED)
    val state: StateFlow<ModelState> = _state

    private val _memUsage = MutableStateFlow(0L)
    val memUsage: StateFlow<Long> = _memUsage

    private var contextPtr: Long = 0L
    private var modelPtr: Long = 0L

    init {
        checkState()
    }

    fun checkState() {
        if (settings.modelDownloaded && settings.modelPath.isNotBlank()) {
            val file = File(settings.modelPath)
            if (file.exists() && file.length() > 500_000_000L) {
                _state.value = ModelState.READY
                Log.i(TAG, "Model file found: ${file.absolutePath} (${file.length() / (1024*1024)} MB)")
            } else {
                Log.w(TAG, "Model file missing or too small: ${file.absolutePath} size=${if(file.exists()) file.length() else "NOT FOUND"}")
                _state.value = ModelState.NOT_DOWNLOADED
            }
        } else {
            _state.value = ModelState.NOT_DOWNLOADED
        }
    }

    fun hasEnoughRam(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availableMb = memInfo.availMem / (1024 * 1024)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        Log.i(TAG, "RAM: available=${availableMb}MB total=${totalMb}MB threshold=${memInfo.threshold / (1024*1024)}MB")
        return availableMb > 500
    }

    fun isLoaded(): Boolean = _state.value == ModelState.LOADED

    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "=== loadModel START ===")

        if (_state.value == ModelState.LOADED && modelPtr != 0L) {
            Log.i(TAG, "Model already loaded, returning true")
            return@withContext true
        }

        val path = settings.modelPath
        Log.i(TAG, "Model path: '$path'")

        if (path.isBlank()) {
            Log.e(TAG, "❌ Model path is blank!")
            _state.value = ModelState.ERROR
            return@withContext false
        }

        val file = File(path)
        if (!file.exists()) {
            Log.e(TAG, "❌ Model file does not exist: $path")
            _state.value = ModelState.ERROR
            return@withContext false
        }

        Log.i(TAG, "Model file size: ${file.length()} bytes (${file.length() / (1024*1024)} MB)")

        if (!hasEnoughRam()) {
            Log.e(TAG, "❌ Not enough RAM")
            _state.value = ModelState.ERROR
            return@withContext false
        }

        _state.value = ModelState.LOADING

        try {
            // Step 1: Load model with timeout
            Log.i(TAG, "Step 1: Calling nativeLoadModel...")
            Log.i(TAG, "  path=$path contextSize=${settings.contextSize} threads=${settings.threads}")

            val loadResult = withTimeoutOrNull(120_000L) { // 2 minute timeout
                nativeLoadModel(path, settings.contextSize, settings.threads)
            }

            if (loadResult == null) {
                Log.e(TAG, "❌ nativeLoadModel TIMED OUT after 120 seconds")
                _state.value = ModelState.ERROR
                return@withContext false
            }

            Log.i(TAG, "nativeLoadModel returned: $loadResult")

            if (!loadResult) {
                Log.e(TAG, "❌ nativeLoadModel returned false")
                _state.value = ModelState.ERROR
                return@withContext false
            }

            // Step 2: Get memory usage
            Log.i(TAG, "Step 2: Getting memory usage...")
            try {
                _memUsage.value = nativeGetMemUsage()
                Log.i(TAG, "Memory usage: ${_memUsage.value / (1024*1024)} MB")
            } catch (e: Exception) {
                Log.w(TAG, "Could not get memory usage: ${e.message}")
            }

            _state.value = ModelState.LOADED
            Log.i(TAG, "=== loadModel SUCCESS ===")
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "❌ loadModel EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            _state.value = ModelState.ERROR
            return@withContext false
        }
    }

    suspend fun generate(prompt: String): String {
        Log.i(TAG, "generate called, state=${_state.value}")

        if (_state.value != ModelState.LOADED) {
            Log.i(TAG, "Model not loaded, attempting to load...")
            val loaded = loadModel()
            if (!loaded) {
                Log.e(TAG, "❌ Failed to load model for generation")
                return "النموذج غير جاهز. تحقق من التنزيل والذاكرة."
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Calling nativeGenerate with prompt length=${prompt.length}")
                val result = withTimeoutOrNull(180_000L) { // 3 min timeout
                    nativeGenerate(
                        prompt,
                        settings.maxTokens,
                        settings.temperature,
                        settings.topP,
                        settings.topK,
                        null
                    )
                }

                if (result == null) {
                    Log.e(TAG, "❌ nativeGenerate TIMED OUT")
                    return@withContext "⏰ انتهت المهلة. النموذج بطيء جداً."
                }

                Log.i(TAG, "Generation result length=${result.length}")
                if (result.isBlank()) {
                    Log.w(TAG, "⚠️ Generation returned empty string")
                    return@withContext "النموذج لم يُنتج رداً. جرّب نصاً أقصر."
                }

                result
            } catch (e: Exception) {
                Log.e(TAG, "❌ generate EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                "خطأ أثناء التوليد: ${e.message}"
            }
        }
    }

    fun unloadModel() {
        Log.i(TAG, "unloadModel called")
        try {
            if (modelPtr != 0L || contextPtr != 0L) {
                nativeUnload()
                modelPtr = 0L
                contextPtr = 0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "unloadModel error: ${e.message}")
        }
        _state.value = ModelState.NOT_DOWNLOADED
        _memUsage.value = 0
        settings.modelDownloaded = false
        settings.modelPath = ""
    }

    private external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Boolean
    private external fun nativeGenerate(
        prompt: String, maxTokens: Int,
        temperature: Float, topP: Float, topK: Int,
        callback: TokenCallback?
    ): String
    private external fun nativeGetMemUsage(): Long
    private external fun nativeUnload()
}

interface TokenCallback {
    fun onToken(token: String)
}
