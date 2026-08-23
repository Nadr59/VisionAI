package com.nadrlab.visionai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadrlab.visionai.vm.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(vm: MainViewModel) {
    val chatHistory by vm.chatHistory.collectAsState()
    val isLoading by vm.isChatLoading.collectAsState()
    val selectedImage by vm.selectedImage.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(chatHistory.size) {
        if (chatHistory.isNotEmpty()) {
            listState.animateScrollToItem(chatHistory.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D0D))) {
        // ═══ Header ═══
        if (selectedImage != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF38BDF8).copy(0.1f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Image, null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("الشات مرتبط بالصورة المحددة", fontSize = 12.sp, color = Color(0xFF38BDF8))
                }
            }
        }

        // ═══ Messages ═══
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (chatHistory.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.ChatBubbleOutline, null,
                                modifier = Modifier.size(64.dp), tint = Color(0xFF555555)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("ابدأ محادثة مع Vision AI", color = Color(0xFF888888), fontSize = 16.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("اسأل عن أي شيء أو تحليل صورة", color = Color(0xFF666666), fontSize = 13.sp)
                        }
                    }
                }
            }

            items(chatHistory) { msg ->
                val isUser = msg.startsWith("USER:")
                val text = msg.removePrefix("USER: ").removePrefix("AI: ")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                ) {
                    if (!isUser) {
                        Box(
                            modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF38BDF8)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("AI", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Spacer(Modifier.width(6.dp))
                    }

                    Card(
                        shape = RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isUser) Color(0xFF38BDF8) else Color(0xFF1A1A2E)
                        ),
                        modifier = Modifier.widthIn(max = 300.dp)
                    ) {
                        Text(
                            text = text,
                            modifier = Modifier.padding(12.dp),
                            fontSize = 14.sp,
                            color = Color.White,
                            lineHeight = 20.sp
                        )
                    }

                    if (isUser) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF03DAC6)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF38BDF8))
                        Spacer(Modifier.width(8.dp))
                        Text("جاري الكتابة...", fontSize = 12.sp, color = Color(0xFF888888))
                    }
                }
            }
        }

        // ═══ Input ═══
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (chatHistory.isNotEmpty()) {
                    IconButton(onClick = { vm.clearChat() }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.DeleteSweep, "مسح", modifier = Modifier.size(20.dp), tint = Color(0xFF888888))
                    }
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("اكتب رسالتك...", color = Color(0xFF555555)) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 3,
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF333355),
                        cursorColor = Color(0xFF38BDF8)
                    )
                )

                IconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            vm.askQuestion(inputText)
                            inputText = ""
                            coroutineScope.launch {
                                listState.animateScrollToItem(maxOf(0, chatHistory.size))
                            }
                        }
                    },
                    enabled = inputText.isNotBlank() && !isLoading,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (inputText.isNotBlank() && !isLoading) Color(0xFF38BDF8)
                            else Color(0xFF252542)
                        )
                ) {
                    Icon(
                        Icons.Default.Send, "إرسال",
                        tint = if (inputText.isNotBlank() && !isLoading) Color.White else Color(0xFF555555)
                    )
                }
            }
        }
    }
}
