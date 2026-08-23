package com.nadrlab.visionai

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // متغير لتخزين الصورة المستلمة من المشاركة
    private var sharedImageUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // استقبال الصورة من المشاركة
        handleIncomingIntent(intent)

        setContent {
            VisionAITheme {
                VisionAINavigation(sharedImageUri)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    sharedImageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
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
fun VisionAINavigation(
    sharedUri: Uri? = null,
    vm: MainViewModel = viewModel()
) {
    var currentScreen by remember { mutableStateOf(Screen.MAIN) }

    // تحميل الصورة المشتركة
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(sharedUri) {
        if (sharedUri != null) {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val inputStream = context.contentResolver.openInputStream(sharedUri)
                    BitmapFactory.decodeStream(inputStream)
                }
                if (bitmap != null) {
                    vm.selectImage(bitmap, sharedUri)
                    currentScreen = Screen.MAIN
                }
            } catch (_: Exception) {}
        }
    }

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
