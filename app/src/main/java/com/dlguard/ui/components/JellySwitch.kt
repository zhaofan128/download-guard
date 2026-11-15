package com.dlguard.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dlguard.ui.theme.LocalGlassConfig
import com.dlguard.ui.theme.LocalMotionEnabled
import com.dlguard.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 自研果冻开关（需求定参版）：
 *  - 轨道 64x36，滑块 28dp，全圆角；开启时轨道为浅蓝→亮蓝横向渐变的水滴晕染填充；
 *  - 位移 spring(dampingRatio = 0.4, stiffness = 400)（Motion.Jelly，全局唯一来源）；
 *  - 点击/拖拽时滑块沿运动方向拉伸至 1.3 倍，松手 220ms 弹回（squash & stretch）；
 *  - 开启瞬间轨道用径向「涟漪」从滑块位置像水滴一样扩散填充；
 *  - 按压缩放 0.94；
 *  - 触觉反馈 TextHandleMove，可在设置里关闭；
 *  - 语义 Role.Switch + stateDescription，TalkBack 可读。
 */
@Composable
fun JellySwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    hapticEnabled: Boolean = true
) {
    val haptics = LocalHapticFeedback.current
    val motionEnabled = LocalMotionEnabled.current
    val config = LocalGlassConfig.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val progress = remember { Animatable(if (checked) 1f else 0f) }
    val stretchX = remember { Animatable(1f) }
    val stretchY = remember { Animatable(1f) }
    val press = remember { Animatable(1f) }
    var dragging by remember { mutableStateOf(false) }

    val travelPx = with(density) { 28.dp.toPx() }
    val horizontalPaddingPx = with(density) { 4.dp.toPx() }

    LaunchedEffect(checked) {
        if (dragging) return@LaunchedEffect
        if (motionEnabled) {
            progress.animateTo(if (checked) 1f else 0f, Motion.Jelly)
        } else {
            progress.snapTo(if (checked) 1f else 0f)
        }
    }

    val trackBase = if (config.surfaceAlpha < 0.3f) {
        Color.White.copy(alpha = 0.10f)
    } else {
        Color(0xFF101828).copy(alpha = 0.08f)
    }

    Box(
        modifier = modifier
            .size(width = 64.dp, height = 36.dp)
            .graphicsLayer {
                scaleX = press.value
                scaleY = press.value
                alpha = if (enabled) 1f else 0.45f
            }
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "已开启" else "已关闭"
            }
            .pointerInput(enabled, checked, motionEnabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        scope.launch { press.animateTo(1.03f, Motion.Press) }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val next = (progress.value + dragAmount / travelPx).coerceIn(0f, 1f)
                        scope.launch { progress.snapTo(next) }
                        // 拖拽方向拉伸，峰值 1.3 倍（需求定参），松手弹回
                        val stretch = (abs(dragAmount) / (travelPx * 0.5f)).coerceIn(0f, 0.3f)
                        scope.launch {
                            stretchX.snapTo(1f + stretch)
                            stretchY.snapTo(1f - stretch * 0.5f)
                        }
                    },
                    onDragEnd = {
                        dragging = false
                        val target = if (progress.value >= 0.5f) 1f else 0f
                        val changed = (target == 1f) != checked
                        scope.launch {
                            if (motionEnabled) {
                                progress.animateTo(target, Motion.Jelly)
                                stretchX.animateTo(1f, tween(Motion.JellySettleDuration))
                                stretchY.animateTo(1f, tween(Motion.JellySettleDuration))
                                press.animateTo(1f, Motion.Press)
                            } else {
                                progress.snapTo(target)
                                stretchX.snapTo(1f)
                                stretchY.snapTo(1f)
                                press.snapTo(1f)
                            }
                        }
                        if (changed) {
                            onCheckedChange(target == 1f)
                            if (hapticEnabled) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        scope.launch {
                            progress.animateTo(if (checked) 1f else 0f, Motion.Jelly)
                            stretchX.animateTo(1f, tween(Motion.JellySettleDuration))
                            stretchY.animateTo(1f, tween(Motion.JellySettleDuration))
                            press.animateTo(1f, Motion.Press)
                        }
                    }
                )
            }
            .pointerInput(enabled, checked, motionEnabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        scope.launch { press.animateTo(0.94f, Motion.Press) }
                        tryAwaitRelease()
                        scope.launch { press.animateTo(1f, Motion.Press) }
                    },
                    onTap = {
                        val next = !checked
                        onCheckedChange(next)
                        if (hapticEnabled) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        scope.launch {
                            if (motionEnabled) {
                                progress.animateTo(if (next) 1f else 0f, Motion.Jelly)
                                // 点击切换：沿运动方向拉伸至 1.3 倍再弹回
                                stretchX.animateTo(1.3f, tween(90))
                                stretchY.animateTo(0.86f, tween(90))
                                stretchX.animateTo(1f, tween(Motion.JellySettleDuration))
                                stretchY.animateTo(1f, tween(Motion.JellySettleDuration))
                            } else {
                                progress.snapTo(if (next) 1f else 0f)
                            }
                        }
                    }
                )
            }
    ) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.height / 2f
            // 绘制用进度一律夹到 [0,1]：Jelly 弹簧（damping 0.4）会过冲约 25%，
            // 原始值直接乘 alpha 会构造出 alpha > 1 的 Color 并抛
            // IllegalArgumentException 崩掉主线程（位移不受限，保留原始值以保果冻感）。
            val p = progress.value.coerceIn(0f, 1f)
            drawRoundRect(color = trackBase, cornerRadius = CornerRadius(radius, radius))
            drawRoundRect(
                color = Color.White.copy(alpha = 0.10f),
                cornerRadius = CornerRadius(radius, radius),
                style = Stroke(width = 1.dp.toPx())
            )
            if (p > 0.01f) {
                val trackPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            left = 0f,
                            top = 0f,
                            right = size.width,
                            bottom = size.height,
                            cornerRadius = CornerRadius(radius, radius)
                        )
                    )
                }
                clipPath(trackPath) {
                    // 开启态轨道底色：浅蓝→亮蓝横向渐变（water-fill 的底层）
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF9BDCFE).copy(alpha = 0.9f * p),
                                accentColor.copy(alpha = 0.95f * p)
                            ),
                            startX = 0f,
                            endX = size.width
                        ),
                        cornerRadius = CornerRadius(radius, radius)
                    )
                    // 水滴晕染：从滑块当前位置向外扩散的径向高亮，模拟液滴落水
                    val centerX = horizontalPaddingPx + 14.dp.toPx() + travelPx * p
                    val fillRadius = size.width * (0.3f + 1.05f * p)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.35f * p),
                                Color.Transparent
                            ),
                            center = Offset(centerX, size.height / 2f),
                            radius = fillRadius
                        ),
                        radius = fillRadius,
                        center = Offset(centerX, size.height / 2f)
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)
                        ),
                        cornerRadius = CornerRadius(radius, radius)
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.18f * p),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (horizontalPaddingPx + travelPx * progress.value).roundToInt(),
                        y = horizontalPaddingPx.roundToInt()
                    )
                }
                .size(28.dp)
                .graphicsLayer {
                    scaleX = stretchX.value
                    scaleY = stretchY.value
                }
                .shadow(3.dp, CircleShape, clip = false)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White, Color(0xFFE9EFF9))
                    ),
                    shape = CircleShape
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f),
                            Color.White.copy(alpha = 0.25f)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(
                        color = accentColor.copy(alpha = 0.9f * progress.value.coerceIn(0f, 1f)),
                        shape = CircleShape
                    )
            )
        }
    }
}
