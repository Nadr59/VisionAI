package com.nadrlab.visionai.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.nadrlab.visionai.ai.ImageProcessor
import com.nadrlab.visionai.domain.*
import com.nadrlab.visionai.vm.MainViewModel
import java.io.File

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val image by viewModel.selectedImage.collectAsState()
    val analysisType by viewModel.analysisType.collectAsState()
    val aiMode by viewModel.aiMode.collectAsState()
    val state by viewModel.state.collectAsState()
    val ocrResult by viewModel.ocrResult.collectAsState()

    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImageSelected(context, it, viewModel) }
    }

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempPhotoUri != null) {
            handleImageSelected(context, tempPhotoUri!!, viewModel)
        }
    }

    // Permission launcher
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            tempPhotoUri = createTempPhotoUri(context)
            cameraLauncher.launch(tempPhotoUri!!)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ═══ العنوان ═══
        item {
            Text(
                "تحليل الصور بالذكاء الاصطناعي",
                color = Color(0xFF38BDF8),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text("صورة واحدة تكشف ألف معلومة", color = Color.Gray, fontSize = 13.sp)
        }

        // ═══ اختيار الصورة ═══
        item {
            if (image != null) {
                Box(modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp))) {
                    Image(
                        bitmap = image!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // زر حذف
                    IconButton(
                        onClick = { viewModel.clearImage() },
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    ) {
                        Icon(Icons.Default.Close, "إزالة", tint = Color.White,
                            modifier = Modifier.background(Color.Black.copy(alpha=0.5f), RoundedCornerShape(20.dp)).padding(4.dp))
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.Image, null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("اختر صورة أو التقط واحدة", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            }
        }

        // ═══ أزرار الصورة ═══
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A5F)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("المعرض")
                }
                Button(
                    onClick = { permLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A5F)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("الكاميرا")
                }
            }
        }

        // ═══ نوع التحليل ═══
        item {
            Text("نوع التحليل:", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AnalysisType.entries) { type ->
                    AnalysisTypeChip(
                        type = type,
                        selected = analysisType == type,
                        onClick = { viewModel.setAnalysisType(type) }  
                    }
                }
            }
        }

        // ═══ وضع AI ═══
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiMode.entries.forEach { mode ->
                    FilterChip(
                        selected = aiMode == mode,
                        onClick = { viewModel.setAiMode(mode) },
                        label = { Text(mode.label, fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF38BDF8).copy(alpha = 0.2f)
                        )
                    )
                }
            }
        }

        // ═══ زر التحليل ═══
        item {
            Button(
                onClick = { viewModel.analyze() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = image != null && !state.isLoading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8)),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.Black, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(state.progress.ifBlank { "جاري التحليل..." }, color = Color.Black)
                } else {
                    Icon(Icons.Default.AutoAwesome, null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text("بدء التحليل", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ═══ الخطأ ═══
        if (state.error.isNotBlank()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1A1A)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(state.error, color = Color.Red, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
                }
            }
        }

        // ═══ النتائج ═══
        state.result?.let { result ->
            item {
                Text("النتائج", color = Color(0xFF38BDF8), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }

            // الوضع المستخدم
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "الوضع: ${state.usedMode.label}",
                        color = Color.Gray, fontSize = 11.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            item { ConfidenceBadge(result.confidence) }

            if (result.contentType.isNotBlank()) {
                item { ResultSection("نوع المحتوى", "📋", Color(0xFF38BDF8), result.contentType) }
            }

            if (result.description.isNotBlank()) {
                item { ResultSection("الوصف", "📝", Color(0xFFE8C547), result.description, onCopy = { copyText(context, result.description) }) }
            }

            if (result.elements.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("🔍 العناصر المكتشفة", color = Color(0xFF4CAF50), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            result.elements.forEach { ElementChip(it) }
                        }
                    }
                }
            }

            if (result.extractedText.isNotBlank()) {
                item { ResultSection("النص المستخرج", "📄", Color(0xFF9C27B0), result.extractedText, onCopy = { copyText(context, result.extractedText) }) }
            }

            if (result.keywords.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("🏷️ الكلمات المفتاحية", color = Color(0xFFFF9800), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(result.keywords) { kw ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A1A)),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(kw, color = Color(0xFFE8C547), fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (result.additionalInfo.isNotBlank()) {
                item { ResultSection("معلومات إضافية", "ℹ️", Color(0xFF38BDF8), result.additionalInfo) }
            }
        }

        // ═══ نتائج البحث ═══
        if (state.searchResults.isNotEmpty()) {
            item {
                Text("🌐 مواقع مرتبطة بالصورة", color = Color(0xFF4CAF50), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            items(state.searchResults) { result ->
                SearchResultCard(result, context)
            }
        }

        // ═══ مساحة سفلية ═══
        item { Spacer(Modifier.height(80.dp)) }
    }
}

private fun handleImageSelected(context: Context, uri: Uri, viewModel: MainViewModel) {
    val bitmap = ImageProcessor.loadBitmap(context, uri)
    if (bitmap != null) {
        viewModel.selectImage(bitmap, uri)
    } else {
        Toast.makeText(context, "فشل تحميل الصورة", Toast.LENGTH_SHORT).show()
    }
}

private fun createTempPhotoUri(context: Context): Uri {
    val dir = File(context.filesDir, "photos").apply { mkdirs() }
    val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
    return androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
}

private fun copyText(context: Context, text: String) {
    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clip.setPrimaryClip(ClipData.newPlainText("result", text))
    Toast.makeText(context, "تم النسخ", Toast.LENGTH_SHORT).show()
}
