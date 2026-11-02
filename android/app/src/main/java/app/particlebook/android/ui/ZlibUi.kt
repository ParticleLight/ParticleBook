package app.particlebook.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.zlib.ZlibClient
import app.particlebook.android.zlib.ZlibException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import kotlin.math.roundToInt

/**
 * P1b 错误文案（spec §3.5/§4）：
 * 用 kind.name 做字符串匹配，不硬编码枚举常量——网络层新增 DAILY_LIMIT / LOGIN_REQUIRED / DOWNLOAD
 * 时 UI 不需要同步发版，未知 kind 回退到异常 message。
 */
object ZlibUiError {

    fun message(throwable: Throwable?): String {
        if (throwable == null) return "操作失败，请稍后重试。"
        val kind = (throwable as? ZlibException)?.kind?.name
        return when (kind) {
            "NO_MIRROR" -> "所有 Z-Library 镜像暂时不可达，请稍后重试或手动切换线路"
            "DAILY_LIMIT" -> "今日下载额度已用完，请登录账号后重试"
            "LOGIN_REQUIRED" -> "请先登录 Z-Library"
            "CHALLENGE_FAILED" -> "反爬验证未通过，请稍后重试"
            "HTTP" -> "服务器返回错误，请稍后重试"
            "PARSE" -> "页面解析失败：镜像可能已改版"
            "NETWORK" -> "网络连接异常，请检查网络后重试"
            "DOWNLOAD" -> "下载失败，请稍后重试"
            else -> throwable.message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试。"
        }
    }

    /** 登录失败文案：ZLoginResult.Failed.Kind 同样用 name 匹配。 */
    fun loginFailed(kindName: String?, pageMessage: String?): String = when (kindName) {
        // 站点原文是英文（"Incorrect email or password"），验收要求中文文案。
        "BAD_CREDENTIALS" -> "邮箱或密码不正确"
        "CHALLENGE_FAILED" -> "反爬验证未通过，请稍后重试"
        "HTTP" -> "服务器返回错误，请稍后重试"
        "NETWORK" -> "网络连接异常，请检查网络后重试"
        "PARSE" -> "登录结果解析失败，请稍后重试"
        else -> pageMessage?.takeIf { it.isNotBlank() } ?: "登录失败，请稍后重试"
    }
}

/** 镜像 URL → 展示用主域（去掉 scheme 与路径）。 */
fun zlibMirrorHost(url: String): String {
    val raw = url.trim()
    if (raw.isEmpty()) return raw
    return runCatching { URI(raw).host }.getOrNull() ?: raw
}

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
                if (!active) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭下载卡片")
                    }
                }
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

/** 图片封面（详情页）：bitmap 就绪前只显示 primaryContainer 占位，不闪烁。 */
@Composable
fun ZlibCoverImage(
    bitmap: Bitmap?,
    title: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.chip),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "《" + title + "》封面",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title.firstOrNull()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** 线路切换弹层（spec §4.7）：加载 mirrorInfo → 单选列表 → switchMirror。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZlibMirrorSheet(
    client: ZlibClient,
    onDismiss: () -> Unit,
    onSwitched: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<app.particlebook.android.zlib.ZMirrorInfo?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var switchingIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { client.mirrorInfo() } }
            .onSuccess { info = it }
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
                        // mirrors 下标 0 起，ZMirrorInfo.index 1 起。
                        val selected = current.index > 0 && index == current.index - 1
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
                            Text(
                                text = zlibMirrorHost(mirror),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}
