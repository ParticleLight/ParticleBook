package app.particlebook.android.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import app.particlebook.android.ui.ReaderMessage
import app.particlebook.android.ui.Spacing

/**
 * 更新日志页：按版本倒序的时间线（版本 + 日期 + 改动条目），当前安装版本带标记。
 *
 * 纯渲染：条目的顺序、内容、当前版本标记全部来自参数，本页不持有业务状态、不读磁盘。
 * 数据由 `assets/about/changelog.json` 提供（格式见 [AboutJson.parseChangelog]）。
 *
 * 为什么标记只有一枚：`currentVersion` 通常只有一个（`2.2.1-p0`）；找不到对应条目时
 * 不臆造「最新」标记——顶部那条本身就是最新的未发布改动，再加一枚徽章只会和版本号打架。
 *
 * 200% 字号：版本、日期、条目都不锁 maxLines、不写死高度；放大的代价是行数变多，
 * 不会有文字被截断。
 */
@Composable
fun ChangelogScreen(
    entries: List<ChangelogEntry>,
    currentVersion: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding()
            .padding(horizontal = Spacing.screen),
    ) {
        Spacer(Modifier.height(Spacing.lg))
        AboutTopBar(title = "更新日志", onBack = onBack)
        Spacer(Modifier.height(Spacing.md))
        if (entries.isEmpty()) {
            ReaderMessage(
                title = "还没有更新日志",
                message = "这个构建没有内置更新日志数据。安装带更新日志的版本后，改动会按版本列在这里。",
                modifier = Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = Spacing.xl),
            ) {
                itemsIndexed(
                    items = entries,
                    key = { _, entry -> entry.version + "|" + entry.date },
                ) { index, entry ->
                    ChangelogRow(
                        entry = entry,
                        isCurrent = isCurrentVersion(entry, currentVersion),
                    )
                    if (index != entries.lastIndex) {
                        HorizontalDivider(
                            thickness = Dp.Hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
                item(key = "footer") { ChangelogFooter() }
            }
        }
    }
}

/** 单条版本：左侧时间线圆点（当前版本用 primary）+ 右侧版本号 / 日期 / 条目。 */
@Composable
private fun ChangelogRow(entry: ChangelogEntry, isCurrent: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.md),
    ) {
        // 时间线圆点：形状 + 颜色双重表达「当前版本」，不只靠颜色。
        Box(
            modifier = Modifier
                .padding(top = Spacing.xs)
                .size(Spacing.xs)
                .clip(CircleShape)
                .background(
                    if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.version,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (entry.date.isNotBlank() || isCurrent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.date.isNotBlank()) {
                        Text(
                            text = formatDate(entry.date),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isCurrent) {
                        Spacer(Modifier.width(Spacing.xs))
                        CurrentVersionBadge()
                    }
                }
            }
            for (highlight in entry.highlights) {
                Row(modifier = Modifier.padding(top = Spacing.xxs)) {
                    // 项目符号是纯装饰：读屏只读条目正文，不逐个念「•」。
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .width(Spacing.sm)
                            .clearAndSetSemantics { },
                    )
                    Text(
                        text = highlight,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 「当前版本」标记：标签文字 + primaryContainer 成对配色，不单靠颜色传达状态。 */
@Composable
private fun CurrentVersionBadge() {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = "当前版本",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        )
    }
}

/** 页脚：说明这页的数据从哪来，避免用户以为它是联网抓的。 */
@Composable
private fun ChangelogFooter() {
    Text(
        text = "更新日志随安装包内置，不联网更新。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.md),
    )
}

/**
 * 日期显示：JSON 里统一写 `yyyy-MM-dd`，界面按原样显示。
 * 不在这里做本地化格式（会引入时区歧义），也不猜测缺日期时的含义——留空就不显示。
 */
private fun formatDate(date: String): String = date.trim()
