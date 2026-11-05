package app.particlebook.android.reader.epub.render

import kotlin.math.max
import kotlin.math.min

/** 单块内的字符区间；endChar 为 exclusive。 */
data class EpubSelectionRange(
    val blockIndex: Int,
    val startChar: Int,
    val endChar: Int,
) {
    fun normalized(): EpubSelectionRange =
        if (endChar < startChar) copy(startChar = endChar, endChar = startChar) else this

    val length: Int
        get() = (max(startChar, endChar) - min(startChar, endChar)).coerceAtLeast(0)
}

/**
 * 一行可选中文本的几何。坐标均为 View 坐标（px）。
 * [line] 是块内行号，[charStart]/[charEnd] 是块内字符偏移。
 */
data class EpubSelectionLine(
    val blockIndex: Int,
    val line: Int,
    val charStart: Int,
    val charEnd: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class EpubSelectionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class EpubSelectionHit(
    val blockIndex: Int,
    val charOffset: Int,
    val lineIndex: Int,
)

/**
 * 选区几何适配器：渲染端用 StaticLayout 实现精确的字符 x 坐标；纯 JVM 测试用 fake。
 * [lines] 的下标就是 [xForChar]/[charAt] 的 lineIndex。
 */
interface EpubSelectionGeometry {
    val lines: List<EpubSelectionLine>

    fun xForChar(lineIndex: Int, charOffset: Int): Float

    fun charAt(lineIndex: Int, x: Float): Int
}

/**
 * 字符区间、矩形和命中的纯逻辑层。
 *
 * M1 的选择范围限定在同一块；跨块选择由控制器/上层按块拆分。矩形按行返回，
 * 调用方在 Canvas 上逐行绘制高亮。
 */
object EpubSelection {

    fun rects(geometry: EpubSelectionGeometry, range: EpubSelectionRange): List<EpubSelectionRect> {
        val normalized = range.normalized()
        if (normalized.length == 0) return emptyList()
        val result = ArrayList<EpubSelectionRect>()
        geometry.lines.forEachIndexed { index, line ->
            if (line.blockIndex != normalized.blockIndex) return@forEachIndexed
            val from = max(normalized.startChar, line.charStart)
            val to = min(normalized.endChar, line.charEnd)
            if (to <= from) return@forEachIndexed
            val left = geometry.xForChar(index, from).coerceIn(line.left, line.right)
            var right = geometry.xForChar(index, to).coerceIn(line.left, line.right)
            if (right <= left) {
                right = min(line.right, left + 1f)
            }
            if (right <= left) return@forEachIndexed
            result += EpubSelectionRect(left = left, top = line.top, right = right, bottom = line.bottom)
        }
        return result
    }

    fun hitTest(
        geometry: EpubSelectionGeometry,
        x: Float,
        y: Float,
    ): EpubSelectionHit? {
        if (geometry.lines.isEmpty()) return null
        val index = geometry.lines.indexOfFirst { y >= it.top && y < it.bottom }
            .takeIf { it >= 0 }
            ?: geometry.lines.indexOfFirst { y < it.top }.takeIf { it >= 0 }
            ?: geometry.lines.lastIndex
        val line = geometry.lines[index]
        val clampedX = x.coerceIn(line.left, line.right)
        val char = geometry.charAt(index, clampedX).coerceIn(line.charStart, line.charEnd)
        return EpubSelectionHit(
            blockIndex = line.blockIndex,
            charOffset = char,
            lineIndex = index,
        )
    }
}
