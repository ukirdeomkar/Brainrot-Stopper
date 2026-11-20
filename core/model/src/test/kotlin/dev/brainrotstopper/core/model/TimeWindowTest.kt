package dev.brainrotstopper.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeWindowTest {

    @Test
    fun `normal window includes start and excludes end`() {
        val window = TimeWindow(startMinuteOfDay = 9 * 60, endMinuteOfDay = 17 * 60)

        assertTrue(window.contains(9 * 60))
        assertTrue(window.contains(12 * 60))
        assertFalse(window.contains(17 * 60))
        assertFalse(window.contains(8 * 60 + 59))
    }

    @Test
    fun `overnight window wraps past midnight`() {
        val window = TimeWindow(startMinuteOfDay = 22 * 60, endMinuteOfDay = 2 * 60)

        assertTrue(window.isOvernight)
        assertTrue(window.contains(23 * 60))
        assertTrue(window.contains(0))
        assertTrue(window.contains(1 * 60 + 59))
        assertFalse(window.contains(2 * 60))
        assertFalse(window.contains(12 * 60))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a zero length window`() {
        TimeWindow(startMinuteOfDay = 10 * 60, endMinuteOfDay = 10 * 60)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an out of range minute`() {
        TimeWindow(startMinuteOfDay = -1, endMinuteOfDay = 60)
    }
}
