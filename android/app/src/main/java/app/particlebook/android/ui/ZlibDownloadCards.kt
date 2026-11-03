package app.particlebook.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** U6：全局叠层最多同时显示 2 张卡片，其余收进「查看全部」（下载记录页）。 */
private const val MAX_OVERLAY_CARDS = 2

/**
 * U6/U9/U3：全局下载卡片叠层，由 AppRoot 只在主 tab（书架/发现/设置）渲染。
 *
 * 排序：进行中 > 失败 > 已取消 > 已完成（完成卡 2.5s 后由 Manager 自动移除），
 * 最多 [MAX_OVERLAY_CARDS] 张；多余任务收成一行「还有 N 个 · 查看全部」。
 */
@Composable
fun ZlibDownloadCardsOverlay(
    tasks: List<ZlibDownloadTask>,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onReimport: (String) -> Unit,
    onLogin: () -> Unit,
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tasks.isEmpty()) return
    val ordered = tasks.sortedBy { zlibDownloadCardRank(it) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            // spec §4.4：卡片宽 fill - 24dp（左右各 12）。
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        ordered.take(MAX_OVERLAY_CARDS).forEach { task ->
            ZlibDownloadCard(
                task = task,
                onCancel = { onCancel(task.id) },
                onRetry = { onRetry(task.id) },
                onDismiss = { onDismiss(task.id) },
                onDiscard = { onDiscard(task.id) },
                onReimport = { onReimport(task.id) },
                onLogin = onLogin,
            )
        }
        val hidden = ordered.size - MAX_OVERLAY_CARDS
        if (hidden > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onViewAll) {
                    Text("还有 " + hidden + " 个 · 查看全部")
                }
            }
        }
    }
}

/**
 * U3：下载记录页页内的「正在下载」区块（记录页只看 history，进行中的任务需要额外展示）。
 * 没有进行中的任务时不渲染任何内容。
 */
@Composable
fun ZlibActiveDownloadsSection(
    tasks: List<ZlibDownloadTask>,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onReimport: (String) -> Unit,
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = tasks.filter { it.isActive }
    if (active.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = "正在下载 " + active.size,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
        )
        active.forEach { task ->
            ZlibDownloadCard(
                task = task,
                onCancel = { onCancel(task.id) },
                onRetry = { onRetry(task.id) },
                onDismiss = {},
                onDiscard = { onDiscard(task.id) },
                onReimport = { onReimport(task.id) },
                onLogin = onLogin,
            )
        }
    }
}

/** 卡片优先级：数字越小越靠上（进行中 > 导入中 > 失败 > 已取消 > 已完成）。 */
private fun zlibDownloadCardRank(task: ZlibDownloadTask): Int = when {
    task.isActive && !task.importing -> 0
    task.isActive -> 1
    task.status == ZlibDownloadStatus.FAILED -> 2
    task.status == ZlibDownloadStatus.CANCELLED -> 3
    else -> 4
}

/** 单张下载卡片（spec §4.4）：标题 + 进度/文案 + 3dp 进度条 + 按状态收敛的动作。 */
@Composable
private fun ZlibDownloadCard(
    task: ZlibDownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
    onReimport: () -> Unit,
    onLogin: () -> Unit,
) {
    val active = task.isActive
    val title = when (task.status) {
        ZlibDownloadStatus.PREPARING -> "准备下载 · " + task.fileName
        ZlibDownloadStatus.DOWNLOADING ->
            if (task.importing) "下载完成 · " + task.fileName else "下载中 · " + task.fileName
        ZlibDownloadStatus.COMPLETED -> "已加入书架 · " + task.fileName
        ZlibDownloadStatus.FAILED -> "下载失败 · " + task.fileName
        ZlibDownloadStatus.CANCELLED -> "已取消 · " + task.fileName
    }
    // 出错/取消类文案用错误色；导入中保持常规色。
    val lineColor = when {
        task.status == ZlibDownloadStatus.FAILED && !task.cancelling -> MaterialTheme.colorScheme.error
        task.status == ZlibDownloadStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.cover),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = statusLine(task),
                        style = MaterialTheme.typography.bodySmall,
                        color = lineColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // U4：导入中不再显示下载进度条（字节已下完），避免「满格但还在转」的误解。
            if (active && !task.importing) {
                Spacer(Modifier.height(Spacing.xs))
                val progress = task.progress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                    )
                }
            }
            cardActions(task = task, onCancel = onCancel, onRetry = onRetry, onDismiss = onDismiss, onDiscard = onDiscard, onReimport = onReimport, onLogin = onLogin)
        }
    }
}

/** 动作区：进行中给「取消」；失败按 errorKind 分流；取消态给「续传/删除」。 */
@Composable
private fun cardActions(
    task: ZlibDownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
    onReimport: () -> Unit,
    onLogin: () -> Unit,
) {
    when {
        task.cancelling -> {
            // 取消已发出、等协程收尾：不再给第二个按钮，避免连点。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "正在取消…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        task.importing -> Unit
        task.isActive -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
        task.status == ZlibDownloadStatus.CANCELLED -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRetry) { Text("续传") }
                TextButton(onClick = onDiscard) { Text("删除") }
            }
        }
        task.status == ZlibDownloadStatus.FAILED && task.errorKind == ZLIB_IMPORT_FAILED_KIND -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onReimport) { Text("重新导入") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
        task.status == ZlibDownloadStatus.FAILED && zlibFailureNeedsLogin(task.errorKind) -> {
            // U9：登录态/额度类失败重试必然再失败，给「去登录」。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onLogin) { Text("去登录") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
        task.status == ZlibDownloadStatus.FAILED -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRetry) { Text("重试") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
        else -> Unit
    }
}

private fun statusLine(task: ZlibDownloadTask): String = when (task.status) {
    ZlibDownloadStatus.PREPARING -> "正在准备…"
    ZlibDownloadStatus.DOWNLOADING -> {
        if (task.importing) {
            "正在加入书架…"
        } else if (task.cancelling) {
            "正在取消…"
        } else {
            val progress = task.progress
            if (progress != null) {
                (progress * 100f).roundToInt().coerceIn(0, 100).toString() + "% · " +
                    formatDownloadBytes(task.received) + " / " + formatDownloadBytes(task.total)
            } else {
                "已下载 " + formatDownloadBytes(task.received)
            }
        }
    }
    ZlibDownloadStatus.COMPLETED -> "已自动导入书架"
    ZlibDownloadStatus.FAILED -> task.errorMessage ?: "下载失败，请重试"
    ZlibDownloadStatus.CANCELLED -> "已取消，可点「续传」继续下载"
}

/** 字节数 → 人类可读（下载卡片/记录页共用）。 */
fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.1f MB", mb)
}
