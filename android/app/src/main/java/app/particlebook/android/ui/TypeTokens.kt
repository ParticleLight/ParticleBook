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
}

/**
 * M3 基础字级：尺寸/行高取规范值；display/headline 用衬线承载「内容」，其余用无衬线承载「操作」。
 *
 * display/headline 的负字距是拉丁衬线大标题的既有决定（中文安全上限），不要按 M3 拉丁原值继续加深。
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
