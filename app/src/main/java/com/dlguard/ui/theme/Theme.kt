package com.dlguard.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun DownloadGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    lowRamDevice: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    val glass = remember(darkTheme, lowRamDevice) {
        if (darkTheme) darkGlassConfig(lowRamDevice) else lightGlassConfig(lowRamDevice)
    }
    val motionEnabled = rememberMotionEnabled()
    // Material3 的 LocalContentColor 默认是纯黑，只有 Surface/Scaffold 才会改写它。
    // 本应用是「全屏渐变 + 玻璃卡片」的布局，页面根节点没有 Surface，一旦有 Text 忘了
    // 显式指定颜色就会变黑字（深色背景下直接看不见）。这里给全局兜底为 onBackground。
    CompositionLocalProvider(
        LocalGlassConfig provides glass,
        LocalMotionEnabled provides motionEnabled,
        LocalContentColor provides scheme.onBackground
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
