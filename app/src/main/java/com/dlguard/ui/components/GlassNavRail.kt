package com.dlguard.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.dlguard.ui.theme.GlassRadii
import com.dlguard.ui.theme.LocalGlassConfig
import com.dlguard.ui.theme.LocalMotionEnabled
import com.dlguard.ui.theme.Motion

/**
 * 宽屏（平板 / 折叠屏展开 / 横屏）用的侧边导航栏。
 *
 * 手机竖屏沿用底部胶囊栏；宽度 ≥ 600dp 时切成左侧竖栏，
 * 一是符合平板上的操作习惯（拇指够不到屏幕底部中间），
 * 二是把纵向空间全部让给内容，避免列表被底栏压住最后一行。
 */
@Composable
fun GlassNavRail(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val config = LocalGlassConfig.current
    val motionEnabled = LocalMotionEnabled.current
    Column(
        modifier = modifier
            .width(96.dp)
            .fillMaxHeight()
            .padding(vertical = 8.dp)
            .liquidGlass(
                cornerRadius = GlassRadii.Bar,
                config = config,
                refraction = true,
                glowColor = MaterialTheme.colorScheme.secondary
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == selectedIndex
            val scale by animateFloatAsState(
                targetValue = if (selected) 1.08f else 1f,
                animationSpec = if (motionEnabled) Motion.Jelly else tween(0),
                label = "railScale$index"
            )
            val highlight by animateFloatAsState(
                targetValue = if (selected) 1f else 0f,
                animationSpec = tween(if (motionEnabled) 240 else 0),
                label = "railHighlight$index"
            )
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(
                        interactionSource = interaction,
                        indication = null
                    ) { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.18f * highlight),
                                    Color.White.copy(alpha = 0.04f * highlight)
                                )
                            ),
                            shape = RoundedCornerShape(22.dp)
                        )
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}
