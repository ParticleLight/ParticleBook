package app.particlebook.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.particlebook.android.platform.AndroidSecretStore
import app.particlebook.android.settings.SettingsExportFile
import app.particlebook.android.settings.SettingsSecretBridge
import app.particlebook.android.settings.SettingsStore
import app.particlebook.android.settings.SharedPreferencesSettingsStorage
import app.particlebook.android.settings.TranslationKeyController
import app.particlebook.android.settings.TranslationKeySection
import app.particlebook.android.ui.data.DownloadLocation
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
 *
 * 2026-11 组件基线：Segmented button 已废弃（component-selection.md「Deprecated map」、
 * m3-expressive.md「Deprecated baselines」），替代品 connected button group 在 material3 1.4.0
 * 没有公开组件；「互斥且全部可见」的选择按同一份 reference 的「Selection and toggles」首选
 * RadioButton。主题选择因此是单选列表，不再有分段按钮分支。
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
    // A023-5：恢复设置后把 AppRoot 的 rememberSaveable 状态重新读一遍，
    // 否则当前页仍显示恢复前的字号/行高（prefs 已更新，但 Compose 状态没变）。
    onSettingsRestored: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // A023-3/4：翻译 Key 只走 Android Keystore；SettingsSecretBridge 固定 translation.apiKey 槽位。
    val translationKeyController = remember {
        TranslationKeyController(
            SettingsSecretBridge(AndroidSecretStore(context.applicationContext)),
        )
    }
    // A023-5：设置导出/恢复入口。只写 filesDir/settings-export.json，不含翻译 Key；
    // 完整备份/恢复 UI 仍属 P3，这里只覆盖设置 JSON 的往返。
    val settingsStore = remember {
        SettingsStore(SharedPreferencesSettingsStorage.from(context.applicationContext))
    }
    val settingsExportFile = remember { SettingsExportFile(context.filesDir) }
    val client = remember { ZlibClient.get(context.applicationContext) }
    var mirrorInfo by remember { mutableStateOf<ZMirrorInfo?>(null) }
    // 线路信息是网络请求：null 既可能是"没查到"也可能是"还没查完"，界面上必须分开表达，
    // 否则用户看到的是永久「—」，分不清是空还是失败。
    var mirrorReady by remember { mutableStateOf(false) }
    var mirrorSheetOpen by rememberSaveable { mutableStateOf(false) }
    var logoutDialogOpen by rememberSaveable { mutableStateOf(false) }
    // P1c：下载目录可在默认目录与用户选择的 SAF 文件夹之间切换（选择结果持久化在 DownloadLocation）。
    var downloadLabel by remember {
        mutableStateOf(DownloadLocation.summary(context.applicationContext))
    }
    // 自定义下载目录是二态设置：关 = 默认目录，开 = 用户选的 SAF 文件夹。
    // 开关只是 DownloadLocation 既有状态的镜像，写入仍走 setTree / reset 两条原路径。
    var customDownloadDir by remember {
        mutableStateOf(DownloadLocation.isCustom(context.applicationContext))
    }
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // P2-2：只有授权 + 可写校验都通过才保存；失败时目录不变，提示也必须说清「未更改」。
        val accepted = DownloadLocation.setTree(context.applicationContext, uri)
        downloadLabel = DownloadLocation.summary(context.applicationContext)
        customDownloadDir = DownloadLocation.isCustom(context.applicationContext)
        scope.launch {
            snackbarHostState.showSnackbar(
                if (accepted) {
                    "已切换下载目录"
                } else {
                    "无法使用该文件夹（需要可写权限），下载目录未更改"
                },
            )
        }
    }
    LaunchedEffect(Unit) {
        // P2-2：启动校验持久授权；失效时回默认目录并提示重选，避免下载一直写只读目录。
        if (DownloadLocation.validateStoredTree(context.applicationContext)) {
            downloadLabel = DownloadLocation.summary(context.applicationContext)
            snackbarHostState.showSnackbar("下载目录授权已失效，已恢复默认目录，请重新选择")
        }
        mirrorInfo = withContext(Dispatchers.IO) {
            runCatching { client.mirrorInfo() }.getOrNull()
        }
        mirrorReady = true
    }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }
    var storageBytes by remember { mutableLongStateOf(0L) }
    // 目录遍历是 IO：读完才敢说"0 MB"，否则「0.0 MB」会被当成"没占用"。
    var storageReady by remember { mutableStateOf(false) }
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
        storageReady = true
    }
    val storageLabel = if (storageBytes <= 0L) {
        "—"
    } else {
        String.format(Locale.US, "%.1f MB", storageBytes / 1024.0 / 1024.0)
    }
    // 字体放大到 1.4 倍以上时底部胶囊变高（72dp），列表留白按 large 档取。
    // 注意：这与主题选择控件无关——主题始终是单选列表，不再有「大字号才降级」的分支。
    val largeFontScale = LocalDensity.current.fontScale >= 1.4f
    // P0-1 / P2-8：设置页没有 bottomBar：内容直接到屏底，底留白须自己盖过胶囊上沿（124dp；200% 132dp）。
    val bottomInset = if (largeFontScale) 140.dp else Spacing.listBottomInset + Spacing.xs

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // P1c：设置页有悬浮胶囊，Snackbar 底边落在胶囊上沿 + 8dp（已含导航条）。
        snackbarHost = { AppSnackbarHost(snackbarHostState, aboveCapsule = true) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)),
            contentPadding = PaddingValues(bottom = bottomInset),
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
                    // 2026-11 组件基线：Segmented button 已废弃，替代品 connected button group 在
                    // material3 1.4.0 没有公开组件；「互斥且全部可见」的选择按 component-selection.md
                    // 的「Selection and toggles」首选 RadioButton。因此这里不再按字号分叉——
                    // 任何字号（含 200%）都用同一份单选列表，标题与说明都允许换行，不做截断。
                    AppThemeMode.entries.forEach { mode ->
                        ThemeModeRow(
                            mode = mode,
                            selected = themeMode == mode,
                            onSelect = { onThemeModeChange(mode) },
                        )
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
                    SettingsRow(
                        title = "存储占用",
                        value = if (storageReady) storageLabel else null,
                        loading = !storageReady,
                    )
                    // 后续版本：行保留在信息层级里，但明确按禁用态渲染，不做成"点了没反应"。
                    SettingsRow(title = "书源管理", support = "后续版本", enabled = false)
                }
            }

            item {
                TranslationKeySection(translationKeyController)
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
                    // 二态设置用 Switch 表达：开 = 自己选目录，关 = 默认目录。
                    // 关掉时不弹确认框——目录只是回到默认位置，已下载的书不受影响。
                    SettingsSwitchRow(
                        title = "使用自定义下载目录",
                        support = if (customDownloadDir) {
                            "当前：" + downloadLabel
                        } else {
                            "默认目录：" + DownloadLocation.defaultDir(context.applicationContext)
                                .absolutePath
                        },
                        checked = customDownloadDir,
                        onCheckedChange = { useCustom ->
                            if (useCustom) {
                                treeLauncher.launch(null)
                            } else {
                                DownloadLocation.reset(context.applicationContext)
                                downloadLabel =
                                    DownloadLocation.summary(context.applicationContext)
                                customDownloadDir = false
                                scope.launch {
                                    snackbarHostState.showSnackbar("已恢复默认下载目录")
                                }
                            }
                        },
                    )
                    SettingsRow(
                        title = "选择文件夹",
                        support = downloadLabel,
                        showChevron = true,
                        onClick = { treeLauncher.launch(null) },
                    )
                    SettingsRow(
                        title = "下载记录",
                        showChevron = true,
                        onClick = onDownloadsClick,
                    )
                    SettingsRow(
                        title = "线路",
                        value = mirrorInfo?.let { "线路 " + it.index + " / " + it.total }
                            ?: if (mirrorReady) "未检测到" else null,
                        loading = !mirrorReady,
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
                    SettingsRow(
                        title = "导出设置",
                        support = "写入 settings-export.json；不含翻译 Key",
                        showChevron = true,
                        onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { settingsExportFile.write(settingsStore) }
                                }
                                result.onSuccess { file ->
                                    snackbarHostState.showSnackbar("已导出到 " + file.absolutePath)
                                }.onFailure { error ->
                                    snackbarHostState.showSnackbar(
                                        "导出失败：" + (error.message ?: "未知错误"),
                                    )
                                }
                            }
                        },
                    )
                    SettingsRow(
                        title = "恢复设置",
                        support = "从 settings-export.json 恢复；翻译 Key 需重新输入",
                        showChevron = true,
                        onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { settingsExportFile.restore(settingsStore) }
                                }
                                result.onSuccess { imported ->
                                    onSettingsRestored()
                                    snackbarHostState.showSnackbar(
                                        "已恢复 " + imported.importedKeyCount + " 个全局键、" +
                                            imported.bookValues.size + " 本书的覆盖值",
                                    )
                                }.onFailure { error ->
                                    snackbarHostState.showSnackbar(
                                        "恢复失败：" + (error.message ?: "未知错误"),
                                    )
                                }
                            }
                        },
                    )
                    SettingsRow(title = "阅读统计", support = "后续版本", enabled = false)
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
                    SettingsRow(title = "更新日志", support = "后续版本", enabled = false)
                    SettingsRow(title = "开源许可", support = "后续版本", enabled = false)
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
                // 标题行同时给出当前值：滑块本身不标刻度，不给数字用户就不知道停在几 sp。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.minTouchTarget),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "字号",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = fontSize.toString() + " sp",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                    valueRange = FontSizeRange,
                    steps = FontSizeSteps,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Spacing.minTouchTarget)
                        // 滑块无刻度文案，读屏必须能读到范围与当前值。
                        .semantics {
                            contentDescription = "字号"
                            stateDescription = fontSize.toString() + " sp，范围 " +
                                FontSizeRange.start.roundToInt() + " 到 " +
                                FontSizeRange.endInclusive.roundToInt() + " sp"
                        },
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
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.minTouchTarget)
                            .selectable(
                                selected = lineHeight == option,
                                role = Role.RadioButton,
                                onClick = { onLineHeightChange(option) },
                            )
                            .padding(horizontal = RowExtraHorizontalPadding),
                        colors = settingsRowColors(),
                        headlineContent = {
                            Text(
                                text = formatLineHeight(option) + " 倍",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        trailingContent = {
                            RadioButton(selected = lineHeight == option, onClick = null)
                        },
                    )
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
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Spacing.minTouchTarget)
                            .selectable(
                                selected = defaultSort == option,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onDefaultSortChange(option) },
                            )
                            .padding(horizontal = RowExtraHorizontalPadding),
                        colors = settingsRowColors(),
                        headlineContent = {
                            Text(
                                text = option.label + if (enabled) "" else "（后续版本）",
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                        .copy(alpha = DisabledContentAlpha)
                                },
                            )
                        },
                        trailingContent = {
                            RadioButton(
                                selected = defaultSort == option,
                                onClick = null,
                                enabled = enabled,
                            )
                        },
                    )
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
                    mirrorReady = true
                }
            },
        )
    }
}

