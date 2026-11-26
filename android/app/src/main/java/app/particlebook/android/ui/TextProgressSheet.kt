package app.particlebook.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.particlebook.android.reader.text.TextProgressTarget

/**
 * 文本阅读器的「跳到位置」弹层（TXT / Markdown / FB2 / HTML 共用）。
 *
 * 与目录弹层（[TextTocSheet]）的分工：目录是「按名字挑一章」，本弹层是「按比例挑一处」——
 * 滑块走 0..1 的整本比例，落到哪一段由调用方的 [TextProgressJump][app.particlebook.android.reader.text.TextProgressJump]
 * 换算，组件本身不算段落、不碰文档。
 *
 * 受控程度刻意分两段（滑块是唯一有本地状态的东西）：
 * - 拖动中：本地 sliderFraction 跟着手指走，同时 [onSeek] 把实时值抛上去，调用方只改预览文案，
 *   正文不滚——拖一下整本滚动会很贵，滚动留到松手；
 * - 松手：本地值原样交给 [onJump]，真正的段落对齐由调用方回算后以新的 [target] 传回来；
 *   [target] 的 fraction 变化且用户没在拖时，用 [LaunchedEffect] 把滑块吸附到段落边界上
 *   （滑块停在手指位置而不是段落边界，会和上方的百分比文案对不上）。
 *
 * 章节行与快捷行都只发意图（[onChapterStep] / [onJump]），不自己算下一章：
 * 章边界、首章/末章判定全在调用方，「能不能去」由 [canGoPreviousChapter] / [canGoNextChapter] 表达。
 *
 * @param target 调用方算好的当前状态：百分比、第几段、章节标题与序号、段落对齐后的 fraction。
 * @param canGoPreviousChapter 上一章按钮是否可用（调用方还要保证 [TextProgressTarget.chapterCount] > 0）。
 * @param canGoNextChapter 下一章按钮是否可用。
 * @param onSeek 拖动中的实时回调，只用于预览文案，不触发布局滚动。
 * @param onJump 松手后的最终值（0..1）；快捷按钮也用 0f / [TextProgressTarget.fraction] / 1f 走这条路。
 * @param onChapterStep -1 = 上一章，+1 = 下一章。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextProgressSheet(
    target: TextProgressTarget,
    canGoPreviousChapter: Boolean,
    canGoNextChapter: Boolean,
    onSeek: (Float) -> Unit,
    onJump: (Float) -> Unit,
    onChapterStep: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 滑块本地值的生命周期绑在「当前段落」上：换段就重新以调用方的对齐值为初值，
    // 同一段内的重排（预览文案刷新）不会把用户正拖着的拇指拽回去。
    var sliderFraction by remember(target.paragraphIndex) {
        mutableStateOf(clampFraction(target.fraction))
    }
    var dragging by remember { mutableStateOf(false) }
    // 只在「调用方给了新 fraction」且「用户没在拖」时同步：拖到一半被外部状态改写滑块会打架，
    // 而松手后调用方回算的段落边界必须能落到拇指上（否则拇指停在 0.42、文案说 0.4187 的那一段）。
    LaunchedEffect(target.fraction) {
        if (!dragging) sliderFraction = clampFraction(target.fraction)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 与目录 / 搜索 / 设置弹层同一套 insets 约定：dialog 窗口自己 edge-to-edge，
        // 底部避让导航条由内容自己做（见下方 navigationBarsPadding）。
        contentWindowInsets = { WindowInsets(0) },
    ) {
        ImmersiveDialogWindow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 横屏 + 200% 字号时这一列会比弹层窗口高（快捷行被 FlowRow 折成两排），
                // 给它自己的滚动比让内容被 sheet 裁掉更稳；内容装得下时它不产生任何位移。
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.md)
                .navigationBarsPadding(),
        ) {
            TextProgressHeader(onDismiss = onDismiss)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md),
            ) {
                Text(
                    text = target.percentLabel,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = target.positionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = clampFraction(sliderFraction),
                onValueChange = { value ->
                    // 先标记拖动再抛值：调用方可能在 onSeek 里立刻回传新的 target，
                    // 这一步保证那次回传不会把滑块拽离手指。
                    dragging = true
                    sliderFraction = value
                    onSeek(value)
                },
                onValueChangeFinished = {
                    // 松手才交出最终值；用本地值而不是 target.fraction —— 后者可能还停在拖动前的段落边界上。
                    dragging = false
                    onJump(clampFraction(sliderFraction))
                },
                valueRange = 0f..1f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md)
                    .heightIn(min = Spacing.minTouchTarget)
                    // 读屏听到的是「42%，第 120 段 / 共 286 段」，而不是一个孤立的光标位置。
                    .semantics {
                        contentDescription = "阅读进度"
                        stateDescription = target.percentLabel + "，" + target.positionLabel
                    },
            )
            Spacer(Modifier.height(Spacing.xxs))
            TextProgressChapterRow(
                target = target,
                canGoPreviousChapter = canGoPreviousChapter,
                canGoNextChapter = canGoNextChapter,
                onChapterStep = onChapterStep,
            )
            Spacer(Modifier.height(Spacing.xs))
            TextProgressQuickRow(
                targetFraction = target.fraction,
                onJump = onJump,
            )
        }
    }
}

/** 标题行：左边「跳到位置」，右边关闭。行高下限 [Spacing.minTouchTarget]，与图标按钮的触控区一致。 */
@Composable
private fun TextProgressHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.minTouchTarget)
            .padding(start = Spacing.md, end = Spacing.xxs, top = Spacing.xxs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "跳到位置",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "关闭",
            )
        }
    }
}

