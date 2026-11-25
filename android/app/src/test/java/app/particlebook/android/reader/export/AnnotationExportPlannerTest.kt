package app.particlebook.android.reader.export

import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AnnotationExportPlanner] 的锁定测试：期望值都对着实现逐条推出来，不按需求描述猜。
 *
 * 三条最容易写错的语义先记在这里：
 * - `plan` 先对**全部**标注做一次全局排序（键见 `orderKey`），再按分组键首次出现建组；
 *   所以「组间顺序」是排序流里各组的首次出现顺序，不等于入参顺序。
 * - `sectionKey` 把 Text / Html / Scroll 分成**三个不同的键**，`sectionTitle` 对三者都返回
 *   「正文」——混合出现时会有多个都叫「正文」的组，不会合成一组。
 * - PDF 的 `page` 是 0 基，导出文字一律是 `page + 1`；EPUB 的 `blockIndex` 同理，位置是「第 blockIndex+1 段」。
 */
class AnnotationExportPlannerTest {

    private val ch01 = "OEBPS/text/ch01.xhtml"
    private val ch02 = "OEBPS/text/ch02.xhtml"

    // ---- 1. EPUB 两章：组标题、组间顺序、组内排序 ---------------------------

    @Test
    fun epubTwoChaptersGroupPerChapterAndSortEntriesByBlockThenOffset() {
        val annotations = listOf(
            annotation(epub(ch02, 5, 0), quote = "乙章一"),
            annotation(epub(ch01, 2, 5), quote = "甲章三"),
            annotation(epub(ch01, 0, 9), quote = "甲章一后"),
            annotation(epub(ch02, 6, 0), type = ReaderAnnotationType.NOTE, note = "乙章笔记"),
            annotation(epub(ch01, 0, 1), quote = "甲章一前"),
            annotation(epub(ch02, 5, 3), quote = "乙章一后"),
        )
        val titles = mapOf(ch01 to "第一章 起", ch02 to "第二章 承")

        val document = AnnotationExportPlanner.plan(
            book = book(),
            annotations = annotations,
            chapterTitleOf = { titles[it] },
        )

        // 组间顺序：ch01 的最小块号(0) 先出现，所以排在 ch02 之前。
        assertEquals(2, document.sections.size)

        val first = document.sections[0]
        assertEquals("第一章 起", first.title)
        assertEquals(listOf("甲章一前", "甲章一后", "甲章三"), first.entries.map { it.quote })
        assertEquals(listOf("第 1 段", "第 1 段", "第 3 段"), first.entries.map { it.location })
        assertEquals(
            listOf(
                AnnotationExportKind.HIGHLIGHT,
                AnnotationExportKind.HIGHLIGHT,
                AnnotationExportKind.HIGHLIGHT,
            ),
            first.entries.map { it.kind },
        )

        val second = document.sections[1]
        assertEquals("第二章 承", second.title)
        // charOffset 5 的两条按 offset 排，块号 5 的两条都落在「第 6 段」。
        assertEquals(listOf("乙章一", "乙章一后", ""), second.entries.map { it.quote })
        assertEquals(listOf("第 6 段", "第 6 段", "第 7 段"), second.entries.map { it.location })
        assertEquals(
            listOf(
                AnnotationExportKind.HIGHLIGHT,
                AnnotationExportKind.HIGHLIGHT,
                AnnotationExportKind.NOTE,
            ),
            second.entries.map { it.kind },
        )

        // 纯函数：不取时钟。
        assertEquals(0L, document.exportedAtMillis)
    }

    @Test
    fun chapterTitleProviderReceivesRawChapterHrefNotSectionKey() {
        val asked = ArrayList<String>()
        val titles = mapOf(ch01 to "第一章", ch02 to "第二章")

        AnnotationExportPlanner.plan(
            book = book(),
            annotations = listOf(annotation(epub(ch01, 0)), annotation(epub(ch02, 0))),
            chapterTitleOf = { href ->
                asked += href
                titles[href]
            },
        )

        assertEquals(setOf(ch01, ch02), asked.toSet())
        // 分组键是 "epub:" + href，但问标题时必须给原始 href。
        assertTrue(asked.none { it.startsWith("epub:") })
    }

    // ---- 2. href 兜底 ---------------------------------------------------------

