package com.nadrlab.visionai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nadrlab.visionai.ui.*
import com.nadrlab.visionai.ui.theme.VisionAiTheme
import com.nadrlab.visionai.vm.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VisionAiTheme {
                VisionAiNavHost()
            }
        }
    }
}

enum class Screen(val label: String, val icon: ImageVector) {
    HOME("الرئيسية", Icons.Default.Home),
    HISTORY("السجل", Icons.Default.History),
    MODEL("النموذج", Icons.Default.Memory),
    SETTINGS("الإعدادات", Icons.Default.Settings)
}

@Composable
fun VisionAiNavHost() {
    val vm: MainViewModel = viewModel()
    var currentScreen by remember { mutableStateOf(Screen.HOME) }

    Scaffold(
        containerColor = Color(0xFF0D0D0D),
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF1A1A2E),
                tonalElevation = 0.dp
            ) {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected = currentScreen == screen,
                        onClick = { currentScreen = screen },
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF38BDF8),
                            selectedTextColor = Color(0xFF38BDF8),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            indicatorColor = Color(0xFF38BDF8).copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (currentScreen) {
                Screen.HOME -> MainScreen(vm)
                Screen.HISTORY -> HistoryScreen(vm)
                Screen.MODEL -> ModelScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}