/** M3 禁用态内容透明度（规范：on* 角色 38%）。 */
private const val DisabledContentAlpha = 0.38f

/** 行左右内边距：与分组容器 20dp 对齐（列表行不贴边框）。 */
private val RowHorizontalPadding = 20.dp

/** M3 ListItem 自带的左右内边距（1.4.0 内部常量，非构造参数）。 */
private val ListItemInnerPadding = 16.dp

/**
 * ListItem 的左右内边距是私有常量（javap 1.4.0 aar：ListItemStartPadding/EndPadding = 16dp），
 * 构造参数里没有 contentPadding，外面补 4dp 正好到 20dp，和分组容器对齐。
 */
private val RowExtraHorizontalPadding = RowHorizontalPadding - ListItemInnerPadding

/**
 * 分组容器（§17.4）：M3 extraLarge 圆角（28dp）+ 1dp outlineVariant 边框，行之间不用分割线。
 * 段标题用 titleSmall + primary：M3 里"章节名"落在 title 档，比 labelLarge 更有层级感。
 */
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.screen)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                start = Spacing.xxs,
                top = Spacing.lg,
                bottom = Spacing.xs,
            ),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(vertical = Spacing.xxs), content = content)
        }
    }
}

/**
 * 行容器色：分组容器已经铺了 surfaceContainerLow，行再刷一层同色即可，
 * 不用裸 transparent——保持"颜色只从语义角色来"这条规则。
 */
