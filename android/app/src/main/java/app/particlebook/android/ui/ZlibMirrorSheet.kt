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
 * 线路切换弹层（spec §4.7 / B6）：单快照 [ZlibClient.mirrorRows] → 单选列表 → 按 URL [ZlibClient.switchMirror]。
 * 每行除 host 外展示分层 / 探测 rank / 延迟 / 上次成功 / 降权状态；行序、host、current 全部来自同一快照，
 * 后台刷新重排也不会「点 A 切 B」。切换结果回传实际切换到的 URL（onSwitched）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZlibMirrorSheet(
    client: ZlibClient,
    onDismiss: () -> Unit,
    onSwitched: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<ZMirrorItem>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var switchingUrl by remember { mutableStateOf<String?>(null) }
    var switchError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) { client.mirrorRows() }
        }
            .onSuccess { rows = it }
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
            val loaded = rows
            when {
                loadError != null -> Text(
                    text = loadError ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                loaded == null -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.lg),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }

                else -> {
                    // T10 P2-6：当前线路下标也从同一份 rows 推导（不再读 mirrorInfo 的第二次快照）。
                    val currentIndex = loaded.indexOfFirst { it.current }
                    Text(
                        text = if (currentIndex >= 0) {
                            "线路 " + (currentIndex + 1) + " / " + loaded.size
                        } else {
                            "线路未知 / " + loaded.size
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    // T17：切换失败只做行内提示，不替换整列表（否则用户无法改选）；提示出现时列表仍可点。
                    switchError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(Spacing.xs))
                    }
                    loaded.forEach { item ->
                        val selected = item.current
                        val meta = zlibMirrorMeta(item)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = selected,
                                    enabled = switchingUrl == null,
                                    role = Role.RadioButton,
                                    onClick = {
                                        if (selected) {
                                            onDismiss()
                                        } else {
                                            scope.launch {
                                                switchingUrl = item.url
                                                switchError = null
                                                val ok = runCatching {
                                                    withContext(Dispatchers.IO) {
                                                        client.switchMirror(item.url)
                                                    }
                                                }.getOrDefault(false)
                                                switchingUrl = null
                                                if (ok) {
                                                    // T17：snackbar 展示 host（设置页/搜索页都按 host 显示），
                                                    // 切换定位仍用这一行的实际 URL。
                                                    onSwitched(zlibMirrorHost(item.url))
                                                    onDismiss()
                                                } else {
                                                    // T17：失败不清空列表；刷新 rows 保证仍能改选。
                                                    switchError = "线路切换失败，请重试"
                                                    rows = runCatching {
                                                        withContext(Dispatchers.IO) { client.mirrorRows() }
                                                    }.getOrNull() ?: rows
                                                }
                                            }
                                        }
                                    },
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null, enabled = switchingUrl == null)
                            Spacer(Modifier.width(Spacing.sm))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.host.ifEmpty { zlibMirrorHost(item.url) },
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
private fun zlibMirrorMeta(item: ZMirrorItem): String {
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
