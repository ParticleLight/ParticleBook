package app.particlebook.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 设置：分组 ListItem 列表，不用卡片包裹行、不用 leading 图标。
 * 修改即时生效，没有保存按钮。
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
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            item {
                SectionHeader("阅读")
                ListItem(
                    headlineContent = { Text("字号") },
                    supportingContent = { Text("正文排版字号") },
                    trailingContent = { ValueTrailing(value = fontSize.toString() + " sp", showChevron = true) },
                    modifier = Modifier.clickable { fontSheetOpen = true },
                )
                ListItem(
                    headlineContent = { Text("行距") },
                    supportingContent = { Text("正文行高倍数") },
                    trailingContent = {
                        ValueTrailing(value = formatLineHeight(lineHeight), showChevron = true)
                    },
                    modifier = Modifier.clickable { lineHeightSheetOpen = true },
                )
                ListItem(
                    headlineContent = { Text("默认排序") },
                    supportingContent = { Text("打开书架时的排序方式") },
                    trailingContent = { ValueTrailing(value = defaultSort.label, showChevron = true) },
                    modifier = Modifier.clickable { sortSheetOpen = true },
                )
            }

            item {
                SectionHeader("外观")
                ListItem(
                    headlineContent = { Text("主题") },
                    supportingContent = { Text("深色模式跟随系统，或手动指定") },
                )
                // 200% 字号下三段按钮放不下（label 换行会溢出胶囊），降级为单选列表行（§14.5 硬性要求）。
                if (largeFontScale) {
                    Column(Modifier.fillMaxWidth()) {
                        AppThemeMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(
                                        selected = themeMode == mode,
                                        role = Role.RadioButton,
                                        onClick = { onThemeModeChange(mode) },
                                    )
                                    .padding(horizontal = Spacing.screen),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = themeMode == mode,
                                    onClick = null,
                                )
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
                            .padding(horizontal = Spacing.screen),
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

            item {
                SectionHeader("书库")
                ListItem(
                    headlineContent = { Text("导入本地文件") },
                    supportingContent = { Text("EPUB / PDF，从系统文件选择器添加") },
                    trailingContent = { ValueTrailing(value = "", showChevron = true) },
                    modifier = Modifier.clickable(onClick = onImportClick),
                )
                ListItem(
                    headlineContent = { Text("存储占用") },
                    trailingContent = { ValueTrailing(value = storageLabel) },
                )
                ListItem(
                    headlineContent = { Text("书源管理") },
                    supportingContent = { Text("后续版本") },
                )
            }

            item {
                SectionHeader("数据")
                ListItem(
                    headlineContent = { Text("阅读统计") },
                    supportingContent = { Text("后续版本") },
                )
                ListItem(
                    headlineContent = {
                        Text("清除阅读记录", color = MaterialTheme.colorScheme.error)
                    },
                    supportingContent = { Text("移除所有书籍的阅读进度") },
                    modifier = Modifier.clickable { clearDialogOpen = true },
                )
            }

            item {
                SectionHeader("关于")
                ListItem(
                    headlineContent = { Text("版本") },
                    trailingContent = { ValueTrailing(value = versionName) },
                )
                ListItem(
                    headlineContent = { Text("更新日志") },
                    supportingContent = { Text("后续版本") },
                )
                ListItem(
                    headlineContent = { Text("开源许可") },
                    supportingContent = { Text("后续版本") },
                )
            }
        }
    }

    if (fontSheetOpen) {
        ModalBottomSheet(onDismissRequest = { fontSheetOpen = false }) {
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
                        lineHeight = (fontSize * 1.7f).sp,
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
        ModalBottomSheet(onDismissRequest = { lineHeightSheetOpen = false }) {
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
        ModalBottomSheet(onDismissRequest = { sortSheetOpen = false }) {
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
                        RadioButton(
                            selected = defaultSort == option,
                            onClick = null,
                            enabled = enabled,
                        )
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
}

/** 行距可选项：4 档，够用且不会在 200% 字号下溢出。 */
private val LineHeightOptions = listOf(1.3f, 1.5f, 1.7f, 2.0f)

/** 行距显示成「1.7」而不是「1.700000」。 */
private fun formatLineHeight(value: Float): String = String.format(Locale.US, "%.1f", value)

/** 行尾值：当前值 + 可选 chevron。 */
@Composable
private fun ValueTrailing(value: String, showChevron: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (value.isNotEmpty()) {
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