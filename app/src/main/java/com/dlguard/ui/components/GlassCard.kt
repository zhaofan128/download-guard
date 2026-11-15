package com.dlguard.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dlguard.ui.theme.Danger
import com.dlguard.ui.theme.GlassRadii
import com.dlguard.ui.theme.LocalGlassConfig
import com.dlguard.ui.theme.LocalMotionEnabled
import com.dlguard.ui.theme.Motion
import com.dlguard.ui.theme.Spacing

/**
 * 液态玻璃卡片。点击时径向高光从中心柔和扩散（替代水波纹），并压缩 0.98。
 * 列表项请把 refraction 保持 false，避免离屏渲染叠加导致掉帧。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassRadii.Card,
    tint: Color = Color.Transparent,
    refraction: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(Spacing.CardPadding),
    content: @Composable ColumnScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val motionEnabled = LocalMotionEnabled.current
    val config = LocalGlassConfig.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = if (motionEnabled) Motion.Press else tween(0),
        label = "glassCardScale"
    )
    val glow by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(if (motionEnabled) Motion.RippleDuration else 0),
        label = "glassCardGlow"
    )
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .liquidGlass(
                cornerRadius = cornerRadius,
                config = config,
                refraction = refraction,
                tint = tint,
                glowColor = MaterialTheme.colorScheme.primary
            )
            .then(clickableModifier)
            .padding(contentPadding)
    ) {
        Column(content = content)
        if (glow > 0.01f) {
            Canvas(Modifier.matchParentSize()) {
                val radius = size.maxDimension * 0.75f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.18f * glow),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = radius
                    ),
                    radius = radius,
                    center = Offset(size.width / 2f, size.height / 2f)
                )
            }
        }
    }
}

enum class GuardButtonStyle { Primary, Glass, Danger }

@Composable
fun GuardButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GuardButtonStyle = GuardButtonStyle.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val motionEnabled = LocalMotionEnabled.current
    val config = LocalGlassConfig.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = if (motionEnabled) Motion.Press else tween(0),
        label = "buttonScale"
    )
    val accent = when (style) {
        GuardButtonStyle.Primary -> MaterialTheme.colorScheme.primary
        GuardButtonStyle.Glass -> MaterialTheme.colorScheme.onSurface
        GuardButtonStyle.Danger -> Danger
    }
    val textColor = when (style) {
        GuardButtonStyle.Primary -> MaterialTheme.colorScheme.onPrimary
        GuardButtonStyle.Glass -> MaterialTheme.colorScheme.onSurface
        GuardButtonStyle.Danger -> if (config.surfaceAlpha < 0.3f) Color(0xFFFCA5A5) else Color(0xFFB91C1C)
    }
    val shape = RoundedCornerShape(GlassRadii.Button)
    val baseModifier = modifier
        .height(48.dp)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = if (enabled) 1f else 0.45f
        }

    val decorated = when (style) {
        GuardButtonStyle.Primary -> baseModifier
            .shadow(8.dp, shape, clip = false, ambientColor = accent.copy(alpha = 0.28f), spotColor = accent.copy(alpha = 0.36f))
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(accent.copy(alpha = 0.92f), accent),
                    start = Offset.Zero,
                    end = Offset(800f, 200f)
                )
            )
        GuardButtonStyle.Glass -> baseModifier
            .liquidGlass(
                cornerRadius = GlassRadii.Button,
                config = config,
                tint = accent.copy(alpha = if (config.surfaceAlpha < 0.3f) 0.10f else 0.06f),
                elevation = 4.dp
            )
        GuardButtonStyle.Danger -> baseModifier
            .liquidGlass(
                cornerRadius = GlassRadii.Button,
                config = config,
                tint = Danger.copy(alpha = if (config.surfaceAlpha < 0.3f) 0.14f else 0.10f),
                elevation = 4.dp
            )
            // 红色玻璃描边 + 微红底色（需求：取消下载按钮的危险态）
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(Danger.copy(alpha = 0.55f), Danger.copy(alpha = 0.2f))
                ),
                shape = RoundedCornerShape(GlassRadii.Button)
            )
    }

    Box(
        modifier = decorated
            .then(
                Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                )
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = textColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }
    }
}
