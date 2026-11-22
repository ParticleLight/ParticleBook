package app.particlebook.android.reader.pdf

/**
 * PDF 朗读单测的公共夹具：造出与 PdfTextSource 同构的 [PdfPageText] / [PdfChar]。
 *
 * 刻意不走 PDFium：真正要钉住的是"整页文本 → 句子 → 字符索引区间"的换算规则，
 * 与渲染、文本抽取本身无关，纯 JVM 即可断言。
 */
internal object PdfTtsFixtures {

    /** 任一非空矩形都足以验证映射；这里只关心索引来回换算，不关心几何。 */
    private val CharRect = PdfNormalizedRect(left = 0.1f, top = 0.1f, width = 0.01f, height = 0.02f)

    /**
     * 按 [text] 逐字符造 [PdfChar]。
     *
     * [indices] 用来模拟 PdfTextSource 跳过 '\u0000' 之后留下的索引空洞：第 n 个字符
     * 的 index 取 indices[n]，不连续也必须原样保留，选区按 index 过滤。
     */
    fun chars(text: String, indices: List<Int> = text.indices.toList()): List<PdfChar> {
        require(indices.size == text.length) { "indices 与文本长度不一致" }
        return indices.mapIndexed { position, index ->
            PdfChar(index = index, text = text[position].toString(), rect = CharRect)
        }
    }

    fun page(text: String, chars: List<PdfChar>, pageIndex: Int = 0): PdfPageText =
        PdfPageText(
            pageIndex = pageIndex,
            pageWidthPoints = 600,
            pageHeightPoints = 800,
            rotationDegrees = 0,
            chars = chars,
            text = text,
        )
}
