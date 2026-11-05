package app.particlebook.android.reader.pdf

/**
 * 把 PDF 用户空间坐标（点、原点左下、y 向上）转换为显示坐标（左上原点、y 向下、0..1）。
 *
 * 处理顺序：先与 crop box 求交，再除以 crop box 尺寸，最后按页面 /Rotate 顺时针
 * 旋转。crop box 缺失或非法时回退到整页。旋转值不是 90 的倍数时按 0 处理。
 */
class PdfPageGeometry(
    pageWidthPoints: Float,
    pageHeightPoints: Float,
    cropBox: PdfBox? = null,
    rotationDegrees: Int = 0,
) {
    val pageWidthPoints: Float = pageWidthPoints
    val pageHeightPoints: Float = pageHeightPoints
    val cropBox: PdfBox
    val rotationDegrees: Int

    private val pageValid: Boolean =
        pageWidthPoints.isFinite() && pageHeightPoints.isFinite() &&
            pageWidthPoints > 0f && pageHeightPoints > 0f

    init {
        this.cropBox = if (cropBox != null && cropBox.isValid) {
            cropBox
        } else {
            PdfBox(0f, 0f, pageWidthPoints.coerceAtLeast(1f), pageHeightPoints.coerceAtLeast(1f))
        }
        this.rotationDegrees = normalizeRotation(rotationDegrees)
    }

    /** 返回字符框在显示坐标系中的矩形；完全落在 crop 外或退化时返回 null。 */
    fun normalize(box: PdfBox): PdfNormalizedRect? {
        if (!pageValid || !box.isValid) return null
        val left = maxOf(box.left, cropBox.left)
        val right = minOf(box.right, cropBox.right)
        val bottom = maxOf(box.bottom, cropBox.bottom)
        val top = minOf(box.top, cropBox.top)
        if (right <= left || top <= bottom) return null

        val u = ((left - cropBox.left) / cropBox.width).coerceIn(0f, 1f)
        val v = ((cropBox.top - top) / cropBox.height).coerceIn(0f, 1f)
        val w = ((right - left) / cropBox.width).coerceIn(0f, 1f)
        val h = ((top - bottom) / cropBox.height).coerceIn(0f, 1f)

        return when (rotationDegrees) {
            90 -> clampRect(1f - v - h, u, h, w)
            180 -> clampRect(1f - u - w, 1f - v - h, w, h)
            270 -> clampRect(v, 1f - u - w, h, w)
            else -> clampRect(u, v, w, h)
        }
    }

    private fun clampRect(left: Float, top: Float, width: Float, height: Float): PdfNormalizedRect? {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val w = width.coerceIn(0f, 1f - l)
        val h = height.coerceIn(0f, 1f - t)
        if (w <= 0f || h <= 0f) return null
        return PdfNormalizedRect(l, t, w, h)
    }

    companion object {
        /** 把 PDFium 的 0/1/2/3 或任意角度归一化为 0/90/180/270。 */
        fun normalizeRotation(degrees: Int): Int {
            val mod = ((degrees % 360) + 360) % 360
            return when (mod) {
                90, 180, 270 -> mod
                else -> 0
            }
        }
    }
}
