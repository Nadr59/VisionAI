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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nadrlab.visionai.data.AnalysisEntity
import com.nadrlab.visionai.vm.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(viewModel: MainViewModel) {
    val history by viewModel.history.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadHistory() }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0D0D0D)).padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("سجل التحليلات", color = Color(0xFF38BDF8), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(16.dp))

        if (history.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("لا توجد تحليلات سابقة", color = Color.Gray, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(history) { item ->
                    HistoryCard(item, onDelete = { viewModel.deleteHistoryItem(item) })
                }
            }
        }
    }
}

@Composable
fun HistoryCard(entity: AnalysisEntity, onDelete: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(entity.contentType, color = Color(0xFF38BDF8), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(dateFormat.format(Date(entity.timestamp)), color = Color.Gray, fontSize = 11.sp)
                }
                Row {
                    Text(entity.analysisType, color = Color(0xFFE8C547), fontSize = 11.sp,
                        modifier = Modifier.background(Color(0xFF2A2A1A), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(entity.aiMode, color = Color.Gray, fontSize = 11.sp,
                        modifier = Modifier.background(Color(0xFF1A1A1A), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(entity.description, color = Color.White, fontSize = 12.sp, maxLines = 3)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, "حذف", tint = Color(0xFFF44336), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
