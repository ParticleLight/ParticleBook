package app.particlebook.android.reader.pdf

/**
 * PDF 用户空间矩形。
 *
 * PDF 坐标原点在页面左下角，y 轴向上，因此约束是 top > bottom。
 * 单位是 PDF 点（1/72 英寸），不包含页面旋转。
 */
data class PdfBox(
    val left: Float,
    val bottom: Float,
    val right: Float,
    val top: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = top - bottom

    val isValid: Boolean
        get() = left.isFinite() && bottom.isFinite() && right.isFinite() && top.isFinite() &&
            right > left && top > bottom

    companion object {
        /**
         * 从四条边构造，自动把上下、左右排序。
         *
         * PdfiumAndroid 的 getPageCropBox 与 textPageGetCharBox 返回的 RectF
         * 上下方向并不一致（char box 保留 PDF 的 top > bottom，crop box 则被
         * 翻转成 Android 的 top < bottom），直接读取 RectF.top 会错位。排序后
         * 再构造可以同时兼容两者，也兼容缺失的 (-1,-1,-1,-1)。
         */
        fun fromEdges(a: Float, b: Float, c: Float, d: Float): PdfBox? {
            if (!a.isFinite() || !b.isFinite() || !c.isFinite() || !d.isFinite()) return null
            val left = minOf(a, c)
            val right = maxOf(a, c)
            val bottom = minOf(b, d)
            val top = maxOf(b, d)
            return if (right > left && top > bottom) PdfBox(left, bottom, right, top) else null
        }
    }
}

/** 归一化到页面裁剪框、左上角为原点、0..1 的矩形。 */
data class PdfNormalizedRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    val isValid: Boolean
        get() = left.isFinite() && top.isFinite() && width.isFinite() && height.isFinite() &&
            width > 0f && height > 0f
}

/** 一个字符及其在显示坐标系中的位置；空白或零宽字符的 rect 为 null。 */
data class PdfChar(
    val index: Int,
    val text: String,
    val rect: PdfNormalizedRect?,
)

/** 一页的文本层结果。text 是 PDFium 直接抽取的整页文本，chars 用于绘制/选择。 */
data class PdfPageText(
    val pageIndex: Int,
    val pageWidthPoints: Int,
    val pageHeightPoints: Int,
    val rotationDegrees: Int,
    val chars: List<PdfChar>,
    val text: String,
) {
    val charCount: Int get() = chars.size
    val hasTextLayer: Boolean get() = chars.isNotEmpty() || text.isNotEmpty()
}
