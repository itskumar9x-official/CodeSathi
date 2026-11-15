package com.inyogeshwar.codesathi.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Semantic colours that Material roles do not cover. */
@Immutable
data class SathiStatus(
    val success: Color,
    val warning: Color,
    /** Live / working accent (the saffron spark). */
    val live: Color,
    val isDark: Boolean,
)

val LocalSathiStatus = staticCompositionLocalOf {
    SathiStatus(SathiColors.Success, SathiColors.Warning, SathiColors.SaffronDeep, false)
}

/** True when the system animator scale is 0 ("Remove animations"). */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun CodeSathiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        }.getOrDefault(false)
    }
    val status = if (darkTheme) {
        SathiStatus(SathiColors.SuccessDark, SathiColors.WarningDark, SathiColors.Saffron, true)
    } else {
        SathiStatus(SathiColors.Success, SathiColors.Warning, SathiColors.SaffronDeep, false)
    }
    CompositionLocalProvider(
        LocalSathiStatus provides status,
        LocalSpacing provides SathiSpacing(),
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) SathiDarkColors else SathiLightColors,
            typography = SathiTypography,
            shapes = SathiShapes,
            content = content
        )
    }
}
