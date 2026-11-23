package app.particlebook.android.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体 token（M3 2026-11：15 基础字级 + 15 emphasized，共 30 风格）。
 *
 * 分层：
 * - [AppTypography] 是 `MaterialTheme.typography`，承载 15 个基础字级；组件一律引用它。
 * - [AppType] 是「内容专用」层：衬线书名 / 区块标题这类 M3 slot 表达不了的品牌字级，界面直接引用。
 * - 数值不另立字级：[AppType] 里的 *Number 档由 [AppTypography] 的同名 slot 派生，只加
 *   `fontFeatureSettings = "tnum"`（tabular figures），尺寸与源 slot 逐项相同。详见「数值档」小节。
 * - 中文不加负字距：负字距只给拉丁衬线大标题，且取中文安全上限（34sp 用 -0.6sp ≈ -0.018em）。
 *   界面文案与正文的 tracking 保持 >= 0，[AppType.bookTitle] / [AppType.topBarTitle] 显式写 0。
 *
 * 与官方 30 风格的差距（已知限制，不是遗漏）：
 * - 官方是同一角色的两套：baseline（默认字重与字距）与 emphasized（字重更重 + 轻微光学微调），
 *   组件默认用 baseline，只在「值得强调的时刻」（badge、主按钮、扩展 FAB、选中项、编辑感标题）换成 emphasized。
 * - material3 1.4.0 内部确实有 15 个 `*Emphasized` 字级，但 Kotlin 侧全部 internal：
 *   getter 被改名为 `getDisplayLargeEmphasized$material3()`，30 参数构造函数同样 internal，
 *   公开的 `Typography.copy` 只有 15 个参数（实测 1.4.0 aar）。`ExperimentalMaterial3ExpressiveApi`
 *   注解本身可用，卡住的是成员可见性，不是 opt-in。所以这里只写 15 档。
 * - 1.5.0 起才公开（版本门槛以官方 release notes 为准；本地离线缓存只有 1.3.1 / 1.4.0）。
 *   升级时在这里统一补 15 个 `*Emphasized`，调用点不改。
 * - 现在的补偿手段：需要强调时用 [emphasized] 把字重 +1 档，或直接选上一档字级；
 *   不要在界面里手写 `fontWeight = ...`，否则 1.5.0 开放 30 档时无法整批替换。
 *
 * 选型纪律：按语义选字级，不按「哪个尺寸刚好放得下」选；不为排版缩重要文字的字号
 * （改字号会破坏组件布局，改行高 / 字距 / 字体才是正解）；正文控制在 40–60 字符/行。
 *
 * 中文行高：官方 2026-08 起按文字高度分类，中文属 medium 类（约比拉丁基准高 7%）。
 * 本文件行高仍是拉丁基线值（display 64/52/44、headline 40/36/32、body 24/20/16 …），
 * 因为整体抬 7% 会重排全部页面、需要真机逐屏核对，留作独立改动；组件因此不要写死文字容器高度。
 */
private val SerifHeading: FontFamily = FontFamily.Serif

/**
 * M3 基础字级：尺寸/行高取规范值；display/headline 用衬线承载「内容」，其余用无衬线承载「操作」。
 *
 * display/headline 的负字距是拉丁衬线大标题的既有决定（中文安全上限），不要按 M3 拉丁原值继续加深。
 *
 * 声明在 [AppType] 之前：数值档要按「源 slot + tnum」派生，顶层 val 是按文件内文本顺序初始化的，
 * 反向引用会读到未初始化值。这个顺序是承重的，不要调换。
 */
