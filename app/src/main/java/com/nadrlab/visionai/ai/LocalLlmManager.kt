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
        private val LOG_FILE = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "visionai_crash_log.txt"
        )

        fun log(msg: String) {
            val line = "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $msg\n"
            Log.d(TAG, msg)
            try {
                LOG_FILE.appendText(line)
            } catch (_: Exception) {}
        }

        fun clearLog() {
            try { LOG_FILE.writeText("=== VisionAI Log Started ===\n") } catch (_: Exception) {}
        }

        init {
            try {
                System.loadLibrary("llama_jni")
                log("✅ llama_jni loaded")
            } catch (e: UnsatisfiedLinkError) {
                log("❌ Failed to load llama_jni: ${e.message}")
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
        clearLog()
        log("=== loadModel START ===")
        log("modelPath: '${settings.modelPath}'")
        log("modelDownloaded: ${settings.modelDownloaded}")

        if (_state.value == ModelState.LOADED && modelPtr != 0L) {
            log("Already loaded, returning true")
            return@withContext true
        }

        val path = settings.modelPath
        if (path.isBlank()) {
            log("❌ Path is blank!")
            _state.value = ModelState.ERROR
            return@withContext false
        }

        val file = File(path)
        log("File exists: ${file.exists()}")
        log("File size: ${file.length()} bytes (${file.length() / (1024*1024)} MB)")

        if (!file.exists()) {
            log("❌ File not found: $path")
            _state.value = ModelState.ERROR
            return@withContext false
        }

        if (file.length() < 100_000_000L) {
            log("❌ File too small: ${file.length() / (1024*1024)} MB")
            _state.value = ModelState.ERROR
            return@withContext false
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        log("RAM available: ${memInfo.availMem / (1024*1024)} MB / ${memInfo.totalMem / (1024*1024)} MB")

        _state.value = ModelState.LOADING

        try {
            log("Calling nativeLoadModel(path=$path, ctx=${settings.contextSize}, threads=${settings.threads})")

            val loadResult = withTimeoutOrNull(120_000L) {
                nativeLoadModel(path, settings.contextSize, settings.threads)
            }

            log("nativeLoadModel returned: $loadResult")

            if (loadResult == null) {
                log("❌ nativeLoadModel TIMED OUT")
                _state.value = ModelState.ERROR
                return@withContext false
            }

            if (!loadResult) {
                log("❌ nativeLoadModel returned false")
                _state.value = ModelState.ERROR
                return@withContext false
            }

            try {
                _memUsage.value = nativeGetMemUsage()
                log("Memory usage: ${_memUsage.value / (1024*1024)} MB")
            } catch (e: Exception) {
                log("⚠️ nativeGetMemUsage error: ${e.message}")
            }

            _state.value = ModelState.LOADED
            log("=== loadModel SUCCESS ===")
            return@withContext true

        } catch (e: Throwable) {
            log("❌ loadModel CRASH: ${e.javaClass.simpleName}: ${e.message}")
            log("Stack: ${e.stackTraceToString().take(500)}")
            _state.value = ModelState.ERROR
            return@withContext false
        }
                }

         suspend fun generate(prompt: String): String {
        log("generate called, state=${_state.value}")

        if (_state.value != ModelState.LOADED) {
            log("Model not loaded, attempting load...")
            val loaded = loadModel()
            if (!loaded) {
                log("❌ Load failed, cannot generate")
                return "النموذج غير جاهز. راجع سجل الأخطاء في Downloads/visionai_crash_log.txt"
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                log("Calling nativeGenerate, promptLen=${prompt.length}, maxTokens=${settings.maxTokens}")

                val result = withTimeoutOrNull(180_000L) {
                    nativeGenerate(prompt, settings.maxTokens, settings.temperature, settings.topP, settings.topK, null)
                }

                log("nativeGenerate returned, length=${result?.length}")

                if (result == null) {
                    log("❌ Generate timed out")
                    return@withContext "⏰ انتهت المهلة"
                }

                if (result.isBlank()) {
                    log("⚠️ Empty result")
                    return@withContext "النموذج لم يُنتج رداً"
                }

                result
            } catch (e: Throwable) {
                log("❌ generate CRASH: ${e.javaClass.simpleName}: ${e.message}")
                "خطأ: ${e.message}"
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
