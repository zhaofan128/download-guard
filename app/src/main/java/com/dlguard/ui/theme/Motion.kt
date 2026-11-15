package com.dlguard.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * 全部动效参数的唯一来源。禁止在页面里散落魔法数字。
 * 时长单位 ms；弹簧参数为 Compose SpringSpec。
 */
object Motion {
    const val PageDuration = 300
    const val FastDuration = 160
    const val StaggerStep = 40
    const val RippleDuration = 250
    const val JellySettleDuration = 220
    const val BreathDuration = 2000
    const val GlowDuration = 220

    /** 果冻弹簧：需求定参 spring(dampingRatio = 0.4f, stiffness = 400f)。 */
    const val JellyDamping = 0.4f
    const val JellyStiffness = 400f
    const val PressDamping = 0.55f
    const val PressStiffness = 700f

    val Jelly: SpringSpec<Float> = spring(dampingRatio = JellyDamping, stiffness = JellyStiffness)
    val Press: SpringSpec<Float> = spring(dampingRatio = PressDamping, stiffness = PressStiffness)
    val Soft: SpringSpec<Float> = spring(dampingRatio = 0.9f, stiffness = 220f)

    val PageEnter: EnterTransition =
        fadeIn(tween(PageDuration, easing = FastOutSlowInEasing)) +
            slideInVertically(tween(PageDuration, easing = FastOutSlowInEasing)) { height -> height / 10 }
    val PageExit: ExitTransition =
        fadeOut(tween(FastDuration, easing = FastOutSlowInEasing)) +
            slideOutVertically(tween(FastDuration, easing = FastOutSlowInEasing)) { height -> -height / 24 }

    val BreathTween: TweenSpec<Float> = tween(BreathDuration, easing = LinearEasing)

    fun <T> ease(ms: Int): TweenSpec<T> = tween(ms, easing = FastOutSlowInEasing)

    fun <T> springOrSnap(enabled: Boolean): FiniteAnimationSpec<T> =
        if (enabled) spring(dampingRatio = JellyDamping, stiffness = JellyStiffness) else tween(0)
}

/** 系统「移除动画」开关：animatorDurationScale == 0 时全部动效直接到终态。 */
val LocalMotionEnabled = staticCompositionLocalOf { true }

@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(readAnimatorScale(context)) }
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = readAnimatorScale(context)
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                false,
                observer
            )
        }
        onDispose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }
    return enabled
}

private fun readAnimatorScale(context: android.content.Context): Boolean {
    val scale = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
    }.getOrDefault(1f)
    return scale > 0f
}
