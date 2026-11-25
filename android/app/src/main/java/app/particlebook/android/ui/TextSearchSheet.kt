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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import app.particlebook.android.reader.text.TextSearchHit

/**
 * TXT / Markdown 阅读器的书内搜索弹层。
 *
 * 与 [PdfSearchSheet] 的分工：PDF 是逐页扫描的异步过程，所以那边要自带进度 / 停止 / 失败状态；
 * 文本阅读器整本已在内存里，[TextSearchHit] 由 TextReaderScreen 同步算好整表丢进来，
 * 本组件只是纯展示 + 一个受控输入框，不持有搜索状态、不启协程。
 *
 * 交互约定：
 * - 输入即搜（每次 onValueChange 都往上抛，防抖由调用方决定），回车不额外触发；
 * - 命中按「第 N 段 · 高亮片段」列出，点一条就交给 onPick 跳段并关闭弹层；
 * - 命中被截断（[truncated]）时只提示「显示前 N 处」，不影响继续改词；是否截断由引擎判定后传进来，
 *   本组件不再拿 hits.size 跟上限比——命中数正好等于上限时并没有更多，比出来的「截断」是假话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextSearchSheet(
    query: String,
    hits: List<TextSearchHit>,
    onQueryChange: (String) -> Unit,
    onPick: (TextSearchHit) -> Unit,
    onDismiss: () -> Unit,
    truncated: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 与目录 / PDF 搜索 / 设置弹层同一套 insets 约定：dialog 窗口自己 edge-to-edge，
        // 底部避让导航条由内容自己做（见下方 navigationBarsPadding）。
        contentWindowInsets = { WindowInsets(0) },
    ) {
        ImmersiveDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.md)
                .navigationBarsPadding(),
        ) {
            TextSearchHeader(onDismiss = onDismiss)
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md)
                    .focusRequester(focusRequester),
                singleLine = true,
                label = { Text("在本书中搜索") },
                trailingIcon = if (query.isEmpty()) {
                    null
                } else {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "清除关键词",
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )

            // 三种状态互斥：还没输入词 / 有词但没命中 / 有命中。
            // 命中分支的两块内容故意直接挂在弹层的 Column 下（而不是再包一层 Column）：
            // weight(1f, fill = false) 只有对「自身高度受窗口约束」的 Column 的直接子项才生效。
            when {
                query.isBlank() -> TextSearchHint("输入关键词开始搜索")
                hits.isEmpty() -> TextSearchHint("没有找到匹配的内容")
                else -> {
                    Text(
                        // 截断时只报「显示前 N 处」，不报「200 处命中（只显示前 200 处）」—— 同一句话里
                        // 重复同一个数字，读起来像 bug。N 用实际列出来的条数，不引用上限常量：
                        // 两者只在真截断时才相等，而 hits.size 永远是对的。
                        text = if (truncated) {
                            "显示前 " + hits.size + " 处命中"
                        } else {
                            hits.size.toString() + " 处命中"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = Spacing.md,
                            vertical = Spacing.xxs,
                        ),
                    )
                    // 列表高度：weight(1f, fill = false) 而不是 heightIn(max = 固定 dp)。
                    // 弹层本身由 sheet 窗口封顶（skipPartiallyExpanded 时最多整屏），weight 拿到的
                    // 就是「标题 + 输入框 + 命中计数」之后的真实剩余高度：命中少时弹层依旧紧凑，
                    // 命中多时列表内部滚动；固定 dp 上限在窄屏 / 大字号下会顶出输入框或白留一截。
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        items(hits, key = { hit -> hit.paragraphIndex.toString() + ":" + hit.start }) { hit ->
                            TextSearchHitRow(hit = hit, onClick = { onPick(hit) })
                        }
                    }
                }
            }
        }
    }
    // 打开即聚焦：弹层存在的唯一目的就是输入关键词，不让用户先点一下输入框。
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun TextSearchHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, end = Spacing.xxs, top = Spacing.xxs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "书内搜索",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭搜索",
            )
        }
    }
}

@Composable
private fun TextSearchHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
    )
}

/**
 * 一条命中：段落标签在上、片段在下（同一列左对齐），片段里命中词反白高亮。
 *
 * 标签单独占一行而不是挤在片段左边，是因为文本段的片段本身要占满宽度读起来才顺；
 * 整块（标签 + 片段）都是点击区，点哪都跳段。
 */
@Composable
private fun TextSearchHitRow(hit: TextSearchHit, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .heightIn(min = Spacing.minTouchTarget)
            .fillMaxWidth()
            .padding(vertical = Spacing.xs, horizontal = Spacing.screen),
    ) {
        Text(
            text = "第 " + (hit.paragraphIndex + 1) + " 段",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = rememberedHighlightedSnippet(hit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.xxs),
        )
    }
}

/** 命中词用 tertiaryContainer 反白：与正文同字号但底色更抓眼，不靠加粗抢排版节奏。 */
@Composable
private fun rememberedHighlightedSnippet(hit: TextSearchHit): AnnotatedString {
    val highlight = SpanStyle(
        background = MaterialTheme.colorScheme.tertiaryContainer,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        fontWeight = FontWeight.SemiBold,
    )
    return remember(hit, highlight) {
        highlightSnippet(hit.snippet, hit.matchStart, hit.matchEnd, highlight)
    }
}

/**
 * 片段里 [matchStart], [matchEnd) 上色。
 *
 * [TextSearchHit] 是按「折叠空白后」的片段给的区间，正常不会越界；
 * 但区间异常时宁可退回纯文本，也不让一个显示细节吞掉整条结果。
 */
private fun highlightSnippet(
    snippet: String,
    matchStart: Int,
    matchEnd: Int,
    highlight: SpanStyle,
): AnnotatedString {
    if (matchStart < 0 || matchEnd > snippet.length || matchEnd < matchStart) {
        return AnnotatedString(snippet)
    }
    return buildAnnotatedString {
        append(snippet.substring(0, matchStart))
        withStyle(highlight) {
            append(snippet.substring(matchStart, matchEnd))
        }
        append(snippet.substring(matchEnd))
    }
}
