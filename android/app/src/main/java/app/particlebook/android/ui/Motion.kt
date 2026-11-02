package app.particlebook.android.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 动效 token（spec §17.1.5 / §17.8.2）。
 *
 * material3 1.4.0 的 `MaterialTheme.motionScheme` 是 internal（1.5.0 才公开），
 * 所以 expressive 手感在这里用等价的 spring/tween 手写；组件内不要再裸写 spring()/tween()，
 * 一律引用这里的 token，便于 1.5.0 升级时整批替换。
 */
object Motion {
    /** 进入位移/缩放（弹簧，damping 0.8 / stiffness 380）。 */
    fun <T> enterSpatial(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 380f)

    /** 进入透明度（220ms 缓入）。 */
    fun <T> enterFade(): FiniteAnimationSpec<T> =
        tween(durationMillis = 220, easing = FastOutSlowInEasing)

    /** 退场位移/缩放（140ms，严格快于进入）。 */
    fun <T> exitSpatial(): FiniteAnimationSpec<T> =
        tween(durationMillis = 140, easing = FastOutLinearInEasing)

    /** 退场透明度（140ms）。 */
    fun <T> exitFade(): FiniteAnimationSpec<T> =
        tween(durationMillis = 140, easing = FastOutLinearInEasing)

    /** 按压 scale 1 → 0.97（快起慢收）。 */
    fun <T> press(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.6f, stiffness = 900f)

    /** 封面真实位图淡入（180ms）。 */
    fun <T> coverFade(): FiniteAnimationSpec<T> =
        tween(durationMillis = 180, easing = FastOutSlowInEasing)

    /** 骨架脉冲（1100ms 线性往复；infiniteRepeatable 只接受 DurationBasedAnimationSpec）。 */
    fun pulse(): DurationBasedAnimationSpec<Float> =
        tween(durationMillis = 1100, easing = LinearEasing)

    /** stagger：只对首屏前 8 项生效，每项 35ms 阶梯。 */
    fun staggerDelay(index: Int): Long = 35L * index.coerceAtMost(7)

    /**
     * 系统「移除动画」（开发者选项 / 无障碍）开关：ANIMATOR_DURATION_SCALE == 0 时成立。
     * 命中时跳过 stagger 与共享元素过渡，避免前庭敏感用户看到位移。
     */
    fun isReducedMotion(context: Context): Boolean =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
}
