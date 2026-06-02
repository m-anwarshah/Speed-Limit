package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette referenced throughout the app UI
val SlateDark = Color(0xFF101820)
val SlateNavy = Color(0xFF1B2A38)
val SlateSteel = Color(0xFF2C3E50)

val AccentCyan = Color(0xFF40C4FF)
val AccentRed = Color(0xFFFF5252)
val AccentGold = Color(0xFFFFB300)
val AccentGreen = Color(0xFF00C853)

val TextWhite = Color(0xFFFFFFFF)
val TextMuted = Color(0xFFB8C7D3)

private val DarkColors = darkColorScheme(
    primary = AccentCyan,
    secondary = AccentGreen,
    background = SlateDark,
    surface = SlateNavy,
    onPrimary = SlateDark,
    onBackground = TextWhite,
    onSurface = TextWhite,
    error = AccentRed
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // app is designed dark-only
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
