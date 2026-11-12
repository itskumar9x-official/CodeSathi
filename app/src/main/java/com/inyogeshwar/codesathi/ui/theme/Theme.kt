package com.inyogeshwar.codesathi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// CodeSathi's own palette — deep green primary, written for this app.
private val GreenPrimary = Color(0xFF1F6F43)
private val GreenDark = Color(0xFF14532D)

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    secondary = GreenDark,
    background = Color(0xFFF7F8F7),
    surface = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4ADE80),
    secondary = Color(0xFF86EFAC),
    background = Color(0xFF121417),
    surface = Color(0xFF1C1F24)
)

@Composable
fun CodeSathiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
