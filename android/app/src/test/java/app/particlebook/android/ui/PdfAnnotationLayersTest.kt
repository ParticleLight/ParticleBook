package app.particlebook.android.ui

import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.ReaderRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [pdfAnnotationsByPage] 的锁定测试。
 *
 * 期望值全部对着实现逐条推出来（PdfAnnotationLayers.kt:27-46），不按需求文字猜：
 * - 过滤次序是 locatorError -> type -> locator 形态（as? Pdf）-> page/rect；
 * - 排序键是 (page, rect.top, rect.left, id)，全部升序，而且先排序再分组；
 * - 分组用 groupBy，返回 LinkedHashMap，所以 map 的 key 顺序就是排序流里各页首次出现的顺序，
 *   即页号升序（page 是第一个排序键），不随入参顺序抖动。
 */
class PdfAnnotationLayersTest {

    // ---- 构造工具：只写必要字段，其余走 ReaderAnnotation 的默认值 ----

    private fun rect(left: Float, top: Float, right: Float = left + 0.1f, bottom: Float = top + 0.05f) =
        ReaderRect(left = left, top = top, right = right, bottom = bottom)

    private fun pdf(page: Int, rect: ReaderRect? = null) =
        ReaderLocator.Pdf(page = page, x = 0.1f, y = 0.2f, rect = rect)

    private fun annotation(
        id: Long,
        locator: ReaderLocator,
        type: ReaderAnnotationType = ReaderAnnotationType.HIGHLIGHT,
        locatorError: String? = null,
    ) = ReaderAnnotation(
        id = id,
        bookId = 7L,
        type = type,
        locator = locator,
        locatorError = locatorError,
    )

    /** 把结果压成「页号 -> id 列表」，断言顺序时比直接比 data class 更好读。 */
    private fun idsByPage(result: Map<Int, List<ReaderAnnotation>>): Map<Int, List<Long>> =
        result.mapValues { entry -> entry.value.map { it.id } }

    private fun assertPages(expected: List<Int>, result: Map<Int, List<ReaderAnnotation>>) {
        assertEquals(expected, result.keys.toList())
    }

    // ---- 1. 空输入 ---------------------------------------------------------

    @Test
    fun emptyInputReturnsEmptyMap() {
        val result = pdfAnnotationsByPage(emptyList())
        assertTrue(result.isEmpty())
        assertEquals(0, result.size)
    }

