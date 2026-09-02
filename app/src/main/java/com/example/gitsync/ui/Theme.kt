package com.example.gitsync.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgColor = Color(0xFF0B0B0F)
val SurfaceColor = Color(0xFF16161D)
val SurfaceColorAlt = Color(0xFF1D1D26)
val AccentGreen = Color(0xFF3DDC97)
val AccentBlue = Color(0xFF5B8CFF)
val AccentRed = Color(0xFFFF6B6B)
val AccentAmber = Color(0xFFFFC46B)
val TextPrimary = Color(0xFFF2F2F5)
val TextSecondary = Color(0xFF9A9AA5)

private val DarkColors = darkColorScheme(
    primary = AccentGreen,
    secondary = AccentBlue,
    background = BgColor,
    surface = SurfaceColor,
    surfaceVariant = SurfaceColorAlt,
    error = AccentRed,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary
)

@Composable
fun GitSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}

@Composable
fun gitSyncTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = AccentGreen,
    unfocusedBorderColor = TextSecondary.copy(alpha = 0.4f),
    focusedLabelColor = AccentGreen,
    unfocusedLabelColor = TextSecondary,
    cursorColor = AccentGreen,
    focusedPlaceholderColor = TextSecondary,
    unfocusedPlaceholderColor = TextSecondary
)
