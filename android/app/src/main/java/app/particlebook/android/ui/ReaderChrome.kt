package app.particlebook.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 阅读器统一 chrome：所有格式（EPUB / PDF / 后续 TXT）共用同一套顶栏与底部胶囊，
 * 避免各格式各写一份颜色/高度/圆角/描边导致观感漂移。
 *
 * 颜色/形状/阴影全部走语义 token（colorScheme / shapes / Elevation），本文件不出现裸 hex：
 * - 顶栏：88dp 高（阅读屏固定几何，与正文可视高度绑定，见 [ReaderBottomPill] 注释）、
 *   colorScheme.surface@88% 半透明，让正文从栏下透出；AppType.topBarTitle；返回固定左侧。
 * - 底栏：64dp 高、surfaceContainerHigh@92%、shapes.extraLarge（28dp，M3 浮层条档位）、
 *   outlineVariant 1dp 描边、floatingShadow（Elevation.level4）。
 *
 * 半透明 alpha 是有意为之：M3 的 surface 透明度用于「内容可透出」的层，不是随手调的不透明度；
 * 禁用态仍按规范用 onSurface.copy(alpha = 0.38f)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = scheme.surface.copy(alpha = 0.88f),
        contentColor = scheme.onSurface,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp),
            contentAlignment = Alignment.Center,
        ) {
            TopAppBar(
                title = {
                    Text(
                        text = title,
                        style = AppType.topBarTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回书架",
                        )
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = scheme.onSurface,
                    navigationIconContentColor = scheme.onSurface,
                    actionIconContentColor = scheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
internal fun ReaderBottomPill(content: @Composable RowScope.() -> Unit) {
    // 浮层条用 M3 的 extraLarge（28dp）档位，而不是把每个元素都做成胶囊；
    // 64dp 高 + 28dp 圆角读起来仍是胶囊，但和其它浮层共用同一档圆角。
    val shape = MaterialTheme.shapes.extraLarge
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl)
            // 不用 navigationBarsPadding：沉浸模式下导航栏显隐会改变底栏高度，
            // 进而改变正文可视高度，导致同一本书两次打开页数不同、进度恢复错页。
            .padding(bottom = Spacing.md)
            .height(64.dp)
            .floatingShadow(shape),
        shape = shape,
        color = scheme.surfaceContainerHigh.copy(alpha = 0.92f),
        contentColor = scheme.onSurface,
        // 半透明容器在深浅两套方案里都要有轮廓，描边用 outlineVariant 而不是固定白 12%。
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
