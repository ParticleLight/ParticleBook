package app.particlebook.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import app.particlebook.android.reader.ReaderAnnotation
import app.particlebook.android.reader.ReaderAnnotationType
import app.particlebook.android.reader.ReaderLocator
import app.particlebook.android.reader.ReaderRect

/** 已存标注在 PDF 页面上的填充不透明度。纸面是白底位图，太浅看不见、太深盖住字。 */
private const val ANNOTATION_FILL_ALPHA = 0.42f

/**
 * 把这本书的标注按页分组，供页面绘制使用。
 *
 * 只收能画的记录：定位符必须是 [ReaderLocator.Pdf]、页号非负、带归一化矩形，
 * 且不是坏 locator（[ReaderAnnotation.locatorError] 非 null 的行只有占位定位符，空串同样算坏）。
 * BOOKMARK 不画 —— PDF 侧不产生书签，真出现了也没有可落笔的位置。
 *
 * 同页按 top、再按 left、最后按 id 排序：同一位置有多条时叠色顺序与阅读顺序一致，
 * 不随数据库返回次序抖动。
 */
internal fun pdfAnnotationsByPage(
    annotations: List<ReaderAnnotation>,
): Map<Int, List<ReaderAnnotation>> = annotations
    .asSequence()
    .filter { it.locatorError == null }
    .filter {
        it.type == ReaderAnnotationType.HIGHLIGHT ||
            it.type == ReaderAnnotationType.NOTE ||
            it.type == ReaderAnnotationType.PDF
    }
    .mapNotNull { annotation ->
        val locator = annotation.locator as? ReaderLocator.Pdf ?: return@mapNotNull null
        // 把页号与矩形一起带上，排序时不必再回头看 locator（也就不会写出恒真的空值兜底）。
        val rect = locator.rect ?: return@mapNotNull null
        if (locator.page < 0) return@mapNotNull null
        Triple(locator.page, rect, annotation)
    }
    .sortedWith(
        compareBy<Triple<Int, ReaderRect, ReaderAnnotation>> { it.first }
            .thenBy { it.second.top }
            .thenBy { it.second.left }
            .thenBy { it.third.id },
    )
    .groupBy({ it.first }, { it.third })

/**
 * 已存标注的落笔层。
 *
 * 与 `PdfSelectionOverlay` 共用同一套归一化矩形换算（0..1 乘实测尺寸），
 * 所以旋转/裁剪下不会与选区错位；只画不接手势，触摸继续落到选区与翻页上。
 */
@Composable
internal fun PdfAnnotationHighlights(
    annotations: List<ReaderAnnotation>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        annotations.forEach { annotation ->
            val rect = (annotation.locator as? ReaderLocator.Pdf)?.rect ?: return@forEach
            val rectWidth = (rect.right - rect.left) * width
            val rectHeight = (rect.bottom - rect.top) * height
            if (rectWidth <= 0f || rectHeight <= 0f) return@forEach
            drawRect(
                color = annotationColor(annotation.color).copy(alpha = ANNOTATION_FILL_ALPHA),
                topLeft = Offset(rect.left * width, rect.top * height),
                size = Size(rectWidth, rectHeight),
            )
        }
    }
}
