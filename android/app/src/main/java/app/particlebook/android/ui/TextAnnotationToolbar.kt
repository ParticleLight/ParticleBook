package app.particlebook.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * 段落标注工具条：长按正文某一段时浮在正文底部的那一条。
 *
 * 只画内容，不管显隐与进出动画——宿主持有它的可见性（贴合点的测量、键盘/安全区避让、
 * 淡入淡出都在宿主那侧），这里被放进组合就表示「现在该显示」。
 *
 * 布局是**单行**：Row 天生不换行，窄屏放不下时整行横向滚动，而不是折成两行把正文往上顶
 * （阅读器里正文高度一变，页数、页码与阅读进度全跟着错）。因此根 Surface 撑满可用宽度，
 * 由宿主用外层的 margin/对齐决定它离屏幕边缘多远，横向滚动也才有确定的视口宽度。
 *
 * 抬升只取**阴影**一种（不给 tonalElevation）：色调抬升是相对 app 的 surface 角色算出来的，
 * 而这条工具条底下压的是阅读页正文，页面底色来自阅读主题、不是 M3 的 surface；此时
 * tonalElevation 叠在已显式指定的 surfaceContainerHigh 上属于同层双重抬升，还分不开正文。
 * 阴影是与底色无关的分离手段，用 [Elevation.level3]（浮动工具条档，和 TtsBar 一致）。
 */
@Composable
internal fun TextAnnotationToolbar(
    paragraphLength: Int,
    currentColor: String?,
    bookmarked: Boolean,
    onHighlight: (String) -> Unit,
    onNote: () -> Unit,
    onToggleBookmark: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surfaceContainerHigh,
        contentColor = scheme.onSurface,
        shadowElevation = Elevation.level3,
    ) {
        Row(
            // padding 在 horizontalScroll 外侧：滚动视口本身内缩，滑动时首尾留白不会被卷走。
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "本段 " + paragraphLength + " 字",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Spacing.sm))
            EpubAnnotationPalette.colors.forEach { color ->
                HighlightColorDot(
                    color = color,
                    selected = color.key == currentColor,
                    onClick = { onHighlight(color.key) },
                )
            }
            Spacer(Modifier.width(Spacing.xxs))
            TextButton(
                onClick = onNote,
                modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
            ) {
                Text("笔记")
            }
            IconButton(
                onClick = onToggleBookmark,
                modifier = Modifier.size(Spacing.minTouchTarget),
            ) {
                Icon(
                    imageVector = if (bookmarked) {
                        Icons.Filled.Bookmark
                    } else {
                        Icons.Outlined.BookmarkBorder
                    },
                    contentDescription = if (bookmarked) "取消书签" else "加书签",
                )
            }
            TextButton(
                onClick = onCopy,
                modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
            ) {
                Text("复制")
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(Spacing.minTouchTarget),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭标注工具条")
            }
        }
    }
}

/**
 * 一个高亮色点：24dp 的圆是「视觉」，32x48dp 的盒子是「命中区」。
 *
 * 圆点用内容色 [annotationColor]（调色板 key 自带 alpha，和正文里真正落笔的高亮同源），
 * 命中区只做透明区域，不再叠任何颜色。选中态用一圈 onSurface 描边表达：
 * 描边是非文字信号，所以同时补 stateDescription，读屏用户才知道这段当前就是这个颜色。
 */
@Composable
private fun HighlightColorDot(
    color: EpubAnnotationColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = Spacing.xl, height = Spacing.minTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = color.label + "高亮"
                if (selected) stateDescription = "当前高亮色"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.lg)
                .clip(CircleShape)
                .background(annotationColor(color.key))
                .then(
                    if (selected) {
                        // 2dp 是描边宽度（stroke），不是间距节奏：Spacing 只管 padding/gap 的数值，
                        // 描边没有对应 token，故就地写并在此说明。
                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else {
                        Modifier
                    },
                ),
        )
    }
}
