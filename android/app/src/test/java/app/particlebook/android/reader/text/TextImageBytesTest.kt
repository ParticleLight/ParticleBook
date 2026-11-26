package app.particlebook.android.reader.text

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [TextImageBytes] 的纯 JVM 单测：内嵌字节与本地文件的读取口径。
 *
 * 契约（task-10）：
 * - [TextImage.bytes] 非空时**只认字节**，空或超过 [TextImage.MAX_BYTES] 一律 null，**不回退** [TextImage.path]；
 * - 返回数组是快照（copyOf），与源数组无别名：调用方改返回值不影响 [TextImage.bytes]，反之亦然；
 * - 本地文件先比 length 再读（最多 MAX_BYTES + 1 字节后复核），缺失 / 目录 / 空文件 / 异常 → null，且不修改源文件。
 *
 * 断言假设：
 * - 空文件按「没有这张图」处理，与内嵌空字节同口径；
 * - 大小上限对本地文件同样生效，MAX_BYTES + 1 必须被拒；
 * - 「先查 length」只是实现层的防竞态优化，不可从外部观测，这里只断言结果（null / 内容相等 / 长度不变）。
 */
internal class TextImageBytesTest {

    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private fun image(bytes: ByteArray? = null, path: String? = null): TextImage =
        TextImage(key = "test:image", bytes = bytes, path = path)

    private fun requireRead(image: TextImage): ByteArray =
        TextImageBytes.read(image) ?: throw AssertionError("expected bytes for image " + image.key)

    private fun writeFile(bytes: ByteArray): File {
        val file = temporaryFolder.newFile()
        file.writeBytes(bytes)
        return file
    }

    private fun payload(size: Int, seed: Int = 0): ByteArray = ByteArray(size) { index -> (index * 31 + seed).toByte() }

    // 1
    @Test
    fun embeddedBytesAreReturnedAsSnapshot() {
        val source = payload(16, seed = 0)
        val read = requireRead(image(bytes = source))
        assertArrayEquals(source, read)
        // 快照：不是同一个数组，读一次拷一次。
        assertNotSame(source, read)
        read[0] = 0x7F
        assertEquals("改返回值不应影响源数组", source[0].toInt(), 0)
        val second = requireRead(image(bytes = source))
        assertNotSame(read, second)
        assertEquals("源数组未被篡改", source[0].toInt(), second[0].toInt())
    }

    // 2
    @Test
    fun embeddedEmptyBytesAreRejected() {
        assertNull(TextImageBytes.read(image(bytes = ByteArray(0))))
    }

    // 3
    @Test
    fun embeddedBytesAtLimitAreAccepted() {
        val source = payload(TextImage.MAX_BYTES, seed = 0)
        val read = requireRead(image(bytes = source))
        assertEquals(TextImage.MAX_BYTES, read.size)
        assertEquals(source.first().toInt(), read.first().toInt())
        assertEquals(source.last().toInt(), read.last().toInt())
    }

    // 4
    @Test
    fun embeddedBytesOverLimitAreRejected() {
        assertNull(TextImageBytes.read(image(bytes = payload(TextImage.MAX_BYTES + 1))))
    }

    // 5
    @Test
    fun embeddedBytesNeverFallBackToReadablePath() {
        val file = writeFile(payload(64, seed = 1))
        assertNull("空字节不回退路径", TextImageBytes.read(image(bytes = ByteArray(0), path = file.absolutePath)))
        assertNull(
            "超限字节不回退路径",
            TextImageBytes.read(image(bytes = payload(TextImage.MAX_BYTES + 1), path = file.absolutePath)),
        )
        // 合规字节只认字节、不碰文件：path 指向目录也照样返回字节。
        val directory = temporaryFolder.newFolder()
        val read = requireRead(image(bytes = payload(8, seed = 2), path = directory.absolutePath))
        assertEquals(8, read.size)
    }

    // 6
    @Test
    fun imageWithoutBytesOrPathIsNull() {
        assertNull(TextImageBytes.read(TextImage(key = "test:none")))
        assertNull(TextImageBytes.read(image(path = "  ")))
    }

    // 7
    @Test
    fun missingBytesAreReadFromPath() {
        val source = payload(33, seed = 3)
        val file = writeFile(source)
        assertArrayEquals(source, requireRead(image(path = file.absolutePath)))
    }

    // 8
    @Test
    fun fileReadIsSnapshotAndLeavesSourceIntact() {
        val source = payload(32, seed = 4)
        val file = writeFile(source)
        val first = TextImageBytes.readFile(file.absolutePath)
            ?: throw AssertionError("expected first read of " + file.name)
        val second = TextImageBytes.readFile(file.absolutePath)
            ?: throw AssertionError("expected second read of " + file.name)
        assertArrayEquals(source, first)
        assertArrayEquals(source, second)
        assertNotSame(first, second)
        first[0] = 0x7F
        assertEquals("两次读取互不别名", source[0].toInt(), second[0].toInt())
        assertArrayEquals("读取不应改写源文件", source, file.readBytes())
    }

    // 9
    @Test
    fun missingFileIsNull() {
        val missing = File(temporaryFolder.root, "missing.bin")
        assertNull(TextImageBytes.readFile(missing.absolutePath))
    }

    // 10
    @Test
    fun directoryIsNull() {
        val directory = temporaryFolder.newFolder()
        assertNull(TextImageBytes.readFile(directory.absolutePath))
        assertNull(TextImageBytes.read(image(path = directory.absolutePath)))
    }

    // 11
    @Test
    fun emptyFileIsNull() {
        val file = writeFile(ByteArray(0))
        assertEquals(0L, file.length())
        assertNull(TextImageBytes.readFile(file.absolutePath))
    }

    // 12
    @Test
    fun fileAtLimitIsAccepted() {
        val source = payload(TextImage.MAX_BYTES, seed = 5)
        val file = writeFile(source)
        val read = TextImageBytes.readFile(file.absolutePath)
            ?: throw AssertionError("expected limit-size file bytes")
        assertEquals(TextImage.MAX_BYTES, read.size)
        assertArrayEquals(source, read)
    }

    // 13
    @Test
    fun fileOverLimitIsRejected() {
        val file = writeFile(payload(TextImage.MAX_BYTES + 1, seed = 6))
        assertEquals((TextImage.MAX_BYTES + 1).toLong(), file.length())
        assertNull(TextImageBytes.readFile(file.absolutePath))
    }

    // 14
    @Test
    fun nullAndBlankPathsAreRejected() {
        assertNull(TextImageBytes.readFile(null))
        assertNull(TextImageBytes.readFile(""))
        assertNull(TextImageBytes.readFile("   "))
    }
}
