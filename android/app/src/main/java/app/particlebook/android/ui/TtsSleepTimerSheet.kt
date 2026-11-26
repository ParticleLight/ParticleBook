package app.particlebook.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import app.particlebook.android.reader.tts.TtsSleepTimer

/**
 * 朗读定时停止的选择弹层（EPUB / PDF / TXT / Markdown / FB2 / HTML 共用一套）。
 *
 * 纯受控 + 纯展示，两条纪律：
 * - **不持有选择状态**：选中哪一档由调用方的 [TtsSleepTimer] 决定（[selectedMinutes]），
 *   点一行只发 [onSelect]（null = 不定时），弹层关不关由宿主决定——本组件不调 sheetState.hide()，
 *   也不在本地先改选中态。先改本地态再等宿主回传，两边不一致时会出现"弹层还开着但勾已经跳走"。
 * - **自己不算时间**：剩余毫秒由宿主从 [TtsSleepTimer.remainingMillis] 取来，格式化只走
 *   [TtsSleepTimer.formatRemaining] 这一个入口（秒向上取整的口径写在核心，UI 不复制一份）。
 *
 * 无障碍：
 * - 单选行：整行 [selectable] + [Role.RadioButton]（命中区是整行，高度 ≥ [Spacing.minTouchTarget]），
 *   [RadioButton] 只当图标（onClick = null），点它不会绕过行语义；
 * - 秒级的「剩余 m:ss」**不设 liveRegion**：设了就是每秒一次无障碍播报，比没有还糟；
 *   剩余时间由朗读条上定时按钮的 contentDescription 承载，读屏用户聚焦时读一次即可。
 *
 * @param selectedMinutes 当前计划时长（分钟）；null = 没有计划。
 * @param remainingMillis 剩余毫秒；null = 没有计划。有值时在标题下显示一行「剩余 m:ss」。
 * @param onSelect 选中一档（null = 不定时）；关闭弹层是 [onDismiss] 的职责。
 * @param onDismiss 关闭弹层（外点、下滑、关闭按钮都走这里）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TtsSleepTimerSheet(
    selectedMinutes: Int?,
    remainingMillis: Long?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 与目录 / 搜索 / 跳到位置 / 设置弹层同一套 insets 约定：dialog 窗口自己 edge-to-edge，
        // 底部避让导航条由内容自己做（见下方 navigationBarsPadding）。
        contentWindowInsets = { WindowInsets(0) },
    ) {
        ImmersiveDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 横屏 + 200% 字号时「标题 + 两行说明 + 五条选项」会高过弹层窗口：
                // 给这一列自己的滚动，比让 sheet 把最后几条选项裁掉好；装得下时它不产生任何位移。
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.md)
                .navigationBarsPadding(),
        ) {
            TtsSleepTimerHeader(onDismiss = onDismiss)
            Text(
                text = "暂停朗读时仍然计时；重新选择会从当前时刻重新计时。到点直接停止朗读，本次朗读结束时定时自动取消。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.xxs),
            )
            if (remainingMillis != null) {
                Text(
                    text = "当前剩余 " + TtsSleepTimer.formatRemaining(remainingMillis),
                    style = AppType.inlineNumber,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(
                        start = Spacing.md,
                        end = Spacing.md,
                        bottom = Spacing.xs,
                    ),
                )
            }
            // 同组单选：selectableGroup 让读屏把这几行报成"n 项中的第 k 项"的一组，
            // 而不是五条互不相干的开关。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xxs)
                    .selectableGroup(),
            ) {
                TtsSleepTimerOptionRow(
                    label = "不定时",
                    selected = selectedMinutes == null,
                    onClick = { onSelect(null) },
                )
                // 选项就是核心的 PRESET_MINUTES：弹层不自带一份 5/15/30/60，
                // 免得以后核心加减档位时弹层还在给一个 start() 会抛异常的值。
                TtsSleepTimer.PRESET_MINUTES.forEach { minutes ->
                    TtsSleepTimerOptionRow(
                        label = minutes.toString() + " 分钟",
                        selected = selectedMinutes == minutes,
                        onClick = { onSelect(minutes) },
                    )
                }
            }
        }
    }
}

/** 标题行：左边「朗读定时停止」，右边关闭。行高下限 [Spacing.minTouchTarget]，与图标按钮的触控区一致。 */
@Composable
private fun TtsSleepTimerHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .padding(start = Spacing.md, end = Spacing.xxs, top = Spacing.xxs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "朗读定时停止",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭朗读定时",
            )
        }
    }
}

/**
 * 一条时长选项：整行可选，单选点放行尾（与 ui/SettingsScreen.kt 的主题单选行同一形状——
 * 扫描时右侧是一列控件，而不是一列圆点）。
 *
 * 标题不设 maxLines：200% 字号下宁可折行，也不把「60 分钟」截成省略号。
 */
@Composable
private fun TtsSleepTimerOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        RadioButton(selected = selected, onClick = null)
    }
}
