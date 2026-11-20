package com.example.thesis.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

val MessagingPrimary = Color(0xFF315C54)

val MessagingLightScheme = lightColorScheme(
    primary = MessagingPrimary,
    secondary = Color(0xFF52645F),
    tertiary = Color(0xFF6D5E40),
    background = Color(0xFFF8F9F7),
    surface = Color(0xFFF8F9F7),
    primaryContainer = Color(0xFFD4E8E1),
    onPrimaryContainer = Color(0xFF0F3831),
    secondaryContainer = Color(0xFFDBE5E0),
    onSecondaryContainer = Color(0xFF192A26),
    surfaceVariant = Color(0xFFE2E7E4)
)

val MessagingDarkScheme = darkColorScheme(
    primary = Color(0xFFA3D0C2),
    secondary = Color(0xFFB8CBC3),
    tertiary = Color(0xFFE0C999),
    primaryContainer = Color(0xFF1E463E),
    onPrimaryContainer = Color(0xFFD4E8E1),
    secondaryContainer = Color(0xFF344641),
    onSecondaryContainer = Color(0xFFDBE5E0),
    surfaceVariant = Color(0xFF2B3A36)
)
