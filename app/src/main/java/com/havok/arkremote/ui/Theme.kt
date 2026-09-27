package com.havok.arkremote.ui

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Fallback = darkColorScheme(
    primary = Color(0xFFB4BEFF),
    onPrimary = Color(0xFF1B2266),
    secondaryContainer = Color(0xFF2A2D42),
    onSecondaryContainer = Color(0xFFDDE0FF),
    background = Color(0xFF101014),
    surface = Color(0xFF101014),
    surfaceContainer = Color(0xFF1A1B22),
)

@Composable
fun ArkTheme(content: @Composable () -> Unit) {
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(LocalContext.current)
    else Fallback
    MaterialTheme(colorScheme = scheme, content = content)
}
