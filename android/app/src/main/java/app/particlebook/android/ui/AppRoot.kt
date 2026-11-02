package app.particlebook.android.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import app.particlebook.android.ui.data.LibraryStore
import kotlinx.coroutines.launch

/** 导航两个目的地：书架 / 设置（找书 P0 未实现，FindScreen 保留待 P1 插回）。阅读器是全屏任务页，不在这里。 */
private enum class TopLevel(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Library(
        label = "书架",
        icon = Icons.AutoMirrored.Outlined.MenuBook,
        selectedIcon = Icons.AutoMirrored.Filled.MenuBook,
    ),
    Settings(
        label = "设置",
        icon = Icons.Outlined.Settings,
        selectedIcon = Icons.Filled.Settings,
    ),
}

/** 轻量持久化：主题与字号。UI 状态只有这两项值得跨进程保留。 */
private class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("pb_mobile_ui", Context.MODE_PRIVATE)

    var themeMode: AppThemeMode
        get() = runCatching {
            AppThemeMode.valueOf(
                prefs.getString(KEY_THEME, AppThemeMode.System.name) ?: AppThemeMode.System.name,
            )
        }.getOrDefault(AppThemeMode.System)
        set(value) {
            prefs.edit().putString(KEY_THEME, value.name).apply()
        }

    var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 18)
        set(value) {
            prefs.edit().putInt(KEY_FONT_SIZE, value).apply()
        }

    /** 正文行高倍数（1.3 / 1.5 / 1.7 / 2.0）。 */
    var lineHeight: Float
        get() = prefs.getFloat(KEY_LINE_HEIGHT, 1.7f)
        set(value) {
            prefs.edit().putFloat(KEY_LINE_HEIGHT, value).apply()
        }

    /** 书架默认排序，存 LibrarySort.name。 */
    var defaultSort: String
        get() = prefs.getString(KEY_DEFAULT_SORT, LibrarySort.RecentRead.name)
            ?: LibrarySort.RecentRead.name
        set(value) {
            prefs.edit().putString(KEY_DEFAULT_SORT, value).apply()
        }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_LINE_HEIGHT = "line_height"
        const val KEY_DEFAULT_SORT = "default_sort"
    }
}

/**
 * App 根：竖屏 = 悬浮胶囊导航（书架 / 设置 / 导入）；手机横屏 = 左侧 NavigationRail（A2 几何不变）。
 * 打开书籍时全屏进入阅读器（不带导航），返回时刷新 Hero 的「最近阅读」。
 */
