package io.github.hoonex.esp32car.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ConsoleDarkColorScheme = darkColorScheme(
    primary = Color(0xFF63D4FF),
    onPrimary = Color(0xFF001F2A),
    primaryContainer = Color(0xFF103648),
    onPrimaryContainer = Color(0xFFD5F4FF),

    secondary = Color(0xFF8BE8B0),
    onSecondary = Color(0xFF052014),
    secondaryContainer = Color(0xFF133725),
    onSecondaryContainer = Color(0xFFC8F7D8),

    tertiary = Color(0xFFFFC56E),
    onTertiary = Color(0xFF2B1900),
    tertiaryContainer = Color(0xFF4B3210),
    onTertiaryContainer = Color(0xFFFFE3B5),

    error = Color(0xFFFF6677),
    onError = Color(0xFF300008),
    errorContainer = Color(0xFF4C1420),
    onErrorContainer = Color(0xFFFFD9DE),

    background = Color(0xFF070A0E),
    onBackground = Color(0xFFF3F7FA),
    surface = Color(0xFF0D1218),
    onSurface = Color(0xFFF3F7FA),
    surfaceVariant = Color(0xFF161D25),
    onSurfaceVariant = Color(0xFF9DAAB5),
    outline = Color(0xFF2A3540),
    outlineVariant = Color(0xFF1D2730)
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ConsoleDarkColorScheme,
        typography = Typography,
        content = content
    )
}
