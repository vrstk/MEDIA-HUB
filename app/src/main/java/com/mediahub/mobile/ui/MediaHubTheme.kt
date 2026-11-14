package com.mediahub.mobile.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF83ADFF),
    secondary = Color(0xFF51D2C7),
    background = Color(0xFF111827),
    surface = Color(0xFF23334D),
    onBackground = Color.White,
    onSurface = Color.White
)
private val LightPalette = lightColorScheme(
    primary = Color(0xFF285DC3),
    secondary = Color(0xFF087A71),
    background = Color(0xFFF3F6FB),
    surface = Color.White
)

@Composable
fun MediaHubTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkPalette else LightPalette,
        content = content
    )
}