    @Test
    fun inputWithoutDrawableRecordsReturnsEmptyMap() {
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(1L, pdf(0, rect(0.1f, 0.1f)), type = ReaderAnnotationType.BOOKMARK),
                annotation(2L, pdf(3, null)),
                annotation(3L, ReaderLocator.Epub("OEBPS/ch01.xhtml", 0)),
                annotation(4L, pdf(-2, rect(0.2f, 0.2f))),
            ),
        )
        assertTrue(result.isEmpty())
        assertEquals(0, result.size)
    }

    // ---- 2. 单条 / 分组键 ------------------------------------------------

    @Test
    fun singleHighlightKeepsSameInstanceUnderItsPageKey() {
        val only = annotation(11L, pdf(3, rect(0.10f, 0.20f)))
        val result = pdfAnnotationsByPage(listOf(only))

        assertPages(listOf(3), result)
        assertEquals(listOf(only), result[3])
        assertEquals(1, result[3]?.size)
        assertEquals(11L, result[3]?.get(0)?.id)
    }

    @Test
    fun pagesAreSeparateGroupsAndKeysArePageAscending() {
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(1L, pdf(9, rect(0.1f, 0.1f))),
                annotation(2L, pdf(0, rect(0.1f, 0.1f))),
                annotation(3L, pdf(4, rect(0.1f, 0.1f))),
                annotation(4L, pdf(0, rect(0.2f, 0.2f))),
            ),
        )

        // 入参顺序是 9 / 0 / 4 / 0，输出 key 必须是升序：page 是首个排序键。
        assertPages(listOf(0, 4, 9), result)
        assertEquals(mapOf(0 to listOf(2L, 4L), 4 to listOf(3L), 9 to listOf(1L)), idsByPage(result))
    }

    // ---- 3. 非 Pdf locator 一律排除 -------------------------------------

    @Test
    fun epubLocatorIsExcluded() {
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(1L, ReaderLocator.Epub("OEBPS/ch01.xhtml", 3, 10, 5)),
                annotation(2L, pdf(2, rect(0.1f, 0.1f))),
            ),
        )
        assertEquals(listOf(2), result.keys.toList())
        assertEquals(listOf(2L), result[2]?.map { it.id })
    }

    @Test
    fun textLocatorIsExcluded() {
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(1L, ReaderLocator.Text(offset = 120, length = 8)),
                annotation(2L, pdf(1, rect(0.1f, 0.1f))),
            ),
        )
        assertEquals(listOf(1), result.keys.toList())
        assertEquals(listOf(2L), result[1]?.map { it.id })
    }

    @Test
    fun scrollLocatorIsExcluded() {
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(1L, ReaderLocator.Scroll(0.5f)),
                annotation(2L, pdf(0, rect(0.3f, 0.3f))),
            ),
        )
        assertEquals(listOf(0), result.keys.toList())
        assertEquals(listOf(2L), result[0]?.map { it.id })
    }

    @Test
    fun htmlLocatorIsExcluded() {
        // 实现用的是 as? ReaderLocator.Pdf，Html 同样不是 Pdf，一并被排除。
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(1L, ReaderLocator.Html("ch02.xhtml", "sec1", 0, 12)),
                annotation(2L, pdf(5, rect(0.2f, 0.2f))),
            ),
        )
        assertEquals(listOf(5), result.keys.toList())
        assertEquals(listOf(2L), result[5]?.map { it.id })
    }

    // ---- 4. 位置不可画的记录 --------------------------------------------

    @Test
    fun pdfLocatorWithoutRectIsExcluded() {
        // 单点位置（rect == null）没有可落笔的矩形。
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(1L, ReaderLocator.Pdf(page = 4, x = 0.3f, y = 0.4f, rect = null)),
                annotation(2L, pdf(4, rect(0.3f, 0.4f))),
            ),
        )
        assertEquals(listOf(4), result.keys.toList())
        assertEquals(listOf(2L), result[4]?.map { it.id })
    }

    @Test
    fun negativePageIsExcluded() {
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(1L, pdf(-1, rect(0.1f, 0.1f))),
                annotation(2L, pdf(-100, rect(0.2f, 0.2f))),
                annotation(3L, pdf(0, rect(0.1f, 0.1f))),
            ),
        )
        assertEquals(listOf(0), result.keys.toList())
        assertEquals(listOf(3L), result[0]?.map { it.id })
    }

    @Test
    fun recordWithLocatorErrorIsExcludedEvenWhenGeometryLooksValid() {
        // locatorError 非空时 locator 只是占位，即便它恰好是一个带矩形的高亮也不能画。
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(1L, pdf(1, rect(0.1f, 0.1f)), locatorError = "v1|pdf|broken"),
                annotation(2L, pdf(1, rect(0.2f, 0.2f))),
            ),
        )
        assertEquals(listOf(1), result.keys.toList())
        assertEquals(listOf(2L), result[1]?.map { it.id })
    }

    @Test
    fun emptyLocatorErrorStringIsAlsoExcluded() {
        // 实现的判据是 locatorError == null，不是「非空串」：空串同样被丢掉（实测，别按直觉写成保留）。
        val result = pdfAnnotationsByPage(
            listOf(annotation(1L, pdf(1, rect(0.1f, 0.1f)), locatorError = "")),
        )
        assertTrue(result.isEmpty())
    }

    // ---- 5. 类型白名单 ---------------------------------------------------

    @Test
    fun bookmarkIsExcluded() {
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(1L, pdf(0, rect(0.1f, 0.1f)), type = ReaderAnnotationType.BOOKMARK),
                annotation(2L, pdf(0, rect(0.2f, 0.2f)), type = ReaderAnnotationType.NOTE),
            ),
        )
        assertEquals(listOf(0), result.keys.toList())
        assertEquals(listOf(2L), result[0]?.map { it.id })
    }

    @Test
    fun highlightNoteAndPdfTypesAreAllKept() {
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(1L, pdf(6, rect(0.1f, 0.30f)), type = ReaderAnnotationType.HIGHLIGHT),
                annotation(2L, pdf(6, rect(0.1f, 0.10f)), type = ReaderAnnotationType.NOTE),
                annotation(3L, pdf(6, rect(0.1f, 0.20f)), type = ReaderAnnotationType.PDF),
            ),
        )
        assertEquals(listOf(6), result.keys.toList())
        assertEquals(listOf(2L, 3L, 1L), result[6]?.map { it.id })
        assertEquals(
            listOf(ReaderAnnotationType.NOTE, ReaderAnnotationType.PDF, ReaderAnnotationType.HIGHLIGHT),
            result[6]?.map { it.type },
        )
    }

    // ---- 6. 同页组内排序：top -> left -> id ------------------------------

    @Test
    fun samePageSortsByRectTopAscending() {
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(10L, pdf(2, rect(0.20f, 0.50f))),
                annotation(11L, pdf(2, rect(0.20f, 0.10f))),
                annotation(12L, pdf(2, rect(0.20f, 0.30f))),
            ),
        )
        assertEquals(listOf(11L, 12L, 10L), result[2]?.map { it.id })
    }

    @Test
    fun sameTopSortsByRectLeftAscending() {
        val top = 0.25f
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(20L, pdf(1, rect(0.50f, top))),
                annotation(21L, pdf(1, rect(0.10f, top))),
                annotation(22L, pdf(1, rect(0.30f, top))),
            ),
        )
        assertEquals(listOf(21L, 22L, 20L), result[1]?.map { it.id })
    }

    @Test
    fun sameTopAndLeftSortsByIdAscending() {
        val shared = rect(0.30f, 0.20f)
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(9L, pdf(0, shared)),
                annotation(5L, pdf(0, shared)),
                annotation(2L, pdf(0, shared)),
            ),
        )
        assertEquals(listOf(2L, 5L, 9L), result[0]?.map { it.id })
    }

    // ---- 7. 不丢数据 -----------------------------------------------------

    @Test
    fun everyDrawableRecordOnTheSamePageStaysInTheGroup() {
        val annotations = listOf(
            annotation(1L, pdf(3, rect(0.05f, 0.40f))),
            annotation(2L, pdf(3, rect(0.15f, 0.10f))),
            annotation(3L, pdf(3, rect(0.25f, 0.30f))),
            annotation(4L, pdf(3, rect(0.35f, 0.20f))),
            annotation(5L, pdf(3, rect(0.45f, 0.50f))),
        )
        val result = pdfAnnotationsByPage(annotations)

        assertEquals(listOf(3), result.keys.toList())
        assertEquals(5, result[3]?.size)
        assertEquals(listOf(2L, 4L, 3L, 1L, 5L), result[3]?.map { it.id })
        // 组内只重排、不丢、不去重：集合与入参一致。
        assertEquals(annotations.map { it.id }.toSet(), result[3]?.map { it.id }?.toSet())
    }

    @Test
    fun totalGroupSizesEqualDrawableRecordCount() {
        val drawable = listOf(
            annotation(1L, pdf(0, rect(0.10f, 0.10f)), type = ReaderAnnotationType.HIGHLIGHT),
            annotation(2L, pdf(0, rect(0.20f, 0.20f)), type = ReaderAnnotationType.NOTE),
            annotation(3L, pdf(2, rect(0.30f, 0.30f)), type = ReaderAnnotationType.PDF),
            annotation(4L, pdf(2, rect(0.40f, 0.40f)), type = ReaderAnnotationType.HIGHLIGHT),
        )
        val discarded = listOf(
            annotation(5L, pdf(1, rect(0.10f, 0.10f)), type = ReaderAnnotationType.BOOKMARK),
            annotation(6L, pdf(2, null)),
            annotation(7L, pdf(-5, rect(0.10f, 0.10f))),
            annotation(8L, pdf(1, rect(0.10f, 0.10f)), locatorError = "boom"),
            annotation(9L, ReaderLocator.Epub("OEBPS/ch01.xhtml", 1)),
            annotation(10L, ReaderLocator.Text(offset = 0)),
            annotation(11L, ReaderLocator.Scroll(0.9f)),
            annotation(12L, ReaderLocator.Html("ch01.xhtml")),
        )
        val result = pdfAnnotationsByPage(discarded + drawable.reversed())

        assertPages(listOf(0, 2), result)
        assertEquals(drawable.size, result.values.sumOf { it.size })
        assertEquals(drawable.map { it.id }.toSet(), result.values.flatten().map { it.id }.toSet())
    }

    @Test
    fun discardedRecordsDoNotCreatePageKeys() {
        // 第 5 页只有一条 rect 缺失的 NOTE，第 6 页只有一条 BOOKMARK —— 都不该留下空组。
        val result = pdfAnnotationsByPage(
            listOf(
                annotation(1L, pdf(5, null), type = ReaderAnnotationType.NOTE),
                annotation(2L, pdf(6, rect(0.1f, 0.1f)), type = ReaderAnnotationType.BOOKMARK),
                annotation(3L, pdf(7, rect(0.1f, 0.1f))),
            ),
        )
        assertEquals(listOf(7), result.keys.toList())
        assertEquals(mapOf(7 to listOf(3L)), idsByPage(result))
    }
}
