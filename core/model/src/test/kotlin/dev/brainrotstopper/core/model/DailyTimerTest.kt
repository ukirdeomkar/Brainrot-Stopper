package dev.brainrotstopper.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyTimerTest {

    @Test
    fun `remaining minutes decrease and never go negative`() {
        val timer = DailyTimer(packageName = "com.example.app", limitMinutes = 30, usedMinutes = 10)

        assertEquals(20, timer.remainingMinutes)
        assertFalse(timer.isExhausted)
        assertEquals(0, DailyTimer("com.example.app", 30, 45).remainingMinutes)
    }

    @Test
    fun `exhausted when usage reaches the limit`() {
        assertFalse(DailyTimer("com.example.app", 30, 29).isExhausted)
        assertTrue(DailyTimer("com.example.app", 30, 30).isExhausted)
        assertTrue(DailyTimer("com.example.app", 30, 31).isExhausted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non positive limit`() {
        DailyTimer("com.example.app", limitMinutes = 0)
    }
}
