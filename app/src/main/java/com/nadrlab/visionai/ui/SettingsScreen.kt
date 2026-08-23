package com.nadrlab.visionai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadrlab.visionai.vm.MainViewModel

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings = viewModel.settings

    var providerName by remember { mutableStateOf(settings.providerName) }
    var providerUrl by remember { mutableStateOf(settings.providerUrl) }
    var providerKey by remember { mutableStateOf(settings.providerKey) }
    var providerModel by remember { mutableStateOf(settings.providerModel) }
    var searchEnabled by remember { mutableStateOf(settings.searchEnabled) }
    var ocrEnabled by remember { mutableStateOf(settings.ocrEnabled) }
    var saveHistory by remember { mutableStateOf(settings.saveHistory) }
    var showKey by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("الإعدادات", color = Color(0xFF38BDF8), fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // ═══ المزود السحابي ═══
        SettingsCard("المزود السحابي") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (settings.isCustomProviderConfigured()) "مزود مخصص نشط"
                    else "يستخدم VisionAI Cloud المجاني",
                    color = if (settings.isCustomProviderConfigured()) Color(0xFF4CAF50)
                    else Color(0xFFFF9800),
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            ProviderField(providerName, "اسم المزود", "مثال: ZenMux") {
                providerName = it; settings.providerName = it
            }
            Spacer(Modifier.height(8.dp))
            ProviderField(providerUrl, "رابط API", "https://api.example.com/v1/chat/completions") {
                providerUrl = it; settings.providerUrl = it
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = providerKey,
                onValueChange = { providerKey = it; settings.providerKey = it },
                label = { Text("مفتاح API", color = Color(0xFF888888), fontSize = 12.sp) },
                placeholder = { Text("sk-...", color = Color(0xFF555555), fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            null, tint = Color(0xFF888888), modifier = Modifier.size(20.dp)
                        )
                    }
                },
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF38BDF8),
                    unfocusedBorderColor = Color(0xFF333355),
                    cursorColor = Color(0xFF38BDF8)
                ),
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(Modifier.height(8.dp))
            ProviderField(providerModel, "اسم النموذج", "glm-5.3 / gpt-4o / ...") {
                providerModel = it; settings.providerModel = it
            }

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showPresets = !showPresets },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8))
                ) {
                    Icon(if (showPresets) Icons.Default.ExpandLess else Icons.Default.Tune, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("نماذج جاهزة", fontSize = 12.sp)
                }

                if (settings.isCustomProviderConfigured()) {
                    Button(
                        onClick = {
                            settings.clearProvider()
                            providerName = ""; providerUrl = ""; providerKey = ""; providerModel = "glm-5.3"
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B))
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("مسح المزود", fontSize = 12.sp)
                    }
                }
            }

            AnimatedVisibility(visible = showPresets) {
                Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PresetButton("ZenMux", "glm-5.3") {
                        providerName = "ZenMux"
                        providerUrl = "https://zenmux.ai/api/v1/chat/completions"
                        providerModel = "glm-5.3"
                        settings.saveProvider(providerName, providerUrl, providerKey, providerModel)
                    }
                    PresetButton("CometAPI", "glm-5.3") {
                        providerName = "CometAPI"
                        providerUrl = "https://api.cometapi.com/v1/chat/completions"
                        providerModel = "glm-5.3"
                        settings.saveProvider(providerName, providerUrl, providerKey, providerModel)
                    }
                    PresetButton("OpenRouter Free", "deepseek-chat-v3") {
                        providerName = "OpenRouter"
                        providerUrl = "https://openrouter.ai/api/v1/chat/completions"
                        providerModel = "deepseek/deepseek-chat-v3-0324:free"
                        settings.saveProvider(providerName, providerUrl, providerKey, providerModel)
                    }
                    PresetButton("OpenRouter Vision", "gemini-2.0-flash") {
                        providerName = "OpenRouter Vision"
                        providerUrl = "https://openrouter.ai/api/v1/chat/completions"
                        providerModel = "google/gemini-2.0-flash-exp:free"
                        settings.saveProvider(providerName, providerUrl, providerKey, providerModel)
                    }
                }
            }
        }

        // ═══ الميزات ═══
        SettingsCard("الميزات") {
            SettingsSwitch("استخراج النصوص (OCR)", ocrEnabled) { ocrEnabled = it; settings.ocrEnabled = it }
            SettingsSwitch("البحث في الإنترنت", searchEnabled) { searchEnabled = it; settings.searchEnabled = it }
            SettingsSwitch("حفظ سجل التحليلات", saveHistory) { saveHistory = it; settings.saveHistory = it }
        }

        // ═══ الخصوصية ═══
        SettingsCard("الخصوصية") {
            Text("بدون مزود مخصص: الصورة تُرسل لـ VisionAI Cloud المجاني", color = Color(0xFFFF9800), fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text("مع مزود مخصص: الصورة تُرسل للمزود الذي اخترته", color = Color(0xFF38BDF8), fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text("الشات يستخدم نفس المزود النشط", color = Color(0xFF888888), fontSize = 11.sp)
        }

        // ═══ حول ═══
        SettingsCard("حول التطبيق") {
            Text("Vision AI v2.0", color = Color(0xFF38BDF8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("تحليل صور + محادثة بالذكاء الاصطناعي", color = Color(0xFF888888), fontSize = 12.sp)
        }

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Color(0xFFE8C547), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun SettingsSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.White, fontSize = 13.sp)
        Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF38BDF8))
        )
    }
}

@Composable
fun ProviderField(value: String, label: String, placeholder: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = Color(0xFF888888), fontSize = 12.sp) },
        placeholder = { Text(placeholder, color = Color(0xFF555555), fontSize = 12.sp) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 13.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(0xFF38BDF8),
            unfocusedBorderColor = Color(0xFF333355),
            cursorColor = Color(0xFF38BDF8)
        ),
        shape = RoundedCornerShape(10.dp)
    )
}

@Composable
fun PresetButton(name: String, model: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(model, fontSize = 10.sp, color = Color(0xFF888888))
        }
    }
}
