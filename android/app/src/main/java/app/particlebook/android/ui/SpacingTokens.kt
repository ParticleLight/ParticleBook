package app.particlebook.android.ui

import androidx.compose.ui.unit.dp

/**
 * 间距 token：8dp 基准（M3 spacing token 系统，2026-11 版）。
 *
 * 基准单位是 8dp：`space100` = 8dp（`md.sys.measurement.space100`）。官方阶梯覆盖 0x–9x（0–72dp），
 * 只给推荐停靠点，产品需要时按倍数扩展；嵌档是 2 / 4 / 6 / 10dp（0.25x / 0.5x / 0.75x / 1.25x），
 * 因为 Material 自己在用；其它值按倍数命名（`space225` = 18dp = 8dp × 2.25）。
 *
 * 「4dp 节奏 / 4dp 网格」是旧说法，已废弃：系统是 8dp 基准 + 少量更细的嵌档，不是 4dp 网格。
 *
 * 语义名 ↔ 倍数 ↔ 官方 id（数值沿用现状，本次只补注释，不做全屏位移）：
 *
 * | token | 值 | 倍数 | 官方 id |
 * | --- | --- | --- | --- |
 * | [xxs] | 4dp | 0.5x | 嵌档（`space50` 语义） |
 * | [xs] | 8dp | 1x | `space100` |
 * | [sm] | 12dp | 1.5x | 自定义 `space150` |
 * | [md] | 16dp | 2x | `space200` |
 * | [lg] | 24dp | 3x | `space300` |
 * | [xl] | 32dp | 4x | `space400` |
 * | [xxl] | 48dp | 6x | `space600` |
 *
 * 只放「跨屏复用」的间距；某一屏的一次性留白请就地写并注释原因，不要往这里堆。
 * 三类语义（padding = 元素内的边到内容 / gap = 同级元素之间 / margin = 元素到父容器或窗口）
 * 由调用点的参数名表达，本对象只负责数值：优先用 padding 与 gap，Material 组件内部很少用 margin。
 * 组件尺寸（如 `tocCell` 48 / `readerBottomBar` 72）与 §17 几何是尺寸而不是节奏，不要按倍数去套。
 */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp

    /** 屏幕左右边距：compact 窗口 16dp。medium 及以上窗口是 24dp（见 spacing-and-layout 断点表），
     *  多窗口布局落地时按断点取 [lg]，不要把这个 token 直接放大到全宽度。 */
    val screen = 16.dp

    /** 网格列间距（1.5x）/ 行间距（2.5x，自定义 `space250` = 20dp）。 */
    val gridColumn = 12.dp
    val gridRow = 20.dp

    /** 小尺寸封面圆角（横屏 48×72 缩略图等用 8dp）。 */
    val coverCorner = ShapeTokens.small

    /** 网格大封面圆角（M3 medium = 12dp）。 */
    val coverCornerLarge = ShapeTokens.medium

    /** 书封柔和彩色阴影高度（阴影深度，不是间距节奏）。 */
    val coverShadow = 20.dp

    /** 书架卡片封面 → 书名（1.25x 嵌档）。 */
    val cardTitleGap = 10.dp

    /**
     * 带悬浮胶囊页面的列表底部留白（P1c 语义统一）：数值即「距屏幕底边的总距离」，**已包含系统导航条**。
     * 胶囊上沿距屏底 124dp（导航区 48 + 间距 12 + 胶囊 64），取 128dp = 124 + 4dp 呼吸；
     * [listBottomInsetLarge] 供 200% 字号（胶囊 72dp → 上沿 132dp）使用。
     * 调用方不要再叠加 navigationBarsPadding()（会变 176dp）；无胶囊的全屏二级页用
     * [bottomBarPadding] + 16dp，不要复用本 token。
     *
     * 200% 字号时同一套间距 token 继续生效，变的是胶囊高度（见 large 档），不是间距倍数。
     */
    val listBottomInset = 128.dp
    val listBottomInsetLarge = 136.dp

    /** 阅读器底栏高度（组件尺寸，不是节奏）。 */
    val readerBottomBar = 72.dp

    /** 目录页码方块边长（触控目标 48dp = 6x）与格间距（1x）。 */
    val tocCell = 48.dp
    val tocGap = 8.dp

    /** 封面 → 书名（1x）。 */
    val coverToTitle = 8.dp

    /** 段头：上 24dp（3x）/ 下 8dp（1x）。 */
    val sectionTop = 24.dp
    val sectionBottom = 8.dp

    /** §17 新增几何：组件尺寸，不参与 8dp 节奏换算。 */
    val heroHeight = 176.dp
    val heroCorner = ShapeTokens.extraLarge
    val heroCoverWidth = 92.dp
    val heroCoverHeight = 123.dp
    val recentCardWidth = 132.dp
    val recentCoverHeight = 176.dp
    // R5：三等分导航 3×88 + 左右各 12 = 288dp（756px @2.625）。
    val navCapsuleWidth = 288.dp
    val navCapsuleHeight = 64.dp
    val thumbCorner = ShapeTokens.small

    /**
     * 最小触控目标（M3 无障碍：48dp = 6x）。
     * 视觉图标可以更小，交互目标不能缩；密度调节只压竖直 padding（Material 支持 0..-4 档），
     * 不缩字号、不缩触控目标。
     */
    val minTouchTarget = 48.dp
}
