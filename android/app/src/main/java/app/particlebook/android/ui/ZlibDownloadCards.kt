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

/** 全局下载卡片叠层：由 AppRoot 放在 Scaffold 上层（距底 96dp，悬浮胶囊之上）。 */
@Composable
fun ZlibDownloadCardsOverlay(
    tasks: List<ZlibDownloadTask>,
    onRetry: (String) -> Unit,
    onDismiss: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tasks.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            // spec §4.4：卡片宽 fill - 24dp（左右各 12）。
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        tasks.forEach { task ->
            ZlibDownloadCard(
                task = task,
                onRetry = { onRetry(task.id) },
                onDismiss = { onDismiss(task.id) },
            )
        }
    }
}

/** 单张下载卡片（spec §4.4）：标题 + 进度/文案 + 3dp 进度条，失败常驻给重试/关闭。 */
@Composable
private fun ZlibDownloadCard(
    task: ZlibDownloadTask,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val active = task.isActive
    val title = when (task.status) {
        ZlibDownloadStatus.PREPARING -> "准备下载 · " + task.fileName
        ZlibDownloadStatus.DOWNLOADING -> "下载中 · " + task.fileName
        ZlibDownloadStatus.COMPLETED -> "下载完成 · " + task.fileName
        ZlibDownloadStatus.FAILED -> "下载失败 · " + task.fileName
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
                        color = if (task.status == ZlibDownloadStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // P1c P2-4：失败/完成卡的动作收敛为「重试 / 关闭」两个 TextButton（见下方），
                // 去掉右上角 X，避免同一「关闭」操作出现两个入口。
            }
            if (active) {
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
            if (task.status == ZlibDownloadStatus.FAILED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onRetry) { Text("重试") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

private fun statusLine(task: ZlibDownloadTask): String = when (task.status) {
    ZlibDownloadStatus.PREPARING -> "正在准备…"
    ZlibDownloadStatus.DOWNLOADING -> {
        val progress = task.progress
        if (progress != null) {
            (progress * 100f).roundToInt().coerceIn(0, 100).toString() + "% · " +
                formatDownloadBytes(task.received) + " / " + formatDownloadBytes(task.total)
        } else {
            "已下载 " + formatDownloadBytes(task.received)
        }
    }
    ZlibDownloadStatus.COMPLETED -> "已自动导入书架"
    ZlibDownloadStatus.FAILED -> task.errorMessage ?: "下载失败，请重试"
}

/** 字节数 → 人类可读（下载卡片/记录页共用）。 */
fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.1f MB", mb)
}
