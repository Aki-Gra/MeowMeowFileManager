package com.meowmeow.filemanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val light = lightColorScheme(
    primary = Color(0xFF6953A2),
    secondary = Color(0xFF6D5D7C),
    tertiary = Color(0xFF8F526F),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF)
)
private val dark = darkColorScheme(
    primary = Color(0xFFD1BCFF),
    secondary = Color(0xFFD8C1E8),
    tertiary = Color(0xFFFFB0D0)
)

@Composable
fun MeowMeowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)
}
