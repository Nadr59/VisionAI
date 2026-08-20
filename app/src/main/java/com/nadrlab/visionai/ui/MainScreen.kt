package com.nadrlab.visionai.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.nadrlab.visionai.ai.ImageProcessor
import com.nadrlab.visionai.domain.AiMode
import com.nadrlab.visionai.domain.AnalysisType
import com.nadrlab.visionai.domain.ConfidenceLevel
import com.nadrlab.visionai.domain.SearchResult
import com.nadrlab.visionai.vm.MainViewModel
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val image by viewModel.selectedImage.collectAsState()
    val analysisType by viewModel.analysisType.collectAsState()
    val aiMode by viewModel.aiMode.collectAsState()
    val state by viewModel.state.collectAsState()
    val chatHistory by viewModel.chatHistory.collectAsState()
    val isChatLoading by viewModel.isChatLoading.collectAsState()
    val lastAnalysisText by viewModel.lastAnalysisText.collectAsState()

    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var questionText by remember { mutableStateOf("") }

    // ═══ Gallery launcher ═══
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bitmap = ImageProcessor.loadBitmap(context, it)
            if (bitmap != null) {
                viewModel.selectImage(bitmap, it)
            } else {
                Toast.makeText(context, "فشل تحميل الصورة", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ═══ Camera launcher ═══
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempPhotoUri != null) {
            val bitmap = ImageProcessor.loadBitmap(context, tempPhotoUri!!)
            if (bitmap != null) {
                viewModel.selectImage(bitmap, tempPhotoUri!!)
            }
        }
    }

    // ═══ Permission launcher ═══
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val dir = File(context.filesDir, "photos").apply { mkdirs() }
            val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            tempPhotoUri = uri
            cameraLauncher.launch(uri)
        }
    }

    // ═══ Main content ═══
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ─── العنوان ───
        item {
            Text(
                "تحليل الصور بالذكاء الاصطناعي",
                color = Color(0xFF38BDF8),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text("صورة واحدة تكشف ألف معلومة", color = Color.Gray, fontSize = 12.sp)
        }

        // ─── الصورة ───
        item {
            if (image != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(16.dp))
                ) {
                    Image(
                        bitmap = image!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    IconButton(
                        onClick = { viewModel.clearImage() },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "إزالة",
                            tint = Color.White,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                                .padding(4.dp)
                        )
                    }
                }
            } else {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Image, null, tint = Color.Gray, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("اختر صورة أو التقط واحدة", color = Color.Gray, fontSize = 13.sp)
                    }
                }
            }
        }

        // ─── أزرار الصورة ───
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A5F)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("المعرض", fontSize = 13.sp)
                }
                Button(
                    onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A5F)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("الكاميرا", fontSize = 13.sp)
                }
            }
        }

        // ─── نوع التحليل ───
        item {
            Text("نوع التحليل:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(AnalysisType.entries.toList()) { type ->
                    AnalysisTypeChip(
                        type = type,
                        selected = analysisType == type,
                        onClick = { viewModel.setAnalysisType(type) }
                    )
                }
            }
        }

        // ─── وضع AI ───
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AiMode.CLOUD, AiMode.AUTO).forEach { mode ->
                    FilterChip(
                        selected = aiMode == mode,
                        onClick = { viewModel.setAiMode(mode) },
                        label = { Text(mode.label, fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF38BDF8).copy(alpha = 0.2f)
                        )
                    )
                }
            }
            val modeDesc = when (aiMode) {
                AiMode.LOCAL -> "النموذج المحلي (غير مُوصى به — بطيء جداً)"
                AiMode.CLOUD -> "رؤية سحابية (سريع — يحلل الصورة بالكامل)"
                AiMode.AUTO -> "تلقائي: يستخدم السحابي"
            }
            Text(modeDesc, color = Color(0xFF666666), fontSize = 10.sp)
            }
            val modeDesc = when (aiMode) {
                AiMode.LOCAL -> "النموذج النصي المحلي (يعالج النصوص فقط — لا يرى الصور)"
                AiMode.CLOUD -> "رؤية سحابية (يحلل الصورة بالكامل)"
                AiMode.AUTO -> "تلقائي: سحابي للصور + محلي للنصوص"
            }
            Text(modeDesc, color = Color(0xFF666666), fontSize = 10.sp)
        }

        // ─── زر التحليل ───
        item {
            Button(
                onClick = { viewModel.analyze() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = image != null && !state.isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(state.progress.ifBlank { "جاري التحليل..." }, color = Color.Black)
                } else {
                    Icon(Icons.Default.AutoAwesome, null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("بدء التحليل", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ─── الخطأ ───
        if (state.error.isNotBlank()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A1A)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(state.error, color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                }
            }
        }

        // ─── النتائج ───
        state.result?.let { result ->
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("النتائج", color = Color(0xFF38BDF8), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            "الوضع: ${state.usedMode.label}",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            item { ConfidenceBadge(result.confidence) }

            if (result.contentType.isNotBlank()) {
                item {
                    ResultSection("نوع المحتوى", "\uD83D\uDCCB", Color(0xFF38BDF8), result.contentType, null)
                }
            }

            if (result.description.isNotBlank()) {
                item {
                    ResultSection("الوصف", "\uD83D\uDCDD", Color(0xFFE8C547), result.description) {
                        copyText(context, result.description)
                    }
                }
            }

            if (result.elements.isNotEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("\uD83D\uDD0D العناصر المكتشفة", color = Color(0xFF4CAF50), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            result.elements.forEach { ElementChip(it) }
                        }
                    }
                }
            }

            if (result.extractedText.isNotBlank()) {
                item {
                    ResultSection("النص المستخرج", "\uD83D\uDCC4", Color(0xFF9C27B0), result.extractedText) {
                        copyText(context, result.extractedText)
                    }
                }
            }

            if (result.keywords.isNotEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("\uD83C\uDFF7\uFE0F الكلمات المفتاحية", color = Color(0xFFFF9800), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(result.keywords) { kw ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A1A)),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            kw,
                                            color = Color(0xFFE8C547),
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (result.additionalInfo.isNotBlank()) {
                item {
                    ResultSection("معلومات إضافية", "\u2139\uFE0F", Color(0xFF38BDF8), result.additionalInfo, null)
                }
            }
        }

        // ─── نتائج البحث ───
        if (state.searchResults.isNotEmpty()) {
            item {
                Text(
                    "\uD83C\uDF10 مواقع مرتبطة بالصورة",
                    color = Color(0xFF4CAF50),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            items(state.searchResults) { result ->
                SearchResultCard(result, context)
            }
        }

        // ─── قسم المساعد النصي المحلي ───
        item {
            Spacer(Modifier.height(4.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "\uD83E\uDD16 المساعد النصي المحلي",
                        color = Color(0xFFE8C547),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "اسأل عن النتائج، ترجم، لخّص، أو اطلب تحسين الصياغة",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )

                    // ─── سجل المحادثة ───
                    if (chatHistory.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        chatHistory.forEach { msg ->
                            val isUser = msg.startsWith("USER:")
                            val text = msg.removePrefix("USER:").removePrefix("AI:").trim()
                            Card(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isUser) Color(0xFF0D2137) else Color(0xFF1A2A1A)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(
                                        if (isUser) "أنت" else "المساعد",
                                        color = if (isUser) Color(0xFF38BDF8) else Color(0xFF4CAF50),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(text, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // ─── خانة السؤال ───
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = questionText,
                            onValueChange = { questionText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    "اسأل عن النتائج...",
                                    color = Color(0xFF555555),
                                    fontSize = 12.sp
                                )
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF38BDF8),
                                unfocusedBorderColor = Color(0xFF333333),
                                cursorColor = Color(0xFF38BDF8)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            singleLine = true
                        )
                        Spacer(Modifier.width(6.dp))
                        IconButton(
                            onClick = {
                                if (questionText.isNotBlank()) {
                                    viewModel.askLocalModel(questionText)
                                    questionText = ""
                                }
                            },
                            enabled = questionText.isNotBlank() && !isChatLoading
                        ) {
                            if (isChatLoading) {
                                CircularProgressIndicator(
                                    Modifier.size(22.dp),
                                    color = Color(0xFF38BDF8),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Send, "إرسال", tint = Color(0xFF38BDF8))
                            }
                        }
                    }

                    // ─── أزرار المعالجة (تظهر فقط بعد التحليل) ───
                    if (lastAnalysisText.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text("معالجة النتائج:", color = Color.Gray, fontSize = 10.sp)
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            QuickAction("\uD83D\uDCDD لخّص") {
                                viewModel.processResults(
                                    "لخّص النتائج التالية بإيجاز:\n$lastAnalysisText",
                                    "لخّص النتائج"
                                )
                            }
                            QuickAction("\uD83C\uDF0D ترجم إنجليزي") {
                                viewModel.processResults(
                                    "ترجم النتائج التالية إلى الإنجليزية:\n$lastAnalysisText",
                                    "ترجم للإنجليزي"
                                )
                            }
                            QuickAction("\uD83D\uDD0D كلمات مفتاحية") {
                                viewModel.processResults(
                                    "استخرج أهم الكلمات المفتاحية من النتائج التالية:\n$lastAnalysisText",
                                    "استخرج الكلمات المفتاحية"
                                )
                            }
                            QuickAction("\u270D\uFE0F أعد الصياغة") {
                                viewModel.processResults(
                                    "أعد صياغة النتائج التالية بطريقة أفضل وأوضح:\n$lastAnalysisText",
                                    "أعد الصياغة"
                                )
                            }
                        }
                    }
                }
            }
        }

        // ─── مساحة سفلية ───
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ═══════════════════════════════════════════
// مكونات مشتركة
// ═══════════════════════════════════════════

@Composable
fun ResultSection(
    title: String,
    icon: String,
    color: Color,
    content: String,
    onCopy: (() -> Unit)?
) {
    if (content.isBlank()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$icon $title", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                if (onCopy != null) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(content, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
fun ElementChip(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2137)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            "\u2022 $text",
            color = Color(0xFF38BDF8),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun ConfidenceBadge(level: ConfidenceLevel) {
    val color = when (level) {
        ConfidenceLevel.HIGH -> Color(0xFF4CAF50)
        ConfidenceLevel.MEDIUM -> Color(0xFFFFC107)
        ConfidenceLevel.LOW -> Color(0xFFFF9800)
        ConfidenceLevel.UNCERTAIN -> Color(0xFFF44336)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            "${level.icon} ${level.label}",
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
fun SearchResultCard(result: SearchResult, context: Context) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2E1A)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(result.title, color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(result.snippet, color = Color.Gray, fontSize = 10.sp, maxLines = 3)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(onClick = {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(result.url)))
                }) {
                    Text("فتح", fontSize = 11.sp)
                }
                androidx.compose.material3.TextButton(onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("url", result.url))
                }) {
                    Text("نسخ الرابط", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun AnalysisTypeChip(
    type: AnalysisType,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF1A3A5F) else Color(0xFF1A1A2E)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            type.labelAr,
            color = if (selected) Color(0xFF38BDF8) else Color.Gray,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun QuickAction(label: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2137)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            color = Color(0xFF38BDF8),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontWeight = FontWeight.Medium
        )
    }
}

private fun copyText(context: Context, text: String) {
    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clip.setPrimaryClip(ClipData.newPlainText("result", text))
    Toast.makeText(context, "تم النسخ", Toast.LENGTH_SHORT).show()
}
