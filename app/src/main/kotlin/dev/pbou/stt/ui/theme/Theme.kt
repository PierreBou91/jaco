package dev.pbou.stt.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pbou.stt.R

/*
 * Dark only, warm neutrals with one orange accent. Contrast was checked against WCAG AA:
 * primary on background is about 8:1, onSurfaceVariant on surfaceContainer about 8:1,
 * outline on surfaces above 3:1 (enough for component borders).
 */

private val Orange = Color(0xFFFF8A4C)

private val Scheme: ColorScheme = darkColorScheme(
    primary = Orange,
    onPrimary = Color(0xFF2A1300),
    primaryContainer = Color(0xFF5C2A0B),
    onPrimaryContainer = Color(0xFFFFDBC9),
    inversePrimary = Color(0xFF9C4A16),
    secondary = Color(0xFFE3BFA8),
    onSecondary = Color(0xFF422B1C),
    secondaryContainer = Color(0xFF3D2F25),
    onSecondaryContainer = Color(0xFFF3DCCB),
    tertiary = Color(0xFFA8CFA0),
    onTertiary = Color(0xFF15301A),
    tertiaryContainer = Color(0xFF263A26),
    onTertiaryContainer = Color(0xFFC8E8C0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF7A1A14),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131211),
    onBackground = Color(0xFFECE7E2),
    surface = Color(0xFF131211),
    onSurface = Color(0xFFECE7E2),
    surfaceVariant = Color(0xFF2E2B28),
    onSurfaceVariant = Color(0xFFBDB5AD),
    surfaceTint = Orange,
    inverseSurface = Color(0xFFECE7E2),
    inverseOnSurface = Color(0xFF322F2C),
    outline = Color(0xFF8C847C),
    outlineVariant = Color(0xFF45403B),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A3735),
    surfaceDim = Color(0xFF131211),
    surfaceContainerLowest = Color(0xFF0E0D0C),
    surfaceContainerLow = Color(0xFF1B1A18),
    surfaceContainer = Color(0xFF201E1C),
    surfaceContainerHigh = Color(0xFF2A2826),
    surfaceContainerHighest = Color(0xFF353230),
)

/** Colors Material does not name. */
@Immutable
data class ExtraColors(val warning: Color, val onWarningContainer: Color, val warningContainer: Color)

val LocalExtraColors = staticCompositionLocalOf {
    ExtraColors(warning = Color(0xFFF2C46D), warningContainer = Color(0xFF3E3216), onWarningContainer = Color(0xFFFBE2B0))
}

private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private fun TextStyle.inter(weight: FontWeight? = null, spacing: Float? = null) = copy(
    fontFamily = Inter,
    fontWeight = weight ?: fontWeight,
    letterSpacing = spacing?.sp ?: letterSpacing,
)

// Material's scale, set in Inter. Inter is wider than Roboto, so body text gets less tracking.
private val Base = Typography()
private val AppTypography = Typography(
    displayLarge = Base.displayLarge.inter(),
    displayMedium = Base.displayMedium.inter(),
    displaySmall = Base.displaySmall.inter(),
    headlineLarge = Base.headlineLarge.inter(FontWeight.SemiBold, -0.2f),
    headlineMedium = Base.headlineMedium.inter(FontWeight.SemiBold, -0.2f),
    headlineSmall = Base.headlineSmall.inter(FontWeight.SemiBold, -0.1f),
    titleLarge = Base.titleLarge.inter(FontWeight.SemiBold, -0.1f),
    titleMedium = Base.titleMedium.inter(FontWeight.SemiBold, 0f),
    titleSmall = Base.titleSmall.inter(FontWeight.Medium, 0f),
    bodyLarge = Base.bodyLarge.inter(spacing = 0f),
    bodyMedium = Base.bodyMedium.inter(spacing = 0.1f),
    bodySmall = Base.bodySmall.inter(spacing = 0.2f),
    labelLarge = Base.labelLarge.inter(FontWeight.Medium, 0.1f),
    labelMedium = Base.labelMedium.inter(FontWeight.Medium, 0.3f),
    labelSmall = Base.labelSmall.inter(FontWeight.Medium, 0.3f),
)

/** Big, airy text for the transcript itself. */
val TranscriptStyle = TextStyle(fontFamily = Inter, fontSize = 20.sp, lineHeight = 31.sp, letterSpacing = 0.sp)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun SttTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
