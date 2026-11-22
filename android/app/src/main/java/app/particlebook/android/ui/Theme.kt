package app.particlebook.android.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** 外观三选一。label 直接作为 SegmentedButton 文案。 */
enum class AppThemeMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
}

/**
 * M3 高度 token：规范五档（0 / 1 / 3 / 6 / 8 / 12）。
 * 组件优先用组件自带的 elevation 参数；只有自绘浮层才引用这里，不要写裸数字。
 */
object Elevation {
    val level0 = 0.dp
    val level1 = 1.dp
    val level2 = 3.dp
    val level3 = 6.dp
    val level4 = 8.dp
    val level5 = 12.dp
}

/** 浮起元素：柔和阴影（ambient 8% 黑 / spot primary 14%）。 */
fun Modifier.floatingShadow(shape: Shape, elevation: Dp = Elevation.level4): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = Color.Black.copy(alpha = 0.08f),
    spotColor = BrandAccent.copy(alpha = 0.14f),
)

/** 封面彩色投影（封面主色 18%，只在网格卡 / Hero 上用）。 */
fun Modifier.coverShadow(shape: Shape, tint: Color, elevation: Dp = Spacing.coverShadow): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = tint.copy(alpha = 0.10f),
    spotColor = tint.copy(alpha = 0.18f),
)

/** blur 仅在 API ≥31 生效；低版本返回原样（调用方用 alpha 0.86 兜底）。 */
fun Modifier.adaptiveBlur(radius: Dp): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blur(radius) else this

/**
 * 无悬浮胶囊的全屏二级页（详情 / 下载记录 / 登录）底部 inset：
 * 只吃掉系统导航栏，内容再自行留 16dp；这些页面没有胶囊，不适用 [Spacing.listBottomInset]
 * （128dp 会留下 112dp 死白）。带胶囊的主 tab 页面直接用 [Spacing.listBottomInset]（距屏底总量）。
 */
@Composable
fun Modifier.bottomBarPadding(): Modifier = this.navigationBarsPadding()

/**
 * 全局 SnackbarHost 的统一底距（P1c）：
 * - [aboveCapsule] = true（书架 / 找书 / 设置等带悬浮胶囊的页面）：底边距屏底 = 胶囊上沿 + 8dp，
 *   即 [Spacing.listBottomInset] + 4dp（200% 用 large 值）；token 已是「距屏底」，这里不再叠 nav inset。
 * - false（登录 / 详情 / 下载记录 / 横屏 rail 等无胶囊页面）：系统导航条之上 16dp。
 */
@Composable
fun AppSnackbarHost(
    hostState: SnackbarHostState,
    aboveCapsule: Boolean,
    modifier: Modifier = Modifier,
) {
    val largeFont = LocalDensity.current.fontScale >= 1.3f
    val bottom = if (aboveCapsule) {
        (if (largeFont) Spacing.listBottomInsetLarge else Spacing.listBottomInset) + Spacing.xxs
    } else {
        Spacing.md
    }
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
            .then(if (aboveCapsule) Modifier else Modifier.navigationBarsPadding())
            .padding(bottom = bottom),
    )
}

/**
 * 应用主题（Material 3 Expressive）。
 *
 * 三个子系统走主题：colorScheme / typography（M3 15 个基础字级）/ shapes（规范阶梯 4·8·12·16·28）。
 * 动效由 [Motion] 的 expressive 等价 token 提供——material3 1.4.0 的 `MaterialTheme.motionScheme`
 * 与 `MotionScheme.expressive()` 都是 internal（1.5.0 才公开），见 `docs/android-port/m3/audit.md`。
 * 组件不要自己拼 spring/tween 或圆角字面量。
 *
 * @param dynamicColor M3 动态取色（Android 12+）；设计规范默认关闭，保留开关，不暴露给用户。
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

        darkTheme -> ParticleBookDarkScheme
        else -> ParticleBookLightScheme
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