    @Test
    fun missingChapterTitleFallsBackToHrefStemAndBlankHrefFallsBackToBodyTitle() {
        val withQuery = "OEBPS/text/ch03.xhtml?ver=2"
        val withFragment = "OEBPS/text/ch04.xhtml#sec2"
        val withoutExtension = "ch05"
        val irrelevant = "OEBPS/text/ch06.xhtml"

        val annotations = listOf(
            annotation(epub(withQuery, 0)),
            annotation(epub(withFragment, 1)),
            annotation(epub("", 2)),
            annotation(epub(withoutExtension, 3)),
            annotation(epub(irrelevant, 4)),
        )

        val document = AnnotationExportPlanner.plan(
            book = book(),
            annotations = annotations,
            chapterTitleOf = { href ->
                // 空白 TOC 标题也算「没有标题」，继续走 href 兜底。
                if (href == irrelevant) "   " else null
            },
        )

        assertEquals(
            listOf("ch03", "ch04", "正文", "ch05", "ch06"),
            document.sections.map { it.title },
        )
        // 空 href 不会产生空标题。
        assertTrue(document.sections.none { it.title.isBlank() })
    }

    @Test
    fun defaultChapterTitleProviderKeepsHrefStemTitles() {
        val document = AnnotationExportPlanner.plan(
            book(),
            listOf(annotation(epub("OEBPS/text/ch09.xhtml", 0))),
        )

        assertEquals("ch09", document.sections.single().title)
    }

    // ---- 3. PDF 一页一组 ------------------------------------------------------

    @Test
    fun pdfGroupsPerPageWithOneBasedTitlesAndYAscendingEntries() {
        val annotations = listOf(
            annotation(ReaderLocator.Pdf(page = 2, y = 0.5f), quote = "第 3 页下"),
            annotation(ReaderLocator.Pdf(page = 0, y = 0.7f), quote = "第 1 页下"),
            annotation(ReaderLocator.Pdf(page = 2, y = 0.1f), quote = "第 3 页上"),
            annotation(ReaderLocator.Pdf(page = 1, y = 0.3f), quote = "第 2 页唯一"),
            annotation(ReaderLocator.Pdf(page = 0, y = 0.2f), quote = "第 1 页上"),
        )

        val document = AnnotationExportPlanner.plan(book(format = BookFormat.Pdf), annotations)

        assertEquals(listOf("第 1 页", "第 2 页", "第 3 页"), document.sections.map { it.title })
        assertEquals(
            listOf(listOf("第 1 页上", "第 1 页下"), listOf("第 2 页唯一"), listOf("第 3 页上", "第 3 页下")),
            document.sections.map { section -> section.entries.map { it.quote } },
        )
        // 条目位置和组标题同一套文字，不重复给页内坐标。
        document.sections.forEach { section ->
            assertEquals(
                List(section.entries.size) { section.title },
                section.entries.map { it.location },
            )
        }
        assertEquals("PDF", document.formatLabel)
    }

    // ---- 4. 同一章多条只建一组 ------------------------------------------------

    @Test
    fun annotationsInSameChapterShareOneSection() {
        val annotations = listOf(
            annotation(epub(ch01, 3), quote = "四"),
            annotation(epub(ch01, 0), quote = "一"),
            annotation(epub(ch01, 4), quote = "五"),
            annotation(epub(ch01, 1), quote = "二"),
            annotation(epub(ch01, 2), quote = "三"),
        )

        val document = AnnotationExportPlanner.plan(
            book = book(),
            annotations = annotations,
            chapterTitleOf = { "同一章" },
        )

        val section = document.sections.single()
        assertEquals("同一章", section.title)
        assertEquals(5, section.entries.size)
        assertEquals(listOf("一", "二", "三", "四", "五"), section.entries.map { it.quote })
        assertEquals(listOf("第 1 段", "第 2 段", "第 3 段", "第 4 段", "第 5 段"), section.entries.map { it.location })
    }

    // ---- 5. locatorError 丢弃 -------------------------------------------------

