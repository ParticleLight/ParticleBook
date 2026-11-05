package app.particlebook.android.reader.epub.parser

import app.particlebook.android.reader.epub.EpubResourceResolver
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * 只读 EPUB ZIP 归档。打开时建立 条目名 -> ZipEntry 索引，避免每次 read 都枚举。
 *
 * 安全限制：
 * - 路径不允许逃出 ZIP 根（../ 由 EpubPaths 拒绝）；
 * - 单条目读取有上限，防止脏包把内存打爆；
 * - 重复条目名保留第一个。
 */
class EpubArchive private constructor(
    private val zip: ZipFile,
    private val entries: Map<String, ZipEntry>,
) : Closeable, EpubResourceResolver {

    val entryNames: List<String>
        get() = entries.keys.toList()

    fun hasEntry(href: String): Boolean = findEntry(href) != null

    /**
     * 读取条目；不存在返回 null。超过 maxBytes 抛 EpubParseException("ENTRY_TOO_LARGE")。
     * href 可以是根相对路径，允许带 fragment。
     */
    fun read(href: String, maxBytes: Int = MAX_ENTRY_BYTES): ByteArray? {
        val entry = findEntry(href) ?: return null
        if (entry.isDirectory) return null
        if (entry.size > maxBytes) {
            throw EpubParseException("ENTRY_TOO_LARGE: $href (${entry.size} > $maxBytes)")
        }
        return try {
            zip.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream(minOf(entry.size.toInt().coerceAtLeast(32), 64 * 1024))
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        throw EpubParseException("ENTRY_TOO_LARGE: $href (> $maxBytes)")
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (e: EpubParseException) {
            throw e
        } catch (e: IOException) {
            throw EpubParseException("READ_FAILED: $href: ${e.message}", e)
        }
    }

    override fun read(href: String): ByteArray? = read(href, MAX_RESOURCE_BYTES)

    override fun close() {
        try {
            zip.close()
        } catch (_: IOException) {
            // 关闭失败不影响解析结果
        }
    }

    private fun findEntry(href: String): ZipEntry? {
        val normalized = try {
            EpubPaths.normalizePath(href)
        } catch (_: EpubParseException) {
            return null
        }
        entries[normalized]?.let { return it }
        // ZIP 条目名里可能保留 %20 之类的字面量；再做一次未解码匹配。
        val raw = href.substringBefore('#').trim().removePrefix("/").replace('\\', '/')
        return entries[raw]
    }

    companion object {
        const val MAX_ENTRY_BYTES: Int = 16 * 1024 * 1024
        const val MAX_RESOURCE_BYTES: Int = 64 * 1024 * 1024

        fun open(file: File): EpubArchive {
            if (!file.isFile) throw EpubParseException("BAD_ZIP: not a file: ${file.path}")
            val zip = try {
                ZipFile(file)
            } catch (e: ZipException) {
                throw EpubParseException("BAD_ZIP: ${e.message}", e)
            } catch (e: IOException) {
                throw EpubParseException("BAD_ZIP: ${e.message}", e)
            }
            val entries = LinkedHashMap<String, ZipEntry>()
            try {
                val en = zip.entries()
                while (en.hasMoreElements()) {
                    val entry = en.nextElement()
                    val name = EpubPaths.normalizePath(entry.name, decodePercent = false)
                    if (name.isEmpty()) continue
                    if (!entries.containsKey(name)) entries[name] = entry
                }
            } catch (e: RuntimeException) {
                zip.close()
                throw EpubParseException("BAD_ZIP: ${e.message}", e)
            }
            if (entries.isEmpty()) {
                zip.close()
                throw EpubParseException("BAD_ZIP: no entries")
            }
            return EpubArchive(zip, entries)
        }
    }
}
