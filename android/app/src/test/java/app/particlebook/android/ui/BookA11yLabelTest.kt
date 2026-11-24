package app.particlebook.android.ui

import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架卡片的朗读契约。
 *
 * 网格卡与「最近添加」横滑卡现在共用 [Book.a11yLabel]：多选态只说「打开」不说「已选中」，
 * 读屏用户就不知道自己选中了没有；漏说「未选中」则无法区分离家状态。两边一旦各写一份文案，
 * 迟早一处有三个逗号一处没有，所以把这条契约钉在测试里。
 */
class BookA11yLabelTest {

    private fun book(
        title: String = "三体",
        author: String? = "刘慈欣",
        format: BookFormat = BookFormat.Epub,
    ) = Book(
        id = 7L,
        title = title,
        author = author,
        format = format,
        filePath = "p1/books/three-body.epub",
        fileSize = 1024L,
    )

    @Test
    fun browseModeSaysFormatAndProgressButNotSelection() {
        val label = book().a11yLabel("已读 45%", selectionMode = false, selected = false)
        assertEquals("《三体》，刘慈欣，EPUB，已读 45%", label)
        assertTrue(!label.contains("选中"))
    }

    @Test
    fun selectionModeAppendsSelected() {
        val label = book().a11yLabel("已读 45%", selectionMode = true, selected = true)
        assertEquals("《三体》，刘慈欣，EPUB，已读 45%，已选中", label)
    }

    @Test
    fun selectionModeAppendsNotSelectedWhenUnpicked() {
        val label = book().a11yLabel("未读", selectionMode = true, selected = false)
        assertEquals("《三体》，刘慈欣，EPUB，未读，未选中", label)
    }

    @Test
    fun unknownAuthorAndPdfAreLabelledExplicitly() {
        val label = book(author = "  ", format = BookFormat.Pdf)
            .a11yLabel("未读", selectionMode = false, selected = false)
        // 空白作者不朗读空串，回落到「未知作者」，否则读屏会念成两个逗号。
        assertEquals("《三体》，未知作者，PDF，未读", label)
    }

    @Test
    fun unsupportedFormatStaysDistinguishable() {
        val label = book(title = "QA unsupported docx", format = BookFormat.Unsupported)
            .a11yLabel("未读", selectionMode = false, selected = false)
        assertEquals("《QA unsupported docx》，刘慈欣，不支持，未读", label)
    }

    @Test
    fun selectionSuffixIsStableAcrossFormats() {
        // 横滑卡与网格卡共用这套文案：格式/进度怎么变，末位的选中后缀都必须还在。
        listOf(BookFormat.Epub, BookFormat.Pdf, BookFormat.Unsupported).forEach { format ->
            val label = book(format = format).a11yLabel("已读 3%", selectionMode = true, selected = true)
            assertTrue(label.endsWith("，已选中"))
        }
    }
}
