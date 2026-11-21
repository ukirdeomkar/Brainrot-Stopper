package dev.brainrotstopper.core.model

import java.time.DayOfWeek
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockScheduleTest {

    @Test
    fun `active inside a window on a selected day`() {
        val schedule = BlockSchedule(
            id = "work",
            label = "Work hours",
            daysOfWeek = setOf(DayOfWeek.MONDAY),
            windows = listOf(TimeWindow(9 * 60, 17 * 60)),
        )

        assertTrue(schedule.isActiveAt(DayOfWeek.MONDAY, 10 * 60))
        assertFalse(schedule.isActiveAt(DayOfWeek.TUESDAY, 10 * 60))
        assertFalse(schedule.isActiveAt(DayOfWeek.MONDAY, 18 * 60))
    }

    @Test
    fun `overnight window carries into the next morning`() {
        val schedule = BlockSchedule(
            id = "night",
            label = "Monday night",
            daysOfWeek = setOf(DayOfWeek.MONDAY),
            windows = listOf(TimeWindow(22 * 60, 2 * 60)),
        )

        assertTrue(schedule.isActiveAt(DayOfWeek.MONDAY, 23 * 60))
        assertTrue(schedule.isActiveAt(DayOfWeek.TUESDAY, 1 * 60))
        assertFalse(schedule.isActiveAt(DayOfWeek.TUESDAY, 23 * 60))
        assertFalse(schedule.isActiveAt(DayOfWeek.SUNDAY, 23 * 60))
    }

    @Test
    fun `empty schedule is never active`() {
        val schedule = BlockSchedule("empty", "Empty", emptySet(), emptyList())

        assertFalse(schedule.isActiveAt(DayOfWeek.MONDAY, 12 * 60))
    }

    @Test
    fun `disabled schedule is never active`() {
        val schedule = BlockSchedule(
            id = "paused",
            label = "Paused",
            daysOfWeek = setOf(DayOfWeek.MONDAY),
            windows = listOf(TimeWindow(9 * 60, 17 * 60)),
            enabled = false,
        )

        assertFalse(schedule.isActiveAt(DayOfWeek.MONDAY, 10 * 60))
    }
}
