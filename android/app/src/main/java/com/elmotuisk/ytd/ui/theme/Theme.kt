package com.elmotuisk.ytd.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val YtdDarkColorScheme = darkColorScheme(
    primary = YtdPrimary,
    onPrimary = YtdOnPrimary,
    background = YtdBackground,
    surface = YtdSurface,
    surfaceVariant = YtdSurfaceVariant,
    onBackground = YtdOnSurface,
    onSurface = YtdOnSurface,
    onSurfaceVariant = YtdOnSurfaceVariant,
    error = YtdError,
)

@Composable
fun YtdTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = YtdDarkColorScheme,
        typography = YtdTypography,
        content = content,
    )
}
