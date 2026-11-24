package app.particlebook.android.reader.export

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV 渲染的锁定测试：每条断言都对着 [AnnotationExportCsv] 的实际排版规则写，
 * 期望值是逐字符拼出来的，不用「包含」偷懒——CSV 的坑全在引号、BOM 和行尾。
 */
class AnnotationExportCsvTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")
    private val utc: ZoneId = ZoneId.of("UTC")

    /** 2023-11-14T22:13:20Z */
    private val sampleEpochMillis = 1_700_000_000_000L

    // ---- 1. 完整两行 + BOM + CRLF ------------------------------------------

    @Test
    fun rendersEveryRowVerbatimWithBomHeaderAndCrlf() {
        val document = AnnotationExportDocument(
            bookTitle = "测试书",
            author = "某作者",
            formatLabel = "EPUB",
            sections = listOf(
                AnnotationExportSection(
                    title = "第一章",
                    entries = listOf(
                        entry(
                            kind = AnnotationExportKind.HIGHLIGHT,
                            location = "第 1 段",
                            quote = "原文一",
                            color = "#FFF176",
                            createdAtMillis = sampleEpochMillis,
                        ),
                        entry(
                            kind = AnnotationExportKind.NOTE,
                            location = "第 2 段",
                            note = "想法",
                        ),
                    ),
                ),
                AnnotationExportSection(
                    title = "第 37 页",
                    entries = listOf(
                        entry(
                            kind = AnnotationExportKind.BOOKMARK,
                            location = "第 1 行",
                            quote = "书签名",
                        ),
                    ),
                ),
            ),
            exportedAtMillis = sampleEpochMillis,
        )

        val result = AnnotationExportCsv.renderCsv(document, AnnotationExportOptions(), shanghai)

        val expected = buildString {
            append("\uFEFF")
            append("类型,章节,位置,原文,笔记,颜色,时间")
            append("\r\n")
            append("高亮,第一章,第 1 段,原文一,,#FFF176,2023-11-15 06:13")
            append("\r\n")
            append("笔记,第一章,第 2 段,,想法,,")
            append("\r\n")
            append("书签,第 37 页,第 1 行,书签名,,,")
            append("\r\n")
        }
        assertEquals(expected, result.content)
        assertEquals(3, result.entryCount)
        assertFalse(result.isEmpty)
        assertEquals("text/csv; charset=utf-8", result.mimeType)
        assertEquals("测试书-标注.csv", result.fileName)
    }

    @Test
    fun exportedAtMillisDoesNotAffectContent() {
        val sections = listOf(
            AnnotationExportSection(
                title = "第一章",
                entries = listOf(entry(AnnotationExportKind.HIGHLIGHT, quote = "原文")),
            ),
        )
        val zero = AnnotationExportCsv.renderCsv(
            AnnotationExportDocument(bookTitle = "书", sections = sections, exportedAtMillis = 0L),
            AnnotationExportOptions(),
            shanghai,
        )
        val stamp = AnnotationExportCsv.renderCsv(
            AnnotationExportDocument(bookTitle = "书", sections = sections, exportedAtMillis = sampleEpochMillis),
            AnnotationExportOptions(),
            shanghai,
        )
        assertEquals(zero.content, stamp.content)
    }

    // ---- 2. 三种类型的中文标签 ---------------------------------------------

    @Test
    fun rendersChineseKindLabels() {
        val document = AnnotationExportDocument(
            bookTitle = "标签",
            sections = listOf(
                AnnotationExportSection(
                    title = "第一章",
                    entries = listOf(
                        entry(AnnotationExportKind.HIGHLIGHT, location = "第 1 段", quote = "高亮原文"),
                        entry(AnnotationExportKind.NOTE, location = "第 2 段", note = "笔记正文"),
                        entry(AnnotationExportKind.BOOKMARK, location = "第 3 段", quote = "书签名"),
                    ),
                ),
            ),
        )

        val rows = dataRows(AnnotationExportCsv.renderCsv(document, AnnotationExportOptions(), shanghai))

        assertEquals(3, rows.size)
        assertEquals("高亮,第一章,第 1 段,高亮原文,,,", rows[0])
        assertEquals("笔记,第一章,第 2 段,,笔记正文,,", rows[1])
        assertEquals("书签,第一章,第 3 段,书签名,,,", rows[2])
    }

    // ---- 3. 转义 -----------------------------------------------------------

    @Test
    fun escapesCommaQuotesLineBreaksAndSectionTitles() {
        val document = AnnotationExportDocument(
            bookTitle = "转义",
            sections = listOf(
                AnnotationExportSection(
                    title = "第一章, 起点",
                    entries = listOf(
                        entry(
                            kind = AnnotationExportKind.HIGHLIGHT,
                            location = "第 1 段",
                            quote = "他说,\"好\"",
                        ),
                        entry(
                            kind = AnnotationExportKind.NOTE,
                            location = "第 2 段",
                            note = "第一行\n第二行",
                        ),
                    ),
                ),
            ),
        )

        val result = AnnotationExportCsv.renderCsv(document, AnnotationExportOptions(), shanghai)

        val expected = buildString {
            append("\uFEFF")
            append("类型,章节,位置,原文,笔记,颜色,时间")
            append("\r\n")
            // 章节名含逗号 → 整列加引号；原文里的引号翻倍。
            append("高亮,\"第一章, 起点\",第 1 段,\"他说,\"\"好\"\"\",,,")
            append("\r\n")
            // 笔记保留换行，由引号包裹；原文成空列仍不加引号。
            append("笔记,\"第一章, 起点\",第 2 段,,\"第一行\n第二行\",,")
            append("\r\n")
        }
        assertEquals(expected, result.content)
        assertEquals(2, result.entryCount)
    }

    // ---- 4. 过滤 -----------------------------------------------------------

    @Test
    fun filtersByKindAndCountsOnlyWrittenRows() {
        val document = threeKindDocument()

        val result = AnnotationExportCsv.renderCsv(
            document,
            AnnotationExportOptions(
                includeBookmarks = false,
                includeHighlights = false,
                includeNotes = true,
            ),
            shanghai,
        )

        val expected = buildString {
            append("\uFEFF")
            append("类型,章节,位置,原文,笔记,颜色,时间")
            append("\r\n")
            append("笔记,第一章,第 2 段,,笔记正文,,")
            append("\r\n")
        }
        assertEquals(expected, result.content)
        assertEquals(1, result.entryCount)
        assertEquals(1, dataRows(result).size)
    }

    @Test
    fun allFiltersOffLeavesOnlyHeader() {
        val result = AnnotationExportCsv.renderCsv(
            threeKindDocument(),
            AnnotationExportOptions(
                includeBookmarks = false,
                includeHighlights = false,
                includeNotes = false,
            ),
            shanghai,
        )

        assertEquals("\uFEFF" + "类型,章节,位置,原文,笔记,颜色,时间" + "\r\n", result.content)
        assertEquals(0, result.entryCount)
        assertTrue(result.isEmpty)
    }

    @Test
    fun emptyDocumentLeavesOnlyHeader() {
        val result = AnnotationExportCsv.renderCsv(
            AnnotationExportDocument(bookTitle = "空书", sections = emptyList()),
            AnnotationExportOptions(),
            shanghai,
        )

        assertEquals(AnnotationExportCsv.BOM + AnnotationExportCsv.HEADER + AnnotationExportCsv.LINE_END, result.content)
        assertEquals(0, result.entryCount)
        assertTrue(result.isEmpty)
    }

    // ---- 5. 时间列 ---------------------------------------------------------

    @Test
    fun formatsTimestampInGivenZoneAndLeavesZeroEmpty() {
        val stamped = AnnotationExportDocument(
            bookTitle = "时间",
            sections = listOf(
                AnnotationExportSection(
                    title = "第一章",
                    entries = listOf(
                        entry(AnnotationExportKind.HIGHLIGHT, quote = "原文", createdAtMillis = sampleEpochMillis),
                    ),
                ),
            ),
        )

        val inShanghai = dataRows(AnnotationExportCsv.renderCsv(stamped, AnnotationExportOptions(), shanghai)).single()
        assertEquals("2023-11-15 06:13", inShanghai.split(",")[6])

        val inUtc = dataRows(AnnotationExportCsv.renderCsv(stamped, AnnotationExportOptions(), utc)).single()
        assertEquals("2023-11-14 22:13", inUtc.split(",")[6])

        val unstamped = AnnotationExportDocument(
            bookTitle = "时间",
            sections = listOf(
                AnnotationExportSection(
                    title = "第一章",
                    entries = listOf(
                        entry(AnnotationExportKind.HIGHLIGHT, quote = "原文", createdAtMillis = 0L),
                    ),
                ),
            ),
        )
        val row = dataRows(AnnotationExportCsv.renderCsv(unstamped, AnnotationExportOptions(), shanghai)).single()
        assertEquals("", row.split(",")[6])
        assertTrue("时间列为空时行尾应是裸逗号: " + row, row.endsWith(","))
    }

    // ---- 6. 文件名 ---------------------------------------------------------

    @Test
    fun derivesFileNameFromBookTitle() {
        assertEquals("未命名书籍-标注.csv", fileNameOf(""))
        assertEquals("未命名书籍-标注.csv", fileNameOf("   "))
        assertEquals("未命名书籍-标注.csv", fileNameOf(":::\\/"))
        assertEquals("深 夜 读书-标注.csv", fileNameOf("深 夜: 读/书"))
        assertEquals("abcdefghi-标注.csv", fileNameOf("a\\b:c*d?e\"f<g>h|i"))
        assertEquals("标题 空格-标注.csv", fileNameOf("标题\u3000\u3000空格"))
        assertEquals("书名-标注.csv", fileNameOf("书\u0007名"))
    }

    @Test
    fun truncatesLongBookTitleToEightyChars() {
        val fileName = fileNameOf("a".repeat(120))
        assertEquals("a".repeat(80) + "-标注.csv", fileName)
        assertTrue(fileName.endsWith("-标注.csv"))

        val exactlyEighty = "b".repeat(80)
        assertEquals(exactlyEighty + "-标注.csv", fileNameOf(exactlyEighty))
    }

    // ---- 7. 控制字符 -------------------------------------------------------

    @Test
    fun dropsControlCharactersButKeepsTabsInNotes() {
        val document = AnnotationExportDocument(
            bookTitle = "控制",
            sections = listOf(
                AnnotationExportSection(
                    title = "第\u0007一章",
                    entries = listOf(
                        entry(
                            kind = AnnotationExportKind.HIGHLIGHT,
                            location = "第\u00011段",
                            quote = "原\u0007文\u0000",
                            color = "\u0007#FF0000",
                        ),
                        entry(
                            kind = AnnotationExportKind.NOTE,
                            note = "a\tb",
                        ),
                    ),
                ),
            ),
        )

        val rows = dataRows(AnnotationExportCsv.renderCsv(document, AnnotationExportOptions(), shanghai))

        assertEquals("高亮,第一章,第1段,原文,,#FF0000,", rows[0])
        assertEquals("笔记,第一章,第 1 段,,a\tb,,", rows[1])
    }

    // ---- 8. 行结构 ---------------------------------------------------------

    @Test
    fun usesOnlyCrlfAndNeverEmitsBlankLines() {
        val document = AnnotationExportDocument(
            bookTitle = "行结构",
            sections = listOf(
                AnnotationExportSection(
                    title = "第一章",
                    entries = listOf(
                        entry(AnnotationExportKind.HIGHLIGHT, location = "第 1 段", quote = "第一行\n第二行"),
                        entry(AnnotationExportKind.HIGHLIGHT, location = "第 2\n段", quote = "甲\r\n乙"),
                        entry(AnnotationExportKind.NOTE, note = "带\r回车"),
                    ),
                ),
            ),
        )

        val result = AnnotationExportCsv.renderCsv(document, AnnotationExportOptions(), shanghai)
        val content = result.content

        assertFalse("不应出现连续换行", content.contains("\n\n"))
        assertFalse("不应出现裸 CRLF 以外的 CR", hasBareCarriageReturn(content))

        val parts = content.split("\r\n")
        assertEquals("split 后最后一段应为空（每行都以 CRLF 结尾）", "", parts.last())
        assertEquals(3 + 2, parts.size)
        assertEquals(3, result.entryCount)
        assertEquals(AnnotationExportCsv.BOM + AnnotationExportCsv.HEADER, parts.first())
        // 原文里的换行被保留（引号包裹），且 CRLF 已归一为 LF。
        assertTrue(content.contains("\"第一行\n第二行\""))
        assertTrue(content.contains("\"甲\n乙\""))
        assertTrue(content.contains("\"带\n回车\""))
        // 位置列的换行压成空格。
        assertTrue(content.contains(",第 2 段,"))
    }

    // ---- helpers -----------------------------------------------------------

    private fun entry(
        kind: AnnotationExportKind,
        location: String = "第 1 段",
        quote: String = "",
        note: String = "",
        color: String? = null,
        createdAtMillis: Long = 0L,
    ): AnnotationExportEntry = AnnotationExportEntry(
        kind = kind,
        location = location,
        quote = quote,
        note = note,
        color = color,
        createdAtMillis = createdAtMillis,
    )

    private fun threeKindDocument(): AnnotationExportDocument = AnnotationExportDocument(
        bookTitle = "过滤",
        sections = listOf(
            AnnotationExportSection(
                title = "第一章",
                entries = listOf(
                    entry(AnnotationExportKind.HIGHLIGHT, location = "第 1 段", quote = "高亮原文"),
                    entry(AnnotationExportKind.NOTE, location = "第 2 段", note = "笔记正文"),
                    entry(AnnotationExportKind.BOOKMARK, location = "第 3 段", quote = "书签名"),
                ),
            ),
        ),
    )

    private fun fileNameOf(bookTitle: String): String = AnnotationExportCsv
        .renderCsv(
            AnnotationExportDocument(bookTitle = bookTitle, sections = emptyList()),
            AnnotationExportOptions(),
            shanghai,
        )
        .fileName

    /** 去掉 BOM + 表头 + 行尾后的数据行（每行不含 CRLF）。 */
    private fun dataRows(result: AnnotationExportResult): List<String> {
        val parts = result.content.split("\r\n")
        return parts.subList(1, parts.size - 1)
    }

    private fun hasBareCarriageReturn(content: String): Boolean {
        var index = 0
        while (index < content.length) {
            if (content[index] == '\r') {
                if (index + 1 >= content.length || content[index + 1] != '\n') return true
                index++
            }
            index++
        }
        return false
    }
}
