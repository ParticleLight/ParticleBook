package app.particlebook.android.ui.data

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * task-25：冷启恢复用到的纯函数（不依赖 Android Context，可直接 JVM 单测）。
 * 覆盖 SAF 复制文件名 uuid/扩展名清洗、文件头嗅探、冷启候选过滤、恢复标题。
 */
class LibraryFileRecoveryTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pb-library-recovery-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun copyFileNameEmbedsTimestampAndUuidPrefix() {
        val name = libraryCopyFileName("zlib", "epub", 1_700_000_000_000L, "abcdef12-3456-7890-abcd-ef1234567890")
        assertEquals("zlib-1700000000000-abcdef12.epub", name)

        // 同一毫秒两次导入：uuid 不同 → 文件名不同，不会互相覆盖。
        val other = libraryCopyFileName("zlib", "epub", 1_700_000_000_000L, "ffffffff-0000")
        assertNotEquals(name, other)

        // 大写 uuid/前缀按小写落盘，避免大小写不敏感文件系统上的歧义。
        assertTrue(
            libraryCopyFileName("import", "pdf", 5L, "ABCDEF12-XXXX")
                .startsWith("import-5-abcdef12."),
        )
    }

    @Test
    fun sanitizeCopyExtensionKeepsOnlyAlphanumerics() {
        assertEquals("pdf", sanitizeCopyExtension("PDF", BookFormat.Pdf))
        assertEquals("epub", sanitizeCopyExtension("EPUB", BookFormat.Pdf))

        // 异常 DISPLAY_NAME（含路径分隔符）不得把扩展名带出目录。
        val pathLike = sanitizeCopyExtension("pdf/../../evil", BookFormat.Epub)
        assertTrue(pathLike.all { it.isLetterOrDigit() })
        assertTrue(pathLike.isNotEmpty())

        // 空扩展名按格式兜底。
        assertEquals("epub", sanitizeCopyExtension("", BookFormat.Epub))
        assertEquals("pdf", sanitizeCopyExtension("   ", BookFormat.Pdf))
    }

    @Test
    fun sniffBookFormatUsesFirstBytes() {
        assertEquals(BookFormat.Pdf, sniffBookFormat(write("a.bin", "%PDF-1.7".toByteArray())))
        assertEquals(
            BookFormat.Epub,
            sniffBookFormat(write("b.bin", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))),
        )
        assertNull(sniffBookFormat(write("c.bin", "plain text".toByteArray())))
        assertNull("读不到文件应返回 null", sniffBookFormat(File(root, "missing.epub")))
    }

    @Test
    fun recoverableFileFilterRejectsPartialAndNonBookFiles() {
        assertTrue(isRecoverableLibraryFile(write("import-1-abcd.epub", EPUB_HEADER)))
        assertTrue(isRecoverableLibraryFile(write("zlib-1-abcd.pdf", "%PDF-1.7".toByteArray())))

        assertFalse("复制中间态不得登记", isRecoverableLibraryFile(write("import-1-abcd.epub.tmp", EPUB_HEADER)))
        assertFalse("非 epub/pdf 扩展名不得登记", isRecoverableLibraryFile(write("notes.txt", EPUB_HEADER)))
        assertFalse("半个复制文件（无书籍头）不得登记", isRecoverableLibraryFile(write("partial.epub", "half".toByteArray())))
        assertFalse("目录不得登记", isRecoverableLibraryFile(File(root, "folder.epub").apply { mkdirs() }))
    }

    @Test
    fun recoveredTitleHidesGeneratedCopyNames() {
        assertEquals("恢复的书籍", recoveredBookTitle(File(root, "import-1700000000000-ab12cd34.epub")))
        assertEquals("恢复的书籍", recoveredBookTitle(File(root, "zlib-1700000000000.epub")))
        assertEquals("我的书", recoveredBookTitle(File(root, "我的书.epub")))
    }

    @Test
    fun isoUtcTimestampMatchesFixtureShape() {
        assertEquals("1970-01-01T00:00:00.000Z", isoUtcTimestamp(0L))
        assertTrue(
            isoUtcTimestamp(1_700_000_000_000L)
                .matches(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")),
        )
    }

    // ------------------------------------------------------------------ 纯文本格式识别

    @Test
    fun textFormatByExtensionIsCaseInsensitiveAndRejectsUnknownTypes() {
        // 扩展名大小写不敏感（SAF DISPLAY_NAME 大小写不可控）。
        assertEquals(BookFormat.Text, textFormatByExtension("TXT"))
        assertEquals(BookFormat.Text, textFormatByExtension("text"))
        assertEquals(BookFormat.Markdown, textFormatByExtension("MD"))
        assertEquals(BookFormat.Markdown, textFormatByExtension("Markdown"))

        // 无扩展名 / 非文本格式不能猜成文本，否则 AZW3 会被送进文本阅读器。
        assertNull(textFormatByExtension(""))
        assertNull(textFormatByExtension("azw3"))
    }

    @Test
    fun looksLikePlainTextAcceptsUtf8ChineseWithoutBom() {
        // UTF-8 中文（无 BOM）编码里没有 0x00，必须认作文本。
        val file = write("chinese.txt", "第一章 纯文本阅读器\n中文标点：测试。".toByteArray(Charsets.UTF_8))
        assertTrue(looksLikePlainText(file))
    }

    @Test
    fun looksLikePlainTextRejectsNulBytes() {
        assertFalse(
            "前 8 KiB 出现 0x00 视为二进制",
            looksLikePlainText(write("binary.txt", byteArrayOf(0x48, 0x65, 0x00, 0x6C, 0x6C, 0x6F))),
        )

        // 只扫前 8 KiB：窗口之后才出现的 0x00 不影响判定（大文件不做全量扫描）。
        val lateNul = ByteArray(8 * 1024 + 16) { 'a'.code.toByte() }.also {
            it[8 * 1024 + 1] = 0x00
        }
        assertTrue(looksLikePlainText(write("late-nul.txt", lateNul)))
    }

    @Test
    fun looksLikePlainTextAcceptsUtf16Bom() {
        // UTF-16 正文必然含大量 0x00，靠 FF FE / FE FF BOM 放行。
        assertTrue(looksLikePlainText(write("le.txt", utf16WithBom("纯文本内容", littleEndian = true))))
        assertTrue(looksLikePlainText(write("be.txt", utf16WithBom("纯文本内容", littleEndian = false))))
    }

    @Test
    fun looksLikePlainTextRejectsEmptyAndMissingFiles() {
        assertFalse("0 字节文件不算文本", looksLikePlainText(write("empty.txt", ByteArray(0))))
        assertFalse("不存在的文件不算文本", looksLikePlainText(File(root, "missing.txt")))
    }

    @Test
    fun sniffBookFormatPrefersPdfAndZipMagic() {
        assertEquals(
            BookFormat.Pdf,
            sniffBookFormat(write("book.bin", "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n".toByteArray())),
        )
        assertEquals(
            BookFormat.Epub,
            sniffBookFormat(write("book.zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00))),
        )
    }

    @Test
    fun sniffBookFormatFallsBackToTextExtension() {
        assertEquals(BookFormat.Text, sniffBookFormat(write("novel.txt", "山河故人".toByteArray(Charsets.UTF_8))))
        assertEquals(BookFormat.Markdown, sniffBookFormat(write("notes.md", "# 标题\n\n- 列表".toByteArray(Charsets.UTF_8))))

        // 文本扩展名但内容是二进制：不能登记成文本。
        assertNull(sniffBookFormat(write("fake.txt", byteArrayOf(0x00, 0x01, 0x02))))
        assertNull(sniffBookFormat(write("blob.bin", byteArrayOf(0x00, 0x01, 0x02))))
    }

    @Test
    fun detectBookFormatUsesHeaderWhenExtensionMissing() {
        // 无扩展名（下载器没给后缀）只能靠文件头。
        assertEquals(BookFormat.Pdf, detectBookFormat(write("no-ext", "%PDF-1.7\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))))
        assertEquals(BookFormat.Markdown, detectBookFormat(write("readme.md", "# 标题".toByteArray())))
        // 扩展名优先于内容：.pdf 即使文件头不合法也按 PDF 处理（扩展名来自下载器，可信）。
        assertEquals(BookFormat.Pdf, detectBookFormat(write("broken.pdf", "not a pdf".toByteArray())))
        assertEquals(BookFormat.Unsupported, detectBookFormat(write("random.dat", byteArrayOf(0x13, 0x37, 0x00, 0x42))))
    }

    @Test
    fun isRecoverableLibraryFileAcceptsOnlyRealBooks() {
        assertTrue(isRecoverableLibraryFile(write("novel.txt", "正文".toByteArray(Charsets.UTF_8))))
        assertTrue(isRecoverableLibraryFile(write("notes.md", "# 正文".toByteArray(Charsets.UTF_8))))

        assertFalse("扩展名不认识不得登记", isRecoverableLibraryFile(write("novel.bin", "正文".toByteArray())))
        assertFalse(
            "含 NUL 的 .txt 内容不像文本，不得登记",
            isRecoverableLibraryFile(write("fake.txt", byteArrayOf(0x41, 0x00, 0x42))),
        )
    }

    @Test
    fun classifyImportedFileTreatsPlainTextAsComplete() {
        assertEquals(
            ImportedFileIntegrity.COMPLETE,
            classifyImportedFile(write("novel.txt", "纯文本正文".toByteArray(Charsets.UTF_8))),
        )
        assertEquals(
            ImportedFileIntegrity.COMPLETE,
            classifyImportedFile(write("notes.md", "# 标题".toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun classifyImportedFileMarksUnknownForBinaryTextAndMissing() {
        // 文本扩展名但含 NUL：既不是完整文本，也无法断言是坏书 → UNKNOWN（不能据此删旧副本）。
        assertEquals(
            ImportedFileIntegrity.UNKNOWN,
            classifyImportedFile(write("fake.txt", byteArrayOf(0x41, 0x00, 0x42))),
        )
        assertEquals(ImportedFileIntegrity.UNKNOWN, classifyImportedFile(write("blob.bin", byteArrayOf(0x41, 0x42))))
        assertEquals(ImportedFileIntegrity.UNKNOWN, classifyImportedFile(File(root, "missing.txt")))
        // 0 字节是唯一能「确认损坏」的无争议形态。
        assertEquals(ImportedFileIntegrity.CORRUPT, classifyImportedFile(write("empty.txt", ByteArray(0))))
    }

    @Test
    fun classifyImportedFileChecksPdfTailAndZipCentralDirectory() {
        assertEquals(
            ImportedFileIntegrity.COMPLETE,
            classifyImportedFile(write("ok.pdf", "%PDF-1.7\n1 0 obj\n<<>>\nendobj\ntrailer\n%%EOF\n".toByteArray())),
        )
        assertEquals(
            ImportedFileIntegrity.CORRUPT,
            classifyImportedFile(write("truncated.pdf", "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".toByteArray())),
        )
        assertEquals(ImportedFileIntegrity.COMPLETE, classifyImportedFile(writeZip("ok.epub")))
        // 只有 ZIP 头、中央目录被截断 —— 下载中断的典型形态。
        assertEquals(
            ImportedFileIntegrity.CORRUPT,
            classifyImportedFile(
                write("truncated.epub", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00)),
            ),
        )
    }

    // ------------------------------------------------------------------ FB2 格式识别

    @Test
    fun looksLikeFb2AcceptsXmlDeclarationAndRootElement() {
        assertTrue(
            "标准 FB2（XML 声明 + FictionBook 根元素）应认作 FB2",
            looksLikeFb2(write("book.fb2", FB2_WITH_DECLARATION.toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun looksLikeFb2AcceptsDocumentStartingWithRootElement() {
        // 没有 XML 声明、直接以根元素开头也是合法 FB2。
        assertTrue(
            looksLikeFb2(write("no-decl.fb2", FB2_WITHOUT_DECLARATION.toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun looksLikeFb2SkipsUtf8Bom() {
        // UTF-8 BOM（EF BB BF）解出来是 \uFEFF，必须先跳过才看得到 XML 声明。
        val withBom = ("\uFEFF" + FB2_WITH_DECLARATION).toByteArray(Charsets.UTF_8)
        assertTrue(looksLikeFb2(write("bom.fb2", withBom)))
        // BOM 之后直接跟根元素（无声明）同样放行。
        assertTrue(
            looksLikeFb2(write("bom-root.fb2", ("\uFEFF" + FB2_WITHOUT_DECLARATION).toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun looksLikeFb2SkipsLeadingWhitespaceAndNewlines() {
        val padded = "\n\n \t\r\n  \t" + FB2_WITH_DECLARATION
        assertTrue(looksLikeFb2(write("padded.fb2", padded.toByteArray(Charsets.UTF_8))))

        val paddedRoot = "\r\n\t " + FB2_WITHOUT_DECLARATION
        assertTrue(looksLikeFb2(write("padded-root.fb2", paddedRoot.toByteArray(Charsets.UTF_8))))
    }

    @Test
    fun looksLikeFb2RejectsPlainText() {
        assertFalse(
            "纯文本不是 FB2",
            looksLikeFb2(write("novel.fb2", "第一章 这不是 XML，只是改了后缀的纯文本".toByteArray(Charsets.UTF_8))),
        )
        assertFalse(looksLikeFb2(write("binary.fb2", byteArrayOf(0x00, 0x01, 0x02, 0x03))))
    }

    @Test
    fun looksLikeFb2RejectsEmptyAndMissingFiles() {
        assertFalse("0 字节文件不是 FB2", looksLikeFb2(write("empty.fb2", ByteArray(0))))
        assertFalse("不存在的文件不是 FB2", looksLikeFb2(File(root, "missing.fb2")))
    }

    @Test
    fun looksLikeFb2RejectsXmlWithForeignRootElement() {
        // 是 XML 但根元素不是 FictionBook：不能进 FB2 分支。
        val catalog = XML_DECLARATION + "<catalog><book>测试</book></catalog>"
        assertFalse(looksLikeFb2(write("catalog.fb2", catalog.toByteArray(Charsets.UTF_8))))
        assertFalse(
            looksLikeFb2(write("catalog-root.fb2", "<catalog><book>测试</book></catalog>".toByteArray(Charsets.UTF_8))),
        )
        // 只有 XML 声明、正文里没有 FictionBook 根元素同样否掉。
        assertFalse(
            looksLikeFb2(write("decl-only.fb2", (XML_DECLARATION + "<html><body/></html>").toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun sniffBookFormatValidatesFb2Content() {
        assertEquals(
            BookFormat.Fb2,
            sniffBookFormat(write("real.fb2", FB2_WITH_DECLARATION.toByteArray(Charsets.UTF_8))),
        )
        // .fb2 扩展名但内容不是 FB2：不能当书登记。
        assertNull(sniffBookFormat(write("fake.fb2", "只是纯文本".toByteArray(Charsets.UTF_8))))
        assertNull(sniffBookFormat(write("empty.fb2", ByteArray(0))))
        // 大小写不敏感（SAF DISPLAY_NAME 不可控）。
        assertEquals(
            BookFormat.Fb2,
            sniffBookFormat(write("UPPER.FB2", FB2_WITHOUT_DECLARATION.toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun sniffBookFormatKeepsTextExtensionForFb2Content() {
        // 扩展名优先：内容是 FB2 XML 的 .txt 仍然是纯文本，不能被 XML 抢走成 FB2。
        val file = write("misnamed.txt", FB2_WITH_DECLARATION.toByteArray(Charsets.UTF_8))
        assertEquals(BookFormat.Text, sniffBookFormat(file))
    }

    @Test
    fun isRecoverableLibraryFileAcceptsOnlyRealFb2UnderLibraryDir() {
        // 冷启扫描只扫 filesDir/_pb_files，真实 FB2 内容应可恢复。
        assertTrue(isRecoverableLibraryFile(libraryFile("real.fb2", FB2_WITH_DECLARATION.toByteArray(Charsets.UTF_8))))
        assertTrue(
            isRecoverableLibraryFile(libraryFile("root-only.fb2", FB2_WITHOUT_DECLARATION.toByteArray(Charsets.UTF_8))),
        )
        // 假内容 / 0 字节不得被冷启扫描登记成书。
        assertFalse(isRecoverableLibraryFile(libraryFile("fake.fb2", "纯文本".toByteArray(Charsets.UTF_8))))
        assertFalse(isRecoverableLibraryFile(libraryFile("empty.fb2", ByteArray(0))))
    }

    @Test
    fun classifyImportedFileMarksFb2ByContentNotExtension() {
        assertEquals(
            ImportedFileIntegrity.COMPLETE,
            classifyImportedFile(write("real.fb2", FB2_WITH_DECLARATION.toByteArray(Charsets.UTF_8))),
        )
        // 假内容既不能算完整，也不够格判「确认损坏」→ UNKNOWN（不能据此删旧副本）。
        assertEquals(
            ImportedFileIntegrity.UNKNOWN,
            classifyImportedFile(write("fake.fb2", "改了后缀的纯文本".toByteArray(Charsets.UTF_8))),
        )
        // 0 字节仍是唯一无争议的损坏形态。
        assertEquals(ImportedFileIntegrity.CORRUPT, classifyImportedFile(write("empty.fb2", ByteArray(0))))
    }

    @Test
    fun detectBookFormatTrustsFb2Extension() {
        assertEquals(
            BookFormat.Fb2,
            detectBookFormat(write("real.fb2", FB2_WITH_DECLARATION.toByteArray(Charsets.UTF_8))),
        )
        // detectBookFormat 只看扩展名（下载器给的后缀可信），内容不合也不会降级。
        assertEquals(BookFormat.Fb2, detectBookFormat(write("fake.fb2", "纯文本".toByteArray(Charsets.UTF_8))))
        assertEquals(
            BookFormat.Fb2,
            detectBookFormat(write("UPPER.FB2", FB2_WITHOUT_DECLARATION.toByteArray(Charsets.UTF_8))),
        )
    }

    // ------------------------------------------------------------------ HTML 格式识别

    @Test
    fun looksLikeHtmlAcceptsDoctypeDeclaration() {
        assertTrue(
            "带 <!doctype html> 的整页应认作 HTML",
            looksLikeHtml(
                write(
                    "page.html",
                    "<!DOCTYPE html>\n<html><body><p>正文</p></body></html>".toByteArray(Charsets.UTF_8),
                ),
            ),
        )
        // 判定前整段 lowercase，doctype 大小写不规范也放行（没有 body / html 骨架，只靠 doctype 命中）。
        assertTrue(
            looksLikeHtml(
                write("upper-doctype.html", "<!DoCtYpE HTML><head><title>t</title></head>".toByteArray(Charsets.UTF_8)),
            ),
        )
    }

    @Test
    fun looksLikeHtmlAcceptsHtmlRootWithoutDoctype() {
        // 没有 doctype、直接以 <html> 开头的整页同样是网页。
        assertTrue(
            looksLikeHtml(write("no-doctype.html", "<html>\n<body>正文</body>\n</html>".toByteArray(Charsets.UTF_8))),
        )
        // 只要有 <html 骨架，缺 head / body 也认。
        assertTrue(
            looksLikeHtml(write("html-only.html", "<html lang=\"zh\">正文</html>".toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun looksLikeHtmlAcceptsBodyOnlyFragment() {
        // 从网页 / Word 复制出来的正文往往只剩 <body> 片段。
        assertTrue(
            looksLikeHtml(
                write("body-fragment.html", "<body>\n<p>第一章</p>\n</body>".toByteArray(Charsets.UTF_8)),
            ),
        )
    }

    @Test
    fun looksLikeHtmlAcceptsParagraphFragment() {
        // 单个段落块也算 HTML 片段。
        assertTrue(looksLikeHtml(write("p-fragment.html", "<p>第一章 起点</p>".toByteArray(Charsets.UTF_8))))
        // 带属性的 <p ...> 走 `<p ` 分支（大小写由 lowercase 归一）。
        assertTrue(
            looksLikeHtml(
                write("p-attr.html", "<P class=\"lead\">第一章</P>".toByteArray(Charsets.UTF_8)),
            ),
        )
    }

    @Test
    fun looksLikeHtmlAcceptsDivFragment() {
        assertTrue(looksLikeHtml(write("div-fragment.html", "<div>第一章</div>".toByteArray(Charsets.UTF_8))))
        assertTrue(
            looksLikeHtml(
                write("div-attr.html", "<DIV id=\"c1\"><span>第一章</span></DIV>".toByteArray(Charsets.UTF_8)),
            ),
        )
    }

    @Test
    fun looksLikeHtmlAcceptsUppercaseTags() {
        assertTrue(
            looksLikeHtml(
                write("upper.html", "<HTML><BODY><P>正文</P></BODY></HTML>".toByteArray(Charsets.UTF_8)),
            ),
        )
    }

    @Test
    fun looksLikeHtmlSkipsUtf8Bom() {
        // UTF-8 BOM（EF BB BF）按 UTF-8 解出来是 \uFEFF；判定前 trimStart，BOM 不得影响结果。
        val withBom = "\uFEFF<!doctype html><p>正文</p>".toByteArray(Charsets.UTF_8)
        assertTrue(looksLikeHtml(write("bom.html", withBom)))
    }

    @Test
    fun looksLikeHtmlToleratesLeadingWhitespaceAndNewlines() {
        // 判定是子串匹配，标签前有空白 / 换行不影响命中。
        val padded = "\n\n \t\r\n<!doctype html>\n<html><body>正文</body></html>"
        assertTrue(looksLikeHtml(write("padded.html", padded.toByteArray(Charsets.UTF_8))))
        assertTrue(
            looksLikeHtml(write("padded-div.html", "\r\n\t  <div>正文</div>".toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun looksLikeHtmlRejectsPlainTextRenderedAsHtml() {
        // 没有任何标签的纯文本即使改名 .html 也不是网页（宁可漏认，不要错认）。
        assertFalse(
            looksLikeHtml(
                write("plain.html", "第一章 这不是网页，只是改了后缀的纯文本。".toByteArray(Charsets.UTF_8)),
            ),
        )
    }

    @Test
    fun looksLikeHtmlRejectsFb2Markup() {
        // FB2 正文里本来就带 <p> 元素，正是 <fictionbook 排除项把 XML 挡在 HTML 分支外。
        assertFalse(looksLikeHtml(write("book.fb2.html", FB2_WITH_DECLARATION.toByteArray(Charsets.UTF_8))))
        assertFalse(
            "根元素大小写不同同样排除",
            looksLikeHtml(write("upper.fb2.html", FB2_WITHOUT_DECLARATION.uppercase().toByteArray(Charsets.UTF_8))),
        )
    }

    @Test
    fun looksLikeHtmlRejectsEmptyAndMissingFiles() {
        assertFalse("0 字节文件不是网页", looksLikeHtml(write("empty.html", ByteArray(0))))
        assertFalse("不存在的文件不是网页", looksLikeHtml(File(root, "missing.html")))
    }

    @Test
    fun isHtmlFileAcceptsHtmlFamilyExtensions() {
        // isHtmlFile 只看文件名，文件不必存在。
        assertTrue(isHtmlFile(File(root, "a.html")))
        assertTrue(isHtmlFile(File(root, "a.htm")))
        assertTrue(isHtmlFile(File(root, "a.xhtml")))
        // SAF DISPLAY_NAME 大小写不可控。
        assertTrue(isHtmlFile(File(root, "A.HTML")))
        assertTrue(isHtmlFile(File(root, "A.Htm")))
        assertTrue(isHtmlFile(File(root, "A.XHTML")))

        assertFalse(isHtmlFile(File(root, "a.txt")))
        assertFalse(isHtmlFile(File(root, "a.fb2")))
        assertFalse(isHtmlFile(File(root, "a.epub")))
        // 扩展名取最后一个点之后：.html.bak 是 bak，不是 html。
        assertFalse(isHtmlFile(File(root, "a.html.bak")))
        assertFalse(isHtmlFile(File(root, "html")))
    }

    @Test
    fun sniffBookFormatValidatesHtmlFamilyExtensions() {
        val real = "<!doctype html><html><body><p>正文</p></body></html>".toByteArray(Charsets.UTF_8)
        assertEquals(BookFormat.Html, sniffBookFormat(write("page.htm", real)))
        assertEquals(BookFormat.Html, sniffBookFormat(write("page.xhtml", real)))
        assertEquals(BookFormat.Html, sniffBookFormat(write("PAGE.HTML", real)))

        // 扩展名给结论就按内容校验：假 HTML 不得登记成书。
        assertNull(sniffBookFormat(write("fake.html", "只是纯文本".toByteArray(Charsets.UTF_8))))
        assertNull(sniffBookFormat(write("fake.htm", "只是纯文本".toByteArray(Charsets.UTF_8))))
        assertNull(sniffBookFormat(write("empty.xhtml", ByteArray(0))))
    }

    @Test
    fun sniffBookFormatKeepsTextExtensionForHtmlContent() {
        // 扩展名优先：内容是 HTML 的 .txt / .md 仍按纯文本类处理，不能被 HTML 抢走。
        val html = "<div>正文</div>".toByteArray(Charsets.UTF_8)
        assertEquals(BookFormat.Text, sniffBookFormat(write("misnamed.txt", html)))
        assertEquals(BookFormat.Markdown, sniffBookFormat(write("misnamed.md", html)))
    }

    @Test
    fun isRecoverableLibraryFileAcceptsOnlyRealHtmlUnderLibraryDir() {
        val real = "<!doctype html><html><body><p>正文</p></body></html>".toByteArray(Charsets.UTF_8)
        assertTrue(isRecoverableLibraryFile(libraryFile("real.html", real)))
        assertTrue(isRecoverableLibraryFile(libraryFile("page.htm", real)))
        assertTrue(isRecoverableLibraryFile(libraryFile("page.xhtml", real)))

        // 假内容 / 0 字节 / FB2 冒充 .html 都不得被冷启扫描登记成书。
        assertFalse(isRecoverableLibraryFile(libraryFile("fake.html", "纯文本".toByteArray(Charsets.UTF_8))))
        assertFalse(isRecoverableLibraryFile(libraryFile("empty.html", ByteArray(0))))
        assertFalse(
            isRecoverableLibraryFile(libraryFile("book.html", FB2_WITH_DECLARATION.toByteArray(Charsets.UTF_8))),
        )
        // 复制中间态不得登记。
        assertFalse(isRecoverableLibraryFile(libraryFile("page.html.tmp", real)))
    }

    @Test
    fun classifyImportedFileMarksHtmlByContent() {
        assertEquals(
            ImportedFileIntegrity.COMPLETE,
            classifyImportedFile(write("page.html", "<body><p>正文</p></body>".toByteArray(Charsets.UTF_8))),
        )
        assertEquals(
            ImportedFileIntegrity.COMPLETE,
            classifyImportedFile(
                write("page.xhtml", "<html><body>正文</body></html>".toByteArray(Charsets.UTF_8)),
            ),
        )
        // 非空但内容不像网页：既不算完整，也不够格判「确认损坏」→ UNKNOWN（不能据此删旧副本）。
        assertEquals(
            ImportedFileIntegrity.UNKNOWN,
            classifyImportedFile(write("fake.html", "改了后缀的纯文本".toByteArray(Charsets.UTF_8))),
        )
        // FB2 冒充 .html：html 分支不成立，扩展名也不属于文本类 → UNKNOWN。
        assertEquals(
            ImportedFileIntegrity.UNKNOWN,
            classifyImportedFile(write("book.html", FB2_WITH_DECLARATION.toByteArray(Charsets.UTF_8))),
        )
        // 0 字节仍是唯一无争议的损坏形态。
        assertEquals(ImportedFileIntegrity.CORRUPT, classifyImportedFile(write("empty.html", ByteArray(0))))
    }

    @Test
    fun detectBookFormatTrustsHtmlExtension() {
        val real = "<!doctype html><html><body><p>正文</p></body></html>".toByteArray(Charsets.UTF_8)
        assertEquals(BookFormat.Html, detectBookFormat(write("page.html", real)))
        // 只信扩展名：内容不像网页、甚至 0 字节，扩展名是 .html 就按 HTML 处理。
        assertEquals(BookFormat.Html, detectBookFormat(write("fake.html", "纯文本".toByteArray(Charsets.UTF_8))))
        assertEquals(BookFormat.Html, detectBookFormat(write("empty.html", ByteArray(0))))
        // 大小写不敏感。
        assertEquals(BookFormat.Html, detectBookFormat(write("PAGE.HTML", real)))
        assertEquals(BookFormat.Html, detectBookFormat(write("page.HtM", real)))
        // HTML 没有魔数可依：无扩展名时 detectBookFormat 只能落到 Unsupported。
        assertEquals(BookFormat.Unsupported, detectBookFormat(write("no-ext-page", real)))
    }

    /** 在 filesDir/_pb_files（冷启扫描的唯一目录）下建文件。 */
    private fun libraryFile(name: String, bytes: ByteArray): File {
        val dir = File(root, "_pb_files").apply { mkdirs() }
        return File(dir, name).apply { writeBytes(bytes) }
    }

    private fun write(name: String, bytes: ByteArray): File = File(root, name).apply { writeBytes(bytes) }

    /** UTF-16 文本（带 FF FE / FE FF BOM），用于验证 BOM 放行分支。 */
    private fun utf16WithBom(text: String, littleEndian: Boolean): ByteArray {
        val bom = if (littleEndian) {
            byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        } else {
            byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        }
        val body = if (littleEndian) {
            text.toByteArray(Charsets.UTF_16LE)
        } else {
            text.toByteArray(Charsets.UTF_16BE)
        }
        return bom + body
    }

    /** 结构完整的 ZIP（含 EOCD + 中央目录），用于验证 EPUB 完整性判定。 */
    private fun writeZip(name: String): File {
        val file = File(root, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
        }
        return file
    }

    private companion object {
        val EPUB_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)

        const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"

        /** 标准 FB2：XML 声明 + FictionBook 根元素 + 最小正文。 */
        const val FB2_WITH_DECLARATION = XML_DECLARATION + "\n" +
            "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">\n" +
            "  <description><title-info><book-title>测试书</book-title></title-info></description>\n" +
            "  <body><section><p>正文</p></section></body>\n" +
            "</FictionBook>\n"

        /** 省略 XML 声明、直接以根元素开头的 FB2。 */
        const val FB2_WITHOUT_DECLARATION = "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\">" +
            "<body><section><p>正文</p></section></body></FictionBook>"
    }
}
