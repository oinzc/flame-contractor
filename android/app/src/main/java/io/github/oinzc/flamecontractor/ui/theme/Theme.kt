package io.github.oinzc.flamecontractor.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.oinzc.flamecontractor.core.theme.ThemePreference
import io.github.oinzc.flamecontractor.core.theme.ThemePreferencesStore

@Immutable
data class FlamePaletteTokens(
    val canvas: Color,
    val surface: Color,
    val surfaceSubtle: Color,
    val surfacePress: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val ink: Color,
    val inkSecondary: Color,
    val inkTertiary: Color,
    val monoCtaContainer: Color,
    val monoCtaContent: Color,
    val ember: Color,
    val emberBright: Color,
    val emberContainer: Color,
    val emberBorder: Color,
    val dockContainer: Color,
    val dockActiveContainer: Color,
)

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
    val warningBorder: Color = warning,
    val dangerBorder: Color = danger,
    val sparkGoldBorder: Color = sparkGold,
)

private val LightFlamePalette = FlamePaletteTokens(
    canvas = Color(0xFFF5F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceSubtle = Color(0xFFF0F2F5),
    surfacePress = Color(0xFFE9ECF1),
    hairline = Color(0xFFE6E8EE),
    hairlineStrong = Color(0xFFD4D8E1),
    ink = Color(0xFF111318),
    inkSecondary = Color(0xFF4B5262),
    inkTertiary = Color(0xFF8A92A3),
    monoCtaContainer = Color(0xFF111318),
    monoCtaContent = Color(0xFFFFFFFF),
    ember = Color(0xFFEA580C),
    emberBright = Color(0xFFF97316),
    emberContainer = Color(0xFFFFF4EC),
    emberBorder = Color(0xFFFED7AA),
    dockContainer = Color(0xFFFFFFFF),
    dockActiveContainer = Color(0xFFF0F2F5),
)

private val DarkFlamePalette = FlamePaletteTokens(
    canvas = Color(0xFF0E1013),
    surface = Color(0xFF171A1F),
    surfaceSubtle = Color(0xFF20242B),
    surfacePress = Color(0xFF272C35),
    hairline = Color(0xFF262B34),
    hairlineStrong = Color(0xFF343B47),
    ink = Color(0xFFF4F5F7),
    inkSecondary = Color(0xFFA9B1BF),
    inkTertiary = Color(0xFF6E7787),
    monoCtaContainer = Color(0xFFF4F5F7),
    monoCtaContent = Color(0xFF0E1013),
    ember = Color(0xFFFB923C),
    emberBright = Color(0xFFF97316),
    emberContainer = Color(0xFF2B1C12),
    emberBorder = Color(0xFF5A351E),
    dockContainer = Color(0xFF171A1F),
    dockActiveContainer = Color(0xFF20242B),
)

private val LightSemanticColors = FlameSemanticColors(
    healthy = Color(0xFF059669),
    healthyContainer = Color(0xFFECFDF5),
    onHealthyContainer = Color(0xFF065F46),
    warning = Color(0xFFB45309),
    warningContainer = Color(0xFFFFFBEB),
    onWarningContainer = Color(0xFF78350F),
    danger = Color(0xFFDC2626),
    dangerContainer = Color(0xFFFEF2F2),
    onDangerContainer = Color(0xFF991B1B),
    sparkGold = Color(0xFFEA580C),
    sparkGoldContainer = Color(0xFFFFF4EC),
    onSparkGoldContainer = Color(0xFF9A3412),
    neutral = Color(0xFF4B5262),
    neutralContainer = Color(0xFFF0F2F5),
    onNeutralContainer = Color(0xFF111318),
    warningBorder = Color(0xFFFDE68A),
    dangerBorder = Color(0xFFFECACA),
    sparkGoldBorder = Color(0xFFFED7AA),
)

private val DarkSemanticColors = FlameSemanticColors(
    healthy = Color(0xFF34D399),
    healthyContainer = Color(0xFF122922),
    onHealthyContainer = Color(0xFFA7F3D0),
    warning = Color(0xFFFBBF24),
    warningContainer = Color(0xFF2B2310),
    onWarningContainer = Color(0xFFFDE68A),
    danger = Color(0xFFF87171),
    dangerContainer = Color(0xFF2E1618),
    onDangerContainer = Color(0xFFFECACA),
    sparkGold = Color(0xFFFB923C),
    sparkGoldContainer = Color(0xFF2B1C12),
    onSparkGoldContainer = Color(0xFFFED7AA),
    neutral = Color(0xFFA9B1BF),
    neutralContainer = Color(0xFF20242B),
    onNeutralContainer = Color(0xFFF4F5F7),
    warningBorder = Color(0xFF5C4718),
    dangerBorder = Color(0xFF5F262A),
    sparkGoldBorder = Color(0xFF5A351E),
)

private fun deriveDynamicPalette(
    basePalette: FlamePaletteTokens,
    dynamic: ColorScheme,
    isDark: Boolean,
): FlamePaletteTokens {
    val resolvedSurface = if (isDark) dynamic.surfaceContainerLow else dynamic.surfaceContainerLowest
    return basePalette.copy(
        canvas = if (isDark) dynamic.surface else dynamic.surfaceContainerLow,
        surface = resolvedSurface,
        surfaceSubtle = dynamic.surfaceContainerHigh,
        surfacePress = dynamic.surfaceContainerHighest,
        hairline = dynamic.outlineVariant,
        hairlineStrong = dynamic.outline,
        ink = dynamic.onSurface,
        inkSecondary = dynamic.onSurfaceVariant,
        inkTertiary = dynamic.outline,
        monoCtaContainer = dynamic.primary,
        monoCtaContent = dynamic.onPrimary,
        ember = dynamic.primary,
        emberBright = dynamic.primary,
        emberContainer = dynamic.primaryContainer,
        emberBorder = dynamic.outlineVariant,
        dockContainer = resolvedSurface,
        dockActiveContainer = dynamic.secondaryContainer,
    )
}

private fun buildMaterialColorScheme(
    palette: FlamePaletteTokens,
    semanticColors: FlameSemanticColors,
    isDark: Boolean,
    dynamicScheme: ColorScheme?,
): ColorScheme {
    if (dynamicScheme != null) {
        return dynamicScheme.copy(
            background = palette.canvas,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceSubtle,
            onSurfaceVariant = palette.inkSecondary,
            outline = palette.hairlineStrong,
            outlineVariant = palette.hairline,
            error = semanticColors.danger,
            onError = if (isDark) Color(0xFF1F0A0B) else Color.White,
            errorContainer = semanticColors.dangerContainer,
            onErrorContainer = semanticColors.onDangerContainer,
        )
    }

    return if (isDark) {
        darkColorScheme(
            primary = palette.monoCtaContainer,
            onPrimary = palette.monoCtaContent,
            primaryContainer = palette.surfaceSubtle,
            onPrimaryContainer = palette.ink,
            secondary = palette.inkSecondary,
            onSecondary = palette.canvas,
            secondaryContainer = palette.surfaceSubtle,
            onSecondaryContainer = palette.ink,
            tertiary = palette.ember,
            onTertiary = Color(0xFF1A0C04),
            tertiaryContainer = palette.emberContainer,
            onTertiaryContainer = palette.ember,
            error = semanticColors.danger,
            onError = Color(0xFF1F0A0B),
            errorContainer = semanticColors.dangerContainer,
            onErrorContainer = semanticColors.onDangerContainer,
            background = palette.canvas,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceSubtle,
            onSurfaceVariant = palette.inkSecondary,
            outline = palette.hairlineStrong,
            outlineVariant = palette.hairline,
            surfaceContainerLowest = palette.canvas,
            surfaceContainerLow = palette.surface,
            surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.surfaceSubtle,
            surfaceContainerHighest = palette.surfacePress,
        )
    } else {
        lightColorScheme(
            primary = palette.monoCtaContainer,
            onPrimary = palette.monoCtaContent,
            primaryContainer = palette.surfaceSubtle,
            onPrimaryContainer = palette.ink,
            secondary = palette.inkSecondary,
            onSecondary = Color.White,
            secondaryContainer = palette.surfaceSubtle,
            onSecondaryContainer = palette.ink,
            tertiary = palette.ember,
            onTertiary = Color.White,
            tertiaryContainer = palette.emberContainer,
            onTertiaryContainer = palette.ember,
            error = semanticColors.danger,
            onError = Color.White,
            errorContainer = semanticColors.dangerContainer,
            onErrorContainer = semanticColors.onDangerContainer,
            background = palette.canvas,
            onBackground = palette.ink,
            surface = palette.surface,
            onSurface = palette.ink,
            surfaceVariant = palette.surfaceSubtle,
            onSurfaceVariant = palette.inkSecondary,
            outline = palette.hairlineStrong,
            outlineVariant = palette.hairline,
            surfaceContainerLowest = palette.surface,
            surfaceContainerLow = palette.surface,
            surfaceContainer = palette.surfaceSubtle,
            surfaceContainerHigh = palette.surfaceSubtle,
            surfaceContainerHighest = palette.surfacePress,
        )
    }
}

private val LocalFlamePalette = staticCompositionLocalOf { LightFlamePalette }
private val LocalFlameColors = staticCompositionLocalOf { LightSemanticColors }
private val LocalFlameIsDark = staticCompositionLocalOf { false }
private val LocalFlameDynamicColorActive = staticCompositionLocalOf { false }

private val FlameTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        fontFeatureSettings = "tnum",
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    titleSmall = TextStyle(
        fontSize = 14.5.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
    ),
    labelLarge = TextStyle(
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    ),
)

