package com.nadrlab.visionai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadrlab.visionai.data.AnalysisEntity
import com.nadrlab.visionai.vm.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val history by vm.history.collectAsState()

    LaunchedEffect(Unit) { vm.loadHistory() }

    if (history.isEmpty()) {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF0D0D0D)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.History, null, Modifier.size(64.dp), tint = Color(0xFF555555))
                Spacer(Modifier.height(12.dp))
                Text("لا يوجد سجل تحليلات", color = Color(0xFF888888))
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0D0D))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "سجل التحليلات (${history.size})",
                    fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    color = Color(0xFF38BDF8),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(history) { entity ->
                HistoryItemCard(entity) { vm.deleteHistoryItem(entity) }
            }
        }
    }
}

@Composable
fun HistoryItemCard(entity: AnalysisEntity, onDelete: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(entity.contentType, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF38BDF8))
                    Text(dateFormat.format(Date(entity.timestamp)), fontSize = 11.sp, color = Color(0xFF888888))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "حذف", tint = Color(0xFFFF6B6B))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(entity.description, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color(0xFFCCCCCC))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(entity.analysisType, fontSize = 11.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Color(0xFF252542), labelColor = Color(0xFFCCCCCC)
                    )
                )
                AssistChip(
                    onClick = {},
                    label = { Text(entity.confidence, fontSize = 11.sp) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Color(0xFF252542), labelColor = Color(0xFFCCCCCC)
                    )
                )
            }
        }
    }
}
