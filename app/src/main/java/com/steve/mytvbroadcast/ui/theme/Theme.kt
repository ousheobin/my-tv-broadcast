package com.steve.mytvbroadcast.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    surfaceTint = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF132F60),
    primaryContainer = Color(0xFF2D4678),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFBFC6DC),
    onSecondary = Color(0xFF293042),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFFDFBBDE),
    onTertiary = Color(0xFF402743),
    tertiaryContainer = Color(0xFF593E5A),
    onTertiaryContainer = Color(0xFFFCD7FB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF44464F),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    inversePrimary = Color(0xFF465D91)
)

@Composable
fun MyTVBroadcastTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
