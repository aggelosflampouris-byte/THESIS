package com.example.thesis.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Acento principal sobrio estilo mensajeria nativa; evita saturaciones artificiales.
val MessagingPrimary = Color(0xFF2A5C55)

val MessagingLightScheme = lightColorScheme(
    primary = MessagingPrimary,
    onPrimary = Color.White,
    secondary = Color(0xFF536360),
    onSecondary = Color.White,
    tertiary = Color(0xFF5D5E58),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF171A19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171A19),
    primaryContainer = Color(0xFFDCECE8),
    onPrimaryContainer = Color(0xFF0E2F2A),
    secondaryContainer = Color(0xFFE9EEEC),
    onSecondaryContainer = Color(0xFF1E2926),
    surfaceVariant = Color(0xFFF2F4F3),
    onSurfaceVariant = Color(0xFF5F6865),
    outlineVariant = Color(0xFFE2E6E4),
    error = Color(0xFFB3261E),
    onError = Color.White
)

val MessagingDarkScheme = darkColorScheme(
    primary = Color(0xFF8CC5BC),
    onPrimary = Color(0xFF0A2A25),
    secondary = Color(0xFFAEC0BC),
    onSecondary = Color(0xFF1A2825),
    tertiary = Color(0xFFBFC2B8),
    background = Color(0xFF111315),
    onBackground = Color(0xFFECEFED),
    surface = Color(0xFF111315),
    onSurface = Color(0xFFECEFED),
    primaryContainer = Color(0xFF1B3B36),
    onPrimaryContainer = Color(0xFFD6ECE8),
    secondaryContainer = Color(0xFF22282B),
    onSecondaryContainer = Color(0xFFDCE4E1),
    surfaceVariant = Color(0xFF1C2023),
    onSurfaceVariant = Color(0xFF98A29F),
    outlineVariant = Color(0xFF2A3034),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)
