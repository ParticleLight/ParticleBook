package app.particlebook.android.ui.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * task-42：书籍评分（1..5 星，null = 未评分）的编解码与持久化。
 *
 * 本地 JVM 单测里 android.jar 的 org.json 是 “not mocked”，生产 [JsonLibraryIndexCodec] 跑不起来，
 * 所以这里沿用仓库既有做法：
 * 1. 用 [RatingIndexCodec] 写出与生产完全相同的 books.json 文本（含 rating 字段），断言磁盘上的真实 JSON；
 * 2. 解码判定复用生产同一个纯函数 [readBookRating]（不经过 org.json，可在纯 JVM 覆盖脏值矩阵）；
 * 3. 再补一条源码契约测试，确认生产 codec / store 确实把 rating 接上（防止测试替身与生产漂移）。
 */
class LibraryRatingTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pb-library-rating-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ---------------------------------------------------------------- 纯函数：读侧判定 / 写侧 clamp

    @Test
    fun readBookRatingAcceptsOnlyWholeStarsInRange() {
        assertEquals(1, readBookRating(1))
        assertEquals(3, readBookRating(3L))
        assertEquals(5, readBookRating(5.0))
        // 缺失 / JSON null（调用方先判 isNull）→ 未评分
        assertNull(readBookRating(null))
        // 越界视为脏数据
        assertNull(readBookRating(0))
        assertNull(readBookRating(6))
        assertNull(readBookRating(-3))
        assertNull(readBookRating(99L))
        // 非整数 / 非数字
        assertNull(readBookRating(2.5))
        assertNull(readBookRating(4.9))
        assertNull(readBookRating("4"))
        assertNull(readBookRating(true))
    }

    @Test
    fun clampBookRatingClampsWritesAndKeepsNull() {
        assertEquals(1, clampBookRating(0))
        assertEquals(1, clampBookRating(-7))
        assertEquals(3, clampBookRating(3))
        assertEquals(5, clampBookRating(5))
        assertEquals(5, clampBookRating(6))
        assertEquals(5, clampBookRating(42))
        assertNull(clampBookRating(null))
    }

    // ---------------------------------------------------------------- JSON 编解码往返

    @Test
    fun indexCodecRoundTripKeepsRatingsAndWritesJsonNull() {
        val books = listOf(
            book("unrated.epub", id = 11L),
            book("one.epub", id = 12L, rating = 1),
            book("three.pdf", id = 13L, rating = 3),
            book("five.pdf", id = 14L, rating = 5),
        )
        val codec = RatingIndexCodec()
        val text = codec.encode(books)

        // null 必须写 JSON null（不是字段缺失、也不是 "null" 字符串）。
        val entries = indexEntries(text)
        assertEquals(4, entries.size)
        assertNull(entries[0]["rating"])
        assertTrue("未评分条目要有 rating 字段", entries[0].containsKey("rating"))
        assertTrue("索引文本要写 JSON null: " + text, text.contains("\"rating\":null"))
        assertEquals(1L, entries[1]["rating"])
        assertEquals(5L, entries[3]["rating"])

        // 往返：解码后每一本的评分与写入一致。
        val decoded = codec.decode(text)
        assertEquals(listOf(null, 1, 3, 5), decoded.map { it.rating })
        assertEquals("其它字段不能被编码路径带坏", books, decoded)
    }

    @Test
    fun legacyIndexWithoutRatingFieldDecodesAsUnrated() {
        // 旧版 books.json（task-42 之前）：完全没有 rating 字段。
        val legacy = """
            {"version":1,"books":[
              {"id":7,"title":"旧书 A","author":null,"format":"epub",
               "filePath":"/tmp/a.epub","fileSize":1024,"addedAt":null,"tags":["zlib-download"]},
              {"id":8,"title":"旧书 B","author":"作者","format":"pdf",
               "filePath":"/tmp/b.pdf","fileSize":2048,"addedAt":"2026-01-01T00:00:00Z","tags":[]}
            ]}
        """.trimIndent()
        val decoded = RatingIndexCodec().decode(legacy)
        assertEquals(2, decoded.size)
        assertEquals(listOf(null, null), decoded.map { it.rating })

        // 真机路径：旧索引照常加载，打一次分后新字段落盘，旧条目仍是「未评分」。
        runBlocking {
            val dir = newTempDir("legacy")
            File(dir, "books.json").writeText(legacy)
            val store = LibraryIndexStore(dir, RatingIndexCodec())
            val loaded = store.load { it }
            assertEquals(2, loaded.size)
            assertTrue("旧索引必须照常读入", loaded.all { it.rating == null })

            val updated = store.setRating(7L, 4)
            assertEquals(4, updated?.rating)
            val reopened = LibraryIndexStore(dir, RatingIndexCodec())
            assertEquals(listOf(4, null), reopened.load { it }.map { it.rating })
        }
    }

    @Test
    fun outOfRangeOrDirtyRatingDecodesAsUnrated() {
        val dirty = """
            {"version":1,"books":[
              {"id":1,"title":"零","format":"epub","filePath":"/tmp/zero.epub","fileSize":1,"rating":0},
              {"id":2,"title":"超上界","format":"epub","filePath":"/tmp/six.epub","fileSize":1,"rating":6},
              {"id":3,"title":"负数","format":"epub","filePath":"/tmp/neg.epub","fileSize":1,"rating":-2},
              {"id":4,"title":"小数","format":"epub","filePath":"/tmp/half.epub","fileSize":1,"rating":2.5},
              {"id":5,"title":"字符串","format":"epub","filePath":"/tmp/str.epub","fileSize":1,"rating":"4"},
              {"id":6,"title":"空值","format":"epub","filePath":"/tmp/null.epub","fileSize":1,"rating":null},
              {"id":7,"title":"正常","format":"epub","filePath":"/tmp/ok.epub","fileSize":1,"rating":4}
            ]}
        """.trimIndent()
        val decoded = RatingIndexCodec().decode(dirty)
        assertEquals(7, decoded.size)
        assertEquals("脏评分一律归 null，只有合法值保留", listOf(null, null, null, null, null, null, 4), decoded.map { it.rating })
    }

    // ---------------------------------------------------------------- IndexStore.setRating

    @Test
    fun setRatingClampsReturnsUpdatedBookAndIsReadBackFromDisk() = runBlocking {
        val dir = newTempDir("clamp")
        val store = LibraryIndexStore(dir, RatingIndexCodec())
        store.load { it }
        val target = store.registerFile(bookFile(dir, "target.epub"), "目标书", null, listOf(DOWNLOAD_TAG)).getOrThrow()
        val other = store.registerFile(bookFile(dir, "other.epub"), "另一本", null, emptyList()).getOrThrow()

        val clampedHigh = store.setRating(target.id, 9)
        assertEquals("越界写入要 clamp 到 5", 5, clampedHigh?.rating)
        assertEquals("返回的 id 不能变", target.id, clampedHigh?.id)
        // store.books（LibraryIndexStore.books）立即可见
        assertEquals(5, store.books.first { it.id == target.id }.rating)
        assertEquals("别的条目不能被改动", null, store.books.first { it.id == other.id }.rating)

        assertEquals(1, store.setRating(target.id, 0)?.rating)
        assertEquals(1, store.books.first { it.id == target.id }.rating)

        // 磁盘上是真实 JSON，且重新 decode 能读回
        val entries = indexEntries(File(dir, "books.json").readText())
        assertEquals(1L, entries.first { it["id"] == target.id }["rating"])
        assertNull("没打分的条目不能因为别人打分被写值", entries.first { it["id"] == other.id }["rating"])
        assertNoTempFilesLeft(dir)

        val reopened = LibraryIndexStore(dir, RatingIndexCodec())
        val reloaded = reopened.load { it }
        assertEquals(1, reloaded.first { it.id == target.id }.rating)
        assertNull(reloaded.first { it.id == other.id }.rating)
    }

    @Test
    fun setRatingNullClearsRatingEverywhere() = runBlocking {
        val dir = newTempDir("clear")
        val store = LibraryIndexStore(dir, RatingIndexCodec())
        store.load { it }
        val target = store.registerFile(bookFile(dir, "clear.epub"), "清除", null, emptyList()).getOrThrow()

        assertEquals(4, store.setRating(target.id, 4)?.rating)
        val cleared = store.setRating(target.id, null)
        assertEquals("清除评分返回更新后的条目", target.id, cleared?.id)
        assertNull(cleared?.rating)
        assertNull(store.books.first { it.id == target.id }.rating)

        val text = File(dir, "books.json").readText()
        assertTrue("清除要写 JSON null: " + text, text.contains("\"rating\":null"))
        assertNull(indexEntries(text).first { it["id"] == target.id }["rating"])

        val reopened = LibraryIndexStore(dir, RatingIndexCodec())
        assertNull(reopened.load { it }.first { it.id == target.id }.rating)
        assertNoTempFilesLeft(dir)
    }

    @Test
    fun setRatingUnknownIdReturnsNullWithoutTouchingDiskOrMemory() = runBlocking {
        val dir = newTempDir("unknown")
        val store = LibraryIndexStore(dir, RatingIndexCodec())
        store.load { it }
        store.registerFile(bookFile(dir, "known.epub"), "已知", null, emptyList()).getOrThrow()
        val indexFile = File(dir, "books.json")
        val beforeText = indexFile.readText()
        val beforeBooks = store.books

        val result = store.setRating(987654321L, 5)

        assertNull("未知 id 返回 null", result)
        assertEquals("未知 id 不写盘", beforeText, indexFile.readText())
        assertEquals("未知 id 不改内存", beforeBooks, store.books)
        assertNoTempFilesLeft(dir)
    }

    @Test
    fun setRatingPersistsAcrossReopenAndSequentialUpdatesKeepLatest() = runBlocking {
        val dir = newTempDir("sequential")
        val store = LibraryIndexStore(dir, RatingIndexCodec())
        store.load { it }
        val target = store.registerFile(bookFile(dir, "seq.epub"), "连续", null, emptyList()).getOrThrow()

        // 同一本书连续两次：必须串行落盘，后一次是最终状态（不丢更新、不留半写状态）。
        assertEquals(2, store.setRating(target.id, 2)?.rating)
        assertEquals(5, store.setRating(target.id, 5)?.rating)
        assertEquals(5, store.books.first { it.id == target.id }.rating)
        assertEquals(5L, indexEntries(File(dir, "books.json").readText()).first { it["id"] == target.id }["rating"])

        val reopened = LibraryIndexStore(dir, RatingIndexCodec())
        assertEquals(5, reopened.load { it }.first { it.id == target.id }.rating)
        assertNoTempFilesLeft(dir)

        // 再读一次也不会被「未评分」覆盖。
        val third = LibraryIndexStore(dir, RatingIndexCodec())
        assertEquals(5, third.load { it }.first { it.id == target.id }.rating)
    }

    @Test
    fun concurrentSetRatingOnTwoBooksKeepsBothUpdates() = runBlocking {
        val dir = newTempDir("concurrent")
        val store = LibraryIndexStore(dir, RatingIndexCodec())
        store.load { it }
        val first = store.registerFile(bookFile(dir, "first.epub"), "第一", null, emptyList()).getOrThrow()
        val second = store.registerFile(bookFile(dir, "second.epub"), "第二", null, emptyList()).getOrThrow()

        val barrier = CyclicBarrier(2)
        val results = listOf(first.id to 3, second.id to 1).map { (id, rating) ->
            async(Dispatchers.IO) {
                barrier.await(30, TimeUnit.SECONDS)
                store.setRating(id, rating)
            }
        }.awaitAll()

        assertEquals(listOf(3, 1), results.map { it?.rating })
        assertEquals(3, store.books.first { it.id == first.id }.rating)
        assertEquals(1, store.books.first { it.id == second.id }.rating)
        val decoded = RatingIndexCodec().decode(File(dir, "books.json").readText())
        assertEquals(3, decoded.first { it.id == first.id }.rating)
        assertEquals(1, decoded.first { it.id == second.id }.rating)
        assertNoTempFilesLeft(dir)
    }

    // ---------------------------------------------------------------- fixtures 合并保留评分

    @Test
    fun fixtureMergeKeepsPersistedRatingForSeedBooks() {
        val dir = newTempDir("merge")
        val seedFile = bookFile(dir, "seed.epub")
        val fixture = book(seedFile.absolutePath, id = 5L)
        val persisted = book(seedFile.absolutePath, id = 5L, rating = 4)
        val persistedOnly = book(bookFile(dir, "downloaded.epub").absolutePath, id = 9L, rating = 2)

        val merged = mergePersistedBooks(listOf(fixture), listOf(persisted, persistedOnly), dir)

        assertEquals(2, merged.size)
        assertEquals("fixtures 优先不能抹掉用户评分", 4, merged.first { it.id == 5L }.rating)
        assertEquals(2, merged.first { it.id == 9L }.rating)
        // 没有评分的样书仍是未评分（不凭空造分）。
        val unrated = mergePersistedBooks(listOf(book(seedFile.absolutePath, id = 5L)), listOf(book(seedFile.absolutePath, id = 5L)), dir)
        assertNull(unrated.single().rating)
    }

    // ---------------------------------------------------------------- 源码契约（防替身漂移）

    @Test
    fun productionCodecAndBridgeWireRating() {
        val storeSource = sourceFile("main/java/app/particlebook/android/ui/data/LibraryStore.kt")
        assertTrue("写入侧 null 必须落 JSON null", storeSource.contains(".put(\"rating\", book.rating ?: JSONObject.NULL)"))
        assertTrue("读取侧要判字段缺失 / JSON null", storeSource.contains("item.isNull(\"rating\")"))
        assertTrue("读取侧复用纯函数判定", storeSource.contains("readBookRating(item.opt(\"rating\"))"))
        assertTrue(
            "setRating 复用 clamp + 串行索引写路径",
            storeSource.contains("suspend fun setRating(bookId: Long, rating: Int?): Book?") &&
                storeSource.contains("val normalized = clampBookRating(rating)") &&
                storeSource.contains("writeIndexLocked(next)"),
        )

        val bridgeSource = sourceFile("main/java/app/particlebook/android/ui/data/LibraryCatalogBridge.kt")
        assertTrue("bookRows 要把 rating 映射到 BookRow", bridgeSource.contains("rating = book.rating"))
    }

    // ---------------------------------------------------------------- helpers

    private fun newTempDir(name: String): File = File(root, name).apply { mkdirs() }

    private fun bookFile(dir: File, name: String): File = File(dir, name).apply {
        if (name.endsWith(".pdf", ignoreCase = true)) {
            writeBytes("%PDF-1.7 rating test".toByteArray())
        } else {
            writeText("epub test content")
        }
    }

    private fun book(path: String, id: Long, rating: Int? = null): Book = Book(
        id = id,
        title = File(path).nameWithoutExtension,
        author = null,
        format = if (path.endsWith(".pdf", ignoreCase = true)) BookFormat.Pdf else BookFormat.Epub,
        filePath = path,
        fileSize = 0L,
        tags = emptyList(),
        rating = rating,
    )

    private fun indexEntries(text: String): List<Map<String, Any?>> {
        val parsed = TestJson.asObject(TestJson.parse(text), "books.json")
        return TestJson.asArray(parsed["books"], "books.json.books")
            .map { TestJson.asObject(it, "books[] 条目") }
    }

    private fun assertNoTempFilesLeft(dir: File) {
        val leftovers = dir.listFiles().orEmpty()
            .filter { it.name.startsWith("books.json.") && it.name.endsWith(".tmp") }
        assertTrue("不应残留临时文件: " + leftovers, leftovers.isEmpty())
    }

    /** 与 LibraryImportDedupeTest 相同的源码定位：从测试工作目录向上找 android/app 根。 */
    private fun sourceFile(relative: String): String {
        val candidates = ArrayList<File>()
        candidates += File(relative)
        candidates += File("app/" + relative)
        var dir: File? = File(System.getProperty("user.dir") ?: "").absoluteFile
        repeat(6) {
            val current = dir
            if (current != null) {
                candidates += File(current, "android/app/" + relative)
                candidates += File(current, "app/" + relative)
                candidates += File(current, relative)
                dir = current.parentFile
            }
        }
        val found = candidates.firstOrNull { it.isFile }
        assumeTrue(
            "找不到 " + relative + "（工作目录=" + System.getProperty("user.dir") + "），跳过源码契约检查",
            found != null,
        )
        return found!!.readText()
    }

    private companion object {
        const val DOWNLOAD_TAG = "zlib-download"
    }
}