@Composable
private fun settingsRowColors() = ListItemDefaults.colors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
)

/**
 * 主题单选行（2026-11 基线）：整行 [selectable] + [Role.RadioButton]，行尾 [RadioButton] 只做视觉，
 * onSelect 由整行触发——命中区是整行（≥ [Spacing.minTouchTarget]），不依赖 20dp 的小圆点。
 *
 * - 标题 + 说明都不设 maxLines：200% 字号时宁可换行长高，也不 ellipsis 截断（§14.5）。
 * - 单选点放行尾，与同组 Switch / chevron 共用一条右侧控制列，扫描时是一列控件而不是一列圆点。
 */
@Composable
private fun ThemeModeRow(
    mode: AppThemeMode,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = RowExtraHorizontalPadding),
        colors = settingsRowColors(),
        headlineContent = {
            Text(
                text = mode.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Text(
                text = themeModeSupport(mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            RadioButton(selected = selected, onClick = null)
        },
    )
}

/** 主题三档的说明：讲清「跟不跟随系统」，避免用户把「浅色」理解成「跟随系统但当前是浅色」。 */
private fun themeModeSupport(mode: AppThemeMode): String = when (mode) {
    AppThemeMode.System -> "跟随系统设置自动切换深浅色"
    AppThemeMode.Light -> "始终使用浅色，不随系统切换"
    AppThemeMode.Dark -> "始终使用深色，不随系统切换"
}

/**
 * 设置行（§17.4）：M3 [ListItem] 承载信息层级——标题 bodyLarge、说明 bodySmall（onSurfaceVariant）、
 * 当前值 bodyMedium + chevron。命中区 ≥ [Spacing.minTouchTarget]（48dp），两行内容自然更高。
 *
 * 状态齐备：
 * - [enabled] = false：按 M3 禁用内容色渲染，且不挂 clickable（不做成"点了没反应"）；
 * - [loading] = true：行尾换成 16dp 进度指示，代替"没查完就先写 0"的假值。
 */
@Composable
private fun SettingsRow(
    title: String,
    support: String? = null,
    value: String? = null,
    showChevron: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    loading: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val titleColor = when {
        !enabled -> scheme.onSurface.copy(alpha = DisabledContentAlpha)
        destructive -> scheme.error
        else -> scheme.onSurface
    }
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .then(
                if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier,
            )
            .padding(horizontal = RowExtraHorizontalPadding),
        colors = settingsRowColors(),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (support != null) {
            {
                Text(
                    text = support,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            null
        },
        trailingContent = if (loading) {
            { SettingsRowSpinner() }
        } else if (value != null || showChevron) {
            { SettingsRowValue(value = value, showChevron = showChevron, enabled = enabled) }
        } else {
            null
        },
    )
}

/**
 * 二态设置行：M3 [ListItem] + 行尾 [Switch]。
 *
 * 整行 [toggleable]：点标题与点开关是同一个动作（M3 的 switch-row 语义），
 * 命中区 = 整行（≥ [Spacing.minTouchTarget]），不依赖 Switch 本体那点拇指区域。
 * Switch 的 onCheckedChange 传 null，避免同一手势触发两次。
 */
@Composable
private fun SettingsSwitchRow(
    title: String,
    support: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = RowExtraHorizontalPadding),
        colors = settingsRowColors(),
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    scheme.onSurface
                } else {
                    scheme.onSurface.copy(alpha = DisabledContentAlpha)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = if (support != null) {
            {
                Text(
                    text = support,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            null
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        },
    )
}

/** 行尾加载态：16dp 进度指示，和 bodyMedium 的值同高，不改变行高。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsRowSpinner() {
    LoadingIndicator(modifier = Modifier.size(Spacing.md))
}

/** 行尾「当前值 + chevron」；值缺失时只留 chevron。 */
@Composable
private fun SettingsRowValue(value: String?, showChevron: Boolean, enabled: Boolean) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DisabledContentAlpha)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
                maxLines = 1,
            )
        }
        if (showChevron) {
            Spacer(Modifier.size(Spacing.xxs))
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 字号滑块范围与档数（14–26sp，1sp 一档）。 */
private val FontSizeRange = 14f..26f
private const val FontSizeSteps = 11

/** 行距可选项：4 档，够用且不会在 200% 字号下溢出。 */
private val LineHeightOptions = listOf(1.3f, 1.5f, 1.7f, 2.0f)

/** 行距显示成「1.7」而不是「1.700000」。 */
private fun formatLineHeight(value: Float): String = String.format(Locale.US, "%.1f", value)
