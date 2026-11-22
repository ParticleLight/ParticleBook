package app.particlebook.android.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 颜色 token：唯一允许出现裸 hex 的地方（M3 语义角色，暖纸基底 + 分层 tonal + 编辑感橙强调）。
 *
 * 约束：
 * - 界面只引用 `MaterialTheme.colorScheme.<role>`，不要引用这里的私有常量。
 * - 每个 on* 角色必须与其容器角色成对使用；对比度由 `docs/android-port/m3/audit.md` 记录基线。
 * - 新增角色前先确认 M3 是否已有语义角色可用（surfaceContainer* / inverse* / outlineVariant）。
 */
/** 品牌强调色（light primary）。阴影与选中态需要非 composable 上下文时引用它，不写裸 hex。 */
internal val BrandAccent = Color(0xFF2563EB)

internal val ParticleBookLightScheme = lightColorScheme(
    primary = BrandAccent,
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
    surfaceTint = BrandAccent,
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

internal val ParticleBookDarkScheme = darkColorScheme(
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
    inversePrimary = BrandAccent,
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