package app.particlebook.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.particlebook.android.reader.pdf.search.PdfSearchHit
import app.particlebook.android.reader.pdf.search.PdfSearchReport

/**
 * PDF 书内搜索的界面状态。
 *
 * 只描述"面板此刻该显示什么"，扫描本身由 PdfReadingPane 的协程负责；
 * 这样面板可以任意重组（切主题、转屏、键盘起落）而不会重启一次扫描。
 */
internal sealed interface PdfSearchUiState {
    data object Idle : PdfSearchUiState
    data class Scanning(
        val scannedPages: Int,
        val pageCount: Int,
        val hitCount: Int,
    ) : PdfSearchUiState

    data class Done(val report: PdfSearchReport) : PdfSearchUiState
    data class Failed(val message: String) : PdfSearchUiState
}

/**
 * 书内搜索弹层。
 *
 * 交互约定：
 * - 回车即搜；扫描中按钮变成「停止」，停止只是取消协程，已找到的结果仍保留在上一个状态里；
 * - 结果按"第 N 页 · 片段"列出，点一条就跳页并关闭弹层，页内高亮由阅读屏自己画；
 * - 扫描上限（[PdfSearchReport.limitReached]）只提示，不阻断用户继续改词重搜。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfSearchSheet(
    query: String,
    state: PdfSearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onPick: (PdfSearchHit) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val scanning = state is PdfSearchUiState.Scanning
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 与目录 / 设置弹层同一套 insets 约定：底部由内容自己避让导航条。
        contentWindowInsets = { WindowInsets(0) },
    ) {
        ImmersiveDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = Spacing.md),
        ) {
            Text(
                text = "搜索全文",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.md),
            )
            Text(
                text = "在整本 PDF 的文本层里查找；扫描版（无文本层）不会有结果",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xxs),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    label = { Text("关键词") },
                    placeholder = { Text("例如：Moby") },
                    trailingIcon = if (query.isEmpty()) {
                        null
                    } else {
                        {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "清空关键词",
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                )
                if (scanning) {
                    TextButton(onClick = onStop) { Text("停止") }
                } else {
                    TextButton(
                        onClick = onSubmit,
                        enabled = query.isNotBlank(),
                    ) { Text("搜索") }
                }
            }

            when (state) {
                is PdfSearchUiState.Idle -> SearchHint(
                    text = "输入关键词后按回车，或点右侧「搜索」",
                )

                is PdfSearchUiState.Scanning -> ScanningBlock(state)
                is PdfSearchUiState.Failed -> FailedBlock(state, onRetry = onSubmit)
                is PdfSearchUiState.Done -> ResultList(state.report, onPick)
            }
        }
    }
    // 打开即聚焦：搜索面板的唯一目的就是输入关键词，没必要让用户先点一下输入框。
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
}

@Composable
private fun SearchHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScanningBlock(state: PdfSearchUiState.Scanning) {
    val total = state.pageCount.coerceAtLeast(0)
    val done = state.scannedPages.coerceIn(0, total)
    val fraction = if (total > 0) done.toFloat() / total.toFloat() else 0f
    Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
        LinearWavyProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = if (total > 0) {
                "已扫描 " + done + " / " + total + " 页 · 命中 " + state.hitCount + " 处"
            } else {
                "正在打开文档…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FailedBlock(state: PdfSearchUiState.Failed, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun ResultList(report: PdfSearchReport, onPick: (PdfSearchHit) -> Unit) {
    val hits = report.hits
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (hits.isEmpty()) {
                "没有找到「" + report.query + "」"
            } else {
                "共 " + hits.size + " 处命中 · 已扫描 " + report.scannedPages + " / " + report.pageCount + " 页"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xxs),
        )
        if (report.limitReached) {
            Text(
                text = "命中过多，只列出前 " + hits.size + " 处；换个更具体的关键词会更准",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xxs),
            )
        }
        if (hits.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                items(hits, key = { hit -> hit.page.toString() + ":" + hit.charStart }) { hit ->
                    SearchHitRow(hit, onClick = { onPick(hit) })
                }
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: PdfSearchHit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "第 " + (hit.page + 1) + " 页",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(64.dp),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = highlightedSnippet(hit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
        )
    }
}

/** 命中词在片段里加粗上色；片段或区间异常时退回纯文本，不因显示细节丢结果。 */
@Composable
private fun highlightedSnippet(hit: PdfSearchHit): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    return remember(hit, accent) {
        if (!hit.hasSnippetMatch) {
            AnnotatedString(hit.snippet)
        } else {
            buildAnnotatedString {
                append(hit.snippet.substring(0, hit.matchStart))
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) {
                    append(hit.snippet.substring(hit.matchStart, hit.matchEnd))
                }
                append(hit.snippet.substring(hit.matchEnd))
            }
        }
    }
}
