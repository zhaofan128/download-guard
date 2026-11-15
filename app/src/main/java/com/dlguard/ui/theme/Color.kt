package com.dlguard.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// 品牌色（青 / 紫双主色）
val Teal300 = Color(0xFF5EEAD4)
val Teal500 = Color(0xFF14B8A6)
val Teal700 = Color(0xFF0F766E)
val TealDeep = Color(0xFF042F2E)
val Violet300 = Color(0xFFA78BFA)
val Violet500 = Color(0xFF8B5CF6)
val VioletDeep = Color(0xFF2E1065)

// 深空背景
val Ink900 = Color(0xFF0B0F1A)
val Ink800 = Color(0xFF131A2B)
val Ink700 = Color(0xFF1B2440)
val Ink600 = Color(0xFF26304A)

// 浅色云雾背景
val Cloud50 = Color(0xFFF5F7FB)
val Cloud100 = Color(0xFFE8EDF7)
val Cloud200 = Color(0xFFDCE3F0)

val Danger = Color(0xFFF87171)
val DangerDeep = Color(0xFFB91C1C)
val Success = Color(0xFF34D399)
val Warning = Color(0xFFFBBF24)

val TextPrimaryDark = Color(0xFFEAF0FF)
val TextSecondaryDark = Color(0xFF9AA8C7)
val TextPrimaryLight = Color(0xFF101828)
val TextSecondaryLight = Color(0xFF5A6784)

val LightScheme = lightColorScheme(
    primary = Teal700,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = TealDeep,
    secondary = Violet500,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = VioletDeep,
    tertiary = Teal500,
    onTertiary = Color.White,
    background = Cloud50,
    onBackground = TextPrimaryLight,
    surface = Color.White,
    onSurface = TextPrimaryLight,
    surfaceVariant = Cloud100,
    onSurfaceVariant = TextSecondaryLight,
    outline = Color(0xFFB6C0D4),
    outlineVariant = Cloud200,
    error = DangerDeep,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

val DarkScheme = darkColorScheme(
    primary = Teal300,
    onPrimary = TealDeep,
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFF99F6E4),
    secondary = Violet300,
    onSecondary = VioletDeep,
    secondaryContainer = Color(0xFF4C1D95),
    onSecondaryContainer = Color(0xFFEDE9FE),
    tertiary = Teal300,
    onTertiary = TealDeep,
    background = Ink900,
    onBackground = TextPrimaryDark,
    surface = Ink800,
    onSurface = TextPrimaryDark,
    surfaceVariant = Ink700,
    onSurfaceVariant = TextSecondaryDark,
    outline = Color(0xFF35405C),
    outlineVariant = Ink600,
    error = Danger,
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2)
)
