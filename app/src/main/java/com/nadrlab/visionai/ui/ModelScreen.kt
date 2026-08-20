package com.nadrlab.visionai.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadrlab.visionai.ai.ModelDownloader
import com.nadrlab.visionai.domain.ModelState
import com.nadrlab.visionai.vm.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun ModelScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val downloader = viewModel.modelDownloader
    val dlState by downloader.state.collectAsState()
    val progress by downloader.progress.collectAsState()
    val statusMsg by downloader.statusMessage.collectAsState()
    val scope = rememberCoroutineScope()

    var manualPath by remember {
        mutableStateOf("/storage/emulated/0/Download/Qwen3-1.7B-Q4_K_M.gguf")
    }

    // ═══ SAF file picker (يعمل مع الصلاحيات) ═══
    val safPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            // Take persistent permission
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            scope.launch { downloader.importFromUri(it) }
        }
    }

    // ═══ طلب صلاحية MANAGE_EXTERNAL_STORAGE ═══
    val storagePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // After returning from settings, try auto-search
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()) {
            scope.launch {
                val found = downloader.findModelOnDevice()
                if (found != null) {
                    downloader.importFromPath(found.absolutePath)
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("إدارة النموذج المحلي", color = Color(0xFF38BDF8), fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // ═══ معلومات ═══
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("النموذج الحالي", color = Color.Gray, fontSize = 12.sp)
                Text(ModelDownloader.MODEL_FILENAME, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                InfoRow("الحجم المطلوب:", "~${ModelDownloader.MODEL_SIZE_MB} MB")
                InfoRow("المساحة المتاحة:", "${downloader.getAvailableSpaceMb()} MB")
                InfoRow("الحالة:", stateLabel(dlState), stateColor(dlState))
                if (statusMsg.isNotBlank()) {
                    Text(statusMsg, color = Color(0xFF888888), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        // ═══ شريط التقدم ═══
        if (dlState == ModelState.DOWNLOADING) {
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(Color(0xFF1A1A2E), RoundedCornerShape(4.dp)),
                    color = Color(0xFF38BDF8),
                    trackColor = Color(0xFF1A1A2E)
                )
                Text("${(progress * 100).toInt()}%", color = Color.White, fontSize = 13.sp)
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Color(0xFF38BDF8),
                    trackColor = Color(0xFF1A1A2E)
                )
            }
            Button(
                onClick = { downloader.cancelDownload() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("إلغاء", color = Color.White)
            }
        }

        // ═══ أزرار عند عدم التثبيت ═══
        if (dlState == ModelState.NOT_DOWNLOADED || dlState == ModelState.ERROR) {

            // الطريقة 1: اختيار ملف (SAF — يعمل دائماً)
            Button(
                onClick = { safPicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.FolderOpen, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("اختيار ملف GGUF", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("من أي مكان في الجهاز (يعمل دائماً)", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }

            // الطريقة 2: بحث تلقائي + صلاحيات
            Button(
                onClick = {
                    scope.launch {
                        // Check if we have permission
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            if (!Environment.isExternalStorageManager()) {
                                // Request permission
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                                intent.data = Uri.parse("package:${context.packageName}")
                                storagePermLauncher.launch(intent)
                                return@launch
                            }
                        }
                        // Search and import
                        val found = downloader.findModelOnDevice()
                        if (found != null) {
                            downloader.importFromPath(found.absolutePath)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A5F)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.PhoneAndroid, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("بحث تلقائي + نسخ", color = Color.White, fontSize = 14.sp)
                    Text("يبحث في Download ويستورده (يحتاج صلاحية)", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                }
            }

            // الطريقة 3: إدخال المسار يدوياً
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("📂 استيراد بالمسار (متقدم)", color = Color(0xFFE8C547), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualPath,
                        onValueChange = { manualPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("مسار الملف", color = Color.Gray, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF333333),
                            cursorColor = Color(0xFF38BDF8)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { scope.launch { downloader.importFromPath(manualPath) } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("استيراد", color = Color.Black, fontSize = 13.sp)
                    }
                }
            }

            // الطريقة 4: تنزيل
            OutlinedButton(
                onClick = { scope.launch { downloader.download() } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Download, null, tint = Color.Gray)
                Spacer(Modifier.width(8.dp))
                Text("تنزيل من الإنترنت (بطيء جداً)", color = Color.Gray, fontSize = 12.sp)
            }

            // نصيحة
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A1A)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("💡 نصيحة", color = Color(0xFFE8C547), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("استخدم 'اختيار ملف GGUF' — يفتح منتقي الملفات ويعمل مع أي مكان", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }

        // ═══ جاهز ═══
        if (dlState == ModelState.READY || dlState == ModelState.LOADED) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2A1A)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    "✅ النموذج جاهز وسيُحمّل تلقائياً عند الحاجة",
                    color = Color(0xFF4CAF50),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
            OutlinedButton(
                onClick = { downloader.deleteModel() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("حذف النموذج", color = Color(0xFFF44336))
            }
        }

        // ═══ تحميل ═══
        if (dlState == ModelState.LOADING) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp).align(Alignment.CenterHorizontally),
                color = Color(0xFF38BDF8)
            )
            Text("جاري تحميل النموذج في الذاكرة...", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun stateLabel(state: ModelState): String = when (state) {
    ModelState.NOT_DOWNLOADED -> "غير مثبت"
    ModelState.DOWNLOADING -> "جاري..."
    ModelState.READY -> "جاهز"
    ModelState.LOADING -> "جاري التحميل..."
    ModelState.LOADED -> "مُحمّل"
    ModelState.ERROR -> "خطأ"
}

private fun stateColor(state: ModelState): Color = when (state) {
    ModelState.READY, ModelState.LOADED -> Color(0xFF4CAF50)
    ModelState.ERROR -> Color(0xFFF44336)
    else -> Color(0xFFFFC107)
}
