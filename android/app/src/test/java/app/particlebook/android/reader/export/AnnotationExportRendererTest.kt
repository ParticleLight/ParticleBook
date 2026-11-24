package app.particlebook.android.reader.export

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AnnotationExportRenderer] 的 JVM 单测：钉住排版骨架、过滤语义、文本清洗与时间格式。
 *
 * 断言一律与实现逐条对齐（先实现后写期望值），不用「大概长这样」的模糊断言：
 * 需要逐字符比较的地方用 [doc]，需要看局部的地方用 contains / startsWith。
 */
class AnnotationExportRendererTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 固定时区下的固定时刻，避免测试依赖机器时区。 */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, shanghai).toInstant().toEpochMilli()

    private fun highlight(
        location: String,
        quote: String = "",
        note: String = "",
        color: String? = null,
        createdAtMillis: Long = 0L,
    ): AnnotationExportEntry = AnnotationExportEntry(
        kind = AnnotationExportKind.HIGHLIGHT,
        location = location,
        quote = quote,
        note = note,
        color = color,
        createdAtMillis = createdAtMillis,
    )

    private fun bookmark(
        location: String,
        quote: String = "",
        note: String = "",
        createdAtMillis: Long = 0L,
    ): AnnotationExportEntry = AnnotationExportEntry(
        kind = AnnotationExportKind.BOOKMARK,
        location = location,
        quote = quote,
        note = note,
        createdAtMillis = createdAtMillis,
    )

    private fun note(
        location: String,
        text: String = "",
        createdAtMillis: Long = 0L,
    ): AnnotationExportEntry = AnnotationExportEntry(
        kind = AnnotationExportKind.NOTE,
        location = location,
        note = text,
        createdAtMillis = createdAtMillis,
    )

    private fun render(
        document: AnnotationExportDocument,
        options: AnnotationExportOptions = AnnotationExportOptions(),
        zone: ZoneId = shanghai,
    ): AnnotationExportResult = AnnotationExportRenderer.renderMarkdown(
        document = document,
        options = options,
        zone = zone,
    )

    /**
     * 期望值统一以「恰好一个结尾换行」收口，与被测实现的落盘契约一致。
     * 用 trimIndent 兜住源码里为了对齐而留的行首缩进（内容行本身顶格，缩进量恒为 0）。
     */
    private fun doc(raw: String): String = raw.trimIndent().trim('\n') + "\n"

    // ---------------------------------------------------------------- 1. 完整骨架

    @Test
    fun fullDocumentRendersFrontMatterTocAndTwoSections() {
        val document = AnnotationExportDocument(
            bookTitle = "深入理解计算机系统",
            author = "Randal E. Bryant",
            formatLabel = "EPUB",
            exportedAtMillis = at(2026, 11, 20, 10, 3),
            sections = listOf(
                AnnotationExportSection(
                    title = "第一章 计算机系统漫游",
                    entries = listOf(
                        highlight(
                            location = "第一章 · 第 3 段",
                            quote = "程序的生命周期",
                            createdAtMillis = at(2026, 11, 19, 9, 0),
                        ),
                    ),
                ),
                AnnotationExportSection(
                    title = "第二章 信息的表示",
                    entries = listOf(
                        note(
                            location = "第二章 · 第 1 段",
                            text = "字节序要记牢",
                            createdAtMillis = at(2026, 11, 19, 9, 5),
                        ),
                    ),
                ),
            ),
        )

        val result = render(document)

        assertEquals(2, result.entryCount)
        assertFalse(result.isEmpty)
        assertEquals(AnnotationExportRenderer.MIME_MARKDOWN, result.mimeType)
        assertEquals("深入理解计算机系统-标注.md", result.fileName)
        assertEquals(
            doc(
                """
# 深入理解计算机系统 — 标注导出

- 作者：Randal E. Bryant
- 格式：EPUB
- 标注：高亮 1 · 笔记 1
- 导出时间：2026-11-20 10:03

## 目录

- [第一章 计算机系统漫游](#ch-1)
- [第二章 信息的表示](#ch-2)

<a id="ch-1"></a>
## 第一章 计算机系统漫游

> 程序的生命周期

- 位置：第一章 · 第 3 段
- 时间：2026-11-19 09:00

<a id="ch-2"></a>
## 第二章 信息的表示

**笔记**：字节序要记牢

- 位置：第二章 · 第 1 段
- 时间：2026-11-19 09:05
""",
            ),
            result.content,
        )
    }

    @Test
    fun singleSectionOmitsTableOfContents() {
        val document = AnnotationExportDocument(
            bookTitle = "单章书",
            sections = listOf(
                AnnotationExportSection("唯一一章", listOf(highlight("第 1 段", quote = "只有一章"))),
            ),
        )

        val content = render(document).content

        assertFalse(content.contains("## 目录"))
        assertTrue(content.contains("<a id=\"ch-1\"></a>\n## 唯一一章"))
    }

    // ---------------------------------------------------------------- 2. 关掉 front matter

    @Test
    fun frontMatterDisabledStartsWithFirstAnchor() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            author = "作者",
            formatLabel = "PDF",
            exportedAtMillis = at(2026, 11, 20, 10, 3),
            sections = listOf(
                AnnotationExportSection("第一章", listOf(highlight("第 1 段", quote = "高亮"))),
                AnnotationExportSection("第二章", listOf(highlight("第 2 段", quote = "另一处高亮"))),
            ),
        )

        val content = render(document, AnnotationExportOptions(includeFrontMatter = false)).content

        assertEquals(
            doc(
                """
<a id="ch-1"></a>
## 第一章

> 高亮

- 位置：第 1 段

<a id="ch-2"></a>
## 第二章

> 另一处高亮

- 位置：第 2 段
                """,
            ),
            content,
        )
        assertFalse(content.startsWith("#"))
        assertFalse(content.contains("标注导出"))
        assertFalse(content.contains("导出时间"))
        assertFalse(content.contains("## 目录"))
    }

    // ---------------------------------------------------------------- 3. 三类条目排版

    @Test
    fun highlightRendersMultilineQuoteThenMetaInFixedOrder() {
        val document = AnnotationExportDocument(
            bookTitle = "高亮书",
            sections = listOf(
                AnnotationExportSection(
                    title = "第三章",
                    entries = listOf(
                        highlight(
                            location = "第三章 · 第 12 段",
                            quote = "高亮第一行\n高亮第二行",
                            note = "附注第一行\n续行一",
                            color = "yellow",
                            createdAtMillis = at(2026, 11, 20, 10, 3),
                        ),
                    ),
                ),
            ),
        )

        val content = render(document).content

        assertTrue(
            content.endsWith(
                doc(
                    """
> 高亮第一行
> 高亮第二行

- 颜色：yellow
- 位置：第三章 · 第 12 段
- 时间：2026-11-20 10:03
- 笔记：附注第一行
  续行一
                    """,
                ),
            ),
        )
    }

    @Test
    fun highlightWithoutQuoteRendersOnlyMetadata() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            sections = listOf(
                AnnotationExportSection("章", listOf(highlight("第 9 段", quote = "", note = "只留附注"))),
            ),
        )

        val content = render(document).content

        assertFalse(content.contains("> "))
        assertTrue(
            content.endsWith(
                doc(
                    """
<a id="ch-1"></a>
## 章

- 位置：第 9 段
- 笔记：只留附注
                    """,
                ),
            ),
        )
    }

    @Test
    fun includeQuotesDisabledDropsQuoteBlockButKeepsEntry() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            sections = listOf(
                AnnotationExportSection("章", listOf(highlight("第 9 段", quote = "不该出现的原文"))),
            ),
        )

        val result = render(document, AnnotationExportOptions(includeQuotes = false))

        assertEquals(1, result.entryCount)
        assertFalse(result.content.contains("不该出现的原文"))
        assertTrue(
            result.content.endsWith(
                doc(
                    """
<a id="ch-1"></a>
## 章

- 位置：第 9 段
                    """,
                ),
            ),
        )
    }

    @Test
    fun bookmarkRendersWithAndWithoutQuote() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            sections = listOf(
                AnnotationExportSection(
                    title = "第一章",
                    entries = listOf(
                        bookmark(
                            location = "第 12 页",
                            quote = "第十二页的折角",
                            createdAtMillis = at(2026, 11, 20, 10, 3),
                        ),
                        bookmark(location = "第 37 页"),
                    ),
                ),
            ),
        )

        val content = render(document).content

        assertTrue(
            content.endsWith(
                doc(
                    """
**书签**：第十二页的折角

- 位置：第 12 页
- 时间：2026-11-20 10:03

**书签**

- 位置：第 37 页
                    """,
                ),
            ),
        )
    }

    @Test
    fun bookmarkNoteUsesIndentedContinuation() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            sections = listOf(
                AnnotationExportSection(
                    "第一章",
                    listOf(bookmark("第 5 页", quote = "折角", note = "想一想\n再看一遍")),
                ),
            ),
        )

        val content = render(document).content

        assertTrue(content.contains("- 笔记：想一想\n  再看一遍"))
    }

    @Test
    fun noteRendersBodyFirstThenMetaWithoutNoteLine() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            sections = listOf(
                AnnotationExportSection(
                    "第三章",
                    listOf(
                        note(
                            location = "第三章 · 第 3 段",
                            text = "第一行想法\n续行 A\n续行 B",
                            createdAtMillis = at(2026, 11, 20, 10, 3),
                        ),
                    ),
                ),
            ),
        )

        val content = render(document).content

        assertTrue(
            content.endsWith(
                doc(
                    """
**笔记**：第一行想法
  续行 A
  续行 B

- 位置：第三章 · 第 3 段
- 时间：2026-11-20 10:03
                    """,
                ),
            ),
        )
        assertFalse(content.contains("- 笔记："))
    }

    @Test
    fun noteWithoutTextRendersBareLabel() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            sections = listOf(AnnotationExportSection("章", listOf(note("第 1 段", text = "")))),
        )

        val content = render(document).content

        assertTrue(content.contains("\n**笔记**\n"))
        assertFalse(content.contains("**笔记**："))
    }

    @Test
    fun quoteWithInteriorBlankLineKeepsBlankQuotedLine() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            sections = listOf(
                AnnotationExportSection("章", listOf(highlight("第 1 段", quote = "上半\n\n下半"))),
            ),
        )

        val content = render(document).content

        assertTrue(content.contains("> 上半\n>\n> 下半"))
    }

    // ---------------------------------------------------------------- 4. 过滤与计数

    @Test
    fun filteringKeepsOnlyHighlightsInCountAndSummary() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            exportedAtMillis = at(2026, 11, 20, 10, 3),
            sections = listOf(
                AnnotationExportSection(
                    "第一章",
                    listOf(
                        highlight("第 1 段", quote = "高亮一"),
                        note("第 2 段", text = "笔记一"),
                        highlight("第 3 段", quote = "高亮二"),
                    ),
                ),
                AnnotationExportSection("第二章", listOf(bookmark("第 7 页", quote = "折角"))),
            ),
        )

        val result = render(
            document,
            AnnotationExportOptions(includeNotes = false, includeBookmarks = false),
        )

        assertEquals(2, result.entryCount)
        assertTrue(result.content.contains("- 标注：高亮 2"))
        assertFalse(result.content.contains("笔记 1"))
        assertFalse(result.content.contains("书签"))
        // 第二章只有书签，被过滤空后整节消失。
        assertFalse(result.content.contains("## 第二章"))
        // 只剩一组，目录也不输出。
        assertFalse(result.content.contains("## 目录"))
    }

    @Test
    fun sectionsFilteredEmptyDisappearEntirely() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            sections = listOf(
                AnnotationExportSection("第一章", listOf(highlight("第 1 段", quote = "高亮"))),
                AnnotationExportSection("第二章", listOf(note("第 2 段", text = "笔记"))),
                AnnotationExportSection("第三章", listOf(bookmark("第 9 页"))),
            ),
        )

        val content = render(
            document,
            AnnotationExportOptions(includeNotes = false, includeBookmarks = false),
        ).content

        assertTrue(content.contains("## 第一章"))
        assertFalse(content.contains("第二章"))
        assertFalse(content.contains("第三章"))
    }

    @Test
    fun summaryListsOnlyNonZeroKindsInFixedOrder() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            exportedAtMillis = at(2026, 11, 20, 10, 3),
            sections = listOf(
                AnnotationExportSection(
                    "第一章",
                    listOf(
                        bookmark("第 1 页"),
                        note("第 2 段", text = "笔记"),
                        highlight("第 3 段", quote = "高亮一"),
                        highlight("第 4 段", quote = "高亮二"),
                    ),
                ),
            ),
        )

        val result = render(document)

        assertEquals(4, result.entryCount)
        assertTrue(result.content.contains("- 标注：高亮 2 · 笔记 1 · 书签 1"))
    }

    // ---------------------------------------------------------------- 5. 空结果

    @Test
    fun emptyResultWithFrontMatterKeepsTitleAndPlaceholder() {
        val document = AnnotationExportDocument(
            bookTitle = "空书",
            author = "某作者",
            exportedAtMillis = at(2026, 11, 20, 10, 3),
        )

        val result = render(document)

        assertEquals(0, result.entryCount)
        assertTrue(result.isEmpty)
        assertEquals("空书-标注.md", result.fileName)
        assertEquals(
            doc(
                """
# 空书 — 标注导出

- 作者：某作者
- 导出时间：2026-11-20 10:03

（没有可导出的标注）
                """,
            ),
            result.content,
        )
        assertFalse(result.content.contains("- 标注："))
    }

    @Test
    fun emptyResultWithoutFrontMatterIsOnlyPlaceholder() {
        val document = AnnotationExportDocument(bookTitle = "空书")

        val result = render(document, AnnotationExportOptions(includeFrontMatter = false))

        assertTrue(result.isEmpty)
        assertEquals("（没有可导出的标注）\n", result.content)
        assertEquals(AnnotationExportRenderer.EMPTY_PLACEHOLDER, result.content.trim())
    }

    @Test
    fun allEntriesFilteredOutYieldsEmptyResult() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            exportedAtMillis = at(2026, 11, 20, 10, 3),
            sections = listOf(AnnotationExportSection("第一章", listOf(note("第 1 段", text = "笔记")))),
        )

        val result = render(
            document,
            AnnotationExportOptions(includeNotes = false),
        )

        assertEquals(0, result.entryCount)
        assertTrue(result.isEmpty)
        assertFalse(result.content.contains("第一章"))
        assertTrue(result.content.endsWith("\n（没有可导出的标注）\n"))
    }

    // ---------------------------------------------------------------- 6. 文本清洗

    @Test
    fun bookTitleNewlinesFlattenedInHeadingAndIllegalCharsDroppedFromFileName() {
        val document = AnnotationExportDocument(
            bookTitle = "我的书:\n第二行/测试",
            sections = listOf(AnnotationExportSection("章", listOf(highlight("第 1 段", quote = "高亮")))),
        )

        val result = render(document)

        assertTrue(result.content.startsWith("# 我的书: 第二行/测试 — 标注导出\n"))
        assertEquals("我的书 第二行测试-标注.md", result.fileName)
        assertEquals("我的书 第二行测试-标注.md", AnnotationExportRenderer.suggestedFileName("我的书:\n第二行/测试"))
    }

    @Test
    fun inlineCleanDropsControlCharactersAndBlockCleanKeepsLineBreaks() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            sections = listOf(
                AnnotationExportSection(
                    "章",
                    listOf(
                        highlight(
                            location = "第 1 段\u0007结束",
                            quote = "\u000C\n首行\u0000\n\n尾行  ",
                        ),
                    ),
                ),
            ),
        )

        val content = render(document).content

        assertTrue(content.contains("- 位置：第 1 段结束"))
        // 换页 / NUL 被去掉，行内空行保留，行尾空格被 trim。
        assertTrue(content.contains("> 首行\n>\n> 尾行"))
        assertFalse(content.contains("\u000C"))
        assertFalse(content.contains("\u0000"))
        assertFalse(content.contains("\u0007"))
    }

    @Test
    fun authorAndFormatBlankFallBackToNothing() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            author = "   ",
            formatLabel = "",
            exportedAtMillis = at(2026, 11, 20, 10, 3),
            sections = listOf(AnnotationExportSection("章", listOf(highlight("第 1 段", quote = "高亮")))),
        )

        val content = render(document).content

        assertFalse(content.contains("- 作者："))
        assertFalse(content.contains("- 格式："))
    }

    // ---------------------------------------------------------------- 7. 文件名

    @Test
    fun suggestedFileNameFallsBackForBlankTitle() {
        assertEquals("未命名书籍-标注.md", AnnotationExportRenderer.suggestedFileName(""))
        assertEquals("未命名书籍-标注.md", AnnotationExportRenderer.suggestedFileName("   "))
        assertEquals("未命名书籍-标注.md", AnnotationExportRenderer.suggestedFileName("///"))
    }

    @Test
    fun suggestedFileNameCollapsesWhitespaceIncludingTabs() {
        assertEquals("a b-标注.md", AnnotationExportRenderer.suggestedFileName("a\t\tb"))
        assertEquals("a b-标注.md", AnnotationExportRenderer.suggestedFileName("  a   b  "))
    }

    @Test
    fun suggestedFileNameTruncatesToLimit() {
        val long = "书".repeat(100)
        val fileName = AnnotationExportRenderer.suggestedFileName(long)

        assertTrue(fileName.endsWith("-标注.md"))
        assertEquals(80, fileName.removeSuffix("-标注.md").length)
        assertEquals(80, AnnotationExportRenderer.MAX_FILE_STEM_CHARS)
    }

    @Test
    fun truncationKeepsSurrogatePairsIntact() {
        // 𝔘（U+1D518）在 UTF-16 里占两个单元；按码点截断才不会切出半个代理对。
        val raw = "\uD835\uDD18".repeat(100)
        val stem = AnnotationExportRenderer.suggestedFileName(raw).removeSuffix("-标注.md")

        assertEquals(80, stem.codePointCount(0, stem.length))
        assertEquals(160, stem.length)
    }

    @Test
    fun mimeAndPlaceholderConstantsAreStable() {
        assertEquals("text/markdown; charset=utf-8", AnnotationExportRenderer.MIME_MARKDOWN)
        assertEquals("（没有可导出的标注）", AnnotationExportRenderer.EMPTY_PLACEHOLDER)
    }

    // ---------------------------------------------------------------- 8. 时间

    @Test
    fun timesUseGivenZoneAtMinutePrecision() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            exportedAtMillis = at(2026, 11, 20, 10, 3),
            sections = listOf(
                AnnotationExportSection(
                    "章",
                    listOf(
                        highlight(
                            location = "第 1 段",
                            quote = "高亮",
                            createdAtMillis = at(2026, 11, 20, 22, 45),
                        ),
                    ),
                ),
            ),
        )

        val inShanghai = render(document, zone = shanghai).content
        assertTrue(inShanghai.contains("- 导出时间：2026-11-20 10:03"))
        assertTrue(inShanghai.contains("- 时间：2026-11-20 22:45"))

        // 同一个 epochMillis 换时区必须跟着变，证明 zone 参数真的被用上了。
        val inUtc = render(document, zone = ZoneId.of("UTC")).content
        assertTrue(inUtc.contains("- 导出时间：2026-11-20 02:03"))
        assertTrue(inUtc.contains("- 时间：2026-11-20 14:45"))
    }

    @Test
    fun zeroExportedAtMillisFallsBackToNow() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            exportedAtMillis = 0L,
            sections = listOf(AnnotationExportSection("章", listOf(highlight("第 1 段", quote = "高亮")))),
        )

        val content = render(document).content
        val line = content.lineSequence().firstOrNull { it.startsWith("- 导出时间：") }
        assertNotNull(line)
        val text = line!!.removePrefix("- 导出时间：")

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val parsed = LocalDateTime.parse(text, formatter).atZone(shanghai)
        // 不断言具体值，只断言落在「现在」的同一分钟（留一分钟余量应对边界）。
        val drift = Duration.between(parsed.toInstant(), Instant.now()).abs()
        assertTrue("导出时间与当前时间相差过大：$text", drift.toMinutes() <= 1)
    }

    @Test
    fun negativeTimestampsAreTreatedAsMissing() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            exportedAtMillis = -1L,
            sections = listOf(
                AnnotationExportSection(
                    "章",
                    listOf(highlight("第 1 段", quote = "高亮", createdAtMillis = -1L)),
                ),
            ),
        )

        val content = render(document).content

        assertFalse(content.contains("- 时间："))
        // exportedAtMillis <= 0 退回当前时间，仍然是到分钟的固定格式。
        val exportLine = content.lineSequence().first { it.startsWith("- 导出时间：") }
        assertEquals("- 导出时间：".length + 16, exportLine.length)
    }

    @Test
    fun entryWithoutTimestampOmitsTimeLine() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            sections = listOf(AnnotationExportSection("章", listOf(highlight("第 1 段", quote = "高亮")))),
        )

        val content = render(document, AnnotationExportOptions(includeFrontMatter = false)).content

        assertFalse(content.contains("- 时间："))
        assertTrue(content.contains("- 位置：第 1 段"))
    }

    // ---------------------------------------------------------------- 9. 段落规范

    @Test
    fun blocksAreSeparatedByExactlyOneBlankLine() {
        val document = AnnotationExportDocument(
            bookTitle = "书",
            author = "作者",
            formatLabel = "EPUB",
            exportedAtMillis = at(2026, 11, 20, 10, 3),
            sections = listOf(
                AnnotationExportSection(
                    "第一章",
                    listOf(
                        highlight("第 1 段", quote = "高亮一\n第二行", note = "附注\n续行"),
                        note("第 2 段", text = "笔记\n续行"),
                    ),
                ),
                AnnotationExportSection(
                    "第二章",
                    listOf(bookmark("第 9 页", quote = "折角")),
                ),
            ),
        )

        val content = render(document).content

        assertFalse("出现了连续多个空行", content.contains("\n\n\n"))
        assertTrue(content.endsWith("\n"))
        assertFalse(content.endsWith("\n\n"))
        // 行尾不留空格 / 制表符。
        content.split('\n').forEach { line ->
            assertEquals("行尾有空白：[$line]", line.trimEnd(), line)
        }
    }
}
