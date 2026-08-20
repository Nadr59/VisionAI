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

class LocalLlmManager(
    private val context: Context,
    private val settings: AppSettings
) {

    companion object {
        private const val TAG = "VisionAI_LLM"

        init {
            try {
                System.loadLibrary("llama_jni")
                Log.i(TAG, "llama_jni loaded OK")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "llama_jni FAILED: ${e.message}")
            }
        }
    }

    private val _state = MutableStateFlow(ModelState.NOT_DOWNLOADED)
    val state: StateFlow<ModelState> = _state

    private val _memUsage = MutableStateFlow(0L)
    val memUsage: StateFlow<Long> = _memUsage

    private var modelLoaded = false

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$ts $msg\n"
        Log.d(TAG, msg)
        try {
            File(context.filesDir, "llm_log.txt").appendText(line)
        } catch (_: Exception) {}
    }

    fun getLogFile(): File = File(context.filesDir, "llm_log.txt")

    fun readLog(): String {
        return try {
            val f = getLogFile()
            if (f.exists()) f.readText().takeLast(2000) else "لا يوجد سجل"
        } catch (_: Exception) {
            "لا يمكن قراءة السجل"
        }
    }

    fun clearLog() {
        try {
            File(context.filesDir, "llm_log.txt").writeText("=== VisionAI LLM Log ===\n")
        } catch (_: Exception) {}
    }

    init {
        clearLog()
        log("LocalLlmManager initialized")
        refreshState()
    }

    fun refreshState() {
        val path = settings.modelPath
        log("refreshState: path='$path' flag=${settings.modelDownloaded}")

        if (path.isBlank() || !settings.modelDownloaded) {
            _state.value = ModelState.NOT_DOWNLOADED
            log("State -> NOT_DOWNLOADED")
            return
        }

        val file = File(path)
        val exists = file.exists()
        val size = if (exists) file.length() else 0L
        log("File exists=$exists size=${size / (1024 * 1024)} MB canRead=${file.canRead()}")

        if (exists && size > 100_000_000L) {
            _state.value = if (modelLoaded) ModelState.LOADED else ModelState.READY
            log("State -> ${_state.value}")
        } else {
            _state.value = ModelState.NOT_DOWNLOADED
            log("State -> NOT_DOWNLOADED (file too small or missing)")
        }
    }

    fun hasEnoughRam(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val availMb = memInfo.availMem / (1024 * 1024)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        log("RAM: ${availMb}MB free / ${totalMb}MB total")
        return availMb > 400
    }

    fun isLoaded(): Boolean = modelLoaded && _state.value == ModelState.LOADED

    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        clearLog()
        log("========== loadModel START ==========")
        log("State: ${_state.value}")
        log("modelLoaded flag: $modelLoaded")

        if (modelLoaded && _state.value == ModelState.LOADED) {
            log("Already loaded, returning true")
            return@withContext true
        }

        val path = settings.modelPath
        log("Path from settings: '$path'")

        if (path.isBlank()) {
            log("ERROR: path is blank")
            _state.value = ModelState.ERROR
            return@withContext false
        }

        val file = File(path)
        log("Absolute path: ${file.absolutePath}")
        log("File exists: ${file.exists()}")
        log("File size: ${file.length()} bytes (${file.length() / (1024 * 1024)} MB)")
        log("File canRead: ${file.canRead()}")

        if (!file.exists()) {
            log("ERROR: file does not exist")
            _state.value = ModelState.ERROR
            return@withContext false
        }

        if (file.length() < 100_000_000L) {
            log("ERROR: file too small (${file.length() / (1024 * 1024)} MB)")
            _state.value = ModelState.ERROR
            return@withContext false
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        log("RAM before load: ${memInfo.availMem / (1024 * 1024)}MB free")

        _state.value = ModelState.LOADING
        log("State -> LOADING")

        try {
            log("Step 1: Calling nativeLoadModel...")
            log("  path = ${file.absolutePath}")
            log("  contextSize = ${settings.contextSize}")
            log("  threads = ${settings.threads}")

            val result = withTimeoutOrNull(120_000L) {
                nativeLoadModel(file.absolutePath, settings.contextSize, settings.threads)
            }

            log("nativeLoadModel returned: $result")

            if (result == null) {
                log("ERROR: nativeLoadModel TIMED OUT after 120 seconds")
                _state.value = ModelState.ERROR
                return@withContext false
            }

            if (!result) {
                log("ERROR: nativeLoadModel returned false")
                log("Possible causes: incompatible model format, corrupted file, or insufficient memory")
                _state.value = ModelState.ERROR
                return@withContext false
            }

            log("Step 2: Model loaded, getting memory usage...")
            try {
                val mem = nativeGetMemUsage()
                _memUsage.value = mem
                log("Memory usage after load: ${mem / (1024 * 1024)} MB")
            } catch (e: Throwable) {
                log("Warning: nativeGetMemUsage failed: ${e.message}")
            }

            modelLoaded = true
            _state.value = ModelState.LOADED
            log("========== loadModel SUCCESS ==========")
            return@withContext true

        } catch (e: Throwable) {
            log("CRASH: ${e.javaClass.simpleName}: ${e.message}")
            log("Stack trace: ${e.stackTraceToString().take(1000)}")
            _state.value = ModelState.ERROR
            modelLoaded = false
            return@withContext false
        }
    }

    suspend fun generate(prompt: String): String {
        log("generate() called")
        log("State: ${_state.value}, modelLoaded: $modelLoaded")

        if (!isLoaded()) {
            log("Model not loaded, calling loadModel()...")
            val ok = loadModel()
            if (!ok) {
                log("Load failed, cannot generate")
                return "النموذج غير جاهز"
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                log("Calling nativeGenerate...")
                log("  prompt length: ${prompt.length}")
                log("  maxTokens: ${settings.maxTokens}")
                log("  temperature: ${settings.temperature}")
                log("  topP: ${settings.topP}")
                log("  topK: ${settings.topK}")

                val result = withTimeoutOrNull(180_000L) {
                    nativeGenerate(
                        prompt,
                        settings.maxTokens,
                        settings.temperature,
                        settings.topP,
                        settings.topK,
                        null
                    )
                }

                log("nativeGenerate returned: len=${result?.length}")

                if (result == null) {
                    log("ERROR: generate TIMED OUT after 180 seconds")
                    return@withContext "⏰ انتهت المهلة (3 دقائق). النموذج بطيء جداً."
                }

                if (result.isBlank()) {
                    log("WARNING: empty result")
                    return@withContext "النموذج لم يُنتج رداً. جرّب نصاً أقصر."
                }

                log("Generation successful (${result.length} chars)")
                result

            } catch (e: Throwable) {
                log("generate CRASH: ${e.javaClass.simpleName}: ${e.message}")
                log("Stack: ${e.stackTraceToString().take(500)}")
                "خطأ أثناء التوليد: ${e.message}"
            }
        }
    }

    fun unloadModel() {
        log("unloadModel called")
        try {
            nativeUnload()
        } catch (e: Throwable) {
            log("nativeUnload error: ${e.message}")
        }
        modelLoaded = false
        _state.value = ModelState.NOT_DOWNLOADED
        _memUsage.value = 0
    }

    // ═══ JNI methods ═══
    private external fun nativeLoadModel(
        path: String,
        contextSize: Int,
        threads: Int
    ): Boolean

    private external fun nativeGenerate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        callback: TokenCallback?
    ): String

    private external fun nativeGetMemUsage(): Long
    private external fun nativeUnload()
}

interface TokenCallback {
    fun onToken(token: String)
}
