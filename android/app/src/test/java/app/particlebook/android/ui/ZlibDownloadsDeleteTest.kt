package app.particlebook.android.ui

import app.particlebook.android.ui.data.Book
import app.particlebook.android.ui.data.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D8①：删下载记录时，「连文件一起删」不能删掉书架里书的本体。
 *
 * 默认下载目录导入走 adoptLocalFile 原地登记，book.filePath == 记录 destPath；
 * SAF 记录的书架副本在 _pb_files，路径不同，删原文件安全。这里锁定判断规则。
 */
class ZlibDownloadsDeleteTest {

    private fun book(filePath: String, vararg tags: String) = Book(
        id = 1L,
        title = "测试书",
        author = null,
        format = BookFormat.Epub,
        filePath = filePath,
        fileSize = 1024L,
        tags = tags.toList(),
    )

    @Test
    fun locallyAdoptedBookIsProtected() {
        val path = "/storage/emulated/0/Download/foo.epub"
        val shelf = listOf(book(path, "zlib:abc"))
        assertTrue(
            "默认目录原地登记的书 filePath 就是下载文件，删文件等于删书",
            bookReferencesDownloadFile(shelf, path),
        )
    }

    @Test
    fun safCopyDoesNotProtectDownloadFile() {
        val shelf = listOf(book("/data/user/0/app/files/_pb_files/foo.epub", "zlib:abc"))
        assertFalse(
            "SAF 记录的书架副本是复制件，删下载原文件安全",
            bookReferencesDownloadFile(shelf, "content://tree/doc/foo.epub"),
        )
    }

    @Test
    fun unrelatedBooksDoNotBlockDeletion() {
        val shelf = listOf(book("/data/user/0/app/files/_pb_files/bar.epub"))
        assertFalse(bookReferencesDownloadFile(shelf, "/storage/emulated/0/Download/foo.epub"))
        assertFalse(bookReferencesDownloadFile(emptyList(), "/storage/emulated/0/Download/foo.epub"))
    }

    @Test
    fun deleteResultMessagesDistinguishFileOutcome() {
        // D8③：文件删除失败 / 被下载占用 / 只删记录必须给不同文案，不能一律「已删除记录与文件」。
        assertEquals("记录已不存在", deleteHistoryMessage(ZlibHistoryDeleteResult.NOT_FOUND))
        assertEquals("已删除记录", deleteHistoryMessage(ZlibHistoryDeleteResult.RECORD_ONLY))
        assertEquals("已删除记录与文件", deleteHistoryMessage(ZlibHistoryDeleteResult.RECORD_AND_FILE))
        assertEquals(
            "记录已删除，文件删除失败",
            deleteHistoryMessage(ZlibHistoryDeleteResult.FILE_DELETE_FAILED),
        )
        assertEquals(
            "该书的下载仍在进行，仅删除记录",
            deleteHistoryMessage(ZlibHistoryDeleteResult.FILE_IN_USE_RECORD_ONLY),
        )
    }

    @Test
    fun blankDestPathNeverMatches() {
        val shelf = listOf(book(""))
        assertFalse(
            "记录没有可信路径时不能误判为书架本体",
            bookReferencesDownloadFile(shelf, ""),
        )
    }
}
