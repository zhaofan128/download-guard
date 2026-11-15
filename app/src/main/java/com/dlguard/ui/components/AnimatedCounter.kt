package com.dlguard.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.dlguard.ui.theme.LocalMotionEnabled
import com.dlguard.ui.theme.MonoNumberStyle

/**
 * 滚动数字：AnimatedContent 上下滑动切换，等宽字体保证不抖动。
 * 系统关闭动画时直接显示终态。
 */
@Composable
fun AnimatedCounter(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MonoNumberStyle,
    color: Color = MaterialTheme.colorScheme.onSurface,
    suffix: String = ""
) {
    val motionEnabled = LocalMotionEnabled.current
    if (!motionEnabled) {
        Text(text = "$value$suffix", style = style, color = color, modifier = modifier)
        return
    }
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            val direction = if (targetState >= initialState) 1 else -1
            (
                slideInVertically(animationSpec = tween(260)) { height -> direction * height } +
                    fadeIn(animationSpec = tween(220))
                ) togetherWith (
                slideOutVertically(animationSpec = tween(200)) { height -> -direction * height } +
                    fadeOut(animationSpec = tween(160))
                ) using SizeTransform(clip = true)
        },
        label = "AnimatedCounter",
        modifier = modifier
    ) { target ->
        Text(text = "$target$suffix", style = style, color = color)
    }
}
