package com.auralis.player.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

data class Accent(val name: String, val color: Color)

val Accents = listOf(
    Accent("Violet", Color(0xFF8B7CFF)),
    Accent("Ocean", Color(0xFF3AA8FF)),
    Accent("Mint", Color(0xFF2FD6A3)),
    Accent("Rose", Color(0xFFFF5C8A)),
    Accent("Sunset", Color(0xFFFF8A3D)),
    Accent("Lime", Color(0xFFB6E34B)),
    Accent("Crimson", Color(0xFFFF4D4D)),
    Accent("Mono", Color(0xFFE6E6EA)),
)

val LocalAccent = staticCompositionLocalOf { Accents[0].color }
val LocalDark = staticCompositionLocalOf { true }

/** themeMode: 0 system, 1 dark, 2 AMOLED black, 3 light */
@Composable
fun AuralisTheme(accentIndex: Int, themeMode: Int, content: @Composable () -> Unit) {
    val accent = Accents[accentIndex.coerceIn(0, Accents.lastIndex)].color
    val dark = when (themeMode) { 0 -> isSystemInDarkTheme(); 3 -> false; else -> true }
    val onAccent = if (accent.luminance() > 0.45f) Color(0xFF101014) else Color.White
    val scheme = if (dark) {
        val bg = if (themeMode == 2) Color.Black else Color(0xFF0E0E13)
        darkColorScheme(
            primary = accent, onPrimary = onAccent, primaryContainer = accent.copy(alpha = 0.22f), onPrimaryContainer = Color.White,
            background = bg, onBackground = Color(0xFFF2F2F7),
            surface = bg, onSurface = Color(0xFFF2F2F7),
            surfaceVariant = if (themeMode == 2) Color(0xFF16161B) else Color(0xFF1A1A22), onSurfaceVariant = Color(0xFFA6A6B3),
            surfaceContainer = if (themeMode == 2) Color(0xFF0C0C0F) else Color(0xFF15151C),
            surfaceContainerHigh = Color(0xFF1E1E27), outline = Color(0xFF3A3A46),
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = onAccent, primaryContainer = accent.copy(alpha = 0.18f), onPrimaryContainer = Color(0xFF101014),
            background = Color(0xFFF7F7FB), onBackground = Color(0xFF14141A),
            surface = Color(0xFFF7F7FB), onSurface = Color(0xFF14141A),
            surfaceVariant = Color(0xFFEAEAF1), onSurfaceVariant = Color(0xFF5E5E6B),
            surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF0F0F6), outline = Color(0xFFCFCFD9),
        )
    }
    CompositionLocalProvider(LocalAccent provides accent, LocalDark provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
