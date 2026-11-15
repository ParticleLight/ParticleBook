package app.particlebook.android.reader.pdf

/** 把 PdfTextLayerController 适配成 PdfDocumentTextSource；不额外持有资源。 */
class PdfTextLayerDocumentSource(
    private val controller: PdfTextLayerController,
) : PdfDocumentTextSource {
    override val pageCount: Int get() = controller.pageCount

    override fun loadPage(page: Int): PdfPageText = controller.loadPage(page)

    override fun charAt(page: Int, xNorm: Float, yNorm: Float): PdfChar? =
        controller.charAt(page, xNorm, yNorm)

    override fun selectionRects(
        page: Int,
        startChar: Int,
        endChar: Int,
    ): List<PdfNormalizedRect> = controller.selectionRects(page, startChar, endChar)

    override fun close() {
        controller.close()
    }
}
