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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelDownloader(private val context: Context, private val settings: AppSettings) {

    companion object {
        const val MODEL_URL = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
        const val MODEL_FILENAME = "Qwen3-1.7B-Q4_K_M.gguf"
        const val MODEL_SIZE_MB = 1250L
        const val MIN_VALID_SIZE = 500_000_000L // 500MB minimum
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _state = MutableStateFlow(ModelState.NOT_DOWNLOADED)
    val state: StateFlow<ModelState> = _state

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage

    @Volatile
    private var cancelled = false

    init {
        checkModel()
    }

    fun checkModel() {
        val file = getModelFile()
        if (file.exists() && file.length() > MIN_VALID_SIZE) {
            settings.modelPath = file.absolutePath
            settings.modelDownloaded = true
            _state.value = ModelState.READY
            _statusMessage.value = "جاهز (${file.length() / (1024 * 1024)} MB)"
        } else {
            settings.modelDownloaded = false
            settings.modelPath = ""
            _state.value = ModelState.NOT_DOWNLOADED
            _statusMessage.value = "غير مثبت"
        }
    }

    fun getModelFile(): File = File(context.filesDir, MODEL_FILENAME)

    fun getAvailableSpaceMb(): Long {
        return context.filesDir.freeSpace / (1024 * 1024)
    }

    // ═══ تنزيل من الإنترنت ═══
    suspend fun download(): Boolean = withContext(Dispatchers.IO) {
        val file = getModelFile()
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")
        cancelled = false
        _state.value = ModelState.DOWNLOADING
        _progress.value = 0f
        _statusMessage.value = "جاري التنزيل..."

        try {
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
                _statusMessage.value = "خطأ في الخادم: ${response.code}"
                return@withContext false
            }

            val body = response.body ?: run {
                _state.value = ModelState.ERROR
                _statusMessage.value = "لا توجد بيانات"
                return@withContext false
            }

            val totalSize = body.contentLength() + downloaded
            val outputStream = FileOutputStream(tempFile, downloaded > 0)
            val buffer = ByteArray(8192)
            var bytesRead: Int

            body.byteStream().use { input ->
                outputStream.use { output ->
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (cancelled) {
                            _state.value = ModelState.NOT_DOWNLOADED
                            _statusMessage.value = "تم الإلغاء"
                            return@withContext false
                        }
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        _progress.value = if (totalSize > 0) downloaded.toFloat() / totalSize else 0f
                        _statusMessage.value = "جاري التنزيل... ${downloaded / (1024 * 1024)} / ${totalSize / (1024 * 1024)} MB"
                    }
                }
            }

            if (tempFile.exists() && tempFile.length() > MIN_VALID_SIZE) {
                tempFile.renameTo(file)
                settings.modelPath = file.absolutePath
                settings.modelDownloaded = true
                _progress.value = 1f
                _state.value = ModelState.READY
                _statusMessage.value = "تم التنزيل بنجاح (${file.length() / (1024 * 1024)} MB)"
                true
            } else {
                tempFile.delete()
                _state.value = ModelState.ERROR
                _statusMessage.value = "الملف غير مكتمل"
                false
            }
        } catch (e: Exception) {
            if (!cancelled) {
                _state.value = ModelState.ERROR
                _statusMessage.value = "خطأ: ${e.message}"
            }
            false
        }
    }

    fun cancelDownload() {
        cancelled = true
    }

    // ═══ استيراد من ملف محلي ═══
    suspend fun importFromFile(sourceFile: File): Boolean = withContext(Dispatchers.IO) {
        _state.value = ModelState.DOWNLOADING
        _progress.value = 0f
        _statusMessage.value = "جاري النسخ..."

        try {
            if (!sourceFile.exists()) {
                _state.value = ModelState.ERROR
                _statusMessage.value = "الملف غير موجود"
                return@withContext false
            }

            if (sourceFile.length() < MIN_VALID_SIZE) {
                _state.value = ModelState.ERROR
                _statusMessage.value = "الملف صغير جداً (${sourceFile.length() / (1024 * 1024)} MB). تأكد من سلامة التنزيل"
                return@withContext false
            }

            val destFile = getModelFile()

            // If source is in the app's files dir already
            if (sourceFile.absolutePath == destFile.absolutePath) {
                settings.modelPath = destFile.absolutePath
                settings.modelDownloaded = true
                _progress.value = 1f
                _state.value = ModelState.READY
                _statusMessage.value = "النموذج جاهز (${destFile.length() / (1024 * 1024)} MB)"
                return@withContext true
            }

            // Copy file
            val totalSize = sourceFile.length()
            var copied = 0L
            val buffer = ByteArray(65536) // 64KB buffer

            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (cancelled) {
                            destFile.delete()
                            _state.value = ModelState.NOT_DOWNLOADED
                            _statusMessage.value = "تم الإلغاء"
                            return@withContext false
                        }
                        output.write(buffer, 0, bytesRead)
                        copied += bytesRead
                        _progress.value = copied.toFloat() / totalSize
                        _statusMessage.value = "جاري النسخ... ${copied / (1024 * 1024)} / ${totalSize / (1024 * 1024)} MB"
                    }
                }
            }

            if (destFile.exists() && destFile.length() > MIN_VALID_SIZE) {
                settings.modelPath = destFile.absolutePath
                settings.modelDownloaded = true
                _progress.value = 1f
                _state.value = ModelState.READY
                _statusMessage.value = "تم الاستيراد بنجاح (${destFile.length() / (1024 * 1024)} MB)"
                true
            } else {
                destFile.delete()
                _state.value = ModelState.ERROR
                _statusMessage.value = "فشل النسخ"
                false
            }
        } catch (e: Exception) {
            _state.value = ModelState.ERROR
            _statusMessage.value = "خطأ: ${e.message}"
            false
        }
    }

    // ═══ البحث عن نموذج على الجهاز ═══
    fun findModelOnDevice(): File? {
        // Check common locations
        val locations = listOf(
            File(context.filesDir, MODEL_FILENAME),
            File("/sdcard/Download/$MODEL_FILENAME"),
            File("/sdcard/Downloads/$MODEL_FILENAME"),
            File("/sdcard/$MODEL_FILENAME"),
            File("/storage/emulated/0/Download/$MODEL_FILENAME"),
            File("/storage/emulated/0/$MODEL_FILENAME"),
            File(context.getExternalFilesDir(null), MODEL_FILENAME)
        )

        return locations.firstOrNull { it.exists() && it.length() > MIN_VALID_SIZE }
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
        _statusMessage.value = "تم الحذف"
    }
}
