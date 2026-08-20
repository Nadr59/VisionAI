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
                Log.i(TAG, "llama_jni loaded")
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
        } catch (_: Exception) { "لا يمكن قراءة السجل" }
    }

    fun clearLog() {
        try {
            File(context.filesDir, "llm_log.txt").writeText("=== VisionAI LLM Log ===\n")
        } catch (_: Exception) {}
    }

    init {
        clearLog()
        log("LocalLlmManager init")
        refreshState()
    }

    fun refreshState() {
        val path = settings.modelPath
        log("refreshState: path='$path' flag=${settings.modelDownloaded}")

        if (path.isBlank() || !settings.modelDownloaded) {
            _state.value = ModelState.NOT_DOWNLOADED
            return
        }

        val file = File(path)
        if (file.exists() && file.length() > 100_000_000L) {
            _state.value = if (modelLoaded) ModelState.LOADED else ModelState.READY
            log("State -> ${_state.value} (${file.length() / (1024 * 1024)} MB)")
        } else {
            _state.value = ModelState.NOT_DOWNLOADED
            log("State -> NOT_DOWNLOADED")
        }
    }

    fun hasEnoughRam(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val avail = memInfo.availMem / (1024 * 1024)
        log("RAM: ${avail}MB available")
        return avail > 400
    }

    fun isLoaded(): Boolean = modelLoaded && _state.value == ModelState.LOADED

    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        clearLog()
        log("========== loadModel START ==========")

        if (modelLoaded && _state.value == ModelState.LOADED) {
            log("Already loaded")
            return@withContext true
        }

        val path = settings.modelPath
        log("Path: '$path'")

        if (path.isBlank()) {
            log("ERROR: path blank")
            _state.value = ModelState.ERROR
            return@withContext false
        }

        val file = File(path)
        log("Exists: ${file.exists()} Size: ${file.length() / (1024 * 1024)} MB CanRead: ${file.canRead()}")

        if (!file.exists() || file.length() < 100_000_000L) {
            log("ERROR: file invalid")
            _state.value = ModelState.ERROR
            return@withContext false
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        log("RAM: ${memInfo.availMem / (1024 * 1024)}MB free")

        _state.value = ModelState.LOADING

        try {
            log("Calling nativeLoadModel...")

            // Use thread with timeout for JNI call
            var result = false
            var error: Throwable? = null

            val loadThread = Thread {
                try {
                    result = nativeLoadModel(file.absolutePath, settings.contextSize, settings.threads)
                } catch (e: Throwable) {
                    error = e
                }
            }
            loadThread.start()
            loadThread.join(180_000) // 3 minute hard timeout

            if (loadThread.isAlive) {
                loadThread.interrupt()
                log("ERROR: nativeLoadModel TIMED OUT after 180s")
                _state.value = ModelState.ERROR
                return@withContext false
            }

            if (error != null) {
                log("ERROR: ${error!!.javaClass.simpleName}: ${error!!.message}")
                _state.value = ModelState.ERROR
                return@withContext false
            }

            log("nativeLoadModel returned: $result")

            if (!result) {
                log("ERROR: nativeLoadModel returned false")
                _state.value = ModelState.ERROR
                return@withContext false
            }

            try {
                _memUsage.value = nativeGetMemUsage()
                log("Memory: ${_memUsage.value / (1024 * 1024)} MB")
            } catch (_: Exception) {}

            modelLoaded = true
            _state.value = ModelState.LOADED
            log("========== loadModel SUCCESS ==========")
            return@withContext true

        } catch (e: Throwable) {
            log("CRASH: ${e.javaClass.simpleName}: ${e.message}")
            _state.value = ModelState.ERROR
            return@withContext false
        }
    }

    suspend fun generate(prompt: String): String {
        log("generate() state=${_state.value}")

        if (!isLoaded()) {
            log("Not loaded, calling loadModel...")
            val ok = loadModel()
            if (!ok) {
                log("Load failed")
                return "النموذج غير جاهز"
            }
        }

        return withContext(Dispatchers.IO) {
            try {
                log("nativeGenerate: ${prompt.length} chars")

                // Use thread with timeout
                var result: String? = null
                var error: Throwable? = null

                val genThread = Thread {
                    try {
                        result = nativeGenerate(
                            prompt,
                            settings.maxTokens,
                            settings.temperature,
                            settings.topP,
                            settings.topK,
                            null
                        )
                    } catch (e: Throwable) {
                        error = e
                    }
                }
                genThread.start()
                genThread.join(180_000) // 3 minute hard timeout

                if (genThread.isAlive) {
                    genThread.interrupt()
                    log("ERROR: generate TIMED OUT")
                    return@withContext "⏰ انتهت المهلة (3 دقائق)"
                }

                if (error != null) {
                    log("ERROR: ${error!!.message}")
                    return@withContext "خطأ: ${error!!.message}"
                }

                val r = result ?: ""
                log("Generate done: ${r.length} chars")
                if (r.isBlank()) return@withContext "النموذج لم يُنتج رداً"
                r

            } catch (e: Throwable) {
                log("CRASH: ${e.message}")
                "خطأ: ${e.message}"
            }
        }
    }

    fun unloadModel() {
        log("unloadModel")
        try { nativeUnload() } catch (_: Exception) {}
        modelLoaded = false
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
