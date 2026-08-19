package com.nadrlab.visionai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadrlab.visionai.ai.ModelDownloader
import com.nadrlab.visionai.domain.ModelState
import com.nadrlab.visionai.vm.MainViewModel

@Composable
fun ModelScreen(viewModel: MainViewModel) {
    val downloader = viewModel.modelDownloader
    val dlState by downloader.state.collectAsState()
    val progress by downloader.progress.collectAsState()
    val llmState by viewModel.localLlm.state.collectAsState()
    val memUsage by viewModel.localLlm.memUsage.collectAsState()

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0D0D0D)).padding(16.dp),
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("الحجم:", color = Color.Gray, fontSize = 12.sp)
                    Text("${ModelDownloader.MODEL_SIZE_MB} MB", color = Color.White, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("المساحة المتاحة:", color = Color.Gray, fontSize = 12.sp)
                    Text("${downloader.getAvailableSpaceMb()} MB", color = Color.White, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("الحالة:", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        when (dlState) {
                            ModelState.NOT_DOWNLOADED -> "غير مثبت"
                            ModelState.DOWNLOADING -> "جاري التنزيل..."
                            ModelState.READY -> "جاهز"
                            ModelState.LOADING -> "جاري التحميل..."
                            ModelState.LOADED -> "مُحمّل و يعمل"
                            ModelState.ERROR -> "خطأ"
                        },
                        color = when (dlState) {
                            ModelState.READY, ModelState.LOADED -> Color(0xFF4CAF50)
                            ModelState.ERROR -> Color(0xFFF44336)
                            else -> Color(0xFFFFC107)
                        },
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
                if (memUsage > 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("استخدام الذاكرة:", color = Color.Gray, fontSize = 12.sp)
                        Text("${memUsage / (1024*1024)} MB", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

        // ═══ شريط التقدم ═══
        if (dlState == ModelState.DOWNLOADING) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).background(Color(0xFF1A1A2E), RoundedCornerShape(4.dp)),
                color = Color(0xFF38BDF8),
                trackColor = Color(0xFF1A1A2E)
            )
            Text("${(progress * 100).toInt()}%", color = Color.White, fontSize = 13.sp)
        }

        // ═══ الأزرار ═══
        when (dlState) {
            ModelState.NOT_DOWNLOADING, ModelState.ERROR -> {
                if (!viewModel.localLlm.hasEnoughRam()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A1A)), shape = RoundedCornerShape(10.dp)) {
                        Text("ذاكرة الجهاز غير كافية. أغلق التطبيقات الأخرى أولاً.",
                            color = Color(0xFFF44336), fontSize = 12.sp, modifier = Modifier.padding(14.dp))
                    }
                }
                Button(
                    onClick = {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            downloader.download()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Download, null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("تنزيل النموذج (${ModelDownloader.MODEL_SIZE_MB} MB)", color = Color.Black)
                }
            }
            ModelState.DOWNLOADING -> {
                Button(
                    onClick = { downloader.cancelDownload() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("إلغاء التنزيل", color = Color.White)
                }
            }
            ModelState.READY -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                viewModel.localLlm.loadModel()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("تحميل", color = Color.White)
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
                CircularProgressIndicator(Modifier.size(32.dp).align(Alignment.CenterHorizontally), color = Color(0xFF38BDF8))
            }
            ModelState.LOADED -> {
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
    }
}
