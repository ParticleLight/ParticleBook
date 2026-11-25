package app.particlebook.android.ui

import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [filterAnnotations] / [annotationFilterCounts] 的 JVM 单测：只覆盖纯列表逻辑，
 * 不碰 Compose 层（侧栏的显隐/排版由 UI 决定，这里只锁「哪些类型算哪一档、顺序是否保序」）。
 *
 * 关键约束：PDF 与 HIGHLIGHT 同属「高亮」档 —— PDF 几何标注在 store 里本来就落成 highlight，
 * 两者是同一批数据的两种编码，拆成两个 chip 会让列表看起来「选了一半」。
 */
class AnnotationFilterTest {

    private fun annotation(id: Long, type: ReaderAnnotationType): ReaderAnnotation =
        ReaderAnnotation(
            id = id,
            bookId = 42L,
            type = type,
            locator = ReaderLocator.Epub(chapterHref = "chap" + id + ".xhtml", blockIndex = 0),
            quote = "q" + id,
            createdAt = 1_000L * id,
        )

    private fun ids(annotations: List<ReaderAnnotation>): List<Long> = annotations.map { it.id }

    /** 按类型声明顺序造一批标注，id 从 1 起：只关心「有哪些档位」时比逐条手写 id 更好读。 */
    private fun annotationsOf(vararg types: ReaderAnnotationType): List<ReaderAnnotation> =
        types.mapIndexed { index, type -> annotation((index + 1).toLong(), type) }

    private val mixed = listOf(
        annotation(1, ReaderAnnotationType.BOOKMARK),
        annotation(2, ReaderAnnotationType.HIGHLIGHT),
        annotation(3, ReaderAnnotationType.NOTE),
        annotation(4, ReaderAnnotationType.PDF),
        annotation(5, ReaderAnnotationType.BOOKMARK),
        annotation(6, ReaderAnnotationType.NOTE),
    )

    /**
     * [resolveFilter] / [annotationFilterCounts] 的取样面：空表、每个档位单独出现、PDF 与笔记混合、全类型混合。
     * 两条属性用例（幂等、与计数一致）都跑这份样本，避免各自漏一个边界。
     */
    private val resolveSamples: List<List<ReaderAnnotation>> = listOf(
        emptyList(),
        annotationsOf(ReaderAnnotationType.BOOKMARK),
        annotationsOf(ReaderAnnotationType.HIGHLIGHT),
        annotationsOf(ReaderAnnotationType.NOTE),
        annotationsOf(ReaderAnnotationType.PDF),
        annotationsOf(ReaderAnnotationType.PDF, ReaderAnnotationType.NOTE),
        mixed,
    )

    // ---- ALL ----

