package com.nadrlab.visionai.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadrlab.visionai.domain.*
import com.nadrlab.visionai.vm.MainViewModel

@Composable
fun MainScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val selectedImage by vm.selectedImage.collectAsState()
    val analysisType by vm.analysisType.collectAsState()
    val chatHistory by vm.chatHistory.collectAsState()
    val isChatLoading by vm.isChatLoading.collectAsState()

    var chatInput by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val bitmap = android.provider.MediaStore.Images.Media.getBitmap(
                context.contentResolver, it
            )
            vm.selectImage(bitmap, it)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ═══ صورة ═══
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A1A2E)
                )
            ) {
                if (selectedImage != null) {
                    Box {
                        Image(
                            bitmap = selectedImage!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.AddPhotoAlternate,
                                null,
                                Modifier.size(48.dp),
                                tint = Color(0xFF38BDF8)
                            )
                            Text(
                                "اضغط لاختيار صورة",
                                color = Color(0xFF888888)
                            )
                            Button(
                                onClick = { imagePicker.launch("image/*") },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF38BDF8)
                                )
                            ) {
                                Icon(Icons.Default.Image, null)
                                Spacer(Modifier.width(8.dp))
                                Text("اختيار صورة")
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
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A1A2E)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "نوع التحليل",
                        color = Color(0xFFE8C547),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AnalysisType.entries.take(5).forEach { type ->
                            FilterChip(
                                selected = analysisType == type,
                                onClick = { vm.setAnalysisType(type) },
                                label = {
                                    Text(
                                        type.labelAr,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF38BDF8),
                                    selectedLabelColor = Color.White,
                                    containerColor = Color(0xFF252542),
                                    labelColor = Color(0xFFCCCCCC)
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AnalysisType.entries.drop(5).forEach { type ->
                            FilterChip(
                                selected = analysisType == type,
                                onClick = { vm.setAnalysisType(type) },
                                label = {
                                    Text(
                                        type.labelAr,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFF38BDF8),
                                    selectedLabelColor = Color.White,
                                    containerColor = Color(0xFF252542),
                                    labelColor = Color(0xFFCCCCCC)
                                )
                            )
                        }
                    }
                }
            }
        }

        // ═══ زر التحليل ═══
        item {
            Button(
                onClick = { vm.analyze() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = selectedImage != null && !state.isLoading,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF38BDF8)
                )
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        Modifier.size(24.dp),
                        Color.White,
                        strokeWidth = 2.dp
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
                        Icon(
                            Icons.Default.Error,
                            null,
                            tint = Color(0xFFFF6B6B)
                        )
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

        // ═══ نتائج البحث ═══
        if (state.searchResults.isNotEmpty()) {
            item {
                Text(
                    "نتائج البحث",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color(0xFFE8C547),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(state.searchResults) { SearchResultCard(it) }
        }

        // ═══ قسم الشات ═══
        if (state.result != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1A1A2E)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "اسأل عن النتائج",
                            color = Color(0xFFE8C547),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
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
                                Modifier
                                    .padding(8.dp)
                                    .size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF38BDF8)
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
                                    Text(
                                        "اكتب سؤالك...",
                                        color = Color(0xFF555555)
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                textStyle = LocalTextStyle.current.copy(
                                    color = Color.White,
                                    fontSize = 13.sp
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
                                    Icons.Default.Send,
                                    "إرسال",
                                    tint = if (chatInput.isNotBlank() && !isChatLoading)
                                        Color(0xFF38BDF8)
                                    else Color(0xFF555555)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══ بطاقة نتيجة التحليل ═══
@Composable
fun AnalysisResultCard(result: AnalysisResult) {
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
                Text(
                    result.contentType,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color(0xFF38BDF8)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(result.confidence.icon, fontSize = 16.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        result.confidence.label,
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(result.description, fontSize = 14.sp, color = Color.White)

            if (result.elements.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "العناصر:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFFE8C547)
                )
                result.elements.forEach {
                    Text(
                        "• $it",
                        fontSize = 13.sp,
                        color = Color(0xFFCCCCCC),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            if (result.extractedText.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "النص المستخرج:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFFE8C547)
                )
                Text(result.extractedText, fontSize = 13.sp, color = Color(0xFFCCCCCC))
            }

            if (result.keywords.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "الكلمات المفتاحية:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFFE8C547)
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
                Text(
                    result.additionalInfo,
                    fontSize = 12.sp,
                    color = Color(0xFF888888)
                )
            }
        }
    }
}

// ═══ بطاقة نتيجة بحث ═══
@Composable
fun SearchResultCard(result: SearchResult) {
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
                color = Color.White
            )
            if (result.snippet.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    result.snippet,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFF888888)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(result.source, fontSize = 11.sp, color = Color(0xFF38BDF8))
        }
    }
}
