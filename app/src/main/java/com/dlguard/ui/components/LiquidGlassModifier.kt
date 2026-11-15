package com.dlguard.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dlguard.ui.theme.GlassConfig
import com.dlguard.ui.theme.GlassRadii
import com.dlguard.ui.theme.LocalGlassConfig

/**
 * 通用液态玻璃 Modifier，七层结构：
 *  1. 有色柔和阴影（深蓝/深紫底色 alpha 0.3、Y 偏移 10dp、模糊 30dp，自绘 BlurMaskFilter，禁用死黑）
 *  2. 半透明底色 + 上下线性渐变
 *  3. 局部真实模糊（Skia BlurMaskFilter 光斑，GPU 加速；低端机关闭）
 *  4. 1~1.5dp 渐变高光描边（左上 White 0.8 → 右下透明，模拟左上入射光）
 *  5. API 33+ AGSL 边缘折射光带 + RGB 色散（RuntimeShader 预编译缓存，低端机/旧系统自动跳过）
 *  6. 3% 极细噪点（预生成 BitmapShader，杜绝色带）
 *  7. 底部内侧暗影，制造边缘凸起的厚度感
 *
 * 降级全部收敛在内部：调用方只写 liquidGlass()，不需要关心 API 版本。
 * 注意：这是 @Composable 扩展函数，只能在组合中调用。
 */
@Composable
fun Modifier.liquidGlass(
    cornerRadius: Dp = GlassRadii.Card,
    config: GlassConfig = LocalGlassConfig.current,
    refraction: Boolean = false,
    tint: Color = Color.Transparent,
    elevation: Dp = config.shadowElevation,
    glowColor: Color = Color.White
): Modifier {
    val context = LocalContext.current
    val density = LocalDensity.current
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var refractionShader by remember { mutableStateOf<RuntimeShader?>(null) }

    LaunchedEffect(
        sizePx,
        refraction,
        config.enableRefraction,
        cornerRadius,
        config.refractionStrength
    ) {
        refractionShader = if (
            refraction &&
            config.enableRefraction &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            sizePx.width > 0 && sizePx.height > 0
        ) {
            LiquidRefraction.shader(
                context = context,
                widthPx = sizePx.width.toFloat(),
                heightPx = sizePx.height.toFloat(),
                cornerRadiusPx = with(density) { cornerRadius.toPx() },
                strengthPx = config.refractionStrength * density.density
            )
        } else {
            null
        }
    }

    return this
        .onSizeChanged { sizePx = it }
        .clip(shape)
        .drawWithCache {
            val radiusPx = cornerRadius.toPx()
            val backgroundBrush = Brush.verticalGradient(
                colors = listOf(config.gradientTop, config.gradientBottom),
                startY = 0f,
                endY = size.height
            )
            val highlightBrush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = config.borderHighlight),
                    Color.White.copy(alpha = config.borderHighlight * 0.12f),
                    Color.Transparent
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height)
            )
            val innerShade = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = if (config.surfaceAlpha < 0.3f) 0.16f else 0.035f)
                ),
                startY = size.height * 0.5f,
                endY = size.height
            )
            // 有色阴影：深蓝/深紫 + Y 偏移 + 大模糊，替代 Modifier.shadow 的死黑
            val coloredShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = config.shadowColor.copy(alpha = config.shadowAlpha).toArgb()
                maskFilter = BlurMaskFilter(config.shadowBlur.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
            val shadowRadiusPx = radiusPx + with(density) { 2.dp.toPx() }
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = glowColor.copy(alpha = config.glowAlpha).toArgb()
                maskFilter = BlurMaskFilter(config.blurRadius.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
            val noisePaint = Paint().apply {
                shader = NoiseTexture.shader()
                alpha = (config.noiseAlpha / 0.12f * 255f).toInt().coerceIn(0, 255)
            }
            val refractionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = refractionShader
            }
            val shadowOffsetY = config.shadowOffsetY.toPx()
            onDrawBehind {
                // 层 1：有色柔和阴影（在内容底下，向下偏移）
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.translate(0f, shadowOffsetY)
                    canvas.nativeCanvas.drawRoundRect(
                        0f, 0f, size.width, size.height,
                        shadowRadiusPx, shadowRadiusPx,
                        coloredShadowPaint
                    )
                    canvas.nativeCanvas.translate(0f, -shadowOffsetY)
                }
                // 层 2：半透明底色 + 渐变
                drawRect(color = Color.White.copy(alpha = config.surfaceAlpha))
                if (tint.alpha > 0.001f) drawRect(color = tint)
                drawRect(brush = backgroundBrush)
                // 层 3：局部弥散光斑（透出底层青紫光）
                if (config.enableBlur) {
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawCircle(
                            size.width * 0.24f,
                            size.height * 0.16f,
                            size.minDimension * 0.55f,
                            glowPaint
                        )
                    }
                }
                // 层 6：3% 磨砂噪点
                if (config.enableNoise) {
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, noisePaint)
                    }
                }
                drawRect(brush = innerShade)
                // 层 5：AGSL 边缘折射
                if (refractionShader != null && config.enableRefraction) {
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, refractionPaint)
                    }
                }
                // 层 4：左上→右下 渐变高光描边
                drawRoundRect(
                    brush = highlightBrush,
                    cornerRadius = CornerRadius(radiusPx, radiusPx),
                    style = Stroke(width = config.borderWidth.toPx())
                )
            }
        }
}

/** 预生成噪点贴图，全程只创建一次，避免大面积渐变出现色带。 */
private object NoiseTexture {
    private var cached: BitmapShader? = null

    fun shader(): BitmapShader {
        cached?.let { return it }
        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val random = java.util.Random(20241114L)
        val pixels = IntArray(size * size)
        for (index in pixels.indices) {
            val alpha = random.nextInt(110)
            pixels[index] = android.graphics.Color.argb(alpha, 255, 255, 255)
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        val created = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        cached = created
        return created
    }
}
