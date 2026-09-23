package com.memamun.speedsync.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.memamun.speedsync.model.ThemeMode

private val DarkColorScheme =
    darkColorScheme(
        primary = DarkAccentPurple,
        onPrimary = OnAccentPurple,
        primaryContainer = Color(0xFF352B4E),
        onPrimaryContainer = Color(0xFFEADDFF),
        secondary = Color(0xFFCCC2DC),
        onSecondary = Color(0xFF332D41),
        secondaryContainer = DarkSecondaryContainer,
        onSecondaryContainer = OnDarkSecondaryContainer,
        surface = DarkSurface,
        onSurface = DarkTextPrimary,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkTextSecondary,
        background = DarkBackground,
        onBackground = DarkTextPrimary,
        outline = Color(0xFF3D394C),
        outlineVariant = DarkSurfaceHighlight,
        error = DarkStatusRed
    )

private val LightColorScheme =
    lightColorScheme(
        primary = LightAccentPurple,
        onPrimary = OnLightAccentPurple,
        primaryContainer = Color(0xFFEADDFF),
        onPrimaryContainer = Color(0xFF21005D),
        secondary = Color(0xFF625B71),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = LightSecondaryContainer,
        onSecondaryContainer = OnLightSecondaryContainer,
        surface = LightSurface,
        onSurface = LightTextPrimary,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightTextSecondary,
        background = LightBackground,
        onBackground = LightTextPrimary,
        outline = Color(0xFF79747E),
        outlineVariant = LightSurfaceHighlight,
        error = LightStatusRed
    )

@Composable
fun MyApplicationTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

/**
 * WCAG 2.1 AA compliant status green that dynamically matches current dark/light surface.
 */
val MaterialTheme.statusGreen: Color
    @Composable
    get() {
        val isDark = MaterialTheme.colorScheme.surface == DarkSurface || MaterialTheme.colorScheme.background == DarkBackground
        return if (isDark) DarkStatusGreen else LightStatusGreen
    }

/**
 * WCAG 2.1 AA compliant status red that dynamically matches current dark/light surface.
 */
val MaterialTheme.statusRed: Color
    @Composable
    get() {
        val isDark = MaterialTheme.colorScheme.surface == DarkSurface || MaterialTheme.colorScheme.background == DarkBackground
        return if (isDark) DarkStatusRed else LightStatusRed
    }

