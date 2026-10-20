package com.usage.claudewidget.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback scheme seeded from Claude's orange, for Android < 12 (no dynamic color).
private val LightColors = lightColorScheme(
    primary = Color(0xFFA0482B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBD0),
    onPrimaryContainer = Color(0xFF3A0B00),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB59E),
    onPrimary = Color(0xFF5E1702),
    primaryContainer = Color(0xFF7E2D15),
    onPrimaryContainer = Color(0xFFFFDBD0),
)

/** Material 3 theme that follows the system: light/dark, and wallpaper colors on Android 12+. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkColors else LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
