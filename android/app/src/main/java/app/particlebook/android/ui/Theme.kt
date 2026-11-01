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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
 * 数值来自 docs/android-port/android-ui-m3.md §3.4。
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

    /** 书封圆角。 */
    val coverCorner = 12.dp

    /** 网格底部为 FAB 预留的空间。 */
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
}

/**
 * 颜色 token（seed = #2563EB 的 M3 色调板，见设计规范 §3.1）。
 * 裸 hex 只允许出现在这里；界面只引用语义角色。
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF565E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF131C2B),
    tertiary = Color(0xFF00696E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF1F6),
    onTertiaryContainer = Color(0xFF002022),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFDF9F4),
    onBackground = Color(0xFF1F1B15),
    surface = Color(0xFFFDF9F4),
    onSurface = Color(0xFF1F1B15),
    surfaceVariant = Color(0xFFE8E1D9),
    onSurfaceVariant = Color(0xFF4E463B),
    surfaceTint = Color(0xFF2563EB),
    inverseSurface = Color(0xFF353029),
    inverseOnSurface = Color(0xFFF4F0EB),
    inversePrimary = Color(0xFFAFC6FF),
    surfaceDim = Color(0xFFDFD9D2),
    surfaceBright = Color(0xFFFDF9F4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F3ED),
    surfaceContainer = Color(0xFFF3EDE6),
    surfaceContainerHigh = Color(0xFFEDE7E0),
    surfaceContainerHighest = Color(0xFFE8E1D9),
    outline = Color(0xFF817566),
    outlineVariant = Color(0xFFD1C5B4),
    scrim = Color(0xFF000000),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF002F65),
    primaryContainer = Color(0xFF00458E),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBEC6DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFF84D4DA),
    onTertiary = Color(0xFF00363A),
    tertiaryContainer = Color(0xFF004F54),
    onTertiaryContainer = Color(0xFF9CF1F6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF17130C),
    onBackground = Color(0xFFE8E1D9),
    surface = Color(0xFF17130C),
    onSurface = Color(0xFFE8E1D9),
    surfaceVariant = Color(0xFF3A342C),
    onSurfaceVariant = Color(0xFFCFC5B8),
    surfaceTint = Color(0xFFB0C6FF),
    inverseSurface = Color(0xFFE8E1D9),
    inverseOnSurface = Color(0xFF353029),
    inversePrimary = Color(0xFF2563EB),
    surfaceDim = Color(0xFF17130C),
    surfaceBright = Color(0xFF3E3830),
    surfaceContainerLowest = Color(0xFF120E07),
    surfaceContainerLow = Color(0xFF1F1B15),
    surfaceContainer = Color(0xFF231F19),
    surfaceContainerHigh = Color(0xFF2F2922),
    surfaceContainerHighest = Color(0xFF3A342C),
    outline = Color(0xFF9B8F7F),
    outlineVariant = Color(0xFF4E463B),
    scrim = Color(0xFF000000),
)

/** 中文排版的字阶：只用 8 个角色，字距归零（M3 默认字距是拉丁度量）。 */
private val AppTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
)

/** 形状 5 档：4 / 8 / 12 / 16 / 28。 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

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
