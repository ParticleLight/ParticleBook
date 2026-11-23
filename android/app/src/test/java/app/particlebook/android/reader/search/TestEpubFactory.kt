package app.particlebook.android.reader.search

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 最小 EPUB 3 夹具：mimetype + container.xml + content.opf + 若干 XHTML 章节。
 *
 * 三个可配置点对应索引器的真实边界：
 * - [spine] 与章节列表解耦，用来造重复 spine 条目；
 * - [missingEntries] 让 spine/manifest 里存在、ZIP 里缺失的章节变成「坏章」；
 * - [chapters] 的 body 直接写 HTML，用来造空章（只有图片/空白）。
 *
 * 所有 XML 都用 buildString 拼：raw string + trimIndent 在插入多行内容后，
 * 公共缩进会变小，`<?xml ...?>` 前会残留空格——XML 声明必须在字节 0，否则 SAX 直接报错。
 */
internal object TestEpubFactory {

    data class Chapter(
        val fileName: String,
        val body: String,
        val title: String = fileName,
    )

    fun write(
        target: File,
        chapters: List<Chapter>,
        spine: List<String> = chapters.map { it.fileName },
        missingEntries: Set<String> = emptySet(),
    ): File {
        val ids = LinkedHashMap<String, String>()
        chapters.forEachIndexed { index, chapter -> ids[chapter.fileName] = "c" + (index + 1) }
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries += "mimetype" to "application/epub+zip".toByteArray()
        entries += "META-INF/container.xml" to containerXml().toByteArray()
        entries += "OEBPS/content.opf" to opf(chapters, ids, spine).toByteArray()
        chapters.forEach { chapter ->
            if (chapter.fileName !in missingEntries) {
                entries += "OEBPS/Text/" + chapter.fileName to chapterXhtml(chapter).toByteArray()
            }
        }
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return target
    }

    private fun chapterXhtml(chapter: Chapter): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<html xmlns=\"http://www.w3.org/1999/xhtml\">")
        appendLine("  <head><title>" + chapter.title + "</title></head>")
        appendLine("  <body>")
        appendLine(chapter.body)
        appendLine("  </body>")
        appendLine("</html>")
    }

    private fun opf(chapters: List<Chapter>, ids: Map<String, String>, spine: List<String>): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\">")
        appendLine("  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">")
        appendLine("    <dc:title>索引测试书</dc:title><dc:language>zh-CN</dc:language>")
        appendLine("  </metadata>")
        appendLine("  <manifest>")
        chapters.forEach { chapter ->
            appendLine(
                "    <item id=\"" + ids.getValue(chapter.fileName) + "\" href=\"Text/" +
                    chapter.fileName + "\" media-type=\"application/xhtml+xml\"/>",
            )
        }
        appendLine("  </manifest>")
        appendLine("  <spine>")
        spine.forEach { fileName ->
            val id = ids[fileName] ?: error("spine references unknown chapter: " + fileName)
            appendLine("    <itemref idref=\"" + id + "\"/>")
        }
        appendLine("  </spine>")
        appendLine("</package>")
    }

    private fun containerXml(): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">")
        appendLine("  <rootfiles>")
        appendLine("    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>")
        appendLine("  </rootfiles>")
        appendLine("</container>")
    }
}
