package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NatureColorScheme = darkColorScheme(
    primary = Color(0xFF76B89B), onPrimary = Color(0xFF092F27),
    secondary = Color(0xFFB1D2A2), background = Color(0xFF101F1A),
    onBackground = Color(0xFFE6F2E8), surface = Color(0xFF1D3328),
    onSurface = Color(0xFFE6F2E8)
)

private val DarkColorScheme = darkColorScheme(
    primary = ElectricBlue,
    onPrimary = Color.White,
    primaryContainer = DarkSurfaceElevated,
    onPrimaryContainer = LightBlue,
    secondary = CyanAccent,
    onSecondary = DarkBackground,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    error = DangerRed,
    onError = Color.White
)

@Composable
fun TaskitatorTheme(
    darkTheme: Boolean = true,
    natureTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (natureTheme) NatureColorScheme else DarkColorScheme,
        typography = Typography,
        content = content
    )
}