    @Test
    fun annotationsWithLocatorErrorAreDroppedWithoutAffectingOtherRows() {
        val annotations = listOf(
            annotation(epub(ch01, 0), quote = "留一"),
            annotation(epub(ch02, 0), quote = "坏章", locatorError = "cannot parse locator"),
            annotation(ReaderLocator.Text(0), quote = "坏行", locatorError = "locator_json 损坏"),
            annotation(epub(ch01, 1), quote = "留二"),
            annotation(epub(ch01, 2), quote = "留三"),
        )

        val document = AnnotationExportPlanner.plan(book(), annotations)

        // 坏行里的 ch02 不该建组，Text 占位也不该带出「正文」组。
        val section = document.sections.single()
        assertEquals("ch01", section.title)
        assertEquals(listOf("留一", "留二", "留三"), section.entries.map { it.quote })
        assertEquals(listOf("第 1 段", "第 2 段", "第 3 段"), section.entries.map { it.location })
        assertTrue(document.sections.none { it.title == "正文" })
    }

    // ---- 6. 类型映射 ----------------------------------------------------------

    @Test
    fun typesMapToExportKindsAndNoteQuoteIsCleared() {
        val annotations = listOf(
            annotation(
                epub(ch01, 0),
                type = ReaderAnnotationType.BOOKMARK,
                quote = "书签名",
                note = "书签附注",
            ),
            annotation(epub(ch01, 1), type = ReaderAnnotationType.HIGHLIGHT, quote = "高亮原文"),
            annotation(
                epub(ch01, 2),
                type = ReaderAnnotationType.NOTE,
                quote = "笔记不该带引用",
                note = "笔记正文",
            ),
            annotation(
                ReaderLocator.Pdf(page = 9, y = 0.4f),
                type = ReaderAnnotationType.PDF,
                quote = "PDF 选区",
            ),
        )

        val document = AnnotationExportPlanner.plan(
            book = book(),
            annotations = annotations,
            chapterTitleOf = { "第一章" },
        )

        assertEquals(listOf("第一章", "第 10 页"), document.sections.map { it.title })

        val chapterEntries = document.sections[0].entries
        assertEquals(
            listOf(
                AnnotationExportKind.BOOKMARK,
                AnnotationExportKind.HIGHLIGHT,
                AnnotationExportKind.NOTE,
            ),
            chapterEntries.map { it.kind },
        )
        // 书签的 quote 保留（当书签名用）。
        assertEquals("书签名", chapterEntries[0].quote)
        assertEquals("书签附注", chapterEntries[0].note)
        // 笔记的 quote 被清空，笔记正文留在 note。
        assertEquals("", chapterEntries[2].quote)
        assertEquals("笔记正文", chapterEntries[2].note)

        val pdfEntry = document.sections[1].entries.single()
        // PDF 几何标注导出成高亮。
        assertEquals(AnnotationExportKind.HIGHLIGHT, pdfEntry.kind)
        assertEquals("PDF 选区", pdfEntry.quote)
        assertEquals("第 10 页", pdfEntry.location)
    }

    // ---- 7. 元数据 ------------------------------------------------------------

    @Test
    fun blankAuthorBecomesNullAndFormatLabelFollowsBookFormat() {
        assertNull(AnnotationExportPlanner.plan(book(author = ""), emptyList()).author)
        assertNull(AnnotationExportPlanner.plan(book(author = "   "), emptyList()).author)
        assertNull(AnnotationExportPlanner.plan(book(author = null), emptyList()).author)

        // 有内容时原样透传，不做 trim。
        assertEquals(" 某 作者 ", AnnotationExportPlanner.plan(book(author = " 某 作者 "), emptyList()).author)

        assertEquals("EPUB", AnnotationExportPlanner.plan(book(format = BookFormat.Epub), emptyList()).formatLabel)
        assertEquals("PDF", AnnotationExportPlanner.plan(book(format = BookFormat.Pdf), emptyList()).formatLabel)
        assertEquals(
            "不支持",
            AnnotationExportPlanner.plan(book(format = BookFormat.Unsupported), emptyList()).formatLabel,
        )
    }

    @Test
    fun bookTitleIsPassedThroughVerbatim() {
        val raw = "  带 空格\n换行\t与制表  "
        val document = AnnotationExportPlanner.plan(book(title = raw), emptyList())

        // 清洗是 Renderer 的事，Planner 只取字段。
        assertEquals(raw, document.bookTitle)
        assertTrue(document.sections.isEmpty())
        assertEquals(0L, document.exportedAtMillis)
    }

    // ---- 8. Text / Html / Scroll ---------------------------------------------

