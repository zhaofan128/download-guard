package com.dlguard.ui.components

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * AGSL 着色器加载与缓存。
 *
 * 设计取舍（真实限制）：Compose 1.6 的 GraphicsLayer 只暴露 BlurEffect / OffsetEffect 两种
 * RenderEffect，无法把 android.graphics.RuntimeShader 注入离屏图层去采样「背后内容」。
 * 因此这里把 AGSL 编译成 RuntimeShader 后直接作为 Paint.shader 参与绘制，
 * 在玻璃圆角边缘生成位移感光带与 RGB 色散，视觉上等价于边缘折射，且只有一次全尺寸 drawRect。
 *
 * 性能约束：
 *  1. 着色器源码只读一次并常驻；
 *  2. RuntimeShader 按「宽x高x圆角x强度」缓存，滚动时复用同一实例，绝不每帧 new；
 *  3. 任何编译/驱动异常都降级为 null，由 Modifier 自动退回「高光 + 半透明」玻璃。
 */
object LiquidRefraction {

    private const val SHADER_ASSET = "shaders/liquid_refraction.agsl"
    private var sourceCache: String? = null

    fun source(context: Context): String {
        sourceCache?.let { return it }
        val text = runCatching {
            context.assets.open(SHADER_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
        sourceCache = text
        return text
    }

    fun shader(
        context: Context,
        widthPx: Float,
        heightPx: Float,
        cornerRadiusPx: Float,
        strengthPx: Float
    ): RuntimeShader? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (widthPx <= 1f || heightPx <= 1f) return null
        val shaderSource = source(context)
        if (shaderSource.isBlank()) return null
        return runCatching {
            RefractionCache.obtain(
                shaderSource = shaderSource,
                width = widthPx,
                height = heightPx,
                corner = cornerRadiusPx,
                strength = strengthPx.coerceIn(0f, 4f * context.resources.displayMetrics.density)
            )
        }.getOrNull()
    }

    /** 兼容旧调用：仅用于需要 android.graphics.RenderEffect 的场景（当前未使用）。 */
    @RequiresApi(Build.VERSION_CODES.S)
    fun blurEffect(radiusPx: Float): RenderEffect =
        RenderEffect.createBlurEffect(radiusPx, radiusPx, android.graphics.Shader.TileMode.CLAMP)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object RefractionCache {
    private val cache = object : LinkedHashMap<String, RuntimeShader>(6, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RuntimeShader>?): Boolean =
            size > 6
    }

    fun obtain(
        shaderSource: String,
        width: Float,
        height: Float,
        corner: Float,
        strength: Float
    ): RuntimeShader {
        val key = "${width.toInt()}x${height.toInt()}_${corner.toInt()}_${strength.toInt()}"
        cache[key]?.let { return it }
        val shader = RuntimeShader(shaderSource)
        shader.setFloatUniform("uSize", width, height)
        shader.setFloatUniform("uCorner", corner)
        shader.setFloatUniform("uStrength", strength)
        cache[key] = shader
        return shader
    }
}
