package com.nadrlab.visionai.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkBg = Color(0xFF0D0D0D)
val Surface = Color(0xFF1A1A2E)
val Accent = Color(0xFF38BDF8)
val AccentGreen = Color(0xFF4CAF50)
val AccentPurple = Color(0xFF9C27B0)
val AccentYellow = Color(0xFFE8C547)
val TextPrimary = Color(0xFFF0F0F0)
val TextMuted = Color(0xFF888888)
val Error = Color(0xFFF44336)

private val DarkColorScheme = darkColorScheme(
    primary = Accent,
    secondary = AccentGreen,
    tertiary = AccentPurple,
    background = DarkBg,
    surface = Surface,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = Error,
    onError = Color.White
)

@Composable
fun VisionAiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography(),
        content = content
    )
}
