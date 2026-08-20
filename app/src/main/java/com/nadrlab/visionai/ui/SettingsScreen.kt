package com.nadrlab.visionai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    var zenmuxKey by remember { mutableStateOf(settings.zenmuxKey) }
    var zenmuxModel by remember { mutableStateOf(settings.zenmuxModel) }
    var zenmuxUrl by remember { mutableStateOf(settings.zenmuxUrl) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("الإعدادات", color = Color(0xFF38BDF8), fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // ═══ وضع AI ═══
        SettingsCard("وضع الذكاء الاصطناعي") {
            AiMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
            SettingsSwitch("استخراج النصوص (OCR)", ocrEnabled) {
                ocrEnabled = it; settings.ocrEnabled = it
            }
            SettingsSwitch("البحث في الإنترنت", searchEnabled) {
                searchEnabled = it; settings.searchEnabled = it
            }
            SettingsSwitch("حفظ سجل التحليلات", saveHistory) {
                saveHistory = it; settings.saveHistory = it
            }
        }

        // ═══ ZenMux API ═══
        SettingsCard("ZenMux API (مباشر — الأسرع)") {
            Text(
                "أضف مفتاح ZenMux للتحليل السحابي المباشر",
                color = Color.Gray,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))

            SettingsTextField(
                label = "API Key",
                value = zenmuxKey,
                onValueChange = { zenmuxKey = it; settings.zenmuxKey = it },
                placeholder = "zm-..."
            )
            SettingsTextField(
                label = "النموذج",
                value = zenmuxModel,
                onValueChange = { zenmuxModel = it; settings.zenmuxModel = it },
                placeholder = "z-ai/glm-5.3-free"
            )
            SettingsTextField(
                label = "الرابط",
                value = zenmuxUrl,
                onValueChange = { zenmuxUrl = it; settings.zenmuxUrl = it },
                placeholder = "https://zenmux.ai/api/chat/completions"
            )
        }

        // ═══ الخصوصية ═══
        SettingsCard("الخصوصية") {
            Text(
                "التحليل المحلي: الصورة لا تغادر الجهاز",
                color = Color(0xFF4CAF50),
                fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "التحليل السحابي: الصورة تُرسل للمزود",
                color = Color(0xFFFF9800),
                fontSize = 12.sp
            )
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════
// Components
// ═══════════════════════════════════════════

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
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF38BDF8))
        )
    }
}

@Composable
fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = ""
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, color = Color.Gray, fontSize = 12.sp) },
        placeholder = { Text(placeholder, color = Color(0xFF444444), fontSize = 11.sp) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Color(0xFF38BDF8),
            unfocusedBorderColor = Color(0xFF333333),
            cursorColor = Color(0xFF38BDF8)
        ),
        shape = RoundedCornerShape(8.dp),
        singleLine = true
    )
}
