package app.particlebook.android.ui

import androidx.compose.ui.unit.dp

/**
 * 间距 token：4dp 节奏，M3 布局规范的 4dp 网格。
 *
 * 只放"跨屏复用"的间距；某一屏的一次性留白请就地写并注释原因，不要往这里堆。
 * 语义名按"从内容到容器"的顺序增长：xxs/xs/sm/md/lg/xl/xxl。
 */
object Spacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp

    /** 屏幕左右边距（compact 16dp）。 */
    val screen = 16.dp

    /** 网格列间距 / 行间距。 */
    val gridColumn = 12.dp
    val gridRow = 20.dp

    /** 小尺寸封面圆角（横屏 48×72 缩略图等用 8dp）。 */
    val coverCorner = ShapeTokens.small

    /** 网格大封面圆角（M3 medium = 12dp）。 */
    val coverCornerLarge = ShapeTokens.medium

    /** 书封柔和彩色阴影高度。 */
    val coverShadow = 20.dp

    /** 书架卡片封面 → 书名。 */
    val cardTitleGap = 10.dp

    /**
     * 带悬浮胶囊页面的列表底部留白（P1c 语义统一）：数值即「距屏幕底边的总距离」，**已包含系统导航条**。
     * 胶囊上沿距屏底 124dp（导航区 48 + 间距 12 + 胶囊 64），取 128dp = 124 + 4dp 呼吸；
     * [listBottomInsetLarge] 供 200% 字号（胶囊 72dp → 上沿 132dp）使用。
     * 调用方不要再叠加 navigationBarsPadding()（会变 176dp）；无胶囊的全屏二级页用
     * [bottomBarPadding] + 16dp，不要复用本 token。
     */
    val listBottomInset = 128.dp
    val listBottomInsetLarge = 136.dp

    /** 阅读器底栏高度。 */
    val readerBottomBar = 72.dp

    /** 目录页码方块。 */
    val tocCell = 48.dp
    val tocGap = 8.dp

    /** 封面 → 书名。 */
    val coverToTitle = 8.dp

    /** 段头：上 24dp / 下 8dp。 */
    val sectionTop = 24.dp
    val sectionBottom = 8.dp

    /** §17 新增几何。 */
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

    /** 最小触控目标（M3 无障碍：48dp）。 */
    val minTouchTarget = 48.dp
}
