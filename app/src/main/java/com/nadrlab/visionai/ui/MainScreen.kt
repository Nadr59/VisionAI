package com.nadrlab.visionai.ui

import android.net.Uri
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadrlab.visionai.ai.ModelDownloader
import com.nadrlab.visionai.domain.ModelState
import com.nadrlab.visionai.vm.MainViewModel
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ModelScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val downloader = viewModel.modelDownloader
    val dlState by downloader.state.collectAsState()
    val progress by downloader.progress.collectAsState()
    val statusMsg by downloader.statusMessage.collectAsState()
    val memUsage by viewModel.localLlm.memUsage.collectAsState()
    val scope = rememberCoroutineScope()

    // File picker for GGUF
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val inputStream = context.contentResolver.openInputStream(it)
            if (inputStream != null) {
                // Copy to temp then import
                scope.launch {
                    val tempFile = File(context.cacheDir, "import_temp.gguf")
                    try {
                        inputStream.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output, bufferSize = 65536)
                            }
                        }
                        downloader.importFromFile(tempFile)
                    } finally {
                        tempFile.delete()
                    }
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

        // ═══ معلومات النموذج ═══
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("النموذج الحالي", color = Color.Gray, fontSize = 12.sp)
                Text(ModelDownloader.MODEL_FILENAME, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                InfoRow("الحجم:", "${ModelDownloader.MODEL_SIZE_MB} MB")
                InfoRow("المساحة المتاحة:", "${downloader.getAvailableSpaceMb()} MB")
                InfoRow("الحالة:", stateLabel(dlState), stateColor(dlState))
                if (statusMsg.isNotBlank()) {
                    Text(statusMsg, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
                if (memUsage > 0) {
                    InfoRow("استخدام الذاكرة:", "${memUsage / (1024 * 1024)} MB")
                }
            }
        }

        // ═══ شريط التقدم ═══
        if (dlState == ModelState.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(Color(0xFF1A1A2E), RoundedCornerShape(5.dp)),
                color = Color(0xFF38BDF8),
                trackColor = Color(0xFF1A1A2E)
            )
            Text("${(progress * 100).toInt()}%", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        // ═══ الأزرار ═══
        when (dlState) {
            ModelState.NOT_DOWNLOADED, ModelState.ERROR -> {

                // تنبيه RAM
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A1A)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("💡 نصيحة", color = Color(0xFFE8C547), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "الأجهزة ذات الذاكرة المحدودة يُفضل تحميل النموذج من خلال المتصفح أو Termux ثم استيراده.",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "الملف: Qwen3-1.7B-Q4_K_M.gguf\nمن: huggingface.co/unsloth/Qwen3-1.7B-GGUF",
                            color = Color(0xFF888888),
                            fontSize = 11.sp
                        )
                    }
                }

                // زر استيراد من الجهاز
                Button(
                    onClick = { filePicker.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("استيراد من الجهاز", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("اختر ملف GGUF من التحميلات", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }

                // زر البحث التلقائي
                Button(
                    onClick = {
                        scope.launch {
                            val found = downloader.findModelOnDevice()
                            if (found != null) {
                                downloader.importFromFile(found)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A5F)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("بحث تلقائي عن النموذج", color = Color.White)
                }

                // زر تنزيل (قد يفشل بسبب RAM)
                OutlinedButton(
                    onClick = { scope.launch { downloader.download() } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = Color.Gray)
                    Spacer(Modifier.width(8.dp))
                    Text("تنزيل من الإنترنت (قد يفشل في الأجهزة المحدودة)", color = Color.Gray, fontSize = 12.sp)
                }
            }
            ModelState.DOWNLOADING -> {
                Button(
                    onClick = { downloader.cancelDownload() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("إلغاء", color = Color.White)
                }
            }
            ModelState.READY -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2A1A)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "✅ النموذج جاهز. يمكنك الآن استخدام التحليل المحلي.",
                        color = Color(0xFF4CAF50),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { scope.launch { viewModel.localLlm.loadModel() } },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("تحميل النموذج", color = Color.White)
                    }
                    OutlinedButton(
                        onClick = { downloader.deleteModel() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("حذف", color = Color(0xFFF44336))
                    }
                }
            }
            ModelState.LOADING -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.CenterHorizontally),
                    color = Color(0xFF38BDF8)
                )
            }
            ModelState.LOADED -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2A1A)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "✅ النموذج مُحمّل ويعمل",
                        color = Color(0xFF4CAF50),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(14.dp)
                    )
                }
                Button(
                    onClick = { viewModel.localLlm.unloadModel() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("إيقاف النموذج", color = Color.White)
                }
            }
        }

        // ═══ طريقة التحميل اليدوي ═══
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("📥 تحميل يدوي من Termux", color = Color(0xFFE8C547), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "cd ~/csv-files\nwget -c \"https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf\"",
                        color = Color(0xFF4CAF50),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp),
                        lineHeight = 16.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "ثم اضغط 'استيراد من الجهاز' واختر الملف من csv-files",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
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
    ModelState.LOADED -> "مُحمّل و يعمل"
    ModelState.ERROR -> "خطأ"
}

private fun stateColor(state: ModelState): Color = when (state) {
    ModelState.READY, ModelState.LOADED -> Color(0xFF4CAF50)
    ModelState.ERROR -> Color(0xFFF44336)
    else -> Color(0xFFFFC107)
}
