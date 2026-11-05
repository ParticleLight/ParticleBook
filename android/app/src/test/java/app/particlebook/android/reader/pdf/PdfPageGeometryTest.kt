package app.particlebook.android.reader.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** P0-4：PDF 字符框 -> 显示坐标的纯 JVM 几何测试。 */
class PdfPageGeometryTest {

    @Test
    fun cropOffsetAndTopDownNormalization() {
        val geometry = PdfPageGeometry(
            pageWidthPoints = 600f,
            pageHeightPoints = 800f,
            cropBox = PdfBox(50f, 100f, 550f, 700f),
            rotationDegrees = 0,
        )
        val rect = geometry.normalize(PdfBox(50f, 600f, 150f, 700f))
        assertNotNull(rect)
        assertEquals(0f, rect!!.left, 0.0001f)
        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(0.2f, rect.width, 0.0001f)
        assertEquals(1f / 6f, rect.height, 0.0001f)
    }

    @Test
    fun rotation90MapsTopLeftToTopRight() {
        val geometry = PdfPageGeometry(100f, 100f, null, 90)
        val rect = geometry.normalize(PdfBox(0f, 90f, 10f, 100f))!!
        assertEquals(0.9f, rect.left, 0.0001f)
        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(0.1f, rect.width, 0.0001f)
        assertEquals(0.1f, rect.height, 0.0001f)
    }

    @Test
    fun rotation180MapsTopLeftToBottomRight() {
        val rect = PdfPageGeometry(100f, 100f, null, 180)
            .normalize(PdfBox(0f, 90f, 10f, 100f))!!
        assertEquals(0.9f, rect.left, 0.0001f)
        assertEquals(0.9f, rect.top, 0.0001f)
    }

    @Test
    fun rotation270MapsTopLeftToBottomLeft() {
        val rect = PdfPageGeometry(100f, 100f, null, 270)
            .normalize(PdfBox(0f, 90f, 10f, 100f))!!
        assertEquals(0f, rect.left, 0.0001f)
        assertEquals(0.9f, rect.top, 0.0001f)
    }

    @Test
    fun clipsCharacterBoxToCropBox() {
        val geometry = PdfPageGeometry(100f, 100f, PdfBox(0f, 0f, 100f, 100f), 0)
        val rect = geometry.normalize(PdfBox(-10f, -10f, 20f, 20f))!!
        assertEquals(0f, rect.left, 0.0001f)
        assertEquals(0.8f, rect.top, 0.0001f)
        assertEquals(0.2f, rect.width, 0.0001f)
        assertEquals(0.2f, rect.height, 0.0001f)
    }

    @Test
    fun boxOutsideCropIsDropped() {
        val geometry = PdfPageGeometry(100f, 100f, PdfBox(20f, 20f, 80f, 80f), 0)
        assertNull(geometry.normalize(PdfBox(0f, 0f, 10f, 10f)))
        assertNull(geometry.normalize(PdfBox(20f, 20f, 20f, 20f)))
    }

    @Test
    fun invalidCropFallsBackToWholePage() {
        val geometry = PdfPageGeometry(100f, 200f, PdfBox(-1f, -1f, -1f, -1f), 0)
        val rect = geometry.normalize(PdfBox(0f, 0f, 50f, 200f))!!
        assertEquals(0f, rect.left, 0.0001f)
        assertEquals(0f, rect.top, 0.0001f)
        assertEquals(0.5f, rect.width, 0.0001f)
        assertEquals(1f, rect.height, 0.0001f)
    }

    @Test
    fun degeneratePageReturnsNull() {
        val geometry = PdfPageGeometry(0f, 0f, null, 0)
        assertNull(geometry.normalize(PdfBox(0f, 0f, 10f, 10f)))
    }

    @Test
    fun pdfBoxFromEdgesSortsBothRectFOrientations() {
        assertEquals(PdfBox(1f, 2f, 3f, 4f), PdfBox.fromEdges(1f, 4f, 3f, 2f))
        assertEquals(PdfBox(1f, 2f, 3f, 4f), PdfBox.fromEdges(1f, 2f, 3f, 4f))
        assertNull(PdfBox.fromEdges(-1f, -1f, -1f, -1f))
    }

    @Test
    fun rotationNormalization() {
        assertEquals(0, PdfPageGeometry.normalizeRotation(0))
        assertEquals(90, PdfPageGeometry.normalizeRotation(90))
        assertEquals(180, PdfPageGeometry.normalizeRotation(540))
        assertEquals(270, PdfPageGeometry.normalizeRotation(-90))
        assertEquals(0, PdfPageGeometry.normalizeRotation(45))
    }
}
