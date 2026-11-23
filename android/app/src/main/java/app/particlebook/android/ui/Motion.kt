package app.particlebook.android.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable

/**
 * 动效 token：material3 2026-11 的物理弹簧体系
 * （`md.sys.motion.spring.{fast|default|slow}.{spatial|effects}`）。
 *
 * ## 为什么默认是弹簧，不是「时长 + 缓动」
 * M3 Expressive（2025-05）把动效系统从缓动/时长换成了物理弹簧。原因不是手感偏好，而是
 * 能力差异：spring 天然处理**中断、手势释放与重定向**（弹簧带着当前速度继续走），固定曲线
 * 做不到——曲线动画被打断只能重启，表现为跳帧。所以本文件的默认交付物是 spring；
 * 只有**不可中断**的动效（骨架脉冲这种无限循环）才退回时长曲线，见 [pulse]。
 *
 * ## 两套 scheme（产品级选择，不进 token 名）
 * - [Expressive]（默认）：可见回弹、轻微过冲（空间轴 ζ=0.6/0.8，过冲 9.5%/1.5%）。
 *   给书卡、封面、入场这类「每屏只有一个值得强调的瞬间」。
 * - [Standard]：几乎不过冲（ζ=0.9，过冲 0.15%）、收敛更快，给密集列表、设置、阅读器 chrome
 *   这类读精度要求高、动效存在感应当接近零的界面。
 * 同一产品只用一套；单个元素可以借用另一套来强调，但要能说出理由。当前选择在 [product]。
 * 换 scheme 不改任何调用点——这正是 scheme 不写进 token 名的意义（motion.md:42）。
 *
 * ## 与 material3 1.4.0 的关系（1.5.0 后整批替换）
 * 这里的六个 token 是对 `MotionScheme` 的**等价实现**，不是自造的第三套体系：
 * - 1.4.0 的 `androidx.compose.material3.MotionScheme` 在 **Kotlin 侧仍是 internal**：接口本身、
 *   工厂 `expressive()` / `standard()`、`MaterialTheme.motionScheme` 全部不可引用。2026-11-23 在本
 *   worktree 实测，编译报错逐字为 `Cannot access 'val motionScheme: MotionScheme': it is internal
 *   in 'androidx.compose.material3/MaterialTheme'`。它的 bytecode 是 public、Kotlin 元数据才标
 *   internal——`javap` 看着能用，写代码就炸，别被字节码骗。
 *   六个方法与本文件 [Tokens] 一一对应：`defaultSpatialSpec / fastSpatialSpec / slowSpatialSpec` 与
 *   `defaultEffectsSpec / fastEffectsSpec / slowEffectsSpec`；
 * - `MaterialTheme` 的内置默认值固定是 standard，只有 `MaterialExpressiveTheme` 才给 expressive，
 *   而本 app 的 `ParticleBookTheme` 用的是 `MaterialTheme`；
 * - 所以这里按 `ExpressiveMotionTokens` / `StandardMotionTokens` 的**实际数值**（phone 档）复刻
 *   六个 spring，手感与 1.4.0 组件一致，且不依赖任何 internal API。
 * 替换路径（1.5.0 公开工厂后）：`Motion.product.spatialDefault.spec()` 逐个换成
 * `MaterialTheme.motionScheme.defaultSpatialSpec()`，可一把 sed；几何与时长量级不变。
 *
 * ## 数值出处（不猜）
 * spatial = 会位移的东西（x/y、旋转、尺寸、圆角），允许过冲后回落；effects = 颜色/透明度，
 * 过冲会闪，所以阻尼 1.0（临界阻尼，0% 过冲）。刚度越大收敛越快。数值取自 material3 1.4.0
 * 的 `ExpressiveMotionTokens` / `StandardMotionTokens`（用 javap 读构建缓存里的 aar 核对过），
 * 是 **phone** 档；motion.md:44 明确 token 值按设备档位（wear/tablet）另有一套。
 * 收敛时间 ≈ 4/(ζ·√k)，据此：expressive 236/257/354ms，standard 119/168/257ms（fast/default/slow）。
 *
 * ## 刻意不做的事
 * - **没有抄 motion.md:55 的 12 条 web 回退 cubic-bezier 表**：那是给没有 spring 运行时的
 *   Web 兜底的近似值，Compose 侧有真 spring，抄进来只会得到 12 个零调用点的常量。
 *   本项目唯一需要时长曲线的动效是骨架脉冲，它已经在 [pulse] 里。
 * - **没有实现 container transform**：它需要 `SharedTransitionLayout` + `sharedElement`，
 *   而依赖树实际解析到的 compose animation 1.8.2 里，`BoundsTransform` / `sharedElement` 仍带
 *   `@ExperimentalSharedTransitionApi`（javap 读 RuntimeInvisibleAnnotations 看到的，不是猜的；
 *   AppRoot 的注释也记着这件事）。为一个封面过渡引入实验 API 不划算。降级方案是 [enter]/[exit]
 *   的淡入淡出 + 调用点自己的 scale，等这组 API 去掉 opt-in 再开。
 * - **没有把 forward/backward 与 lateral 拆成两个函数**：M3 里两者是同一台 shared axis 机器，
 *   差别只在「动的是层级还是同级」，也就是调用点 `AnimatedContent` 拿什么当 targetState；
 *   spec 完全一致。拆开只会得到两个函数体相同的壳。统一走 [sharedAxis]。
 */