@Composable
fun ParticleBookApp() {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    var themeModeName by rememberSaveable { mutableStateOf(preferences.themeMode.name) }
    val themeMode = AppThemeMode.entries.firstOrNull { it.name == themeModeName } ?: AppThemeMode.System
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var openBookId by rememberSaveable { mutableLongStateOf(-1L) }
    var fontSize by rememberSaveable { mutableIntStateOf(preferences.fontSize) }
    var lineHeight by rememberSaveable { mutableFloatStateOf(preferences.lineHeight) }
    var defaultSortName by rememberSaveable { mutableStateOf(preferences.defaultSort) }
    // 阅读器关闭后 +1，强制书架重算 Hero（SharedPreferences 里的进度不是 Compose 状态）。
    var progressEpoch by remember { mutableIntStateOf(0) }
    val defaultSort = LibrarySort.entries.firstOrNull { it.name == defaultSortName }
        ?: LibrarySort.RecentRead

    val store = remember { LibraryStore(context) }
    LaunchedEffect(Unit) { store.ensureLoaded() }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val books: List<Book> = store.books
    val openBook = remember(books, openBookId) { books.firstOrNull { it.id == openBookId } }

    // 多选状态只在会话内有效（书源本身也还没有持久化）。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var deleteDialogOpen by remember { mutableStateOf(false) }

    LaunchedEffect(books, openBookId) {
        if (openBookId >= 0L && openBook == null) openBookId = -1L
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            store.importDocument(uri)
                .onSuccess { book ->
                    snackbarHostState.showSnackbar("已导入《" + book.title + "》")
                    if (book.format == BookFormat.Pdf) openBookId = book.id
                }
                .onFailure { throwable ->
                    snackbarHostState.showSnackbar("导入失败：" + (throwable.message ?: "未知错误"))
                }
        }
    }
    val launchImport: () -> Unit = {
        importLauncher.launch(
            arrayOf("application/pdf", "application/epub+zip", "application/octet-stream"),
        )
    }

    val confirmDelete: () -> Unit = {
        val ids = selectedIds
        deleteDialogOpen = false
        scope.launch {
            store.removeBooks(ids)
            exitSelection()
            snackbarHostState.showSnackbar("已删除 " + ids.size + " 本书")
        }
    }

    BackHandler(enabled = openBook == null && selectionMode) { exitSelection() }
    BackHandler(enabled = openBook == null && !selectionMode && tabIndex != 0) { tabIndex = 0 }

    ParticleBookTheme(mode = themeMode) {
        AnimatedContent(
            targetState = openBook,
            transitionSpec = {
                // 书架 ↔ 阅读器：缩放 + 淡入淡出（SharedTransitionLayout 没有稳定 API，spec 允许的降级方案）。
                if (targetState != null) {
                    (fadeIn(Motion.enterFade<Float>()) +
                        scaleIn(initialScale = 0.94f, animationSpec = Motion.enterSpatial<Float>()))
                        .togetherWith(fadeOut(Motion.exitFade<Float>()))
                } else {
                    fadeIn(Motion.enterFade<Float>())
                        .togetherWith(
                            fadeOut(Motion.exitFade<Float>()) +
                                scaleOut(targetScale = 0.96f, animationSpec = Motion.exitSpatial<Float>()),
                        )
                }
            },
            label = "shelfToReader",
        ) { readingBook ->
        if (readingBook != null) {
            ReaderScreen(
                book = readingBook,
                onBack = {
                    openBookId = -1L
                    progressEpoch += 1
                },
            )
        } else {
            val configuration = LocalConfiguration.current
            // 914×411dp 的手机横屏命中；竖屏、以及高度 ≥480dp 的平板横屏都不命中。
            val compactLandscape =
                configuration.screenWidthDp >= 600 && configuration.screenHeightDp < 480
            AppShell(
                compactLandscape = compactLandscape,
                tabIndex = tabIndex,
                onTabSelected = { tabIndex = it },
                onImport = launchImport,
                snackbarHostState = snackbarHostState,
                selectionMode = selectionMode,
                onDelete = { deleteDialogOpen = true },
            ) {
                when (tabIndex) {
                    0 -> LibraryScreen(
                        books = books,
                        loading = store.loading,
                        error = store.error,
                        selectionMode = selectionMode,
                        selectedIds = selectedIds,
                        onOpenBook = { book ->
                            exitSelection()
                            openBookId = book.id
                        },
                        onEnterSelection = { book ->
                            selectionMode = true
                            selectedIds = setOf(book.id)
                        },
                        onToggleSelection = { book ->
                            val next = if (book.id in selectedIds) {
                                selectedIds - book.id
                            } else {
                                selectedIds + book.id
                            }
                            if (next.isEmpty()) {
                                exitSelection()
                            } else {
                                selectedIds = next
                            }
                        },
                        onSelectAll = { selectedIds = books.map { it.id }.toSet() },
                        onExitSelection = { exitSelection() },
                        onStartSelectionMode = {
                            selectionMode = true
                            selectedIds = emptySet()
                        },
                        onImportClick = launchImport,
                        onRetry = { scope.launch { store.reload() } },
                        defaultSort = defaultSort,
                        progressEpoch = progressEpoch,
                    )

                    else -> SettingsScreen(
                        themeMode = themeMode,
                        onThemeModeChange = { mode ->
                            themeModeName = mode.name
                            preferences.themeMode = mode
                        },
                        fontSize = fontSize,
                        onFontSizeChange = { size ->
                            fontSize = size
                            preferences.fontSize = size
                        },
                        lineHeight = lineHeight,
                        onLineHeightChange = { value ->
                            lineHeight = value
                            preferences.lineHeight = value
                        },
                        defaultSort = defaultSort,
                        onDefaultSortChange = { option ->
                            defaultSortName = option.name
                            preferences.defaultSort = option.name
                        },
                        onImportClick = launchImport,
                        onClearReadingRecords = {
                            scope.launch {
                                snackbarHostState.showSnackbar("已清除本会话的阅读记录")
                            }
                        },
                    )
                }
            }
        }
        }
    }

    if (deleteDialogOpen) {
        ParticleBookTheme(mode = themeMode) {
            AlertDialog(
                onDismissRequest = { deleteDialogOpen = false },
                title = { Text("删除 " + selectedIds.size + " 本书？") },
                text = { Text("会从书架移除，不会删除手机上的文件。此操作不可撤销。") },
                confirmButton = {
                    TextButton(onClick = confirmDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteDialogOpen = false }) { Text("取消") }
                },
            )
        }
    }
}

