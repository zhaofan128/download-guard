package com.dlguard.ui.components

import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.dlguard.ui.theme.Cloud100
import com.dlguard.ui.theme.Cloud50
import com.dlguard.ui.theme.Ink800
import com.dlguard.ui.theme.Ink900
import com.dlguard.ui.theme.LocalMotionEnabled
import com.dlguard.ui.theme.Teal300
import com.dlguard.ui.theme.Violet300

/**
 * 深空 / 云雾渐变背景 + 两团缓慢漂移的弥散光斑（青 #5EEAD4 / 紫 #A78BFA，Alpha 0.25，20s 周期）。
 * 光斑用缓存的 RadialGradient + Canvas 矩阵变换绘制，动画过程中不产生新对象。
 */
@Composable
fun GlassBackground(
    dark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val motionEnabled = LocalMotionEnabled.current
    val transition = rememberInfiniteTransition(label = "glassBlobs")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(20_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blobDrift"
    )
    val drift2 by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(26_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blobDrift2"
    )
    val phase = if (motionEnabled) drift else 0.35f
    val phase2 = if (motionEnabled) drift2 else 0.65f

    val background = if (dark) {
        Brush.linearGradient(colors = listOf(Ink900, Ink800))
    } else {
        Brush.linearGradient(colors = listOf(Cloud50, Cloud100))
    }
    val teal = Teal300.copy(alpha = if (dark) 0.25f else 0.18f)
    val violet = Violet300.copy(alpha = if (dark) 0.25f else 0.16f)
    val shaderTeal = remember(teal) { radialShader(teal.toArgb()) }
    val shaderViolet = remember(violet) { radialShader(violet.toArgb()) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

    Box(modifier = modifier.fillMaxSize().background(background)) {
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                paint.shader = shaderTeal
                var save = native.save()
                val tealRadius = size.minDimension * 0.88f
                native.translate(
                    size.width * (0.12f + 0.18f * phase),
                    size.height * (0.10f + 0.12f * (1f - phase))
                )
                native.scale(tealRadius, tealRadius)
                native.drawCircle(0f, 0f, 1f, paint)
                native.restoreToCount(save)

                paint.shader = shaderViolet
                save = native.save()
                val violetRadius = size.minDimension * 0.78f
                native.translate(
                    size.width * (0.78f + 0.12f * phase2),
                    size.height * (0.74f + 0.10f * (1f - phase2))
                )
                native.scale(violetRadius, violetRadius)
                native.drawCircle(0f, 0f, 1f, paint)
                native.restoreToCount(save)
            }
        }
        content()
    }
}

private fun radialShader(color: Int): RadialGradient {
    val transparent = color and 0x00FFFFFF
    return RadialGradient(
        0f,
        0f,
        1f,
        intArrayOf(color, transparent),
        floatArrayOf(0f, 1f),
        Shader.TileMode.CLAMP
    )
}
