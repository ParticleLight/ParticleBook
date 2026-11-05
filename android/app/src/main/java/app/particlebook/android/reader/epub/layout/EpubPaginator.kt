package app.particlebook.android.reader.epub.layout

/**
 * 纯数学分页器：把每个块的行切片装进页，不依赖 Android。
 *
 * 规则（M0 简化）：
 * - marginTop 在块/分段第一次落到页面时生效；段中换页会在新页重加 marginTop。
 * - marginBottom 在块结束后生效；若超出页面，留给下一页的判断吸收，不单独产生空页。
 * - 不可拆块放不下时整块换页；空页也放不下时允许单块超高（避免死循环）。
 * - PageBreak/显式 pageBreakBefore 不产生空白页；连续分页符只算一次。
 * - 文本块允许按行切片；行高可变，用 LineMetrics 的累计高度而不是假设固定行高。
 */
class EpubPaginator {

    fun paginate(blocks: List<MeasuredBlock>, pageHeightPx: Int): List<Page> {
        if (blocks.isEmpty()) return emptyList()
        require(pageHeightPx > 0) { "pageHeightPx must be > 0" }

        val pages = ArrayList<Page>()
        var slices = ArrayList<PageSlice>()
        var y = 0

        fun finishPage() {
            if (slices.isEmpty()) return
            pages += Page(index = pages.size, slices = slices.toList(), usedHeightPx = y)
            slices = ArrayList()
            y = 0
        }

        fun firstLineHeight(block: MeasuredBlock): Int {
            val lines = block.lines ?: return block.heightPx
            if (lines.lineCount <= 0) return block.heightPx
            return (lines.lineBottom(0) - lines.lineTop(0)).coerceAtLeast(1)
        }

        for (block in blocks) {
            if (block.pageBreakBefore && slices.isNotEmpty()) finishPage()
            if (block.forcePageBreakAfter && !block.isSplittable && block.heightPx <= 0) {
                finishPage()
                continue
            }

            if (!block.isSplittable) {
                val total = block.marginTopPx + block.heightPx + block.marginBottomPx
                if (slices.isNotEmpty() && y + total > pageHeightPx) finishPage()
                y += block.marginTopPx
                slices += PageSlice(
                    blockIndex = block.blockIndex,
                    lineStart = 0,
                    lineEnd = 0,
                    topPx = y,
                    heightPx = block.heightPx,
                )
                y += block.heightPx + block.marginBottomPx
                if (block.forcePageBreakAfter) finishPage()
                continue
            }

            val lines = block.lines ?: continue
            var line = 0
            var needsTopMargin = true
            while (line < lines.lineCount) {
                if (needsTopMargin) {
                    if (slices.isNotEmpty() && y + block.marginTopPx + firstLineHeight(block) > pageHeightPx) {
                        finishPage()
                    }
                    y += block.marginTopPx
                    needsTopMargin = false
                }

                val available = pageHeightPx - y
                var end = line
                while (end < lines.lineCount &&
                    lines.lineBottom(end) - lines.lineTop(line) <= available
                ) {
                    end++
                }
                if (end == line) {
                    if (slices.isNotEmpty()) {
                        finishPage()
                        needsTopMargin = true
                        continue
                    }
                    // 空页也放不下一行：强制放一行，避免无限循环。
                    end = line + 1
                }

                val sliceHeight = lines.lineBottom(end - 1) - lines.lineTop(line)
                slices += PageSlice(
                    blockIndex = block.blockIndex,
                    lineStart = line,
                    lineEnd = end,
                    topPx = y,
                    heightPx = sliceHeight,
                )
                y += sliceHeight
                line = end
                if (line < lines.lineCount) {
                    finishPage()
                    needsTopMargin = true
                }
            }
            y += block.marginBottomPx
            if (block.forcePageBreakAfter) finishPage()
        }
        finishPage()
        return pages
    }
}
