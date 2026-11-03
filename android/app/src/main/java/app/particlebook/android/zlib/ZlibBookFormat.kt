package app.particlebook.android.zlib

import java.io.File

/**
 * 书籍格式嗅探（task-51 D14）：服务器给的 Content-Disposition/filename= 在 UI 路径不可达，
 * 而 detail.format 可能缺失/给错 → 下载完成后按文件头纠正扩展名，避免「EPUB 存成 .pdf
 * 然后交给 PDF 解析器必失败」。
 */
internal object ZlibBookFormat {

    const val PDF = "pdf"
    const val EPUB = "epub"

    /** 文件头嗅探：%PDF → pdf；ZIP（PK 03 04 / PK 05 06）→ epub。 */
    fun detect(prefix: ByteArray, length: Int = prefix.size): String? {
        if (length >= 4 &&
            prefix[0] == '%'.code.toByte() &&
            prefix[1] == 'P'.code.toByte() &&
            prefix[2] == 'D'.code.toByte() &&
            prefix[3] == 'F'.code.toByte()
        ) {
            return PDF
        }
        if (length >= 4 &&
            prefix[0] == 0x50.toByte() &&
            prefix[1] == 0x4B.toByte() &&
            prefix[2] == 0x03.toByte() &&
            prefix[3] == 0x04.toByte()
        ) {
            return EPUB
        }
        if (length >= 4 &&
            prefix[0] == 0x50.toByte() &&
            prefix[1] == 0x4B.toByte() &&
            prefix[2] == 0x05.toByte() &&
            prefix[3] == 0x06.toByte()
        ) {
            return EPUB
        }
        return null
    }

    /** 读文件前 8 字节嗅探；文件不存在/读失败返回 null。 */
    fun sniff(file: File): String? = try {
        if (!file.isFile || file.length() < 4L) {
            null
        } else {
            val prefix = ByteArray(8)
            file.inputStream().use { input ->
                var total = 0
                while (total < prefix.size) {
                    val read = input.read(prefix, total, prefix.size - total)
                    if (read < 0) break
                    total += read
                }
                detect(prefix, total)
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 按嗅探结果纠正扩展名：返回新文件名（含正确扩展）；不需要改名返回 null。
     * book.pdf + epub → book.epub；book + pdf → book.pdf。
     */
    fun correctedName(fileName: String, sniffed: String?): String? {
        if (sniffed.isNullOrBlank()) return null
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val current = if (dot > 0) fileName.substring(dot + 1).lowercase() else ""
        if (current == sniffed) return null
        return base + "." + sniffed
    }
}
