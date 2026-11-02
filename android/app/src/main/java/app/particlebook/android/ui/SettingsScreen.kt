package app.particlebook.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.particlebook.android.zlib.ZAccount
import app.particlebook.android.zlib.ZMirrorInfo
import app.particlebook.android.zlib.ZlibClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 设置（§17.4）：34sp Serif 大标题 + 分组容器（28dp 圆角 / 1dp outlineVariant 边框 / 行高 ≥64dp），
 * 行内不用 Card、不用 leading 图标，跳转行右侧是「当前值 + chevron」。修改即时生效，没有保存按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    lineHeight: Float,
    onLineHeightChange: (Float) -> Unit,
    defaultSort: LibrarySort,
    onDefaultSortChange: (LibrarySort) -> Unit,
    onImportClick: () -> Unit,
    onClearReadingRecords: () -> Unit,
    account: ZAccount?,
    onLoginClick: () -> Unit,
    onLogout: () -> Unit,
    onDownloadsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val client = remember { ZlibClient.get(context.applicationContext) }
    var mirrorInfo by remember { mutableStateOf<ZMirrorInfo?>(null) }
    var mirrorSheetOpen by rememberSaveable { mutableStateOf(false) }
    var logoutDialogOpen by rememberSaveable { mutableStateOf(false) }
    val downloadDir = remember {
        ZlibDownloadManager.downloadDir(context.applicationContext).absolutePath
    }
    LaunchedEffect(Unit) {
        mirrorInfo = withContext(Dispatchers.IO) {
            runCatching { client.mirrorInfo() }.getOrNull()
        }
    }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }
    var storageBytes by remember { mutableLongStateOf(0L) }
    var fontSheetOpen by rememberSaveable { mutableStateOf(false) }
    var lineHeightSheetOpen by rememberSaveable { mutableStateOf(false) }
    var sortSheetOpen by rememberSaveable { mutableStateOf(false) }
    var clearDialogOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        storageBytes = withContext(Dispatchers.IO) {
            File(context.filesDir, "_pb_files")
                .walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        }
    }
    val storageLabel = if (storageBytes <= 0L) {
        "—"
    } else {
        String.format(Locale.US, "%.1f MB", storageBytes / 1024.0 / 1024.0)
    }
    // 字体放大到 1.4 倍以上时，三段按钮的三个中文 label 会横向放不下 → 换单选列表。
    val largeFontScale = LocalDensity.current.fontScale >= 1.4f

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)),
            // 底部留出悬浮胶囊导航的位置（64 + 12 + 系统栏），免得最后一组被压住。
            contentPadding = PaddingValues(bottom = 132.dp),
        ) {
            item {
                Text(
                    text = "设置",
                    style = AppType.pageTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        start = Spacing.screen + 4.dp,
                        top = Spacing.md,
                        bottom = Spacing.xs,
                    ),
                )
            }

            item {
                SettingsGroup(title = "阅读") {
                    SettingsRow(
                        title = "字号",
                        support = "EPUB 正文字号（PDF 保持原版式）",
                        value = fontSize.toString() + " sp",
                        showChevron = true,
                        onClick = { fontSheetOpen = true },
                    )
                    SettingsRow(
                        title = "行距",
                        support = "EPUB 正文行距（PDF 保持原版式）",
                        value = formatLineHeight(lineHeight),
                        showChevron = true,
                        onClick = { lineHeightSheetOpen = true },
                    )
                    SettingsRow(
                        title = "默认排序",
                        support = "打开书架时的排序方式",
                        value = defaultSort.label,
                        showChevron = true,
                        onClick = { sortSheetOpen = true },
                    )
                }
            }

            item {
                SettingsGroup(title = "外观") {
                    SettingsRow(
                        title = "主题",
                        support = "深色模式跟随系统，或手动指定",
                    )
                    // 200% 字号下三段按钮放不下（label 换行会溢出胶囊），降级为单选列表行（§14.5 硬性要求）。
                    if (largeFontScale) {
                        Column(Modifier.fillMaxWidth()) {
                            AppThemeMode.entries.forEach { mode ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .selectable(
                                            selected = themeMode == mode,
                                            role = Role.RadioButton,
                                            onClick = { onThemeModeChange(mode) },
                                        )
                                        .padding(horizontal = 20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = themeMode == mode, onClick = null)
                                    Spacer(Modifier.size(Spacing.sm))
                                    Text(
                                        text = mode.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                        }
                    } else {
                        SingleChoiceSegmentedButtonRow(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 20.dp, bottom = Spacing.md),
                        ) {
                            AppThemeMode.entries.forEachIndexed { index, mode ->
                                SegmentedButton(
                                    selected = themeMode == mode,
                                    onClick = { onThemeModeChange(mode) },
                                    shape = SegmentedButtonDefaults.itemShape(
                                        index = index,
                                        count = AppThemeMode.entries.size,
                                    ),
                                ) {
                                    Text(
                                        text = mode.label,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingsGroup(title = "书库") {
                    SettingsRow(
                        title = "导入本地文件",
                        support = "EPUB / PDF，从系统文件选择器添加",
                        showChevron = true,
                        onClick = onImportClick,
                    )
                    SettingsRow(title = "存储占用", value = storageLabel)
                    SettingsRow(title = "书源管理", support = "后续版本")
                }
            }

            item {
                SettingsGroup(title = "Z-Library") {
                    SettingsRow(
                        title = "账号",
                        support = if (account == null) {
                            "登录后可下载更多书籍"
                        } else {
                            "已登录"
                        },
                        value = account?.email?.take(24) ?: "登录",
                        showChevron = true,
                        onClick = {
                            if (account == null) {
                                onLoginClick()
                            } else {
                                logoutDialogOpen = true
                            }
                        },
                    )
                    SettingsRow(
                        title = "下载目录",
                        support = downloadDir,
                    )
                    SettingsRow(
                        title = "下载记录",
                        showChevron = true,
                        onClick = onDownloadsClick,
                    )
                    SettingsRow(
                        title = "线路",
                        value = mirrorInfo?.let { "线路 " + it.index + " / " + it.total } ?: "—",
                        showChevron = true,
                        onClick = { mirrorSheetOpen = true },
                    )
                    SettingsRow(
                        title = "说明",
                        support = "下载的书籍会自动导入书架；线路不通时可手动切换。",
                    )
                }
            }

            item {
                SettingsGroup(title = "数据") {
                    SettingsRow(title = "阅读统计", support = "后续版本")
                    SettingsRow(
                        title = "清除阅读记录",
                        support = "移除所有书籍的阅读进度",
                        destructive = true,
                        onClick = { clearDialogOpen = true },
                    )
                }
            }

            item {
                SettingsGroup(title = "关于") {
                    SettingsRow(title = "版本", value = versionName)
                    SettingsRow(title = "更新日志", support = "后续版本")
                    SettingsRow(title = "开源许可", support = "后续版本")
                }
            }
        }
    }

    if (fontSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { fontSheetOpen = false },
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screen),
            ) {
                Text("字号", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(Spacing.xs))
                Text(
                    text = "故乡的河里，有一条小小的船……",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = fontSize.sp,
                        // D6：预览行距跟随当前「行距」设置，和行距 sheet 保持一致。
                        lineHeight = (fontSize * lineHeight).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(Spacing.xs))
                Slider(
                    value = fontSize.toFloat(),
                    onValueChange = { onFontSizeChange(it.roundToInt()) },
                    valueRange = 14f..26f,
                    steps = 11,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    FilledTonalButton(onClick = { fontSheetOpen = false }) { Text("完成") }
                }
                Spacer(Modifier.size(Spacing.sm))
            }
        }
    }

    if (lineHeightSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { lineHeightSheetOpen = false },
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screen),
            ) {
                Text("行距", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(Spacing.xs))
                Text(
                    text = "故乡的河里，有一条小小的船……",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * lineHeight).sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(Spacing.xs))
                LineHeightOptions.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = lineHeight == option,
                                role = Role.RadioButton,
                                onClick = { onLineHeightChange(option) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = lineHeight == option, onClick = null)
                        Spacer(Modifier.size(Spacing.sm))
                        Text(
                            text = formatLineHeight(option) + " 倍",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    FilledTonalButton(onClick = { lineHeightSheetOpen = false }) { Text("完成") }
                }
                Spacer(Modifier.size(Spacing.sm))
            }
        }
    }

    if (sortSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sortSheetOpen = false },
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.screen),
            ) {
                Text("默认排序", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(Spacing.xs))
                LibrarySort.entries.forEach { option ->
                    val enabled = option != LibrarySort.Rating
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = defaultSort == option,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onDefaultSortChange(option) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = defaultSort == option, onClick = null, enabled = enabled)
                        Spacer(Modifier.size(Spacing.sm))
                        Text(
                            text = option.label + if (enabled) "" else "（后续版本）",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    FilledTonalButton(onClick = { sortSheetOpen = false }) { Text("完成") }
                }
                Spacer(Modifier.size(Spacing.sm))
            }
        }
    }

    if (clearDialogOpen) {
        AlertDialog(
            onDismissRequest = { clearDialogOpen = false },
            title = { Text("清除阅读记录？") },
            text = { Text("会移除所有书籍的阅读进度，此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearDialogOpen = false
                        onClearReadingRecords()
                    },
                ) {
                    Text("清除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearDialogOpen = false }) { Text("取消") }
            },
        )
    }

    if (logoutDialogOpen) {
        AlertDialog(
            onDismissRequest = { logoutDialogOpen = false },
            title = { Text("退出登录？") },
            text = { Text("退出后可以随时重新登录；已下载的书仍保留在书架。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        logoutDialogOpen = false
                        onLogout()
                        scope.launch { snackbarHostState.showSnackbar("已退出登录") }
                    },
                ) {
                    Text("退出登录", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { logoutDialogOpen = false }) { Text("取消") }
            },
        )
    }

    if (mirrorSheetOpen) {
        ZlibMirrorSheet(
            client = client,
            onDismiss = { mirrorSheetOpen = false },
            onSwitched = { host ->
                mirrorSheetOpen = false
                scope.launch {
                    snackbarHostState.showSnackbar("已切换到 " + host)
                    mirrorInfo = withContext(Dispatchers.IO) {
                        runCatching { client.mirrorInfo() }.getOrNull()
                    }
                }
            },
        )
    }
}

/** 分组容器（§17.4）：28dp 圆角 + 1dp outlineVariant 边框，内部行之间不用分割线。 */
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = Spacing.lg, bottom = Spacing.xs),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.group),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(vertical = 4.dp), content = content)
        }
    }
}

/** 设置行（§17.4）：高 ≥64dp、左右 20dp，标题 bodyLarge、说明 bodySmall、值 bodyMedium + 20dp chevron。 */
@Composable
private fun SettingsRow(
    title: String,
    support: String? = null,
    value: String? = null,
    showChevron: Boolean = false,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (support == null) 60.dp else 64.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (support != null) {
                Text(
                    text = support,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (showChevron) {
            Spacer(Modifier.size(Spacing.xxs))
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 行距可选项：4 档，够用且不会在 200% 字号下溢出。 */
private val LineHeightOptions = listOf(1.3f, 1.5f, 1.7f, 2.0f)

/** 行距显示成「1.7」而不是「1.700000」。 */
private fun formatLineHeight(value: Float): String = String.format(Locale.US, "%.1f", value)