    @Test
    fun textLocatorUsesOneBasedCharacterOffsetInBodySection() {
        val annotations = listOf(
            annotation(ReaderLocator.Text(offset = 30), quote = "三十"),
            annotation(ReaderLocator.Text(offset = 5), quote = "五"),
            annotation(ReaderLocator.Text(offset = 0), type = ReaderAnnotationType.BOOKMARK, quote = "开头"),
        )

        val document = AnnotationExportPlanner.plan(book(), annotations)

        val section = document.sections.single()
        assertEquals("正文", section.title)
        assertEquals(listOf("第 1 字符", "第 6 字符", "第 31 字符"), section.entries.map { it.location })
        assertEquals(listOf("开头", "五", "三十"), section.entries.map { it.quote })
    }

    @Test
    fun htmlLocatorUsesFragmentOrDocumentStart() {
        val annotations = listOf(
            annotation(ReaderLocator.Html(href = "notes.html", fragment = "sec-3")),
            annotation(ReaderLocator.Html(href = "notes.html")),
            annotation(ReaderLocator.Html(href = "notes.html", fragment = "   ")),
        )

        val document = AnnotationExportPlanner.plan(book(), annotations)

        val locations = document.sections.single().entries.map { it.location }
        assertEquals("正文", document.sections.single().title)
        // 空白 fragment 与缺省 fragment 都退化成「文档开头」。
        assertEquals(2, locations.count { it == "文档开头" })
        assertEquals(listOf("sec-3"), locations.filter { it != "文档开头" })
    }

    @Test
    fun scrollLocatorRoundsPercentIntoBodySection() {
        val document = AnnotationExportPlanner.plan(
            book(),
            listOf(
                annotation(ReaderLocator.Scroll(0.4537f)),
                annotation(ReaderLocator.Scroll(0f)),
                annotation(ReaderLocator.Scroll(1f)),
            ),
        )

        val locations = document.sections.single().entries.map { it.location }
        assertEquals("正文", document.sections.single().title)
        assertEquals(3, locations.size)
        // (percent * 100).toInt() 截断，不四舍五入。
        assertTrue(locations.contains("约 45%"))
        assertTrue(locations.contains("约 0%"))
        assertTrue(locations.contains("约 100%"))
    }

    @Test
    fun textHtmlAndScrollShareOneBodySectionOrderedByLocatorKind() {
        val document = AnnotationExportPlanner.plan(
            book(),
            listOf(
                annotation(ReaderLocator.Scroll(0.3f)),
                annotation(ReaderLocator.Html(href = "notes.html", fragment = "sec-1")),
                annotation(ReaderLocator.Text(offset = 41)),
            ),
        )

        // 三者都没有章页可对位，标题都叫「正文」；按 locator 类型再拆组只会导出三个同名
        // 小节，所以合成一组，组内按档位：字符偏移 → 文档顺序 → 滚动百分比。
        assertEquals(1, document.sections.size)
        assertEquals("正文", document.sections.single().title)
        assertEquals(
            listOf("第 42 字符", "sec-1", "约 30%"),
            document.sections.single().entries.map { it.location },
        )
    }

    @Test
    fun manyMixedBodyLocatorsSortWithoutViolatingComparatorContract() {
        // 回归：Html/Scroll 曾经没有 orderKey、比较器左边返回 1 右边返回 -1，违反对称性；
        // 元素多到触发 TimSort 归并时会抛 IllegalArgumentException: Comparison method
        // violates its general contract。60 条混排必须能排完且一条不丢。
        val annotations = (0 until 60).map { index ->
            when (index % 3) {
                0 -> annotation(ReaderLocator.Scroll((index / 60f)))
                1 -> annotation(ReaderLocator.Html(href = "notes.html", fragment = "sec-" + index))
                else -> annotation(ReaderLocator.Text(offset = index))
            }
        }

        val entries = AnnotationExportPlanner.plan(book(), annotations).sections.single().entries

        assertEquals(60, entries.size)
        // 档位顺序：纯文本在最前、滚动在最后。
        assertEquals("第 3 字符", entries.first().location)
        assertTrue(entries.last().location.startsWith("约 "))
    }

    // ---- 9. 字段透传 ----------------------------------------------------------

