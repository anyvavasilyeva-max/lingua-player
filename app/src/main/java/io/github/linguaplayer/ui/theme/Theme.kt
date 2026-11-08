package io.github.linguaplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Собственная палитра (индиго + бирюзовый акцент) — без заимствования фирменных цветов сервисов.
private val Light = lightColorScheme(
    primary = Color(0xFF3949AB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE0FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFFD84315),
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFF8F9FC),
    surfaceContainer = Color(0xFFEEF0F6),
    surfaceContainerHigh = Color(0xFFE6E8F0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF08218A),
    primaryContainer = Color(0xFF2A3A9A),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFB2DFDB),
    tertiary = Color(0xFFFFB59C),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceContainer = Color(0xFF1D2026),
    surfaceContainerHigh = Color(0xFF272A31),
)

@Composable
fun LinguaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
