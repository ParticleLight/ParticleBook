package app.particlebook.android.reader.epub.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubImageBudgetTest {

    @Test
    fun reservesUntilBudgetIsFull() {
        val budget = EpubImageBudget(100_000L)
        assertTrue(budget.tryReserve(100, 100))
        assertEquals(40_000L, budget.usedBytes)
        assertTrue(budget.tryReserve(100, 100))
        assertEquals(80_000L, budget.usedBytes)
        assertFalse(budget.tryReserve(100, 100))
        assertEquals(80_000L, budget.usedBytes)
    }

    @Test
    fun rejectsInvalidOrOversizedImages() {
        val budget = EpubImageBudget(1_000L)
        assertFalse(budget.tryReserve(0, 100))
        assertFalse(budget.tryReserve(-1, 100))
        assertFalse(budget.tryReserve(100, 100))
        assertEquals(0L, budget.usedBytes)
    }

    @Test
    fun releaseAndResetRestoreCapacity() {
        val budget = EpubImageBudget(100_000L)
        assertTrue(budget.tryReserve(100, 100))
        budget.release(40_000L)
        assertEquals(0L, budget.usedBytes)
        assertTrue(budget.tryReserve(100, 100))
        budget.reset()
        assertEquals(0L, budget.usedBytes)
        assertEquals(100_000L, budget.availableBytes)
    }

    @Test
    fun bytesForArgb8888() {
        val budget = EpubImageBudget()
        assertEquals(40_000L, budget.bytesFor(100, 100))
        assertEquals(0L, budget.bytesFor(0, 100))
        assertEquals(24L * 1024L * 1024L, budget.bytesFor(2048, 3072))
    }
}
