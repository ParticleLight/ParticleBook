package app.particlebook.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.particlebook.android.reader.text.TextChapter
import app.particlebook.android.reader.text.TextOutline

/**
 * TXT / Markdown / FB2 / HTML 阅读器的目录弹层。
 *
 * 与 EPUB 目录的分工：EPUB 的章节在容器里自带 href 与嵌套结构，目录由 [app.particlebook.android.reader.epub.EpubReaderController.tocTargets] 直接给出；
 * 文本类没有容器，目录是 [app.particlebook.android.reader.text.TextOutline] 从标题段落推导出来的，
 * 所以这边只吃一份扁平的 [TextChapter] 列表，不涉及跳转协议——点一条就让调用方滚到那一段。
 *
 * 交互约定：
 * - 缩进按归一化层级画（level 1 顶格，每深一级缩进 [Spacing.md]），与正文里的标题字号递减对应；
 * - [activeParagraphIndex] 所属的那一条用主色 + 加粗 +「当前」标出，用户能看出读到哪了；
 * - 一个标题都没有时调用方根本不显示入口，所以这里不做空态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextTocSheet(
    chapters: List<TextChapter>,
    activeParagraphIndex: Int,
    onPick: (TextChapter) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 当前章节复用 TextOutline.chapterAt：判定只写一份，目录高亮与「读到哪一章」不会各自漂移。
    val active = TextOutline.chapterAt(chapters, activeParagraphIndex)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 与搜索 / 设置弹层同一套 insets 约定：底部避让导航条由内容自己做。
        contentWindowInsets = { WindowInsets(0) },
    ) {
        ImmersiveDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.md)
                .navigationBarsPadding(),
        ) {
            TextTocHeader(
                count = chapters.size,
                // 目录有条数上限：正好铺满时告诉用户只列了前 N 条，别让人以为全书就这么多标题。
                truncated = chapters.size >= TextOutline.DEFAULT_MAX_CHAPTERS,
                onDismiss = onDismiss,
            )
            // weight(1f, fill = false)：章少时弹层紧凑，章多时列表内部滚动（与搜索弹层同一理由，
            // 固定 dp 上限在窄屏 / 大字号下会顶出标题行）。
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                items(chapters, key = { it.paragraphIndex }) { chapter ->
                    TextTocRow(
                        chapter = chapter,
                        active = chapter === active,
                        onClick = { onPick(chapter) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TextTocHeader(count: Int, truncated: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, end = Spacing.xxs, top = Spacing.xxs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "目录",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            // 用「条」不用「章」：Markdown / HTML 的标题可能只是小节，叫「章」会误导。
            text = if (truncated) "前 " + count + " 条" else count.toString() + " 条",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = Spacing.xs),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭目录",
            )
        }
    }
}

/**
 * 一条目录项：左边按层级缩进，右边最多两行。
 *
 * 缩进只写到第 3 级为止（[MAX_INDENT_LEVEL]）：再深的标题在窄屏上会挤成一列字，
 * 层级信息改由字号/字重承担，而不是无限缩进。
 */
@Composable
private fun TextTocRow(chapter: TextChapter, active: Boolean, onClick: () -> Unit) {
    val indent = Spacing.screen + Spacing.md * (chapter.level.coerceIn(1, MAX_INDENT_LEVEL) - 1)
    Text(
        text = chapter.title,
        style = if (chapter.level == 1) {
            MaterialTheme.typography.titleSmall
        } else {
            MaterialTheme.typography.bodyMedium
        },
        fontWeight = if (active) FontWeight.SemiBold else null,
        color = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = Spacing.minTouchTarget)
            .padding(start = indent, end = Spacing.screen, top = Spacing.xs, bottom = Spacing.xs),
    )
}

/** 缩进封顶层级：再深的标题只靠字号区分，不再继续右移。 */
private const val MAX_INDENT_LEVEL = 3