/**
 * 导航外壳：
 * - 竖屏：内容全屏 + 底部悬浮胶囊 [NavCapsule]（宽 250 / 高 64 / 距底 insets+12dp）；
 * - 手机横屏：左侧 NavigationRail 80dp（A2 几何不变，无底部胶囊）。
 * 多选时底部换成 SelectionBar（横屏 rail 仍显示）。
 */
@Composable
private fun AppShell(
    compactLandscape: Boolean,
    tabIndex: Int,
    onTabSelected: (Int) -> Unit,
    onImport: () -> Unit,
    snackbarHostState: SnackbarHostState,
    selectionMode: Boolean,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (compactLandscape) {
        // 整个区域先避让右侧系统栏（横屏三键导航是右侧竖条）：顶栏 / FAB 都在这个 Row 里，
        // 这样 FAB 不会被导航条压住；insets 在这里被消费，子级不会重复加内边距。
        Row(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End)),
        ) {
            NavigationRail(modifier = Modifier.fillMaxHeight()) {
                TopLevel.entries.forEachIndexed { index, destination ->
                    NavigationRailItem(
                        selected = tabIndex == index,
                        onClick = { onTabSelected(index) },
                        icon = {
                            Icon(
                                imageVector = if (tabIndex == index) {
                                    destination.selectedIcon
                                } else {
                                    destination.icon
                                },
                                contentDescription = null,
                            )
                        },
                        label = {
                            Text(
                                text = destination.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            Scaffold(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (selectionMode) SelectionBar(onDelete = onDelete)
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding()),
                ) {
                    content()
                }
            }
        }
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (selectionMode) SelectionBar(onDelete = onDelete)
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
                    // 横屏时系统三键导航在屏幕右侧：只避让左右安全区，底部由胶囊自己处理。
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                    ),
            ) {
                content()
                if (!selectionMode) {
                    NavCapsule(
                        tabIndex = tabIndex,
                        onTabSelected = onTabSelected,
                        onImport = onImport,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = Spacing.sm),
                    )
                }
            }
        }
    }
}

/** 悬浮胶囊底栏（§17.2.1）：250×64dp、full 圆角、surfaceContainerHigh@92% + 1dp 白 12% 描边 + 柔和投影。 */
@Composable
private fun NavCapsule(
    tabIndex: Int,
    onTabSelected: (Int) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = CircleShape
    // 200% 字号下固定 250dp 会把「书架 / 设置」压成「书… / 设…」→ 大字号改成随屏宽伸展、标签完整可读。
    val largeFont = LocalDensity.current.fontScale >= 1.3f
    val widthModifier = if (largeFont) {
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md)
    } else {
        Modifier.width(Spacing.navCapsuleWidth)
    }
    Surface(
        modifier = modifier
            .then(widthModifier)
            .height(Spacing.navCapsuleHeight)
            .floatingShadow(shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevel.entries.forEachIndexed { index, destination ->
                val selected = tabIndex == index
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                        )
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onTabSelected(index) },
                        )
                        .padding(horizontal = Spacing.xs),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (selected) destination.selectedIcon else destination.icon,
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Spacer(Modifier.width(Spacing.xs))
            FilledIconButton(
                onClick = onImport,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "导入本地文件")
            }
        }
    }
}

/** 多选底栏：书柜/更多是 P1 占位（禁用），删除是主操作。 */
@Composable
private fun SelectionBar(onDelete: () -> Unit) {
    BottomAppBar {
        TextButton(onClick = {}, enabled = false) { Text("加入书柜") }
        TextButton(onClick = {}, enabled = false) { Text("更多") }
        TextButton(onClick = onDelete) {
            Text("删除", color = MaterialTheme.colorScheme.error)
        }
    }
}
