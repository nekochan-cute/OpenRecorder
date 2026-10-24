package org.openrecorder.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFFD9363E), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2DF), onPrimaryContainer = Color(0xFF5C0A0E),
    secondary = Color(0xFF5B5E64), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8EB), onSecondaryContainer = Color(0xFF1C1D20),
    tertiary = Color(0xFFB36A00), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE5C2), onTertiaryContainer = Color(0xFF3A2200),
    background = Color(0xFFFAF9F8), onBackground = Color(0xFF1B1B1C),
    surface = Color(0xFFFAF9F8), onSurface = Color(0xFF1B1B1C),
    surfaceVariant = Color(0xFFECEAE8), onSurfaceVariant = Color(0xFF626060),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F4F2),
    surfaceContainer = Color(0xFFF0EEEC), surfaceContainerHigh = Color(0xFFEAE8E6), surfaceContainerHighest = Color(0xFFE4E2E0),
    outline = Color(0xFF8E8B88), outlineVariant = Color(0xFFDCD9D6),
    error = Color(0xFFBA1A1A), onError = Color.White,
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF6F6B), onPrimary = Color(0xFF3B0507),
    primaryContainer = Color(0xFF6E1519), onPrimaryContainer = Color(0xFFFFDAD7),
    secondary = Color(0xFFC4C6CC), onSecondary = Color(0xFF2D2F33),
    secondaryContainer = Color(0xFF2E3034), onSecondaryContainer = Color(0xFFE2E3E8),
    tertiary = Color(0xFFFFB95C), onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF5A3600), onTertiaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFF0F0F10), onBackground = Color(0xFFE8E6E3),
    surface = Color(0xFF0F0F10), onSurface = Color(0xFFE8E6E3),
    surfaceVariant = Color(0xFF2A2A2C), onSurfaceVariant = Color(0xFFA8A5A1),
    surfaceContainerLowest = Color(0xFF0A0A0B), surfaceContainerLow = Color(0xFF161617),
    surfaceContainer = Color(0xFF1B1B1C), surfaceContainerHigh = Color(0xFF242425), surfaceContainerHighest = Color(0xFF2E2E2F),
    outline = Color(0xFF79766F), outlineVariant = Color(0xFF353432),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

/** Distinct, equally weighted hues for speakers; index = speaker order. */
fun speakerColor(index: Int, dark: Boolean): Color {
    val light = listOf(0xFF2F6FDB, 0xFF1F8A5B, 0xFFB36A00, 0xFF8B4FD1, 0xFFC2417A, 0xFF0E8A9A)
    val night = listOf(0xFF8AB4FF, 0xFF6FD3A0, 0xFFFFB95C, 0xFFC9A2FF, 0xFFFF94BE, 0xFF6FD6E3)
    return Color((if (dark) night else light)[index.mod(light.size)])
}

private val base = Typography()
val AppTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Light, fontFeatureSettings = "tnum"),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontFeatureSettings = "tnum"),
    labelMedium = base.labelMedium.copy(fontFeatureSettings = "tnum"),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
)

/** Large timer digits. */
val TimerStyle = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum", letterSpacing = 0.sp)

@Composable
fun RecorderTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = AppTypography,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
}

val isDark @Composable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f
private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue
