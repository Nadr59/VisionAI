package com.nadrlab.visionai.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.nadrlab.visionai.ai.CloudVisionManager
import com.nadrlab.visionai.domain.*
import com.nadrlab.visionai.vm.MainViewModel
import java.io.File

@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val selectedImage by vm.selectedImage.collectAsState()
    val analysisType by vm.analysisType.collectAsState()
    val chatHistory by vm.chatHistory.collectAsState()
    val isChatLoading by vm.isChatLoading.collectAsState()
    val serviceStatus by vm.serviceStatus.collectAsState()
    val isCheckingStatus by vm.isCheckingStatus.collectAsState()
    val webSearch by vm.webSearch.collectAsState()

    var chatInput by remember { mutableStateOf("") }
    var searchInput by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    vm.selectImage(bitmap, it)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "فشل تحميل الصورة", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            val uri = tempCameraUri
            if (uri != null) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    if (bitmap != null) {
                        vm.selectImage(bitmap, uri)
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            try {
                val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                tempCameraUri = uri
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                Toast.makeText(context, "فشل فتح الكاميرا", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "إذن الكاميرا مطلوب", Toast.LENGTH_SHORT).show()
        }
    }

    fun openCamera() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            try {
                val file = File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                )
                tempCameraUri = uri
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                Toast.makeText(context, "فشل فتح الكاميرا", Toast.LENGTH_SHORT).show()
            }
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(Unit) {
        vm.checkServiceStatus()
    }

    SelectionContainer {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0D0D))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ═══ بطاقة حالة الخدمة ═══
            item {
                ServiceStatusCard(
                    status = serviceStatus,
                    isChecking = isCheckingStatus,
                    onRefresh = { vm.checkServiceStatus() }
                )
            }

            // ═══ ═══ ═══ ═══ ═══ ═══ ═══ ═══
            // ═══ البحث في الإنترنت ═══
            // ═══ ═══ ═══ ═══ ═══ ═══ ═══ ═══
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Search, null,
                                tint = Color(0xFFE8C547),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "البحث في الإنترنت",
                                color = Color(0xFFE8C547),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = searchInput,
                                onValueChange = { searchInput = it },
                                modifier = Modifier.weight(1f),
                                placeholder = {
                                    Text("ابحث عن أي شيء...", color = Color(0xFF555555))
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                textStyle = LocalTextStyle.current.copy(
                                    color = Color.White, fontSize = 13.sp
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color(0xFF38BDF8),
                                    unfocusedBorderColor = Color(0xFF333355),
                                    cursorColor = Color(0xFF38BDF8)
                                )
                            )

                            IconButton(
                                onClick = {
                                    if (searchInput.isNotBlank()) {
                                        vm.searchWeb(searchInput)
                                    }
                                },
                                enabled = searchInput.isNotBlank() && !webSearch.isLoading,
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(
                                        if (searchInput.isNotBlank() && !webSearch.isLoading)
                                            Color(0xFF38BDF8)
                                        else Color(0xFF252542),
                                        CircleShape
                                    )
                            ) {
                                if (webSearch.isLoading) {
                                    CircularProgressIndicator(
                                        Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Search, "بحث",
                                        tint = if (searchInput.isNotBlank())
                                            Color.White else Color(0xFF555555)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ═══ نتائج البحث ═══
            if (webSearch.results.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "نتائج البحث: ${webSearch.query}",
                            fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            color = Color(0xFFE8C547)
                        )
                        IconButton(
                            onClick = { vm.clearSearch() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close, "مسح",
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFF888888)
                            )
                        }
                    }
                }
                items(webSearch.results) { result ->
                    SearchResultCard(result)
                }
            }

            // ═══ خطأ البحث ═══
            if (webSearch.error.isNotBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFF6B6B).copy(0.1f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFFF6B6B))
                            Spacer(Modifier.width(8.dp))
                            Text(webSearch.error, color = Color(0xFFFF6B6B), fontSize = 13.sp)
                        }
                    }
                }
            }

            // ═══ صورة ═══
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
                ) {
                    if (selectedImage != null) {
                        Box {
                            Image(
                                bitmap = selectedImage!!.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().height(250.dp),
                                contentScale = ContentScale.Crop
                            )
                            IconButton(
                                onClick = { vm.clearImage() },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .background(Color.Black.copy(0.5f), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, "إزالة", tint = Color.White)
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.AddPhotoAlternate, null,
                                    Modifier.size(48.dp), tint = Color(0xFF38BDF8)
                                )
                                Text("اختر صورة للتحليل", color = Color(0xFF888888))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { imagePicker.launch("image/*") },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF38BDF8)
                                        )
                                    ) {
                                        Icon(Icons.Default.Image, null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("معرض")
                                    }
                                    Button(
                                        onClick = { openCamera() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF252542)
                                        )
                                    ) {
                                        Icon(Icons.Default.CameraAlt, null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("كاميرا")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ═══ نوع التحليل ═══
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "نوع التحليل",
                            color = Color(0xFFE8C547),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))

                        val types = AnalysisType.entries
                        val rows = types.chunked(3)

                        rows.forEach { rowTypes ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                rowTypes.forEach { type ->
                                    AnalysisChip(
                                        type = type,
                                        selected = analysisType,
                                        onSelect = { vm.setAnalysisType(it) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                repeat(3 - rowTypes.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            // ═══ زر التحليل ═══
            item {
                Button(
                    onClick = { vm.analyze() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = selectedImage != null && !state.isLoading,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(24.dp), Color.White, strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(state.progress.ifBlank { "جاري التحليل..." })
                    } else {
                        Icon(Icons.Default.Analytics, null)
                        Spacer(Modifier.width(8.dp))
                        Text("تحليل الصورة", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ═══ خطأ ═══
            if (state.error.isNotBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFF6B6B).copy(0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, null, tint = Color(0xFFFF6B6B))
                            Spacer(Modifier.width(8.dp))
                            Text(state.error, color = Color(0xFFFF6B6B))
                        }
                    }
                }
            }

            // ═══ النتائج ═══
            state.result?.let { result ->
                item { AnalysisResultCard(result) }
            }

            // ═══ نتائج بحث التحليل ═══
            if (state.searchResults.isNotEmpty()) {
                item {
                    Text(
                        "نتائج متعلقة",
                        fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        color = Color(0xFFE8C547),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(state.searchResults) { result ->
                    SearchResultCard(result)
                }
            }

            // ═══ قسم الشات ═══
            if (state.result != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "اسأل عن النتائج",
                                color = Color(0xFFE8C547),
                                fontWeight = FontWeight.Bold, fontSize = 14.sp
                            )
                            Spacer(Modifier.height(8.dp))

                            chatHistory.forEach { msg ->
                                val isUser = msg.startsWith("USER:")
                                Text(
                                    msg.removePrefix("USER: ").removePrefix("AI: "),
                                    fontSize = 13.sp,
                                    color = if (isUser) Color(0xFF38BDF8) else Color.White,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }

                            if (isChatLoading) {
                                CircularProgressIndicator(
                                    Modifier.padding(8.dp).size(20.dp),
                                    strokeWidth = 2.dp, color = Color(0xFF38BDF8)
                                )
                            }

                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = chatInput,
                                    onValueChange = { chatInput = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = {
                                        Text("اكتب سؤالك...", color = Color(0xFF555555))
                                    },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    textStyle = LocalTextStyle.current.copy(
                                        color = Color.White, fontSize = 13.sp
                                    ),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color(0xFF38BDF8),
                                        unfocusedBorderColor = Color(0xFF333355),
                                        cursorColor = Color(0xFF38BDF8)
                                    )
                                )
                                IconButton(
                                    onClick = {
                                        if (chatInput.isNotBlank()) {
                                            vm.askQuestion(chatInput)
                                            chatInput = ""
                                        }
                                    },
                                    enabled = chatInput.isNotBlank() && !isChatLoading
                                ) {
                                    Icon(
                                        Icons.Default.Send, "إرسال",
                                        tint = if (chatInput.isNotBlank() && !isChatLoading)
                                            Color(0xFF38BDF8) else Color(0xFF555555)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══ Chip مساعد ═══
@Composable
fun AnalysisChip(
    type: AnalysisType,
    selected: AnalysisType,
    onSelect: (AnalysisType) -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected == type,
        onClick = { onSelect(type) },
        label = {
            Text(
                type.labelAr,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Color(0xFF38BDF8),
            selectedLabelColor = Color.White,
            containerColor = Color(0xFF252542),
            labelColor = Color(0xFFCCCCCC)
        )
    )
}

// ═══ بطاقة حالة الخدمة ═══
@Composable
fun ServiceStatusCard(
    status: CloudVisionManager.ServiceStatus,
    isChecking: Boolean,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (status.online) Color(0xFF1A2E1A) else Color(0xFF2E1A1A)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (status.online) Color(0xFF4CAF50)
                                else if (status.error.isNotBlank()) Color(0xFFFF6B6B)
                                else Color(0xFFFF9800),
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (status.online) "الخدمة متصلة"
                        else if (status.error.isNotBlank()) "الخدمة غير متاحة"
                        else "جاري الفحص...",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                IconButton(
                    onClick = onRefresh,
                    enabled = !isChecking,
                    modifier = Modifier.size(32.dp)
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF38BDF8)
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh, "تحديث",
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFF38BDF8)
                        )
                    }
                }
            }

            if (status.provider.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("المزود: ${status.provider}", color = Color(0xFF888888), fontSize = 11.sp)
            }

            if (status.models.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "النماذج: ${status.models.joinToString("، ")}",
                    color = Color(0xFF38BDF8), fontSize = 11.sp
                )
            }

            if (status.remaining >= 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "الطلبات المتبقية: ${status.remaining}",
                    color = if (status.remaining > 10) Color(0xFF4CAF50)
                    else if (status.remaining > 3) Color(0xFFFF9800)
                    else Color(0xFFFF6B6B),
                    fontSize = 11.sp
                )
            }

            if (status.error.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(status.error, color = Color(0xFFFF6B6B), fontSize = 11.sp)
            }
        }
    }
}

