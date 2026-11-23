package app.particlebook.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.DialogProperties
import app.particlebook.android.reader.ReaderLocator

/*
 * PDF 页级跳转：起始页优先级（纯函数）+ 「跳到第 N 页」对话框。
 *
 * 逻辑部分刻意不碰 Compose / Android，单测直接覆盖；composable 只负责输入过滤与校验，
 * 真正的滚动留在 PdfReadingPane 的 jumpToPage（listState.scrollToItem + 现有导航钩子）。
 */

/** 页码输入框最多接收的位数：9 位足以覆盖任何真实 PDF，也保证解析不会拖长。 */
private const val MaxPdfPageDigits = 9

/** 页码允许的字符：半角与全角数字（中文输入法直接敲的数字不必来回切键盘）。 */
internal fun isPdfPageDigit(char: Char): Boolean = char in '0'..'9' || char in '０'..'９'

/**
 * 解析页码输入，返回 1 起算、已夹到 1..[pageCount] 的目标页；不可用时返回 null。
 *
 * - 空白、以及任何非数字字符（负号、小数点、字母、夹在中间的空格）→ null：
 *   宁可让「跳转」保持禁用，也不替用户猜他想跳哪一页；
 * - 全角数字按半角处理；
 * - 0 与超界值夹取到范围内（输入 0 落到第 1 页，输入 999 落到最后一页）；
 * - 逐位饱和累加，输入再长也不会溢出。
 */
internal fun parsePdfPageInput(raw: String, pageCount: Int): Int? {
    if (pageCount <= 0) return null
    val text = raw.trim()
    if (text.isEmpty()) return null
    var value = 0L
    for (char in text) {
        val digit = when (char) {
            in '0'..'9' -> char - '0'
            in '０'..'９' -> char - '０'
            else -> return null
        }
        value = (value * 10 + digit).coerceAtMost(Int.MAX_VALUE.toLong())
    }
    return value.coerceIn(1L, pageCount.toLong()).toInt()
}

/**
 * 起始页优先级（0 起算），单测覆盖：
 *
 * 1. 全文搜索命中的 [jump]：只认 0 until [pageCount] 内的页。页数变了、换了文件时这个页号
 *    已经不代表同一处内容，直接忽略，交回原来的恢复逻辑；
 * 2. 上次阅读的 [restored] locator：沿用既有恢复语义，越界时夹到最后一页
 *    （要不要落盘由 PdfProgressState 的失效保护决定，这里不改它的判断）；
 * 3. 旧版页码 [savedPage]；
 * 4. 0。
 */
internal fun initialPdfPage(
    jump: ReaderLocator.Pdf?,
    restored: ReaderLocator.Pdf?,
    savedPage: Int?,
    pageCount: Int,
): Int {
    if (pageCount <= 0) return 0
    if (jump != null && jump.page in 0 until pageCount) return jump.page
    return (restored?.page ?: savedPage ?: 0).coerceIn(0, pageCount - 1)
}

/**
 * 「跳到第 N 页」对话框：标题与按钮文案跟阅读器底栏一致。
 *
 * @param currentPage 当前可见页（0 起算），作为输入框初值。
 * @param onConfirm 回调 1 起算的目标页，范围已由 [parsePdfPageInput] 夹好。
 */
@Composable
internal fun PdfPageJumpDialog(
    pageCount: Int,
    currentPage: Int,
    onDismiss: () -> Unit,
    onConfirm: (page: Int) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var input by rememberSaveable(pageCount) {
        mutableStateOf((currentPage + 1).coerceIn(1, pageCount).toString())
    }
    val target = parsePdfPageInput(input, pageCount)
    AlertDialog(
        onDismissRequest = onDismiss,
        // spec §5.3：dialog 自己处理 insets，窗口不做系统栏 fit，避免开合时整页下移。
        properties = DialogProperties(decorFitsSystemWindows = false),
        title = { Text("跳到第 N 页") },
        text = {
            Column {
                ImmersiveDialogWindow()
                OutlinedTextField(
                    value = input,
                    // 只留数字：解析层还能兜住粘贴进来的脏字符，但没必要让它们出现在框里。
                    onValueChange = { raw ->
                        input = raw.filter { isPdfPageDigit(it) }.take(MaxPdfPageDigits)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    singleLine = true,
                    label = { Text("页码") },
                    supportingText = { Text("共 $pageCount 页，范围 1–$pageCount") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { target?.let(onConfirm) }),
                )
            }
            // 打开即可输入：页码是纯数字，没必要让用户先点一下输入框。
            // 放在 dialog 自己的组合里请求焦点：外层组合的 effect 可能早于 dialog 窗口挂载。
            LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
        },
        confirmButton = {
            TextButton(
                enabled = target != null,
                onClick = { target?.let(onConfirm) },
            ) { Text("跳转") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
