package app.particlebook.android.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.particlebook.android.ui.data.Book
import java.time.LocalDate

/**
 * 书籍详情弹层（task-43）：书名 / 作者 / 元信息 / 进度 + 五星评分。
 *
 * 定位：**纯展示 + 单向上报**。评分状态不在这里落地，rating 由 [Book] 带入，
 * 点击只回报 [onRatingChange]，父层（AppRoot）写完索引后重组 —— 组件内没有
 * `remember { mutableStateOf }` 的本地副本，因此不会出现「界面已亮、磁盘没写」的假成功。
 *
 * 弹层形态沿用仓库既有约定（[ZlibMirrorSheet] / 书架排序弹层）：
 * - `skipPartiallyExpanded`：内容少于半屏也该一次展开，不做中途停靠；
 * - `contentWindowInsets = WindowInsets(0)`：insets 交给内容自己处理，底部用 `navigationBarsPadding()`，
 *   避免「sheet 再吃一次系统栏」把内容顶高；
 * - 左右边距取 [Spacing.screen]，与其它弹层同一条边线；
 * - 内容可滚动：200% 字号 + 长书名时宁可滚动，也不截断信息。
 *
 * 星级控件：M3 没有官方 rating input，用 5 个 [IconButton] 组合实现。已选态同时改**形状**
 * （实心 [Icons.Filled.Star] / 空心 [Icons.Outlined.StarBorder]）与**颜色**（primary / onSurfaceVariant），
 * 不只靠颜色表达状态；每颗星都有独立 contentDescription，读屏能听到「第 3 星，已选」。
 * 命中区固定 [Spacing.minTouchTarget]（48dp）：图标视觉尺寸可以小，点击目标不能缩。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailSheet(
    book: Book,
    progressLabel: String?,
    onRatingChange: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 越界评分（0 / 6）按未评分处理：写侧 LibraryStore.setRating 会 clamp，界面不该显示「0 星」这种不存在的状态。
    val rating = normalizedRating(book.rating)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Spacing.screen),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        // 200% 字号：书名最多三行，再长才省略；弹层本体可滚动，不靠压字号塞进一行。
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Spacing.xxs))
                    Text(
                        text = book.authorLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(Spacing.minTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = detailMetaLine(book),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (progressLabel != null) {
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    text = progressLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(Spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(MAX_STARS) { index ->
                    // index 是 0-based 循环变量，星星序号从 1 开始：starContentDescription 收的是序号本身。
                    val star = index + 1
                    val selected = rating != null && rating >= star
                    IconButton(
                        onClick = { onRatingChange(nextRatingFor(rating, star)) },
                        modifier = Modifier.size(Spacing.minTouchTarget),
                    ) {
                        Icon(
                            imageVector = if (selected) {
                                Icons.Filled.Star
                            } else {
                                Icons.Outlined.StarBorder
                            },
                            contentDescription = starContentDescription(star, selected),
                            tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ratingSummary(rating),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onRatingChange(null) },
                    // 未评分时没有可清除的值：禁用而不是隐藏，保持按钮位置稳定，也说明「这个动作存在」。
                    // 判据与星级/摘要同一份归一值，界面不会出现「显示未评分却可清除」的自相矛盾状态。
                    enabled = rating != null,
                    modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
                ) {
                    Text("清除评分")
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

/** 评分合法区间：与 [Book.rating] 的文档一致（1..5，null = 未评分）。 */
private const val MIN_RATING = 1
private const val MAX_RATING = 5

/** 星级颗数，与星星序号上界同值；分开命名是因为前者是布局数量、后者是取值边界。 */
private const val MAX_STARS = 5

/** 已选星星的数量文案：「未评分」/「4 星」。越界值（0、6、负数）同样落到「未评分」，不产生不存在的档位。 */
internal fun ratingSummary(rating: Int?): String {
    val stars = normalizedRating(rating)
    return if (stars == null) "未评分" else stars.toString() + " 星"
}

/**
 * 点击第 [tapped] 颗星之后应该写入的评分：
 * - 点的是当前已选的那一颗 → null（再点一次取消，符合「同一颗星可反选」的直觉）；
 * - 其余情况 clamp 到 1..5 后返回，脏输入（[tapped] 为 0 或 6）不会写出越界分；
 * - 当前值越界（0 / 6，理论上写侧已 clamp）不与任何星相等，因此第一次点击总会落成一个合法评分。
 */
internal fun nextRatingFor(current: Int?, tapped: Int): Int? {
    val target = tapped.coerceIn(MIN_RATING, MAX_RATING)
    val selected = normalizedRating(current)
    return if (selected == target) null else target
}

/**
 * 弹层元信息行：「EPUB · 1.2 MB · 添加于 2026-11-20」。
 *
 * 格式走 [Book.format] 的 label，大小走 [Book.sizeLabel]（未知大小是「—」，不是空串）。
 * [Book.addedAt] 为空、格式不认识或是脏日期（如 2026-02-30）时整段省略，**不抛异常**：
 * 元信息行是展示层最后一道兜底，索引里历史遗留的脏值不该让弹层崩掉。
 */
internal fun detailMetaLine(book: Book): String {
    val parts = ArrayList<String>(3)
    parts.add(book.format.label)
    parts.add(book.sizeLabel)
    addedDateLabel(book.addedAt)?.let { parts.add("添加于 " + it) }
    return parts.joinToString(" · ")
}

/**
 * 单颗星的读屏文案：index 是星星序号本身（1..5），与界面上的「第 N 星」同值。
 * 选中态写进文案而不是只改颜色 —— 读屏用户拿不到颜色信息。
 */
internal fun starContentDescription(index: Int, selected: Boolean): String =
    if (selected) "第 " + index + " 星，已选" else "第 " + index + " 星"

/** 把 rating 归一到合法区间；越界与 null 一视同仁地视为「未评分」。 */
private fun normalizedRating(rating: Int?): Int? =
    rating?.takeIf { it in MIN_RATING..MAX_RATING }

/** ISO-8601 日期的前 10 位；只认补零的 yyyy-MM-dd，其它写法按脏值处理。 */
private val ISO_DATE_PREFIX = Regex("^\\d{4}-\\d{2}-\\d{2}")

/**
 * addedAt → 「2026-11-20」。索引里是 ISO-8601 UTC（`2026-11-20T09:30:00Z`），fixtures 里也有纯日期。
 * 取前 10 位再按 ISO_LOCAL_DATE 严格解析：2 月 30 日这类「长得像日期」的脏值解析失败 → null → 省略该段。
 */
private fun addedDateLabel(addedAt: String?): String? {
    val raw = addedAt?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val datePart = ISO_DATE_PREFIX.find(raw)?.value ?: return null
    return runCatching { LocalDate.parse(datePart).toString() }.getOrNull()
}
