package com.nadrlab.visionai.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.nadrlab.visionai.domain.CustomSearchEngine
import com.nadrlab.visionai.domain.SearchEngine
import com.nadrlab.visionai.vm.MainViewModel

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings = viewModel.settings
    val customEngines by viewModel.customEngines.collectAsState()

    var providerName by remember { mutableStateOf(settings.providerName) }
    var providerUrl by remember { mutableStateOf(settings.providerUrl) }
    var providerKey by remember { mutableStateOf(settings.providerKey) }
    var providerModel by remember { mutableStateOf(settings.providerModel) }
    var searchEnabled by remember { mutableStateOf(settings.searchEnabled) }
    var ocrEnabled by remember { mutableStateOf(settings.ocrEnabled) }
    var saveHistory by remember { mutableStateOf(settings.saveHistory) }
    var showKey by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var showAddEngine by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadCustomEngines()
    }

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
            Text(
                if (settings.isCustomProviderConfigured()) "مزود مخصص نشط"
                else "يستخدم VisionAI Cloud المجاني",
                color = if (settings.isCustomProviderConfigured()) Color(0xFF4CAF50)
                else Color(0xFFFF9800),
                fontSize = 12.sp
            )

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
                    Icon(
                        if (showPresets) Icons.Default.ExpandLess else Icons.Default.Tune,
                        null, modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("نماذج جاهزة", fontSize = 12.sp)
                }

                if (settings.isCustomProviderConfigured()) {
                    Button(
                        onClick = {
                            settings.clearProvider()
                            providerName = ""
                            providerUrl = ""
                            providerKey = ""
                            providerModel = "glm-5.3"
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
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
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

        // ═══ محرك البحث ═══
        SettingsCard("محرك البحث المفضل") {
            var selectedEngineId by remember { mutableStateOf(settings.searchEngine) }

            SearchEngine.entries.forEach { engine ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedEngineId = engine.name
                            settings.searchEngine = engine.name
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RadioButton(
                        selected = selectedEngineId == engine.name,
                        onClick = {
                            selectedEngineId = engine.name
                            settings.searchEngine = engine.name
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF38BDF8))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${engine.icon} ${engine.labelAr}",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(engine.description, color = Color(0xFF888888), fontSize = 11.sp)
                    }
                }
                if (engine != SearchEngine.entries.last()) {
                    HorizontalDivider(color = Color(0xFF252542))
                }
            }

            if (customEngines.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "محركات مخصصة",
                    color = Color(0xFFE8C547),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))

                customEngines.forEach { engine ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedEngineId = engine.id
                                settings.searchEngine = engine.id
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(
                            selected = selectedEngineId == engine.id,
                            onClick = {
                                selectedEngineId = engine.id
                                settings.searchEngine = engine.id
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF38BDF8))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${engine.icon} ${engine.nameAr}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(engine.name, color = Color(0xFF888888), fontSize = 11.sp)
                        }
                        IconButton(
                            onClick = {
                                viewModel.deleteCustomEngine(engine.id)
                                if (selectedEngineId == engine.id) {
                                    selectedEngineId = "SEARXNG"
                                    settings.searchEngine = "SEARXNG"
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete, "حذف",
                                tint = Color(0xFFFF6B6B),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = Color(0xFF252542))
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { showAddEngine = !showAddEngine },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8))
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("إضافة محرك بحث مخصص")
            }

            AnimatedVisibility(visible = showAddEngine) {
                AddCustomEngineSection(
                    onAdd = { engine ->
                        viewModel.addCustomEngine(engine)
                        showAddEngine = false
                    },
                    onCancel = { showAddEngine = false }
                )
            }
        }

        // ═══ الخصوصية ═══
        SettingsCard("الخصوصية") {
            Text(
                "بدون مزود مخصص: الصورة تُرسل لـ VisionAI Cloud المجاني",
                color = Color(0xFFFF9800), fontSize = 12.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "مع مزود مخصص: الصورة تُرسل للمزود الذي اخترته",
                color = Color(0xFF38BDF8), fontSize = 12.sp
            )
        }

        // ═══ حول ═══
        SettingsCard("حول التطبيق") {
            Text("Vision AI v2.0", color = Color(0xFF38BDF8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("تحليل صور + محادثة + بحث بالذكاء الاصطناعي", color = Color(0xFF888888), fontSize = 12.sp)
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ═══ نموذج إضافة محرك مخصص ═══
@Composable
fun AddCustomEngineSection(
    onAdd: (CustomSearchEngine) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var nameAr by remember { mutableStateOf("") }
    var urlTemplate by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("🔍") }
    var showExamples by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF252542))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "إضافة محرك بحث",
                color = Color(0xFFE8C547),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF38BDF8).copy(0.1f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        "كيفية الإضافة:",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("1. افتح محرك البحث في المتصفح", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                    Text("2. ابحث عن أي شيء", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                    Text("3. انسخ رابط الصفحة من شريط العنوان", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                    Text("4. احذف كلمة البحث واستبدلها بـ {query}", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("مثال:", color = Color(0xFFE8C547), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "https://yandex.com/search/?text=hello",
                        color = Color(0xFF888888), fontSize = 10.sp
                    )
                    Text("↓", color = Color(0xFF38BDF8), fontSize = 10.sp)
                    Text(
                        "https://yandex.com/search/?text={query}",
                        color = Color(0xFF4CAF50), fontSize = 10.sp
                    )
                }
            }

            OutlinedButton(
                onClick = { showExamples = !showExamples },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE8C547))
            ) {
                Icon(
                    if (showExamples) Icons.Default.ExpandLess else Icons.Default.Lightbulb,
                    null, modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("نماذج جاهزة للنسخ", fontSize = 12.sp)
            }

            AnimatedVisibility(visible = showExamples) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CustomSearchEngine.EXAMPLES.forEach { example ->
                        OutlinedButton(
                            onClick = {
                                name = example.name
                                nameAr = example.nameAr
                                urlTemplate = example.urlTemplate
                                icon = example.icon
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${example.icon} ${example.nameAr}", fontSize = 13.sp)
                                Text(example.name, fontSize = 11.sp, color = Color(0xFF888888))
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = nameAr,
                onValueChange = { nameAr = it },
                label = { Text("الاسم بالعربية", color = Color(0xFF888888), fontSize = 12.sp) },
                placeholder = { Text("ياندكس", color = Color(0xFF555555), fontSize = 12.sp) },
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

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("الاسم بالإنجليزية", color = Color(0xFF888888), fontSize = 12.sp) },
                placeholder = { Text("Yandex", color = Color(0xFF555555), fontSize = 12.sp) },
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

            OutlinedTextField(
                value = urlTemplate,
                onValueChange = { urlTemplate = it },
                label = { Text("رابط البحث", color = Color(0xFF888888), fontSize = 12.sp) },
                placeholder = {
                    Text(
                        "https://yandex.com/search/?text={query}",
                        color = Color(0xFF555555), fontSize = 12.sp
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF38BDF8),
                    unfocusedBorderColor = Color(0xFF333355),
                    cursorColor = Color(0xFF38BDF8)
                ),
                shape = RoundedCornerShape(10.dp)
            )

            Text("الأيقونة", color = Color(0xFF888888), fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("🔍", "🔴", "🔵", "🟢", "🟡", "🟣", "🦁", "🌳", "🌍", "🇨🇳", "👁️", "⚡").forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (icon == emoji) Color(0xFF38BDF8) else Color(0xFF1A1A2E),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { icon = emoji },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emoji, fontSize = 16.sp)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B6B))
                ) {
                    Text("إلغاء")
                }
                Button(
                    onClick = {
                        if (nameAr.isNotBlank() && urlTemplate.isNotBlank() && urlTemplate.contains("{query}")) {
                            onAdd(
                                CustomSearchEngine(
                                    name = name.ifBlank { nameAr },
                                    nameAr = nameAr,
                                    urlTemplate = urlTemplate,
                                    icon = icon
                                )
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    enabled = nameAr.isNotBlank() && urlTemplate.isNotBlank() && urlTemplate.contains("{query}"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("إضافة")
                }
            }
        }
    }
}

// ═══ مكونات مساعدة ═══
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