    @Test
    fun all_returnsSameInstanceAndOrder() {
        val result = filterAnnotations(mixed, AnnotationFilter.ALL)
        assertSame("ALL 不该复制列表，调用方按引用比较时行为必须稳定", mixed, result)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), ids(result))
    }

    // ---- 单类型过滤 ----

    @Test
    fun bookmark_keepsOnlyBookmarksInOrder() {
        assertEquals(listOf(1L, 5L), ids(filterAnnotations(mixed, AnnotationFilter.BOOKMARK)))
    }

    @Test
    fun note_keepsOnlyNotesInOrder() {
        assertEquals(listOf(3L, 6L), ids(filterAnnotations(mixed, AnnotationFilter.NOTE)))
    }

    @Test
    fun highlight_includesPdfAnnotations() {
        // 2 是 HIGHLIGHT，4 是 PDF：两者都必须出现在「高亮」里，且保持原始相对顺序。
        assertEquals(listOf(2L, 4L), ids(filterAnnotations(mixed, AnnotationFilter.HIGHLIGHT)))
    }

    // ---- PDF 单独存在 ----

    @Test
    fun pdfOnly_appearsUnderHighlightAndNowhereElse() {
        val pdfs = listOf(annotation(10, ReaderAnnotationType.PDF), annotation(11, ReaderAnnotationType.PDF))
        assertEquals(listOf(10L, 11L), ids(filterAnnotations(pdfs, AnnotationFilter.HIGHLIGHT)))
        assertTrue(filterAnnotations(pdfs, AnnotationFilter.BOOKMARK).isEmpty())
        assertTrue(filterAnnotations(pdfs, AnnotationFilter.NOTE).isEmpty())
    }

    // ---- 空列表 ----

    @Test
    fun emptyList_everyFilterIsEmpty() {
        for (filter in AnnotationFilter.values()) {
            assertTrue("空列表在 " + filter + " 下必须为空", filterAnnotations(emptyList(), filter).isEmpty())
        }
        assertEquals(0, filterAnnotations(emptyList(), AnnotationFilter.ALL).size)
    }

    // ---- 全是一种类型 ----

    @Test
    fun singleType_neverCrashesAndCountsToOneBucket() {
        val notes = List(4) { annotation((it + 1).toLong(), ReaderAnnotationType.NOTE) }
        assertEquals(4, filterAnnotations(notes, AnnotationFilter.ALL).size)
        assertEquals(4, filterAnnotations(notes, AnnotationFilter.NOTE).size)
        assertTrue(filterAnnotations(notes, AnnotationFilter.HIGHLIGHT).isEmpty())
        // 只有一种可筛类型 -> 侧栏据此隐藏整行筛选。
        assertEquals(1, annotationFilterCounts(notes).size)
        assertEquals(AnnotationFilter.NOTE to 4, annotationFilterCounts(notes).single())
    }

    // ---- 混合列表里类型不互相串 ----

    @Test
    fun bookmarkAndNote_doNotBleedIntoEachOther() {
        val bookmarks = filterAnnotations(mixed, AnnotationFilter.BOOKMARK)
        val notes = filterAnnotations(mixed, AnnotationFilter.NOTE)
        assertTrue("书签结果里不该有笔记", bookmarks.none { it.type == ReaderAnnotationType.NOTE })
        assertTrue("笔记结果里不该有书签", notes.none { it.type == ReaderAnnotationType.BOOKMARK })
        assertEquals(listOf(1L, 5L), ids(bookmarks))
        assertEquals(listOf(3L, 6L), ids(notes))
    }

    // ---- 未知组合：PDF + NOTE 同时存在 ----

    @Test
    fun pdfAndNote_coexistWithoutLeaking() {
        val list = listOf(
            annotation(7, ReaderAnnotationType.PDF),
            annotation(8, ReaderAnnotationType.NOTE),
            annotation(9, ReaderAnnotationType.PDF),
        )
        assertEquals(listOf(7L, 9L), ids(filterAnnotations(list, AnnotationFilter.HIGHLIGHT)))
        assertEquals(listOf(8L), ids(filterAnnotations(list, AnnotationFilter.NOTE)))
        assertTrue(filterAnnotations(list, AnnotationFilter.BOOKMARK).isEmpty())
    }

    // ---- 计数辅助函数（决定筛选行显隐） ----

    @Test
    fun counts_skipAllAndEmptyBucketsInFixedOrder() {
        assertEquals(
            listOf(
                AnnotationFilter.BOOKMARK to 2,
                AnnotationFilter.HIGHLIGHT to 2,
                AnnotationFilter.NOTE to 2,
            ),
            annotationFilterCounts(mixed),
        )
    }

    @Test
    fun counts_mergePdfIntoHighlightBucket() {
        val list = listOf(
            annotation(1, ReaderAnnotationType.PDF),
            annotation(2, ReaderAnnotationType.HIGHLIGHT),
            annotation(3, ReaderAnnotationType.PDF),
        )
        // 3 条几何标注（1 条 HIGHLIGHT + 2 条 PDF）合并成「高亮 3」，也只有 1 个可筛档位。
        assertEquals(listOf(AnnotationFilter.HIGHLIGHT to 3), annotationFilterCounts(list))
        assertEquals(1, annotationFilterCounts(list).size)
    }

    // ---- resolveFilter：已消失的档位回落 ALL（防「列表永远空且无 chip 可点」死锁） ----

    @Test
    fun resolve_all_isStableRegardlessOfContent() {
        // ALL 表示「没有筛选」，与列表内容无关：空表、只有书签、混合列表都得原样留在 ALL。
        assertEquals(AnnotationFilter.ALL, resolveFilter(emptyList(), AnnotationFilter.ALL))
        assertEquals(
            AnnotationFilter.ALL,
            resolveFilter(annotationsOf(ReaderAnnotationType.BOOKMARK), AnnotationFilter.ALL),
        )
        assertEquals(AnnotationFilter.ALL, resolveFilter(mixed, AnnotationFilter.ALL))
    }

    @Test
    fun resolve_note_keptWhenNotesExist() {
        assertEquals(AnnotationFilter.NOTE, resolveFilter(mixed, AnnotationFilter.NOTE))
        assertEquals(
            AnnotationFilter.NOTE,
            resolveFilter(annotationsOf(ReaderAnnotationType.NOTE), AnnotationFilter.NOTE),
        )
    }

    @Test
    fun resolve_bookmark_keptWhenBookmarksExist_fallsBackOtherwise() {
        assertEquals(AnnotationFilter.BOOKMARK, resolveFilter(mixed, AnnotationFilter.BOOKMARK))
        assertEquals(
            AnnotationFilter.BOOKMARK,
            resolveFilter(annotationsOf(ReaderAnnotationType.BOOKMARK), AnnotationFilter.BOOKMARK),
        )
        // 只剩笔记：书签 chip 不存在了，档位必须回到 ALL，否则列表空且无处可点。
        assertEquals(
            AnnotationFilter.ALL,
            resolveFilter(annotationsOf(ReaderAnnotationType.NOTE), AnnotationFilter.BOOKMARK),
        )
    }

    @Test
    fun resolve_highlight_keptWhenHighlightsExist_fallsBackOtherwise() {
        assertEquals(AnnotationFilter.HIGHLIGHT, resolveFilter(mixed, AnnotationFilter.HIGHLIGHT))
        assertEquals(
            AnnotationFilter.HIGHLIGHT,
            resolveFilter(annotationsOf(ReaderAnnotationType.HIGHLIGHT), AnnotationFilter.HIGHLIGHT),
        )
        assertEquals(
            AnnotationFilter.ALL,
            resolveFilter(annotationsOf(ReaderAnnotationType.BOOKMARK), AnnotationFilter.HIGHLIGHT),
        )
    }

    @Test
    fun resolve_note_fallsBackToAllWhenOnlyHighlights() {
        val highlights = annotationsOf(
            ReaderAnnotationType.HIGHLIGHT,
            ReaderAnnotationType.HIGHLIGHT,
        )
        // 前置条件：这批数据里真的一条笔记都没有，否则这条用例测的就不是回落。
        assertTrue(filterAnnotations(highlights, AnnotationFilter.NOTE).isEmpty())
        assertEquals(AnnotationFilter.ALL, resolveFilter(highlights, AnnotationFilter.NOTE))
    }

    @Test
    fun resolve_pdfOnly_keepsHighlightAndFallsBackFromNote() {
        val pdfs = annotationsOf(ReaderAnnotationType.PDF, ReaderAnnotationType.PDF)
        // PDF 归「高亮」档：有 PDF 就算高亮档非空，选中它必须保留。
        assertEquals(AnnotationFilter.HIGHLIGHT, resolveFilter(pdfs, AnnotationFilter.HIGHLIGHT))
        // 但 PDF 不是笔记，选「笔记」时该档位一条都没有 -> 回 ALL。
        assertEquals(AnnotationFilter.ALL, resolveFilter(pdfs, AnnotationFilter.NOTE))
        assertEquals(AnnotationFilter.ALL, resolveFilter(pdfs, AnnotationFilter.BOOKMARK))
    }

    @Test
    fun resolve_emptyList_everyConcreteFilterFallsBack() {
        // 空列表下没有任何 chip，任何具体档位都无解，只能回 ALL。
        for (filter in AnnotationFilter.values()) {
            assertEquals(
                "空列表下 " + filter + " 必须回落到 ALL",
                AnnotationFilter.ALL,
                resolveFilter(emptyList(), filter),
            )
        }
        assertTrue(annotationFilterCounts(emptyList()).isEmpty())
    }

    @Test
    fun resolve_isIdempotent() {
        for (list in resolveSamples) {
            for (filter in AnnotationFilter.values()) {
                val once = resolveFilter(list, filter)
                // 期望值本身不变量：对已解析的结果再解析一次不得改变。
                assertEquals(
                    "resolveFilter 二次调用必须稳定：" + filter,
                    once,
                    resolveFilter(list, once),
                )
                // 同一输入重复调用也必须稳定（纯函数）。
                assertEquals("resolveFilter 重复调用必须稳定：" + filter, once, resolveFilter(list, filter))
            }
        }
    }

    @Test
    fun resolve_keepsFilterExactlyWhenItsChipWouldExist() {
        for (list in resolveSamples) {
            val available = annotationFilterCounts(list).map { it.first }.toSet()
            for (filter in AnnotationFilter.values()) {
                if (filter == AnnotationFilter.ALL) continue
                // 保留 <=> 该档位计数 > 0（chip 存在）；两者共用同一个 filterOf 分桶，结论必须一致。
                assertEquals(
                    "档位 " + filter + " 的取舍必须与 chip 是否存在一致",
                    filter in available,
                    resolveFilter(list, filter) == filter,
                )
            }
        }
    }

    @Test
    fun resolve_afterDeletingLastOfBucket_releasesFilterAndListIsVisibleAgain() {
        // 复现死锁：mixed 里有 3、6 两条笔记，先筛到「笔记」是合法的。
        assertEquals(AnnotationFilter.NOTE, resolveFilter(mixed, AnnotationFilter.NOTE))
        // 把笔记全部删掉：计数归零 -> 「笔记」chip 消失。
        val notesDeleted = mixed.filterNot { it.type == ReaderAnnotationType.NOTE }
        assertTrue(
            "前置条件：删完之后不该再有「笔记」chip",
            annotationFilterCounts(notesDeleted).none { it.first == AnnotationFilter.NOTE },
        )
        // 档位据此回落 ALL，列表恢复可见 —— 死锁解除。
        val resolved = resolveFilter(notesDeleted, AnnotationFilter.NOTE)
        assertEquals(AnnotationFilter.ALL, resolved)
        assertEquals(listOf(1L, 2L, 4L, 5L), ids(filterAnnotations(notesDeleted, resolved)))
    }
}

