package com.nadrlab.visionai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nadrlab.visionai.ui.*
import com.nadrlab.visionai.ui.theme.VisionAITheme
import com.nadrlab.visionai.vm.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VisionAITheme {
                VisionAINavigation()
            }
        }
    }
}

enum class Screen(val label: String, val icon: ImageVector) {
    MAIN("الرئيسية", Icons.Default.Home),
    CHAT("الشات", Icons.Default.Chat),
    HISTORY("التاريخ", Icons.Default.History),
    SETTINGS("الإعدادات", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionAINavigation(vm: MainViewModel = viewModel()) {
    var currentScreen by remember { mutableStateOf(Screen.MAIN) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentScreen == screen,
                        onClick = { currentScreen = screen }
                    )
                }
            }
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background
        ) {
            when (currentScreen) {
                Screen.MAIN -> MainScreen(vm)
                Screen.CHAT -> ChatScreen(vm)
                Screen.HISTORY -> HistoryScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}
