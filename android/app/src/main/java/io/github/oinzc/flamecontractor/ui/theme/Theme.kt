package io.github.oinzc.flamecontractor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class FlameSemanticColors(
    val healthy: Color,
    val healthyContainer: Color,
    val onHealthyContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val sparkGold: Color,
    val sparkGoldContainer: Color,
    val onSparkGoldContainer: Color,
    val neutral: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color,
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFC94A17),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBD0),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF77574B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF9DFD5),
    onSecondaryContainer = Color(0xFF2C160E),
    tertiary = Color(0xFF945D00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF301B00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF221A17),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF221A17),
    surfaceVariant = Color(0xFFF4DFD8),
    onSurfaceVariant = Color(0xFF53433E),
    outline = Color(0xFF85736D),
    outlineVariant = Color(0xFFD8C2BA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFDF1ED),
    surfaceContainer = Color(0xFFF7EBE7),
    surfaceContainerHigh = Color(0xFFF1E5E1),
    surfaceContainerHighest = Color(0xFFEBE0DC),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB59A),
    onPrimary = Color(0xFF5D1800),
    primaryContainer = Color(0xFF852700),
    onPrimaryContainer = Color(0xFFFFDBD0),
    secondary = Color(0xFFE7BDB0),
    onSecondary = Color(0xFF442A20),
    secondaryContainer = Color(0xFF5D4035),
    onSecondaryContainer = Color(0xFFFFDBD0),
    tertiary = Color(0xFFF8BE68),
    onTertiary = Color(0xFF4D2D00),
    tertiaryContainer = Color(0xFF704500),
    onTertiaryContainer = Color(0xFFFFDDB3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF19120F),
    onBackground = Color(0xFFEFE0DB),
    surface = Color(0xFF19120F),
    onSurface = Color(0xFFEFE0DB),
    surfaceVariant = Color(0xFF53433E),
    onSurfaceVariant = Color(0xFFD8C2BA),
    outline = Color(0xFFA08D86),
    outlineVariant = Color(0xFF53433E),
    surfaceContainerLowest = Color(0xFF140D0B),
    surfaceContainerLow = Color(0xFF221A17),
    surfaceContainer = Color(0xFF261E1B),
    surfaceContainerHigh = Color(0xFF312825),
    surfaceContainerHighest = Color(0xFF3C3330),
)

private val LightSemanticColors = FlameSemanticColors(
    healthy = Color(0xFF157A4B),
    healthyContainer = Color(0xFFDDF6E7),
    onHealthyContainer = Color(0xFF06381F),
    warning = Color(0xFFB35E00),
    warningContainer = Color(0xFFFFE7C6),
    onWarningContainer = Color(0xFF3A1C00),
    danger = Color(0xFFC22727),
    dangerContainer = Color(0xFFFDE2E1),
    onDangerContainer = Color(0xFF4B0909),
    sparkGold = Color(0xFFC77700),
    sparkGoldContainer = Color(0xFFFFF0CC),
    onSparkGoldContainer = Color(0xFF3E2200),
    neutral = Color(0xFF685D59),
    neutralContainer = Color(0xFFEDE4E0),
    onNeutralContainer = Color(0xFF2A2320),
)

private val DarkSemanticColors = FlameSemanticColors(
    healthy = Color(0xFF79DCA5),
    healthyContainer = Color(0xFF0E4B2D),
    onHealthyContainer = Color(0xFFC6F7D8),
    warning = Color(0xFFFFBA66),
    warningContainer = Color(0xFF5C3100),
    onWarningContainer = Color(0xFFFFE3C0),
    danger = Color(0xFFFF8A80),
    dangerContainer = Color(0xFF6D1515),
    onDangerContainer = Color(0xFFFFDAD6),
    sparkGold = Color(0xFFFFD066),
    sparkGoldContainer = Color(0xFF573900),
    onSparkGoldContainer = Color(0xFFFFEBB8),
    neutral = Color(0xFFBDB0AB),
    neutralContainer = Color(0xFF3B322E),
    onNeutralContainer = Color(0xFFEBE0DC),
)

private val LocalFlameColors = staticCompositionLocalOf { LightSemanticColors }

private val FlameTypography = Typography(
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = TextStyle(
        fontSize = 19.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
    ),
)

private val FlameShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun FlameTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalFlameColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FlameTypography,
            shapes = FlameShapes,
            content = content,
        )
    }
}

object FlameThemeTokens {
    val semanticColors: FlameSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFlameColors.current
}
