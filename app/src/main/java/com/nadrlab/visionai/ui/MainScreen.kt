package com.nadrlab.visionai.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadrlab.visionai.ai.ModelDownloader
import com.nadrlab.visionai.domain.ModelState
import com.nadrlab.visionai.vm.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun ModelScreen(viewModel: MainViewModel) {
    val downloader = viewModel.modelDownloader
    val dlState by downloader.state.collectAsState()
    val progress by downloader.progress.collectAsState()
    val llmState by viewModel.localLlm.state.collectAsState()
    val memUsage by viewModel.localLlm.memUsage.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("إدارة النموذج المحلي", color = Color(0xFF38BDF8), fontSize = 22.sp, fontWeight = FontWeight.Bold)

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

                if (memUsage > 0) {
                    InfoRow("استخدام الذاكرة:", "${memUsage / (1024 * 1024)} MB")
                }
            }
        }

        if (dlState == ModelState.DOWNLOADING) {
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
        }

        when (dlState) {
            ModelState.NOT_DOWNLOADED, ModelState.ERROR -> {
                if (!viewModel.localLlm.hasEnoughRam()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A1A)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            "ذاكرة الجهاز غير كافية. أغلق التطبيقات الأخرى أولاً.",
                            color = Color(0xFFF44336),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
                Button(
                    onClick = { scope.launch { downloader.download() } },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = Color.Black)
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
                        onClick = { scope.launch { viewModel.localLlm.loadModel() } },
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
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(32.dp)
                        .align(Alignment.CenterHorizontally),
                    color = Color(0xFF38BDF8)
                )
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

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = Color.White) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun stateLabel(state: ModelState): String = when (state) {
    ModelState.NOT_DOWNLOADED -> "غير مثبت"
    ModelState.DOWNLOADING -> "جاري التنزيل..."
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
