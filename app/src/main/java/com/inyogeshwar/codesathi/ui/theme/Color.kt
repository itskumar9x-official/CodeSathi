package com.inyogeshwar.codesathi.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * CodeSathi brand palette (v1.2) — "Sathi" teal with a saffron spark.
 * Teal carries the brand and interactive states; saffron is the small
 * warm accent (the spark in the logo, live/working states, highlights).
 */
object SathiColors {
    val TealDeep = Color(0xFF0B5F5A)
    val Teal = Color(0xFF0D9488)
    val TealBright = Color(0xFF14B8A6)
    val Mint = Color(0xFF5EEAD4)
    val MintPale = Color(0xFFC9F7EC)
    val Saffron = Color(0xFFFFB547)
    val SaffronDeep = Color(0xFF9A5B00)

    val Mist = Color(0xFFF4F8F6)
    val Ink = Color(0xFF10201E)
    val Dusk = Color(0xFF0A1113)
    val DuskRaised = Color(0xFF131E20)
    val DuskHigh = Color(0xFF182527)
    val DuskHighest = Color(0xFF1E2C2F)

    /** Code / terminal surface, used in both themes. */
    val CodeSurface = Color(0xFF0E1719)
    val CodeHeader = Color(0xFF152124)
    val CodeText = Color(0xFFE3EEEB)
    val CodeMuted = Color(0xFF8DA19D)

    val Success = Color(0xFF2E8B57)
    val SuccessDark = Color(0xFF7BD8A2)
    val Warning = Color(0xFFB26A00)
    val WarningDark = Color(0xFFFFC46B)
    val Error = Color(0xFFBA1A1A)
    val ErrorDark = Color(0xFFFFB4AB)
}

internal val SathiLightColors = lightColorScheme(
    primary = Color(0xFF0B7A72),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDF3EA),
    onPrimaryContainer = Color(0xFF00302C),
    secondary = Color(0xFF3F6A64),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEBE7),
    onSecondaryContainer = Color(0xFF14302C),
    tertiary = SathiColors.SaffronDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2B8),
    onTertiaryContainer = Color(0xFF2F1A00),
    background = SathiColors.Mist,
    onBackground = SathiColors.Ink,
    surface = SathiColors.Mist,
    onSurface = SathiColors.Ink,
    surfaceVariant = Color(0xFFE2EBE8),
    onSurfaceVariant = Color(0xFF4A5D5A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFCFB),
    surfaceContainer = Color(0xFFEBF1EF),
    surfaceContainerHigh = Color(0xFFE4ECE9),
    surfaceContainerHighest = Color.White,
    outline = Color(0xFF7A8C89),
    outlineVariant = Color(0xFFD5E0DD),
    error = SathiColors.Error,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

internal val SathiDarkColors = darkColorScheme(
    primary = SathiColors.Mint,
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF0F4F49),
    onPrimaryContainer = Color(0xFFB8F5EA),
    secondary = Color(0xFF9CCFC6),
    onSecondary = Color(0xFF06302B),
    secondaryContainer = Color(0xFF1D3936),
    onSecondaryContainer = Color(0xFFCBE8E2),
    tertiary = SathiColors.WarningDark,
    onTertiary = Color(0xFF462A00),
    tertiaryContainer = Color(0xFF5C3B00),
    onTertiaryContainer = Color(0xFFFFE2B8),
    background = SathiColors.Dusk,
    onBackground = Color(0xFFE3EEEB),
    surface = SathiColors.Dusk,
    onSurface = Color(0xFFE3EEEB),
    surfaceVariant = Color(0xFF1A2629),
    onSurfaceVariant = Color(0xFF9FB3AF),
    surfaceContainerLowest = Color(0xFF060B0C),
    surfaceContainerLow = Color(0xFF0F181A),
    surfaceContainer = SathiColors.DuskRaised,
    surfaceContainerHigh = SathiColors.DuskHigh,
    surfaceContainerHighest = SathiColors.DuskHighest,
    outline = Color(0xFF6B807C),
    outlineVariant = Color(0xFF26383A),
    error = SathiColors.ErrorDark,
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFFFDAD6),
)
