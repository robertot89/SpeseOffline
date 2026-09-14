package com.rob.speseoffline

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF3257D5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FF),
    onPrimaryContainer = Color(0xFF10215F),
    secondary = Color(0xFF3E6375),
    secondaryContainer = Color(0xFFD8EAF2),
    tertiary = Color(0xFF68703A),
    background = Color(0xFFF7F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECEEF5),
    outline = Color(0xFF777A86),
    error = Color(0xFFBA1A1A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7C4FF),
    onPrimary = Color(0xFF002A77),
    primaryContainer = Color(0xFF173F9E),
    onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = Color(0xFFB9CAD3),
    secondaryContainer = Color(0xFF243F4B),
    tertiary = Color(0xFFD0D99A),
    background = Color(0xFF111318),
    surface = Color(0xFF191B20),
    surfaceVariant = Color(0xFF262930),
    outline = Color(0xFF90939D),
    error = Color(0xFFFFB4AB)
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontSize = 27.sp, fontWeight = FontWeight.Bold, lineHeight = 33.sp),
    headlineSmall = TextStyle(fontSize = 23.sp, fontWeight = FontWeight.Bold, lineHeight = 29.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun SpeseOfflineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