private val FlameShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun FlameTheme(
    preference: ThemePreference = ThemePreference(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = ThemePreferencesStore.resolveIsDark(preference.mode, systemDark)
    val dynamicActive = ThemePreferencesStore.resolveEffectiveDynamicColor(
        enabled = preference.dynamicColorEnabled,
        sdkInt = Build.VERSION.SDK_INT,
    )
    val context = LocalContext.current
    val basePalette = if (isDark) DarkFlamePalette else LightFlamePalette
    val semanticColors = if (isDark) DarkSemanticColors else LightSemanticColors

    val dynamicScheme = if (dynamicActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        null
    }

    val palette = if (dynamicScheme != null) {
        deriveDynamicPalette(
            basePalette = basePalette,
            dynamic = dynamicScheme,
            isDark = isDark,
        )
    } else {
        basePalette
    }

    val colorScheme = buildMaterialColorScheme(
        palette = palette,
        semanticColors = semanticColors,
        isDark = isDark,
        dynamicScheme = dynamicScheme,
    )

    CompositionLocalProvider(
        LocalFlamePalette provides palette,
        LocalFlameColors provides semanticColors,
        LocalFlameIsDark provides isDark,
        LocalFlameDynamicColorActive provides (dynamicScheme != null),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = FlameTypography,
            shapes = FlameShapes,
            content = content,
        )
    }
}

object FlameThemeTokens {
    val palette: FlamePaletteTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalFlamePalette.current

    val semanticColors: FlameSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFlameColors.current

    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalFlameIsDark.current

    val isDynamicColorActive: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalFlameDynamicColorActive.current
}
