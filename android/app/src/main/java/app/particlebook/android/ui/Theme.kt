package app.particlebook.android.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** 外观三选一。label 直接作为 SegmentedButton 文案。 */
enum class AppThemeMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
}

/**
 * 间距 token：4dp 节奏。
 * 数值来自 docs/android-port/android-ui-m3.md §3.4 / §17（2026 观感改版）。
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
    val coverCorner = 8.dp

    /** 网格大封面圆角（§17.1.3 medium = 16dp）。 */
    val coverCornerLarge = 16.dp

    /** 书封柔和彩色阴影高度（§17.1.4：20dp）。 */
    val coverShadow = 20.dp

    /** 书架卡片封面 → 书名。 */
    val cardTitleGap = 10.dp

    /** 网格底部为悬浮胶囊预留的空间。 */
    val listBottomInset = 96.dp

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
    val heroCorner = 24.dp
    val heroCoverWidth = 92.dp
    val heroCoverHeight = 123.dp
    val recentCardWidth = 132.dp
    val recentCoverHeight = 176.dp
    // R5：三等分导航 3×88 + 左右各 12 = 288dp（756px @2.625）。
    val navCapsuleWidth = 288.dp
    val navCapsuleHeight = 64.dp
    val thumbCorner = 8.dp
}

/** §17.1.3 圆角分级：同一屏最多 3 档。 */
object Radius {
    val chip = 8.dp
    val thumb = 12.dp
    val cover = 16.dp
    val card = 20.dp
    val hero = 24.dp
    val group = 28.dp
    val sheet = 28.dp
}

/**
 * 颜色 token 2.0（§17.1.1 暖纸基底 + 分层 tonal + 编辑感橙强调）。
 * 裸 hex 只允许出现在这里；界面只引用语义角色。
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6FF),
    onPrimaryContainer = Color(0xFF001740),
    secondary = Color(0xFF5B5348),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9E2D7),
    onSecondaryContainer = Color(0xFF1F1B15),
    tertiary = Color(0xFFC2410C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE0D2),
    onTertiaryContainer = Color(0xFF3C0C00),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF7F1),
    onBackground = Color(0xFF17130D),
    surface = Color(0xFFFBF7F1),
    onSurface = Color(0xFF17130D),
    surfaceVariant = Color(0xFFE2DACD),
    onSurfaceVariant = Color(0xFF4B4337),
    surfaceTint = Color(0xFF2563EB),
    inverseSurface = Color(0xFF322E27),
    inverseOnSurface = Color(0xFFF6F0E8),
    inversePrimary = Color(0xFFB0C6FF),
    surfaceDim = Color(0xFFEDE6DC),
    surfaceBright = Color(0xFFFFFDFA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F1E9),
    surfaceContainer = Color(0xFFF0EAE1),
    surfaceContainerHigh = Color(0xFFE9E2D7),
    surfaceContainerHighest = Color(0xFFE2DACD),
    outline = Color(0xFF8A7D6C),
    outlineVariant = Color(0xFFE5DCCE),
    scrim = Color(0xFF000000),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF002B6B),
    primaryContainer = Color(0xFF00429B),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFCFC5B8),
    onSecondary = Color(0xFF322D25),
    secondaryContainer = Color(0xFF3E372E),
    onSecondaryContainer = Color(0xFFF2EADF),
    tertiary = Color(0xFFFFB59C),
    onTertiary = Color(0xFF5A1800),
    tertiaryContainer = Color(0xFF7A2A0C),
    onTertiaryContainer = Color(0xFFFFDCCC),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF16130E),
    onBackground = Color(0xFFF2EADF),
    surface = Color(0xFF16130E),
    onSurface = Color(0xFFF2EADF),
    surfaceVariant = Color(0xFF352E25),
    onSurfaceVariant = Color(0xFFCDC2B3),
    surfaceTint = Color(0xFFB0C6FF),
    inverseSurface = Color(0xFFF2EADF),
    inverseOnSurface = Color(0xFF322E27),
    inversePrimary = Color(0xFF2563EB),
    surfaceDim = Color(0xFF100E0A),
    surfaceBright = Color(0xFF221D16),
    surfaceContainerLowest = Color(0xFF0C0A07),
    surfaceContainerLow = Color(0xFF1C1812),
    surfaceContainer = Color(0xFF221D17),
    surfaceContainerHigh = Color(0xFF2B251E),
    surfaceContainerHighest = Color(0xFF352E25),
    outline = Color(0xFF9A8E7E),
    outlineVariant = Color(0xFF3E372E),
    scrim = Color(0xFF000000),
)

/**
 * 字体（§17.1.2）：衬线只做「内容」（书名 / Hero / 区块大标题），无衬线做「操作」。
 * 中文不加字距；负字距只给衬线大标题（取中文安全值）。
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

    /** 网格卡书名（16sp Serif，§17.1.2 Title）。 */
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

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = SerifHeading, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
    headlineLarge = TextStyle(fontFamily = SerifHeading, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = SerifHeading, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = SerifHeading, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
)

/** §17.1.3：8 / 12 / 16 / 20 / 28。 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 浮起元素：柔和阴影（ambient 8% 黑 / spot primary 14%，§17.1.4）。 */
fun Modifier.floatingShadow(shape: Shape, elevation: Dp = 16.dp): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = Color.Black.copy(alpha = 0.08f),
    spotColor = Color(0xFF2563EB).copy(alpha = 0.14f),
)

/** 封面彩色投影（封面主色 18%，只在网格卡 / Hero 上用）。 */
fun Modifier.coverShadow(shape: Shape, tint: Color, elevation: Dp = Spacing.coverShadow): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = tint.copy(alpha = 0.10f),
    spotColor = tint.copy(alpha = 0.18f),
)

/** §17.1.4：blur 仅在 API ≥31 生效；低版本返回原样（调用方用 alpha 0.86 兜底）。 */
fun Modifier.adaptiveBlur(radius: Dp): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blur(radius) else this

/**
 * 应用主题。
 *
 * @param dynamicColor 设计规范默认关闭；保留开关，P0 不暴露给用户。
 */
@Composable
fun ParticleBookTheme(
    mode: AppThemeMode = AppThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (mode) {
        AppThemeMode.System -> isSystemInDarkTheme()
        AppThemeMode.Light -> false
        AppThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
