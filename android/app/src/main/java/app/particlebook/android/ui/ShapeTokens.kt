package app.particlebook.android.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * M3 形状阶梯（规范值 4 / 8 / 12 / 16 / 28）。
 *
 * 这是全应用圆角的唯一来源：
 * - 组件内优先用 `MaterialTheme.shapes.<slot>`（small/medium/large/extraLarge）。
 * - 仍需要 Dp 的地方（描边、裁剪半径）用 [Radius]，它的值只能引用 [ShapeTokens]，不许再写数字。
 * - 不要新增 20dp / 24dp 这类规范外档位：同一屏最多三档，且必须落在阶梯上。
 */
object ShapeTokens {
    val extraSmall = 4.dp
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val extraLarge = 28.dp
}

internal val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(ShapeTokens.extraSmall),
    small = RoundedCornerShape(ShapeTokens.small),
    medium = RoundedCornerShape(ShapeTokens.medium),
    large = RoundedCornerShape(ShapeTokens.large),
    extraLarge = RoundedCornerShape(ShapeTokens.extraLarge),
)

/**
 * 旧调用点兼容层：`RoundedCornerShape(Radius.x)` 还能编译，但取值必须来自 [ShapeTokens]。
 * 新代码请直接用 `MaterialTheme.shapes.*`；这个对象只为存量代码保留，逐个替掉后可以删。
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