    @Test
    fun createdAtAndColorArePassedThroughEntryByEntry() {
        val created = 1_700_000_123_456L
        val annotations = listOf(
            annotation(epub(ch01, 0), quote = "有颜色", color = "#FFF176", createdAt = created, note = "附注"),
            annotation(epub(ch01, 1), quote = "无颜色", color = null, createdAt = 0L),
        )

        val entries = AnnotationExportPlanner.plan(book(), annotations).sections.single().entries

        assertEquals(created, entries[0].createdAtMillis)
        assertEquals("#FFF176", entries[0].color)
        assertEquals("附注", entries[0].note)
        assertEquals(0L, entries[1].createdAtMillis)
        assertNull(entries[1].color)
        assertEquals("", entries[1].note)
    }

    @Test
    fun emptyAnnotationListKeepsBookMetadataOnly() {
        val document = AnnotationExportPlanner.plan(book(title = "空书", author = "作者", format = BookFormat.Pdf), emptyList())

        assertEquals("空书", document.bookTitle)
        assertEquals("作者", document.author)
        assertEquals("PDF", document.formatLabel)
        assertTrue(document.sections.isEmpty())
        assertEquals(0L, document.exportedAtMillis)
    }

    // ---- 10. textLocationOf ---------------------------------------------------

    @Test
    fun callerSuppliedTextLocationsReplaceCharacterOffsetsInBodySection() {
        val asked = ArrayList<Int>()
        val annotations = listOf(
            annotation(ReaderLocator.Text(offset = 199), quote = "一百九十九"),
            annotation(ReaderLocator.Text(offset = 0), quote = "开头"),
        )

        val document = AnnotationExportPlanner.plan(
            book = book(),
            annotations = annotations,
            textLocationOf = { offset ->
                asked += offset
                "第 " + (offset / 100) + " 段"
            },
        )

        val section = document.sections.single()
        assertEquals("正文", section.title)
        // 位置文案整句由调用方给，Planner 不再拼「第 N 字符」。
        assertEquals(listOf("第 0 段", "第 1 段"), section.entries.map { it.location })
        assertEquals(listOf("开头", "一百九十九"), section.entries.map { it.quote })
        // 传进去的是原始 0 基 offset，不是 1 基字符序数：199 若被 +1 就成了「第 2 段」。
        // 求值发生在排序前，按入参顺序每条 Text 一次（199 在前、0 在后）。
        assertEquals(listOf(199, 0), asked)
    }

    @Test
    fun omittedTextLocationProviderKeepsOneBasedCharacterOffsetText() {
        val annotations = listOf(
            annotation(ReaderLocator.Text(offset = 41), quote = "四十二"),
            annotation(ReaderLocator.Text(offset = 0), quote = "开头"),
        )

        // 不传 textLocationOf：走默认实现，位置仍是「第 offset+1 字符」。
        val document = AnnotationExportPlanner.plan(book(), annotations)

        val section = document.sections.single()
        assertEquals("正文", section.title)
        assertEquals(listOf("第 1 字符", "第 42 字符"), section.entries.map { it.location })

        // 显式传默认实现（恒 null）与不传等价：默认参数没被顺手换成别的文案。
        val explicitDefault = AnnotationExportPlanner.plan(
            book = book(),
            annotations = annotations,
            textLocationOf = { null },
        )
        assertEquals(
            document.sections.single().entries.map { it.location },
            explicitDefault.sections.single().entries.map { it.location },
        )
    }

    @Test
    fun nullAndBlankTextLocationsFallBackToCharacterOffset() {
        val annotations = listOf(
            annotation(ReaderLocator.Text(offset = 6), quote = "返回 null"),
            annotation(ReaderLocator.Text(offset = 7), quote = "返回空格"),
            annotation(ReaderLocator.Text(offset = 8), quote = "返回控制空白"),
        )

        val document = AnnotationExportPlanner.plan(
            book = book(),
            annotations = annotations,
            textLocationOf = { offset ->
                when (offset) {
                    6 -> null
                    7 -> "   "
                    else -> "\t\r\n"
                }
            },
        )

        // 判空用 isBlank 而不是 isEmpty：纯空白与控制空白都退回字符序数（offset + 1）。
        assertEquals(
            listOf("第 7 字符", "第 8 字符", "第 9 字符"),
            document.sections.single().entries.map { it.location },
        )
        assertEquals(
            listOf("返回 null", "返回空格", "返回控制空白"),
            document.sections.single().entries.map { it.quote },
        )
    }

