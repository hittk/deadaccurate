package com.deadaccurate.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The house style: deep navy with brass/gold instrumentation, after the
// original Kargathra & Co. timegrapher design. Always dark — this is a
// workbench instrument, not a document.
val Navy = Color(0xFF0B1322)
val NavyHeader = Color(0xFF091020)
val NavyCard = Color(0xFF15233E)
val NavyCardLow = Color(0xFF101B31)
val Gold = Color(0xFFE2A93F)
val GoldDim = Color(0xFFB8892F)
val InkBright = Color(0xFFE9EEF8)
val InkMuted = Color(0xFF8195B8)
val LineFaint = Color(0xFF24365A)
val LineFainter = Color(0xFF1A2A49)
val ErrorRed = Color(0xFFE0685E)
val GoldBright = Color(0xFFF0C46A)

private val Scheme = darkColorScheme(
    primary = Gold,
    onPrimary = NavyHeader,
    primaryContainer = GoldDim,
    onPrimaryContainer = NavyHeader,
    secondary = InkMuted,
    onSecondary = NavyHeader,
    // "Active/open" accent: a brighter brass (the meter while the gate
    // passes signal). The M3 default tertiary is pink — very much not
    // the house style.
    tertiary = GoldBright,
    onTertiary = NavyHeader,
    tertiaryContainer = GoldDim,
    onTertiaryContainer = NavyHeader,
    // FilterChip selected state draws from secondaryContainer.
    secondaryContainer = Gold,
    onSecondaryContainer = NavyHeader,
    background = Navy,
    onBackground = InkBright,
    surface = Navy,
    onSurface = InkBright,
    surfaceVariant = NavyCard,
    onSurfaceVariant = InkMuted,
    surfaceContainer = NavyCard,
    surfaceContainerLow = NavyCardLow,
    surfaceContainerHigh = NavyCard,
    surfaceContainerHighest = NavyCard,
    outline = LineFaint,
    outlineVariant = LineFainter,
    error = ErrorRed,
    onError = InkBright,
)

// Serif for the brand and the big instrument numbers; wide-tracked
// letters for the small-caps-style panel labels.
private val BaseType = Typography()
private val Type = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 44.sp,
    ),
    headlineMedium = BaseType.headlineMedium.copy(fontFamily = FontFamily.Serif),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        letterSpacing = 1.5.sp,
    ),
    titleMedium = BaseType.titleMedium.copy(fontFamily = FontFamily.Serif),
    labelLarge = BaseType.labelLarge.copy(letterSpacing = 2.sp),
    labelMedium = BaseType.labelMedium.copy(letterSpacing = 2.5.sp),
    labelSmall = BaseType.labelSmall.copy(letterSpacing = 2.sp),
)

@Composable
fun DeadAccurateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = Type,
        content = content,
    )
}