// ═══ بطاقة نتيجة التحليل ═══
@Composable
fun AnalysisResultCard(result: AnalysisResult) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        result.contentType,
                        fontWeight = FontWeight.Bold, fontSize = 18.sp,
                        color = Color(0xFF38BDF8)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(result.confidence.icon, fontSize = 14.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(result.confidence.label, fontSize = 11.sp, color = Color(0xFF888888))
                }

                IconButton(
                    onClick = {
                        val fullText = buildString {
                            append("نوع المحتوى: ${result.contentType}\n")
                            append("الثقة: ${result.confidence.label}\n\n")
                            append("${result.description}\n\n")
                            if (result.elements.isNotEmpty()) {
                                append("العناصر:\n")
                                result.elements.forEach { append("• $it\n") }
                                append("\n")
                            }
                            if (result.extractedText.isNotBlank()) {
                                append("النص المستخرج:\n${result.extractedText}\n\n")
                            }
                            if (result.keywords.isNotEmpty()) {
                                append("الكلمات المفتاحية: ${result.keywords.joinToString("، ")}\n\n")
                            }
                            if (result.additionalInfo.isNotBlank()) {
                                append(result.additionalInfo)
                            }
                        }
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("analysis", fullText)
                        )
                        Toast.makeText(context, "تم النسخ", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy, "نسخ",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(result.description, fontSize = 14.sp, color = Color.White)

            if (result.elements.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "العناصر:", fontWeight = FontWeight.Bold,
                    fontSize = 13.sp, color = Color(0xFFE8C547)
                )
                result.elements.forEach {
                    Text(
                        "• $it", fontSize = 13.sp, color = Color(0xFFCCCCCC),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            if (result.extractedText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "النص المستخرج:", fontWeight = FontWeight.Bold,
                    fontSize = 13.sp, color = Color(0xFFE8C547)
                )
                Text(result.extractedText, fontSize = 13.sp, color = Color(0xFFCCCCCC))
            }

            if (result.keywords.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "الكلمات المفتاحية:", fontWeight = FontWeight.Bold,
                    fontSize = 13.sp, color = Color(0xFFE8C547)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    result.keywords.take(5).forEach {
                        SuggestionChip(
                            onClick = {},
                            label = { Text(it, fontSize = 11.sp) },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = Color(0xFF252542),
                                labelColor = Color(0xFFCCCCCC)
                            )
                        )
                    }
                }
            }

            if (result.additionalInfo.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(result.additionalInfo, fontSize = 12.sp, color = Color(0xFF888888))
            }
        }
    }
}

// ═══ بطاقة نتيجة بحث ═══
@Composable
fun SearchResultCard(result: SearchResult) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF252542))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                result.title,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = Color(0xFF38BDF8),
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(context, "لا يمكن فتح الرابط", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            if (result.snippet.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    result.snippet, fontSize = 12.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color(0xFFCCCCCC)
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    result.source,
                    fontSize = 11.sp,
                    color = Color(0xFF888888),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                )

                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("url", result.url)
                        )
                        Toast.makeText(context, "تم نسخ الرابط", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy, "نسخ الرابط",
                        tint = Color(0xFF888888),
                        modifier = Modifier.size(14.dp)
                    )
                }

                IconButton(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(context, "لا يمكن فتح الرابط", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.OpenInBrowser, "فتح",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