object Motion {

    // ───────────────────────────── token ─────────────────────────────

    /**
     * 一个 spring token：阻尼比 + 刚度。motion.md:24 的第三个属性（初速度）不在这里固定，
     * 由 Compose 的动画运行状态在中断时自动带上——这正是用 spring 而不是曲线的收益。
     *
     * @param dampingRatio 1.0 = 临界阻尼（不过冲）；< 1 有回弹；越小越弹。
     * @param stiffness 刚度，√k 与自然频率成正比；越大越快、越「硬」。
     */
    @Immutable
    data class Spring(val dampingRatio: Float, val stiffness: Float) {

        /** 交给 `animate*AsState` / `animateContentSize` / `fadeIn` 等任意 FiniteAnimationSpec 位置。 */
        fun <T> spec(): SpringSpec<T> = spring(dampingRatio = dampingRatio, stiffness = stiffness)
    }

    /**
     * 一套 scheme 的六个 token：`{fast|default|slow} × {spatial|effects}`。
     *
     * 取向（motion.md:34-40）：fast = 小元件（开关/按钮/chip）；default = 覆盖部分屏幕
     * （bottom sheet、展开的导航栏）；slow = 整屏切换与整屏内容刷新。
     */
    @Immutable
    data class Tokens(
        val spatialFast: Spring,
        val spatialDefault: Spring,
        val spatialSlow: Spring,
        val effectsFast: Spring,
        val effectsDefault: Spring,
        val effectsSlow: Spring,
    )

    /** expressive：可见回弹。数值 = 1.4.0 `ExpressiveMotionTokens`（phone 档）。 */
    val Expressive: Tokens = Tokens(
        spatialFast = Spring(dampingRatio = 0.6f, stiffness = 800f), // ζ=0.6 → 过冲 ≈ 9.5%
        spatialDefault = Spring(dampingRatio = 0.8f, stiffness = 380f), // ζ=0.8 → 过冲 ≈ 1.5%
        spatialSlow = Spring(dampingRatio = 0.8f, stiffness = 200f),
        effectsFast = Spring(dampingRatio = 1f, stiffness = 3800f), // ζ=1.0 → 不过冲（透明度过冲会闪）
        effectsDefault = Spring(dampingRatio = 1f, stiffness = 1600f),
        effectsSlow = Spring(dampingRatio = 1f, stiffness = 800f),
    )

    /** standard：克制。数值 = 1.4.0 `StandardMotionTokens`（phone 档）。 */
    val Standard: Tokens = Tokens(
        spatialFast = Spring(dampingRatio = 0.9f, stiffness = 1400f), // ζ=0.9 → 过冲 ≈ 0.15%
        spatialDefault = Spring(dampingRatio = 0.9f, stiffness = 700f),
        spatialSlow = Spring(dampingRatio = 0.9f, stiffness = 300f),
        effectsFast = Spring(dampingRatio = 1f, stiffness = 3800f), // effects 两套相同：透明度没有「性格」
        effectsDefault = Spring(dampingRatio = 1f, stiffness = 1600f),
        effectsSlow = Spring(dampingRatio = 1f, stiffness = 800f),
    )

