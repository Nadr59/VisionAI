package com.nadrlab.visionai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadrlab.visionai.domain.AiMode
import com.nadrlab.visionai.vm.MainViewModel

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings = viewModel.settings
    var searchEnabled by remember { mutableStateOf(settings.searchEnabled) }
    var ocrEnabled by remember { mutableStateOf(settings.ocrEnabled) }
    var saveHistory by remember { mutableStateOf(settings.saveHistory) }
    var aiMode by remember { mutableStateOf(settings.aiMode) }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0D0D0D)).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("الإعدادات", color = Color(0xFF38BDF8), fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // ═══ وضع AI ═══
        SettingsCard("وضع الذكاء الاصطناعي") {
            AiMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = aiMode == mode.name,
                        onClick = { aiMode = mode.name; settings.aiMode = mode.name },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF38BDF8))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(mode.label, color = Color.White, fontSize = 14.sp)
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
            Text("التحليل المحلي: الصورة لا تغادر الجهاز", color = Color(0xFF4CAF50), fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text("التحليل السحابي: الصورة تُرسل للمزود", color = Color(0xFFFF9800), fontSize = 12.sp)
        }

        Spacer(Modifier.height(80.dp))
    }
}
        // ═══ ZenMux ═══
        item {
            SettingsSection("ZenMux API (مباشر)") {
                SettingsTextField(
                    label = "API Key",
                    value = settings.zenmuxKey,
                    onValueChange = { settings.zenmuxKey = it },
                    placeholder = "zm-..."
                )
                SettingsTextField(
                    label = "النموذج",
                    value = settings.zenmuxModel,
                    onValueChange = { settings.zenmuxModel = it },
                    placeholder = "z-ai/glm-5.3-free"
                )
                SettingsTextField(
                    label = "الرابط",
                    value = settings.zenmuxUrl,
                    onValueChange = { settings.zenmuxUrl = it },
                    placeholder = "https://zenmux.ai/api/chat/completions"
                )
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
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF38BDF8)))
    }
}
