package app.particlebook.android.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字体 token（M3 最新规范：15 个基础字级）。
 *
 * 分层：
 * - [AppTypography] 是 `MaterialTheme.typography`，只放 M3 语义字级；组件一律引用它。
 * - [AppType] 是"内容专用"层：衬线书名/区块标题这类 M3 slot 表达不了的品牌字级，界面直接引用。
 * - 中文不加负字距；负字距只给拉丁衬线大标题，取值按中文安全上限。
 *
 * M3 Expressive 的 15 个 `*Emphasized` 字级在 material3 1.4.0 里是 **internal**
 * （`Typography.copy(...Emphasized)` 与 `ExperimentalMaterial3ExpressiveApi` 都不可访问，
 * 1.5.0 才公开；而 1.5.0 要求 Compose 1.12 / Kotlin 2.2，当前工具链是 AGP 8.7.3 + Kotlin 2.0.21）。
 * 因此强调只通过基础字级表达：需要强调时用高一档字重或上一档字级，
 * 不要在界面里手写 fontWeight 覆盖；升级到 1.5.0 时在这里统一补 Emphasized。
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

/** M3 基础字级：尺寸/行高取规范值；display/headline 用衬线承载"内容"，其余用无衬线承载"操作"。 */
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

/** 字重 +1 档：Normal→Medium→SemiBold→Bold；已是 Bold 则不变。 */
internal fun FontWeight.emphasized(): FontWeight = when (this) {
    FontWeight.Normal -> FontWeight.Medium
    FontWeight.Medium -> FontWeight.SemiBold
    FontWeight.SemiBold -> FontWeight.Bold
    else -> this
}
