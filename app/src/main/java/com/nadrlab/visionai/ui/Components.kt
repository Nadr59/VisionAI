package com.nadrlab.visionai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadrlab.visionai.domain.*

// ═══ بطاقة نتيجة ═══
@Composable
fun ResultSection(
    title: String,
    icon: String,
    color: Color,
    content: String,
    onCopy: (() -> Unit)? = null
) {
    if (content.isBlank()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$icon $title", color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (onCopy != null) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ContentCopy, null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(content, color = Color.White, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

// ═══ بطاقة عنصر ═══
@Composable
fun ElementChip(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2137)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            "• $text",
            color = Color(0xFF38BDF8),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

// ═══ شارة الثقة ═══
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
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

// ═══ بطاقة بحث ═══
@Composable
fun SearchResultCard(result: SearchResult, context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2E1A)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(result.title, color = Color(0xFF4CAF50), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(result.snippet, color = Color.Gray, fontSize = 11.sp, maxLines = 3)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
                }) {
                    Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("فتح", fontSize = 12.sp)
                }
                TextButton(onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("url", result.url))
                }) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("نسخ", fontSize = 12.sp)
                }
            }
        }
    }
}

// ═══ زر اختيار نوع التحليل ═══
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
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