    @Test
    fun textLocationProviderLeavesEpubAndPdfLocationsUntouched() {
        val annotations = listOf(
            annotation(ReaderLocator.Text(offset = 250), quote = "正文选区"),
            annotation(epub(ch01, blockIndex = 2, charOffset = 4), quote = "章节选区"),
            annotation(ReaderLocator.Pdf(page = 4, y = 0.5f), quote = "页内选区"),
        )
        val asked = ArrayList<Int>()

        val baseline = AnnotationExportPlanner.plan(book = book(), annotations = annotations)
        val withProvider = AnnotationExportPlanner.plan(
            book = book(),
            annotations = annotations,
            textLocationOf = { offset ->
                asked += offset
                "第 " + (offset / 100) + " 段"
            },
        )

        // 分组与组间顺序只由 locator 决定：章 → 页 → 正文。
        assertEquals(listOf("ch01", "第 5 页", "正文"), withProvider.sections.map { it.title })
        assertEquals(
            listOf("章节选区", "页内选区", "正文选区"),
            withProvider.sections.flatMap { section -> section.entries.map { it.quote } },
        )
        // EPUB「第 blockIndex+1 段」与 PDF「第 page+1 页」不受 provider 影响，只有 Text 换文案。
        assertEquals(
            listOf("第 3 段", "第 5 页", "第 2 段"),
            withProvider.sections.flatMap { section -> section.entries.map { it.location } },
        )
        assertEquals(
            listOf("第 3 段", "第 5 页", "第 251 字符"),
            baseline.sections.flatMap { section -> section.entries.map { it.location } },
        )
        // provider 只对 ReaderLocator.Text 求值一次，其余 locator 一次都没问。
        assertEquals(listOf(250), asked)
    }

    @Test
    fun textLocationProviderDoesNotChangeBodyGroupingOrEntryOrder() {
        val annotations = listOf(
            annotation(ReaderLocator.Scroll(0.5f), quote = "滚动"),
            annotation(ReaderLocator.Html(href = "notes.html", fragment = "sec-2"), quote = "网页"),
            annotation(ReaderLocator.Text(offset = 100), quote = "晚"),
            annotation(ReaderLocator.Text(offset = 12), quote = "早"),
        )

        val baseline = AnnotationExportPlanner.plan(book(), annotations)
        val withProvider = AnnotationExportPlanner.plan(
            book = book(),
            annotations = annotations,
            textLocationOf = { offset -> "第 " + (offset / 100) + " 段" },
        )

        // 分组口径不变：三种 locator 仍合成一个「正文」组，组内 字符偏移 → 文档顺序 → 滚动。
        assertEquals(1, withProvider.sections.size)
        assertEquals("正文", withProvider.sections.single().title)
        assertEquals(
            listOf("早", "晚", "网页", "滚动"),
            withProvider.sections.single().entries.map { it.quote },
        )
        assertEquals(
            listOf("第 13 字符", "第 101 字符", "sec-2", "约 50%"),
            baseline.sections.single().entries.map { it.location },
        )
        assertEquals(
            listOf("第 0 段", "第 1 段", "sec-2", "约 50%"),
            withProvider.sections.single().entries.map { it.location },
        )
    }

    // ---- 造数据 ---------------------------------------------------------------

    private companion object {
        private const val BOOK_ID = 42L
    }

    private fun book(
        title: String = "测试书",
        author: String? = "某作者",
        format: BookFormat = BookFormat.Epub,
    ): Book = Book(
        id = BOOK_ID,
        title = title,
        author = author,
        format = format,
        filePath = "/sdcard/Books/fixture." + format.raw,
        fileSize = 2_048L,
    )

    private fun epub(href: String, blockIndex: Int, charOffset: Int = 0): ReaderLocator.Epub =
        ReaderLocator.Epub(chapterHref = href, blockIndex = blockIndex, charOffset = charOffset)

    private fun annotation(
        locator: ReaderLocator,
        type: ReaderAnnotationType = ReaderAnnotationType.HIGHLIGHT,
        quote: String = "",
        note: String = "",
        color: String? = null,
        createdAt: Long = 0L,
        locatorError: String? = null,
    ): ReaderAnnotation = ReaderAnnotation(
        bookId = BOOK_ID,
        type = type,
        locator = locator,
        quote = quote,
        note = note,
        color = color,
        createdAt = createdAt,
        locatorError = locatorError,
    )
}
