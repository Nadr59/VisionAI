package com.nadrlab.visionai.ai

import android.content.Context
import com.nadrlab.visionai.data.AppSettings
import com.nadrlab.visionai.domain.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelDownloader(private val context: Context, private val settings: AppSettings) {

    companion object {
        const val MODEL_URL = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
        const val MODEL_FILENAME = "Qwen3-1.7B-Q4_K_M.gguf"
        const val MODEL_SHA256 = "" // Fill after first download verification
        const val MODEL_SIZE_MB = 1250L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No timeout for large files
        .build()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _state = MutableStateFlow(ModelState.NOT_DOWNLOADED)
    val state: StateFlow<ModelState> = _state

    private var downloadThread: Thread? = null
    @Volatile private var cancelled = false

    init {
        checkModel()
    }

    fun checkModel() {
        val file = getModelFile()
        _state.value = if (file.exists() && file.length() > 100_000_000) {
            settings.modelPath = file.absolutePath
            settings.modelDownloaded = true
            ModelState.READY
        } else {
            settings.modelDownloaded = false
            ModelState.NOT_DOWNLOADED
        }
    }

    fun getModelFile(): File = File(context.filesDir, MODEL_FILENAME)

    fun getAvailableSpaceMb(): Long {
        return context.filesDir.freeSpace / (1024 * 1024)
    }

    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        val file = getModelFile()
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")
        cancelled = false
        _state.value = ModelState.DOWNLOADING
        _progress.value = 0f

        try {
            // Resume support
            var downloaded = 0L
            if (tempFile.exists()) {
                downloaded = tempFile.length()
            }

            val requestBuilder = Request.Builder().url(MODEL_URL)
            if (downloaded > 0) {
                requestBuilder.addHeader("Range", "bytes=$downloaded-")
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful && response.code != 206) {
                _state.value = ModelState.ERROR
                return@withContext false
            }

            val body = response.body ?: run {
                _state.value = ModelState.ERROR
                return@withContext false
            }

            val totalSize = body.contentLength() + downloaded
            val outputStream = tempFile.outputStream(append = downloaded > 0)
            val buffer = ByteArray(8192)
            var bytesRead: Int

            body.byteStream().use { input ->
                outputStream.use { output ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (cancelled) {
                            _state.value = ModelState.NOT_DOWNLOADED
                            return@withContext false
                        }
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        _progress.value = if (totalSize > 0) downloaded.toFloat() / totalSize else 0f
                    }
                }
            }

            // Rename temp to final
            if (tempFile.exists() && tempFile.length() > 100_000_000) {
                tempFile.renameTo(file)
                settings.modelPath = file.absolutePath
                settings.modelDownloaded = true
                _progress.value = 1f
                _state.value = ModelState.READY
                true
            } else {
                _state.value = ModelState.ERROR
                false
            }
        } catch (e: Exception) {
            if (!cancelled) _state.value = ModelState.ERROR
            false
        }
    }

    fun cancelDownload() {
        cancelled = true
    }

    fun deleteModel() {
        val file = getModelFile()
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")
        file.delete()
        tempFile.delete()
        settings.modelPath = ""
        settings.modelDownloaded = false
        _state.value = ModelState.NOT_DOWNLOADED
        _progress.value = 0f
    }
}
