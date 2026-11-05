package app.particlebook.android.reader.epub.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubSelectionTest {

    @Test
    fun rectsAreClippedToEachLine() {
        val geometry = fakeGeometry(
            lines = listOf(
                line(block = 0, line = 0, charStart = 0, charEnd = 5, top = 0f, bottom = 10f),
                line(block = 0, line = 1, charStart = 5, charEnd = 10, top = 10f, bottom = 20f),
                line(block = 1, line = 0, charStart = 0, charEnd = 5, top = 20f, bottom = 30f),
            ),
        )
        val rects = EpubSelection.rects(geometry, EpubSelectionRange(0, 3, 8))
        assertEquals(2, rects.size)
        assertEquals(60f, rects[0].left, 0.01f)
        assertEquals(100f, rects[0].right, 0.01f)
        assertEquals(0f, rects[1].left, 0.01f)
        assertEquals(60f, rects[1].right, 0.01f)
    }

    @Test
    fun reversedRangeIsNormalized() {
        val geometry = fakeGeometry(
            listOf(line(0, 0, 0, 5, 0f, 10f)),
        )
        val a = EpubSelection.rects(geometry, EpubSelectionRange(0, 4, 1))
        val b = EpubSelection.rects(geometry, EpubSelectionRange(0, 1, 4))
        assertEquals(1, a.size)
        assertEquals(1, b.size)
        assertEquals(b[0].left, a[0].left, 0.01f)
        assertEquals(b[0].right, a[0].right, 0.01f)
    }

    @Test
    fun emptyAndSingleCharacterRangesHaveNoRect() {
        val geometry = fakeGeometry(listOf(line(0, 0, 0, 5, 0f, 10f)))
        assertTrue(EpubSelection.rects(geometry, EpubSelectionRange(0, 2, 2)).isEmpty())
        assertEquals(1, EpubSelection.rects(geometry, EpubSelectionRange(0, 4, 3)).size)
    }

    @Test
    fun hitTestReturnsBlockAndCharacter() {
        val geometry = fakeGeometry(
            listOf(
                line(block = 0, line = 0, charStart = 0, charEnd = 5, top = 0f, bottom = 10f),
                line(block = 0, line = 1, charStart = 5, charEnd = 10, top = 10f, bottom = 20f),
            ),
        )
        val hit = EpubSelection.hitTest(geometry, 60f, 15f)
        assertNotNull(hit)
        assertEquals(0, hit!!.blockIndex)
        assertEquals(5 + 3, hit.charOffset)
        assertEquals(1, hit.lineIndex)
    }

    @Test
    fun hitTestClampsOutsideContent() {
        val geometry = fakeGeometry(listOf(line(0, 0, 0, 4, 20f, 30f)))
        val hit = EpubSelection.hitTest(geometry, -10f, 100f)
        assertNotNull(hit)
        assertEquals(0, hit!!.charOffset)
    }

    @Test
    fun emptyGeometryHasNoHit() {
        assertNull(EpubSelection.hitTest(fakeGeometry(emptyList()), 1f, 1f))
    }

    private fun line(
        block: Int,
        line: Int,
        charStart: Int,
        charEnd: Int,
        top: Float,
        bottom: Float,
    ): EpubSelectionLine = EpubSelectionLine(
        blockIndex = block,
        line = line,
        charStart = charStart,
        charEnd = charEnd,
        left = 0f,
        top = top,
        right = 100f,
        bottom = bottom,
    )

    private fun fakeGeometry(lines: List<EpubSelectionLine>): EpubSelectionGeometry =
        object : EpubSelectionGeometry {
            override val lines: List<EpubSelectionLine> = lines

            override fun xForChar(lineIndex: Int, charOffset: Int): Float {
                val line = lines[lineIndex]
                val span = (line.charEnd - line.charStart).coerceAtLeast(1)
                val fraction = (charOffset - line.charStart).toFloat() / span
                return line.left + fraction.coerceIn(0f, 1f) * (line.right - line.left)
            }

            override fun charAt(lineIndex: Int, x: Float): Int {
                val line = lines[lineIndex]
                val fraction = ((x - line.left) / (line.right - line.left).coerceAtLeast(1f))
                    .coerceIn(0f, 1f)
                return line.charStart + (fraction * (line.charEnd - line.charStart)).toInt()
            }
        }
}