internal val AppTypography: Typography = Typography(
    displayLarge = TextStyle(fontFamily = SerifHeading, fontWeight = FontWeight.SemiBold, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = SerifHeading, fontWeight = FontWeight.SemiBold, fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = (-0.4).sp),
    displaySmall = TextStyle(fontFamily = SerifHeading, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = (-0.3).sp),
    headlineLarge = TextStyle(fontFamily = SerifHeading, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontFamily = SerifHeading, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = SerifHeading, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

/** 内容专用字级：M3 slot 表达不了「衬线书名」，单独暴露。 */
object AppType {
    /** 页面大标题（书架 / 设置顶部，34sp Serif）。 */
    val pageTitle = TextStyle(
        fontFamily = SerifHeading,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
    )

    /** 区块标题（全部书籍 / 最近添加，22sp Serif）。 */
    val sectionTitle = TextStyle(
        fontFamily = SerifHeading,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    )

    /** Hero 书名（28sp Serif）。 */
    val heroTitle = TextStyle(
        fontFamily = SerifHeading,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    )

    /** 网格卡书名（16sp Serif）。 */
    val bookTitle = TextStyle(
        fontFamily = SerifHeading,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    )

    /** 顶栏滚动后的小标题（16sp Sans）。 */
    val topBarTitle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    )

    // ------------------------------------------------------------------------
    // 数值档：既有 M3 slot + tabular figures
    // ------------------------------------------------------------------------
    //
    // 为什么要单独一刀：M3 没有「数值字级」，官方排版规范只给了一条排版纪律——
    // 「Use tabular figures for tables, counters and clocks so values stay aligned」
    // （skill material-design-3-ui / references/typography.md「Rules」）。
    // 本应用里会变的数字都在窄容器里居中或靠边：统计页柱顶分钟、hero 时长、三宫格汇总、
    // 朗读条「本页 3 / 9」与「1.2x」。默认字体是比例数字（proportional figures），
    // 「1」比「8」窄，于是 9 → 10、1.2x → 1.5x 这类切换会让整行宽随数字变化，
    // 居中文本左右微跳、右对齐数字左右抖——这就是要修的字宽抖动。
    // tabular figures（OpenType 特性 tnum）让每个数字占同一个 advance，
    // 数字再多也只是换字符、不改行宽。
    //
    // 为什么是「派生」而不是新字号：tnum 是字体特性，不是字级。若在这里另写一套
    // 字号/行高，同一个数字会出现两套几何，迁移就变成「顺手改了排版」。所以每档都用
    // [AppTypography] 的同名 slot copy 而来：字号、行高、字重、字距、字体全部逐项相同，
    // 唯一差异是 fontFeatureSettings。迁移前后尺寸零变化，柱状图列宽与朗读条布局不动。
    //
    // 支持前提：Compose 的 TextStyle.fontFeatureSettings 最终落到
    // Paint.setFontFeatureSettings（API 21+），本应用 minSdk = 26（Android 8.0），
    // 不存在旧系统回落分支；Android 8.0 起的系统 Roboto 自带 tnum，FontFamily.Default
    // 的数字因此能真正对齐。换品牌字体时必须验它带 tnum：不带时特性被静默忽略，
    // 不崩、但数字退回比例宽度（抖动会回来），这条由 TypeTokensNumericTest 的字面断言兜不住。
    //
    // 选档：面板大数字用 display 级（[heroNumber]），行内数字用 label 级
    // （[inlineNumber] / [compactNumber] / [tickNumber]），汇总格用 title 级
    // （[summaryNumber]，它本来就是 titleMedium 的语义位置）。不要为了塞进容器换档。

    /** Hero 主数字（统计页「本周阅读」时长）：displaySmall 字面（36sp / 44sp 衬线）+ tnum。 */
    val heroNumber: TextStyle = AppTypography.displaySmall.tabular()

    /** 汇总格数字（累计时长 / 累计翻页 / 读过 N 本）：titleMedium 字面（16sp / 24sp）+ tnum。 */
    val summaryNumber: TextStyle = AppTypography.titleMedium.tabular()

    /** 行内数字（朗读条语速「1.2x」）：labelLarge 字面（14sp / 20sp）+ tnum，与 TextButton 默认文字档同高。 */
    val inlineNumber: TextStyle = AppTypography.labelLarge.tabular()

    /**
     * 紧凑行内数字（朗读条「本页 n / m」）：labelMedium 字面（12sp / 16sp）+ tnum。
     *
     * 这一行固定部件多（四个 48dp 图标按钮 + 语速按钮），进度文案涨到 14sp 会被挤成省略号，
     * 所以不能并到 [inlineNumber]；两档都保留 tnum，n 位数变化时不再左右推挤。
     */
    val compactNumber: TextStyle = AppTypography.labelMedium.tabular()

    /** 图表刻度数字（统计页柱顶分钟）：labelSmall 字面（11sp / 16sp）+ tnum，1/7 列宽放得下四位数。 */
    val tickNumber: TextStyle = AppTypography.labelSmall.tabular()
}

/** 打开 OpenType 的 tabular figures（tnum）特性；数值档只允许通过它派生，见 AppType 的「数值档」小节。 */
private fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TABULAR_FIGURES)

/** OpenType 特性标签：等宽（tabular）数字。CSS font-feature-settings 语法，Android 直接透传给 Paint。 */
private const val TABULAR_FIGURES = "tnum"

/**
 * 字重 +1 档：Normal→Medium→SemiBold→Bold；已是 Bold 则不变。
 *
 * 这是 1.4.0 拿不到 emphasized 字级时的唯一强调路径（见文件头的差距说明）：
 * 只加字重，不动字号与字距——emphasized 的本质也是这个。1.5.0 公开后改成换 token，
 * 用 `AppTypography.titleLargeEmphasized` 之类的槽位，本函数随之下线。
 */
internal fun FontWeight.emphasized(): FontWeight = when (this) {
    FontWeight.Normal -> FontWeight.Medium
    FontWeight.Medium -> FontWeight.SemiBold
    FontWeight.SemiBold -> FontWeight.Bold
    else -> this
}
