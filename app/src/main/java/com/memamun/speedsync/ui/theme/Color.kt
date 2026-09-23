package com.memamun.speedsync.ui.theme

import androidx.compose.ui.graphics.Color

// Deep OLED Black & Material 3 Expressive Tonal Hierarchy (Dark)
val OledBlack = Color(0xFF09090D)             // Canvas background (pure deep black)
val DarkBackground = OledBlack
val DarkSurface = Color(0xFF13131A)              // Elevated cards and bottom bar
val DarkSurfaceVariant = Color(0xFF1E1C27)       // Subtle containers & secondary surfaces
val DarkSurfaceHighlight = Color(0xFF2B2838)     // Inactive tracks, dividers, borders
val DarkTextPrimary = Color(0xFFF3F0F7)          // Crisp pure readable white
val DarkTextSecondary = Color(0xFFA29CAE)        // Refined muted silver

// Light / Day Palette
val LightBackground = Color(0xFFF6F6FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEEEAF4)
val LightSurfaceHighlight = Color(0xFFE2DDEC)
val LightTextPrimary = Color(0xFF16151D)
val LightTextSecondary = Color(0xFF676274)

// Material 3 Expressive Brand Accents (Pixel Electric Periwinkle / Lavender)
val DarkAccentPurple = Color(0xFFCBB8FF)         // Luminous, calm, elegant periwinkle
val LightAccentPurple = Color(0xFF6550A4)        // Deep rich purple
val AccentPurple = DarkAccentPurple
val OnAccentPurple = Color(0xFF2C155E)
val OnLightAccentPurple = Color(0xFFFFFFFF)

// M3 Expressive Secondary Containers (Subtle selected pill)
val DarkSecondaryContainer = Color(0xFF282535)   // Subtle, non-striking selected pill
val LightSecondaryContainer = Color(0xFFEADBFF)  // Soft delicate lavender
val OnDarkSecondaryContainer = Color(0xFFE8DEF8)
val OnLightSecondaryContainer = Color(0xFF21005D)

// Expressive Status Accents (Pixel-style high-contrast)
val LightStatusGreen = Color(0xFF047857)         // Contrast > 4.5:1
val DarkStatusGreen = Color(0xFF4ADE80)          // Pixel Emerald Green, contrast > 8:1
val LightStatusRed = Color(0xFFDC2626)           // Contrast > 4.5:1
val DarkStatusRed = Color(0xFFF87171)            // Pixel Coral Red, contrast > 5:1
val StatusGreen = DarkStatusGreen
val StatusRed = DarkStatusRed
val StatusBlue = Color(0xFF60A5FA)

// Compatibility aliases
val TextPrimary = DarkTextPrimary
val TextSecondary = DarkTextSecondary

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