    /**
     * 产品级 scheme：改这一行就换整套手感，调用点不动。
     *
     * 这里选 expressive——书卡、封面、入场的回弹是产品的情绪表达；阅读器 chrome 只走
     * effects token，本身就不会被回弹影响。若将来设置里给「减少动效」以外的「安静模式」，
     * 就让那片界面直接用 `Motion.Standard`。
     */
    val product: Tokens get() = Expressive

    // ──────────────────────── transition pattern ────────────────────────

    /** 位移轴。Android 惯例：沿 x 或 y 展开，避免 scale / z 位移（那暗示了层级变化，motion.md:89）。 */
    enum class Axis { Horizontal, Vertical }

    /** 方向：Forward = 进入下一层（新内容从尾侧进入）；Backward = 返回上一层。 */
    enum class Direction { Forward, Backward }

    /**
     * **forward/backward** 与 **lateral** 两个 pattern 的实现：Shared axis。
     *
     * 进入用 default spatial + default effects，退出用 fast spatial + fast effects——退出**严格
     * 快于**进入（motion.md:109：退出与关闭比进入更快；按 4/(ζ·√k) 估算 236ms vs 257ms 收敛）。
     * 位移量用整宽/整高（[slideInHorizontally] 的 `fullWidth`），不掺魔法系数。
     *
     * 用法（层级）：
     * ```
     * AnimatedContent(targetState = route, transitionSpec = {
     *     Motion.sharedAxis(Axis.Horizontal, if (targetState.depth > initialState.depth) {
     *         Direction.Forward
     *     } else {
     *         Direction.Backward
     *     })
     * }) { ... }
     * ```
     * 同级（lateral，比如 tabs / 画廊）用同一函数，只是 targetState 换成页签索引：
     * 语义差别在「动的是哪一层」，不在 spec，所以不另开 API。
     */
    fun sharedAxis(
        axis: Axis = Axis.Horizontal,
        direction: Direction = Direction.Forward,
        tokens: Tokens = product,
    ): ContentTransform {
        val sign = if (direction == Direction.Forward) 1 else -1
        return when (axis) {
            Axis.Horizontal ->
                (slideInHorizontally(animationSpec = tokens.spatialDefault.spec()) { width -> sign * width } +
                    fadeIn(animationSpec = tokens.effectsDefault.spec()))
                    .togetherWith(
                        slideOutHorizontally(animationSpec = tokens.spatialFast.spec()) { width -> -sign * width } +
                            fadeOut(animationSpec = tokens.effectsFast.spec()),
                    )

            Axis.Vertical ->
                (slideInVertically(animationSpec = tokens.spatialDefault.spec()) { height -> sign * height } +
                    fadeIn(animationSpec = tokens.effectsDefault.spec()))
                    .togetherWith(
                        slideOutVertically(animationSpec = tokens.spatialFast.spec()) { height -> -sign * height } +
                            fadeOut(animationSpec = tokens.effectsFast.spec()),
                    )
        }
    }

    /**
     * **top level** pattern：切顶层目的地（底栏/导航栏换页）。只做交叉淡化，不做位移与缩放：
     * 顶层目的地之间没有「谁更深」的方向，位移会暗示不存在的层级（motion.md:85、:89）。
     * 退出仍快于进入。
     */
    fun fadeThrough(tokens: Tokens = product): ContentTransform =
        fadeIn(animationSpec = tokens.effectsDefault.spec())
            .togetherWith(fadeOut(animationSpec = tokens.effectsFast.spec()))

    /**
     * **enter and exit** pattern：组件出现/离开（FAB、dialog、菜单、snackbar、tooltip）。
     * 只做透明度：Android 上 scale 会读成「浮起来」，而浮起由 elevation 表达，不该由动效抢答。
     * 用于 `AnimatedVisibility(enter = Motion.enter(), exit = Motion.exit())`。
     */
    fun enter(tokens: Tokens = product): EnterTransition =
        fadeIn(animationSpec = tokens.effectsDefault.spec())

    /** 见 [enter]。退出用 fast effects，保证「消失比出现快」。 */
    fun exit(tokens: Tokens = product): ExitTransition =
        fadeOut(animationSpec = tokens.effectsFast.spec())

    // ─────────────────────── 旧 API（薄封装，勿新增调用） ───────────────────────
    // 40+ 调用点暂时不动：这里只换内部实现，签名不变，逐个迁到 token 之后再删。
    // 行为变化都在注释里写明，方便回归时对照。