/**
 * 单测专用评分编解码：字段与生产 [JsonLibraryIndexCodec] 一致（version=1 + books[]，camelCase），
 * 额外写 rating：null → JSON null，其它 → 整数。解码把 rating 交给生产同一个 [readBookRating]。
 */
private class RatingIndexCodec : LibraryIndexCodec {

    override fun encode(books: List<Book>): String = buildString {
        append("{\"version\":1,\"books\":[")
        books.forEachIndexed { index, book ->
            if (index > 0) append(',')
            append("{\"id\":").append(book.id)
            append(",\"title\":").append(TestJson.quote(book.title))
            append(",\"author\":").append(book.author?.let(TestJson::quote) ?: "null")
            append(",\"format\":").append(TestJson.quote(book.format.raw))
            append(",\"filePath\":").append(TestJson.quote(book.filePath))
            append(",\"fileSize\":").append(book.fileSize)
            append(",\"addedAt\":").append(book.addedAt?.let(TestJson::quote) ?: "null")
            append(",\"tags\":[")
            book.tags.forEachIndexed { tagIndex, tag ->
                if (tagIndex > 0) append(',')
                append(TestJson.quote(tag))
            }
            append(']')
            append(",\"rating\":").append(book.rating?.toString() ?: "null")
            append('}')
        }
        append("]}")
    }

    override fun decode(json: String): List<Book> {
        val root = TestJson.asObject(TestJson.parse(json), "books.json 根节点")
        val array = TestJson.asArray(root["books"], "books.json.books")
        return array.map { raw ->
            val item = TestJson.asObject(raw, "books[] 条目")
            val filePath = item["filePath"] as? String
                ?: throw IllegalArgumentException("books[] 缺少 filePath")
            Book(
                id = item["id"] as? Long ?: throw IllegalArgumentException("books[] 缺少 id"),
                title = item["title"] as? String ?: "未命名",
                author = item["author"] as? String,
                format = BookFormat.fromRaw(item["format"] as? String),
                filePath = filePath,
                fileSize = item["fileSize"] as? Long ?: 0L,
                addedAt = item["addedAt"] as? String,
                tags = (item["tags"]?.let { TestJson.asArray(it, "tags") } ?: emptyList())
                    .map { it as String },
                // 与生产 readIndexRating 同语义：缺字段/JSON null 先归 null，其余交给同一个纯函数。
                rating = readBookRating(item["rating"]),
            )
        }
    }
}
