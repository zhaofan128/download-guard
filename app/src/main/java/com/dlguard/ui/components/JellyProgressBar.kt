package com.dlguard.ui.components

import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dlguard.ui.theme.LocalMotionEnabled
import com.dlguard.ui.theme.Motion

/**
 * 果冻进度条：下载卡片第二行。
 *
 *  - 主体：青→蓝渐变圆角条，进度变化用 spring 跟随（damping 0.4 / stiffness 400，同果冻开关），
 *    大文件分块跳变时进度条是「流」过去的而不是「跳」过去的；
 *  - 液态拖尾：进度头部向后拖出一条渐变尾巴（宽度 ≈ 条高 × 4，透明度递减），
 *    运动时模拟液体被拉长的粘滞感；静止时拖尾收拢进头部；
 *  - 头部光点：白色高光小圆 + 呼吸半径，让「正在下载」一眼可辨；
 *  - 轨道：白 8% 半透明 + 1dp 内描边，贴液态玻璃质感。
 */
@Composable
fun JellyProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    headColor: Color = Color(0xFF5EEAD4),
    tailColor: Color = Color(0xFF38BDF8)
) {
    val motionEnabled = LocalMotionEnabled.current
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (motionEnabled) {
            spring(dampingRatio = 0.4f, stiffness = 400f)
        } else {
            tween(0)
        },
        label = "jellyProgress"
    )
    val glowPhase by rememberInfiniteTransition(label = "jellyGlow")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = androidx.compose.animation.core.LinearEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "glowPhase"
        )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
    ) {
        val trackHeight = size.height
        val radius = trackHeight / 2f

        // 轨道
        drawRoundRect(
            color = Color.White.copy(alpha = 0.08f),
            cornerRadius = CornerRadius(radius, radius)
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.14f),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = 1.dp.toPx())
        )

        val clamped = animated.coerceIn(0f, 1f)
        if (clamped <= 0.005f) return@Canvas
        val fillWidth = size.width * clamped

        // 液态拖尾：头部向后拖出的渐变尾巴
        val tailLength = if (active) trackHeight * 4f else 0f
        val tailStart = (fillWidth - tailLength).coerceAtLeast(0f)
        if (tailLength > 0f && tailStart < fillWidth) {
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        tailColor.copy(alpha = 0.06f),
                        headColor.copy(alpha = 0.28f)
                    ),
                    startX = tailStart,
                    endX = fillWidth
                ),
                topLeft = Offset(tailStart, 0f),
                size = Size(fillWidth - tailStart, trackHeight),
                cornerRadius = CornerRadius(radius, radius)
            )
        }

        // 主体填充：青→蓝渐变
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(tailColor.copy(alpha = 0.85f), headColor),
                startX = 0f,
                endX = fillWidth.coerceAtLeast(1f)
            ),
            topLeft = Offset(0f, 0f),
            size = Size(fillWidth, trackHeight),
            cornerRadius = CornerRadius(radius, radius)
        )

        // 头部高光光点（呼吸）
        val headX = fillWidth - radius
        if (headX > 0f) {
            val breath = if (active) 0.5f + 0.5f * glowPhase else 0f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.9f),
                        Color.White.copy(alpha = 0.15f + 0.25f * breath)
                    ),
                    center = Offset(headX, trackHeight / 2f),
                    radius = radius * (1.2f + 0.5f * breath)
                ),
                radius = radius * (1.1f + 0.45f * breath),
                center = Offset(headX, trackHeight / 2f)
            )
        }
    }
}

/** 液态玻璃分割线：1dp 渐变透明白线（Alpha 0.1 → 0.02），替代死板实线。 */
@Composable
fun GlassDivider(modifier: Modifier = Modifier, alpha: Float = 0.1f) {
    val brush = remember(alpha) {
        Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = alpha),
                Color.Transparent
            )
        )
    }
    Canvas(modifier = modifier.fillMaxWidth().height(1.dp)) {
        drawRect(brush = brush, size = Size(size.width, 1.dp.toPx().coerceAtMost(size.height)))
    }
}
