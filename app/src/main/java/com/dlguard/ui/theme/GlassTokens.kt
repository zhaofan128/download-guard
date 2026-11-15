package com.dlguard.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃参数。深色/浅色两套分别调参（绝非简单反转 Alpha）：
 *  - 浅色模式靠高白度与低对比制造「磨砂白玻璃」；
 *  - 深色模式靠低白度 + 更高阴影 + 更强高光制造「暗夜玻璃」。
 * 低端机（isLowRamDevice）关闭 AGSL 折射、噪点与局部模糊，仅保留半透明 + 描边。
 *
 * 有色阴影（禁用死黑）：shadowColor 为深蓝/深紫底色，
 * shadowOffsetY=10dp、shadowBlur=30dp，由 LiquidGlassModifier 自绘。
 */
@Immutable
data class GlassConfig(
    val surfaceAlpha: Float,
    val gradientTop: Color,
    val gradientBottom: Color,
    val borderHighlight: Float,
    val borderWidth: Dp,
    val blurRadius: Dp,
    val noiseAlpha: Float,
    val shadowAlpha: Float,
    val shadowElevation: Dp,
    val shadowColor: Color,
    val shadowOffsetY: Dp,
    val shadowBlur: Dp,
    val refractionStrength: Float,
    val enableRefraction: Boolean,
    val enableNoise: Boolean,
    val enableBlur: Boolean,
    val glowAlpha: Float
)

fun lightGlassConfig(lowRam: Boolean): GlassConfig = GlassConfig(
    surfaceAlpha = 0.55f,
    gradientTop = Color.White.copy(alpha = 0.86f),
    gradientBottom = Color.White.copy(alpha = 0.46f),
    borderHighlight = 0.8f,
    borderWidth = 1.dp,
    blurRadius = 30.dp,
    noiseAlpha = 0.03f,
    shadowAlpha = 0.3f,
    shadowElevation = 8.dp,
    shadowColor = Color(0xFF3B3F8F),
    shadowOffsetY = 10.dp,
    shadowBlur = 30.dp,
    refractionStrength = 1.6f,
    enableRefraction = !lowRam,
    enableNoise = !lowRam,
    enableBlur = true,
    glowAlpha = 0.16f
)

fun darkGlassConfig(lowRam: Boolean): GlassConfig = GlassConfig(
    surfaceAlpha = 0.06f,
    gradientTop = Color.White.copy(alpha = 0.12f),
    gradientBottom = Color.White.copy(alpha = 0.015f),
    borderHighlight = 0.5f,
    borderWidth = 1.2.dp,
    blurRadius = 30.dp,
    noiseAlpha = 0.03f,
    shadowAlpha = 0.5f,
    shadowElevation = 12.dp,
    shadowColor = Color(0xFF1B1240),
    shadowOffsetY = 10.dp,
    shadowBlur = 30.dp,
    refractionStrength = 2.4f,
    enableRefraction = !lowRam,
    enableNoise = !lowRam,
    enableBlur = true,
    glowAlpha = 0.28f
)

val LocalGlassConfig = staticCompositionLocalOf { lightGlassConfig(lowRam = false) }

/** 圆角体系 */
object GlassRadii {
    val Card = 24.dp
    val Button = 20.dp
    val Dialog = 28.dp
    val Bar = 32.dp
    val Pill = 999.dp
}

/** 8dp 基准栅格，页面左右留白 20dp */
object Spacing {
    val Page = 20.dp
    val Grid = 8.dp
    val CardPadding = 18.dp
    val SectionGap = 16.dp
    val ItemGap = 12.dp
}
