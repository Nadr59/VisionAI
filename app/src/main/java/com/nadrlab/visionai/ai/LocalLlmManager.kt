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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalLlmManager(private val context: Context, private val settings: AppSettings) {

    companion object {
        private const val TAG = "VisionAI_LLM"

        init {
            try {
                System.loadLibrary("llama_jni")
                Log.i(TAG, "llama_jni loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load llama_jni: ${e.message}")
            }
        }
    }

    private val logFile = File(context.filesDir, "crash_log.txt")
    private val _state = MutableStateFlow(ModelState.NOT_DOWNLOADED)
    val state: StateFlow<ModelState> = _state
    private val _memUsage = MutableStateFlow(0L)
    val memUsage: StateFlow<Long> = _memUsage
    private var modelPtr: Long = 0L

    private fun log(msg: String) {
        val line = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $msg\n"
        Log.d(TAG, msg)
        try { logFile.appendText(line) } catch (_: Exception) {}
    }

    init {
        try { logFile.writeText("=== VisionAI Log ===\n") } catch (_: Exception) {}
        checkState()
    }

    fun getLogFile(): File = logFile

    fun checkState() {
        if (settings.modelDownloaded && settings.modelPath.isNotBlank()) {
            val file = File(settings.modelPath)
            if (file.exists() && file.length() > 100_000_000L) {
                _state.value = ModelState.READY
                log("Model found: ${file.length() / (1024*1024)} MB")
            } else {
                _state.value = ModelState.NOT_DOWNLOADED
                log("Model not found or too small")
            }
        } else {
            _state.value = ModelState.NOT_DOWNLOADED
        }
    }

    fun hasEnoughRam(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val avail = memInfo.availMem / (1024 * 1024)
        log("RAM check: available=${avail}MB")
        return avail > 400
    }

    fun isLoaded(): Boolean = _state.value == ModelState.LOADED

    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        logFile.writeText("=== loadModel START ===\n")
        log("State: ${_state.value}")
        log("Path: '${settings.modelPath}'")
        log("Downloaded flag: ${settings.modelDownloaded}")

        if (_state.value == ModelState.LOADED && modelPtr != 0L) {
            log("Already loaded")
            return@withContext true
        }

        val path = settings.modelPath
        if (path.isBlank()) {
            log("ERROR: path is blank")
            _state.value = ModelState.ERROR
            return@withContext false
        }

        val file = File(path)
        log("File exists: ${file.exists()}")
        log("File size: ${file.length()} (${file.length() / (1024*1024)} MB)")

        if (!file.exists()) {
            log("ERROR: file not found")
            _state.value = ModelState.ERROR
            return@withContext false
        }

        if (file.length() < 100_000_000L) {
            log("ERROR: file too small (${file.length() / (1024*1024)} MB)")
            _state.value = ModelState.ERROR
            return@withContext false
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        log("RAM: ${memInfo.availMem / (1024*1024)}MB free / ${memInfo.totalMem / (1024*1024)}MB total")

        _state.value = ModelState.LOADING

        try {
            log("Calling nativeLoadModel...")
            log("  path=$path")
            log("  contextSize=${settings.contextSize}")
            log("  threads=${settings.threads}")

            val result = withTimeoutOrNull(120_000L) {
                nativeLoadModel(path, settings.contextSize, settings.threads)
            }

            log("nativeLoadModel returned: $result")

            if (result == null) {
                log("ERROR: TIMED OUT after 120s")
                _state.value = ModelState.ERROR
                return@withContext false
            }

            if (!result) {
                log("ERROR: nativeLoadModel returned false")
                _state.value = ModelState.ERROR
                return@withContext false
            }

            try {
                _memUsage.value = nativeGetMemUsage()
                log("Memory usage: ${_memUsage.value / (1024*1024)} MB")
            } catch (e: Exception) {
                log("getMemUsage error: ${e.message}")
            }

            modelPtr = 1L
            _state.value = ModelState.LOADED
            log("SUCCESS: model loaded")
            return@withContext true

        } catch (e: Throwable) {
            log("CRASH: ${e.javaClass.simpleName}: ${e.message}")
            log("Stack: ${e.stackTraceToString().take(500)}")
            _state.value = ModelState.ERROR
            return@withContext false
        }
    }

    suspend fun generate(prompt: String): String {
        log("generate called, state=${_state.value}")

        if (_state.value != ModelState.LOADED) {
            log("Not loaded, attempting load...")
            val loaded = loadModel()
            if (!loaded) {
                log("Load failed")
                return "النموذج غير جاهز"
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                log("nativeGenerate: promptLen=${prompt.length}")
                val result = withTimeoutOrNull(180_000L) {
                    nativeGenerate(prompt, settings.maxTokens, settings.temperature, settings.topP, settings.topK, null)
                }
                log("nativeGenerate done, len=${result?.length}")

                if (result == null) return@withContext "⏰ انتهت المهلة"
                if (result.isBlank()) return@withContext "لم يُنتج رداً"
                result
            } catch (e: Throwable) {
                log("generate CRASH: ${e.message}")
                "خطأ: ${e.message}"
            }
        }
    }

    fun unloadModel() {
        try { nativeUnload() } catch (_: Exception) {}
        modelPtr = 0L
        _state.value = ModelState.NOT_DOWNLOADED
        _memUsage.value = 0
    }

    private external fun nativeLoadModel(path: String, contextSize: Int, threads: Int): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float, topP: Float, topK: Int, callback: TokenCallback?): String
    private external fun nativeGetMemUsage(): Long
    private external fun nativeUnload()
}

interface TokenCallback {
    fun onToken(token: String)
}
