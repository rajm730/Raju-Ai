package com.raju.aiassistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val RajuColors = darkColorScheme(
    primary = ReactorCyan,
    onPrimary = SpaceBlack,
    primaryContainer = PanelNavyHigh,
    onPrimaryContainer = ReactorCore,
    secondary = ReactorBlue,
    onSecondary = SpaceBlack,
    tertiary = HoloViolet,
    background = SpaceBlack,
    onBackground = TextPrimary,
    surface = PanelNavy,
    onSurface = TextPrimary,
    surfaceVariant = PanelNavyHigh,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = PanelNavy,
    error = ErrorRed,
    onError = SpaceBlack,
    outline = ReactorCyan.copy(alpha = 0.4f),
)

private val RajuTypography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = 1.5.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = 0.8.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 1.sp),
)

/** The app is always dark (sci-fi HUD look), independent of the system theme. */
@Composable
fun RajuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RajuColors, typography = RajuTypography, content = content)
}
