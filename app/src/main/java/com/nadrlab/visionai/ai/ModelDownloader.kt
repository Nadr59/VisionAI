package com.nadrlab.visionai.ai

import android.content.Context
import android.net.Uri
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
        const val MIN_VALID_SIZE = 500_000_000L
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

    fun getAvailableSpaceMb(): Long = context.filesDir.freeSpace / (1024 * 1024)

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
            if (tempFile.exists()) downloaded = tempFile.length()

            val requestBuilder = Request.Builder().url(MODEL_URL)
            if (downloaded > 0) requestBuilder.addHeader("Range", "bytes=$downloaded-")

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
                        _statusMessage.value = "تنزيل... ${downloaded / (1024 * 1024)} / ${totalSize / (1024 * 1024)} MB"
                    }
                }
            }

            returnFinalFile(tempFile, file)
        } catch (e: Exception) {
            if (!cancelled) {
                _state.value = ModelState.ERROR
                _statusMessage.value = "خطأ: ${e.message}"
            }
            false
        }
    }

    // ═══ استيراد من مسار يدوي ═══
    suspend fun importFromPath(path: String): Boolean = withContext(Dispatchers.IO) {
        val sourceFile = File(path.trim())
        if (!sourceFile.exists()) {
            _state.value = ModelState.ERROR
            _statusMessage.value = "الملف غير موجود: $path"
            return@withContext false
        }
        copyModelFile(sourceFile)
    }

    // ═══ استيراد من ContentResolver URI ═══
    suspend fun importFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        _state.value = ModelState.DOWNLOADING
        _progress.value = 0f
        _statusMessage.value = "جاري الاستيراد..."

        val destFile = getModelFile()
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.importing")

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                _state.value = ModelState.ERROR
                _statusMessage.value = "لا يمكن فتح الملف"
                return@withContext false
            }

            var totalBytes = 0L
            val buffer = ByteArray(65536)

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (cancelled) {
                            tempFile.delete()
                            _state.value = ModelState.NOT_DOWNLOADED
                            _statusMessage.value = "تم الإلغاء"
                            return@withContext false
                        }
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        _statusMessage.value = "استيراد... ${totalBytes / (1024 * 1024)} MB"
                        _progress.value = -1f // unknown progress
                    }
                }
            }

            returnFinalFile(tempFile, destFile)
        } catch (e: Exception) {
            tempFile.delete()
            _state.value = ModelState.ERROR
            _statusMessage.value = "خطأ: ${e.message}"
            false
        }
    }

    // ═══ نسخ من ملف موجود ═══
    private suspend fun copyModelFile(sourceFile: File): Boolean {
        _state.value = ModelState.DOWNLOADING
        _progress.value = 0f
        _statusMessage.value = "جاري النسخ من: ${sourceFile.name}..."

        val destFile = getModelFile()
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.copying")

        try {
            if (sourceFile.absolutePath == destFile.absolutePath) {
                settings.modelPath = destFile.absolutePath
                settings.modelDownloaded = true
                _progress.value = 1f
                _state.value = ModelState.READY
                _statusMessage.value = "النموذج جاهز (${destFile.length() / (1024 * 1024)} MB)"
                return true
            }

            val totalSize = sourceFile.length()
            var copied = 0L
            val buffer = ByteArray(65536)

            FileInputStream(sourceFile).use { input ->
                FileOutputStream(tempFile).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (cancelled) {
                            tempFile.delete()
                            _state.value = ModelState.NOT_DOWNLOADED
                            _statusMessage.value = "تم الإلغاء"
                            return false
                        }
                        output.write(buffer, 0, bytesRead)
                        copied += bytesRead
                        _progress.value = copied.toFloat() / totalSize
                        _statusMessage.value = "نسخ... ${copied / (1024 * 1024)} / ${totalSize / (1024 * 1024)} MB"
                    }
                }
            }

            returnFinalFile(tempFile, destFile)
        } catch (e: Exception) {
            tempFile.delete()
            _state.value = ModelState.ERROR
            _statusMessage.value = "خطأ: ${e.message}"
            false
        }
    }

    // ═══ تحقق واحتفظ بالملف النهائي ═══
    private fun returnFinalFile(tempFile: File, destFile: File): Boolean {
        if (tempFile.exists() && tempFile.length() > MIN_VALID_SIZE) {
            if (destFile.exists()) destFile.delete()
            tempFile.renameTo(destFile)
            settings.modelPath = destFile.absolutePath
            settings.modelDownloaded = true
            _progress.value = 1f
            _state.value = ModelState.READY
            _statusMessage.value = "تم بنجاح! (${destFile.length() / (1024 * 1024)} MB)"
            return true
        } else {
            val size = if (tempFile.exists()) tempFile.length() / (1024 * 1024) else 0
            tempFile.delete()
            _state.value = ModelState.ERROR
            _statusMessage.value = "الملف صغير ($size MB). الحد الأدنى: ${MIN_VALID_SIZE / (1024 * 1024)} MB"
            return false
        }
    }

    fun cancelDownload() {
        cancelled = true
    }

    fun findModelOnDevice(): File? {
        val locations = listOf(
            File("/storage/emulated/0/Download/$MODEL_FILENAME"),
            File("/storage/emulated/0/Downloads/$MODEL_FILENAME"),
            File("/sdcard/Download/$MODEL_FILENAME"),
            File("/sdcard/$MODEL_FILENAME"),
            File(context.getExternalFilesDir(null), MODEL_FILENAME)
        )
        return locations.firstOrNull { it.exists() && it.length() > MIN_VALID_SIZE }
    }

    fun deleteModel() {
        val file = getModelFile()
        val tempFile = File(context.filesDir, "$MODEL_FILENAME.tmp")
        val copyFile = File(context.filesDir, "$MODEL_FILENAME.copying")
        val importFile = File(context.filesDir, "$MODEL_FILENAME.importing")
        file.delete()
        tempFile.delete()
        copyFile.delete()
        importFile.delete()
        settings.modelPath = ""
        settings.modelDownloaded = false
        _state.value = ModelState.NOT_DOWNLOADED
        _progress.value = 0f
        _statusMessage.value = "تم الحذف"
    }
}