    /** 进入的位移/缩放：expressive default spatial（0.8/380，与旧手写值逐位相同）。 */
    @Deprecated(
        message = "改用 Motion.product.spatialDefault.spec()；两者数值相同。",
        replaceWith = ReplaceWith("Motion.product.spatialDefault.spec()"),
    )
    fun <T> enterSpatial(): FiniteAnimationSpec<T> = product.spatialDefault.spec()

    /**
     * 进入的透明度：expressive default effects（ζ=1.0 / k=1600，收敛 ≈100ms）。
     * 旧实现是 tween(220, FastOutSlowIn)；换 spring 后更快收口，且中途反向不跳帧。
     */
    @Deprecated(
        message = "改用 Motion.product.effectsDefault.spec()。",
        replaceWith = ReplaceWith("Motion.product.effectsDefault.spec()"),
    )
    fun <T> enterFade(): FiniteAnimationSpec<T> = product.effectsDefault.spec()

    /**
     * 退场的位移/缩放：expressive fast spatial（0.6/800，收敛 ≈236ms）。
     * 旧实现是 tween(140, FastOutLinearIn)：曲线更快但**不可中断**，阅读器 chrome 反复
     * 收起/展开时会在中途硬跳。仍严格快于 [enterSpatial]（257ms），守住「退场快于进入」。
     */
    @Deprecated(
        message = "改用 Motion.product.spatialFast.spec()。",
        replaceWith = ReplaceWith("Motion.product.spatialFast.spec()"),
    )
    fun <T> exitSpatial(): FiniteAnimationSpec<T> = product.spatialFast.spec()

    /** 退场的透明度：expressive fast effects（ζ=1.0 / k=3800，收敛 ≈65ms）。旧实现 tween(140)。 */
    @Deprecated(
        message = "改用 Motion.product.effectsFast.spec()。",
        replaceWith = ReplaceWith("Motion.product.effectsFast.spec()"),
    )
    fun <T> exitFade(): FiniteAnimationSpec<T> = product.effectsFast.spec()

    /** 按压 scale 1 → 0.97：与 fast spatial 同档（旧手写 0.6/900，刚度 900→800，收敛差 ≈14ms，不足 1 帧）。 */
    @Deprecated(
        message = "改用 Motion.product.spatialFast.spec()。",
        replaceWith = ReplaceWith("Motion.product.spatialFast.spec()"),
    )
    fun <T> press(): FiniteAnimationSpec<T> = product.spatialFast.spec()

    /** 封面位图淡入（Crossfade）：fast effects。旧实现 tween(180, FastOutSlowIn)。 */
    @Deprecated(
        message = "改用 Motion.product.effectsFast.spec()。",
        replaceWith = ReplaceWith("Motion.product.effectsFast.spec()"),
    )
    fun <T> coverFade(): FiniteAnimationSpec<T> = product.effectsFast.spec()

    // ───────────────────────────── 工具 ─────────────────────────────

    /**
     * 骨架脉冲：1100ms 线性往复。
     *
     * 这是本文件**唯一**保留时长曲线的地方，因为它是无限循环、没有终点、也谈不上被中断——
     * spring 需要目标值，这里没有。`infiniteRepeatable` 只接受 DurationBasedAnimationSpec，
     * 签名因此保持 [DurationBasedAnimationSpec]。
     *
     * 注意：无限循环不受 `MotionDurationScale` 缩放影响，调用点必须自己查 [isReducedMotion]，
     * 命中时渲染静态版本，否则会一直脉冲（android-ui-m3.md §6 已记为硬约束）。
     */
    fun pulse(): DurationBasedAnimationSpec<Float> = tween(durationMillis = 1100, easing = LinearEasing)

    /** stagger：只对首屏前 8 项生效，每项 35ms 阶梯。 */
    fun staggerDelay(index: Int): Long = 35L * index.coerceAtMost(7)

    /**
     * 系统「移除动画」（开发者选项 / 无障碍）开关：ANIMATOR_DURATION_SCALE == 0 时成立。
     * 命中时跳过 stagger 与共享元素过渡，避免前庭敏感用户看到位移。
     *
     * 读的是系统设置而不是 Compose 的 `MotionDurationScale`：后者挂在 effect 的 coroutine
     * context 里，普通 composable 拿不到；而两者读的是同一个系统值，结果等价。
     */
    fun isReducedMotion(context: Context): Boolean =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
}