/**
 * 章节导航行：两侧按钮跨章，中间是「当前章标题 + 第 k / 共 n 章」。
 *
 * 没有目录时（[TextProgressTarget.chapterCount] == 0）两个按钮一起禁用，中间换成一句说明——
 * 按钮留着但点不动，比整行消失更容易让人明白「这本书没有章节结构」，也不会让弹层高度突变。
 * 标题允许两行：Markdown / HTML 的长标题在窄屏上折行比截成省略号更有用。
 */
@Composable
private fun TextProgressChapterRow(
    target: TextProgressTarget,
    canGoPreviousChapter: Boolean,
    canGoNextChapter: Boolean,
    onChapterStep: (Int) -> Unit,
) {
    val hasChapters = target.chapterCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 4dp 外边距 + TextButton 自带 12dp 内边距 = 16dp，与上方的百分比、位置文案左对齐。
            .padding(horizontal = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onChapterStep(-1) },
            enabled = hasChapters && canGoPreviousChapter,
            modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
        ) {
            Text("上一章")
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.xxs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (hasChapters) {
                // chapterIndex == 0 表示当前段还在第一章之前（书名页、前言、封面后的空段）。
                // 这时 chapterTitle 是 null，但不能说「这本书没有可跳转的章节」——目录明明有，
                // 「第 0 / 共 12 章」更是自相矛盾。
                val beforeFirstChapter = target.chapterIndex <= 0
                Text(
                    text = target.chapterTitle ?: if (beforeFirstChapter) "第一章之前" else "（无目录）",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (beforeFirstChapter) {
                        "共 " + target.chapterCount + " 章 · 还没到第一章"
                    } else {
                        "第 " + target.chapterIndex + " / 共 " + target.chapterCount + " 章"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = "这本书没有可跳转的章节",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        TextButton(
            onClick = { onChapterStep(1) },
            enabled = hasChapters && canGoNextChapter,
            modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
        ) {
            Text("下一章")
        }
    }
}

/**
 * 快捷行：开头 / 当前位置 / 结尾。
 *
 * 用 [FlowRow] 而不是 [Row]：200% 字号下三个四字按钮在 360dp 宽的弹层里放不下，
 * 换行比压字或截断可读（同 ui/AnnotationListSheet.kt 的 chip 行、ui/LibraryScreen.kt 的格式行）。
 * 「当前位置」传的是调用方那份 [TextProgressTarget.fraction]——段落对齐后的真实位置，
 * 不是滑块当前拇指值，所以拖动中途点它不会落到段落中间。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextProgressQuickRow(
    targetFraction: Float,
    onJump: (Float) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        TextProgressQuickButton(label = "本书开头", onClick = { onJump(0f) })
        TextProgressQuickButton(label = "当前位置", onClick = { onJump(targetFraction) })
        TextProgressQuickButton(label = "本书结尾", onClick = { onJump(1f) })
    }
}

/** 快捷按钮：OutlinedButton 的 M3 默认形状（主题 shape token 的胶囊档），不另写圆角字面量。 */
@Composable
private fun TextProgressQuickButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = Spacing.minTouchTarget),
        // 默认 24dp 左右内边距在三个四字按钮并排时太宽；16dp 仍大于触控区所需，且留出换行余地。
        contentPadding = PaddingValues(horizontal = Spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

/**
 * 滑块值域是 0..1：NaN 与越界一律夹回边界。
 *
 * 调用方给的 fraction 理论上已经归一（[app.particlebook.android.reader.text.TextProgressJump] 负责），
 * 但 Slider 拿到 NaN 会算出 NaN 的轨道比例，这里做最后一道保险，代价是一次 isFinite 判断。
 */
private fun clampFraction(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
