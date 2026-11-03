package app.particlebook.android.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.zlib.ZMirrorItem
import app.particlebook.android.zlib.ZlibClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 线路切换弹层（spec §4.7 / B6）：加载 mirrorInfo + mirrorItems → 单选列表 → switchMirror。
 * 每行除 host 外展示分层 / 探测 rank / 延迟 / 上次成功 / 降权状态；切换逻辑与顺序不变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZlibMirrorSheet(
    client: ZlibClient,
    onDismiss: () -> Unit,
    onSwitched: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<app.particlebook.android.zlib.ZMirrorInfo?>(null) }
    var items by remember { mutableStateOf<List<ZMirrorItem>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var switchingIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                // 两次读取都只碰内存（注册表首次可能读一次文件），顺序一致可直接按 index 对齐。
                client.mirrorInfo() to client.mirrorItems()
            }
        }
            .onSuccess { (loadedInfo, loadedItems) ->
                info = loadedInfo
                items = loadedItems
            }
            .onFailure { loadError = ZlibUiError.message(it) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.screen),
        ) {
            Text("线路", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Spacing.xxs))
            val current = info
            when {
                loadError != null -> Text(
                    text = loadError ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                current == null -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.lg),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }

                else -> {
                    Text(
                        text = if (current.index in 1..current.total) {
                            // ZMirrorInfo.index 从 1 开始（0 = 未知）。
                            "线路 " + current.index + " / " + current.total
                        } else {
                            "线路未知 / " + current.total
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    current.mirrors.forEachIndexed { index, mirror ->
                        // mirrors 下标 0 起，ZMirrorInfo.index 1 起；items 与 mirrors 同序。
                        val item = items.getOrNull(index)
                        val selected = item?.current ?: (current.index > 0 && index == current.index - 1)
                        val host = item?.host?.takeIf { it.isNotEmpty() } ?: zlibMirrorHost(mirror)
                        val meta = zlibMirrorMeta(item)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selected,
                                    enabled = switchingIndex < 0,
                                    role = Role.RadioButton,
                                    onClick = {
                                        if (selected) {
                                            onDismiss()
                                        } else {
                                            scope.launch {
                                                switchingIndex = index
                                                val ok = runCatching {
                                                    withContext(Dispatchers.IO) { client.switchMirror(index) }
                                                }.getOrDefault(false)
                                                switchingIndex = -1
                                                if (ok) {
                                                    onSwitched(zlibMirrorHost(mirror))
                                                    onDismiss()
                                                } else {
                                                    loadError = "线路切换失败，请稍后重试"
                                                }
                                            }
                                        }
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null, enabled = switchingIndex < 0)
                            Spacer(Modifier.width(Spacing.sm))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = host,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (meta.isNotEmpty()) {
                                    Text(
                                        text = meta,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

/** 弹层每行元信息（B6）：分层 · rank · 延迟 · 上次成功 · 暂不可用 / 已固定。 */
private fun zlibMirrorMeta(item: ZMirrorItem?): String {
    item ?: return ""
    val parts = ArrayList<String>(5)
    parts.add(
        when (item.tier) {
            "official" -> "官方"
            "dynamic" -> "动态"
            else -> "兜底"
        },
    )
    parts.add(
        when (item.rank) {
            0 -> "正式站"
            1 -> "未探测"
            2 -> "推广页"
            3 -> "其它"
            else -> "不通"
        },
    )
    if (item.latencyMs >= 0L) parts.add(item.latencyMs.toString() + " ms")
    if (item.successFresh) parts.add("上次成功")
    if (item.demoted) parts.add("暂不可用")
    if (item.pinned) parts.add("已固定")
    return parts.joinToString(" · ")
}
