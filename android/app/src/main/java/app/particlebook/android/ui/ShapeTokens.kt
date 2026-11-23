package app.particlebook.android.ui

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * M3 形状阶梯（2026-11 规范：10 档）。
 *
 * | 档位 | 规范名 | 圆角 |
 * | --- | --- | --- |
 * | [none] | None | 0dp |
 * | [extraSmall] | Extra small | 4dp |
 * | [small] | Small | 8dp |
 * | [medium] | Medium | 12dp |
 * | [large] | Large | 16dp |
 * | [largeIncreased] | Large increased | 20dp |
 * | [extraLarge] | Extra large | 28dp |
 * | [extraLargeIncreased] | Extra large increased | 32dp |
 * | [extraExtraLarge] | Extra extra large | 48dp |
 * | [AppShapes.full] | Full | 短边一半（几何推导，没有 Dp 值） |
 *
 * 这是全应用圆角的唯一来源：
 * - 组件优先用 `MaterialTheme.shapes.<slot>`；它只投影 5 个公开 slot，见 [AppShapes.materialTheme]。
 * - 20 / 32 / 48 三档在 1.4.0 的 `Shapes` 里没有公开 slot（三个 slot 与 8 参数构造函数都是 internal，
 *   1.5.0 才公开），需要时取 [AppShapes] 的同名值，不要退化成 `RoundedCornerShape(20.dp)` 这种字面量。
 * - 仍需要 Dp 的地方（描边、裁剪半径）用 [Radius]，它的值只能引用本对象。
 * - 24dp **不是**规范档位。旧注释把 20dp / 24dp 一起写成「规范外档位」是错的：
 *   20dp 是 Large increased、32dp 是 Extra large increased、48dp 是 Extra extra large，只有 24dp 无对应档位。
 *
 * 用法约束（与规范一致）：
 * - 同一屏最多三档；形状要「有意图但不被过度解读」，同一个波形/圆角不必各自赋含义。
 * - 嵌套圆角按光学补偿：外半径 - 内边距 = 内半径（如 48 - 14 = 34），内外不要同值。
 * - full 只留给真正的胶囊/圆形控件与 hero 时刻；整屏全圆是习惯，不是系统。
 * - 层次优先用 surface 角色与间距表达，形状不承担「这层比那层高」的语义。
 */
object ShapeTokens {
    val none = 0.dp
    val extraSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val largeIncreased = 20.dp
    val extraLarge = 28.dp
    val extraLargeIncreased = 32.dp
    val extraExtraLarge = 48.dp
}

/**
 * 10 档形状值。`MaterialTheme.shapes` 只有 5 个 slot，其余档位、以及需要 `CornerBasedShape` 的描边与裁剪直接取这里。
 *
 * 类型用 [CornerBasedShape]：「描边/裁剪」既要 `Shape` 也要 `RoundedCornerShape`，
 * 这样两种用法都不用再转换。
 */
internal object AppShapes {
    val none: CornerBasedShape = RoundedCornerShape(ShapeTokens.none)
    val extraSmall: CornerBasedShape = RoundedCornerShape(ShapeTokens.extraSmall)
    val small: CornerBasedShape = RoundedCornerShape(ShapeTokens.small)
    val medium: CornerBasedShape = RoundedCornerShape(ShapeTokens.medium)
    val large: CornerBasedShape = RoundedCornerShape(ShapeTokens.large)
    val largeIncreased: CornerBasedShape = RoundedCornerShape(ShapeTokens.largeIncreased)
    val extraLarge: CornerBasedShape = RoundedCornerShape(ShapeTokens.extraLarge)
    val extraLargeIncreased: CornerBasedShape = RoundedCornerShape(ShapeTokens.extraLargeIncreased)
    val extraExtraLarge: CornerBasedShape = RoundedCornerShape(ShapeTokens.extraExtraLarge)

    /** Full：短边一半。取代旧的「50% of component size」写法，胶囊与圆形 FAB 同用此值。 */
    val full: CornerBasedShape = RoundedCornerShape(percent = 50)

    /**
     * `MaterialTheme.shapes` 投影（[ParticleBookTheme] 用）。
     *
     * 1.4.0 的 `Shapes` 在 Kotlin 侧只公开 5 个 slot：`largeIncreased` / `extraLargeIncreased` /
     * `extraExtraLarge` 三个 getter 与 8 参数构造函数本身都是 internal（实测 `Cannot access
     * 'constructor(...)': it is internal`），1.5.0 才公开。因此这里只投影 5 档；
     * 其余档位由组件直接取 [AppShapes] 的同名值——两处引用同一组 dp，不会出现两套数值。
     */
    internal val materialTheme: Shapes = Shapes(
        extraSmall = extraSmall,
        small = small,
        medium = medium,
        large = large,
        extraLarge = extraLarge,
    )
}

/**
 * 旧调用点兼容层：`RoundedCornerShape(Radius.x)` 还能编译，但取值必须来自 [ShapeTokens]。
 * 新代码请直接用 `MaterialTheme.shapes.*` 或 [AppShapes]；这个对象只为存量代码保留，逐个替掉后可以删。
 *
 * 目前只剩一处活跃调用（设置页翻译密钥卡片的 [group]）；名字保留是为了不打断存量调用点。
 */
object Radius {
    val chip = ShapeTokens.small
    val thumb = ShapeTokens.small
    val cover = ShapeTokens.medium
    val card = ShapeTokens.large
    val hero = ShapeTokens.extraLarge
    val group = ShapeTokens.extraLarge
    val sheet = ShapeTokens.extraLarge
}
