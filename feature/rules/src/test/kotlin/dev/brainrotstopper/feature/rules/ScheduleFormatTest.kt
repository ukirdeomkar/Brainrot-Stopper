package dev.brainrotstopper.feature.rules

import dev.brainrotstopper.core.model.TimeWindow
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleFormatTest {

    @Test
    fun `every day is summarised when all days are selected`() {
        assertEquals("Every day", ScheduleFormat.daysSummary(DayOfWeek.entries.toSet()))
    }

    @Test
    fun `weekday and weekend sets get short names`() {
        assertEquals(
            "Weekdays",
            ScheduleFormat.daysSummary(
                setOf(
                    DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY,
                ),
            ),
        )
        assertEquals("Weekends", ScheduleFormat.daysSummary(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)))
    }

    @Test
    fun `arbitrary days are listed monday first`() {
        assertEquals(
            "Mon, Wed, Fri",
            ScheduleFormat.daysSummary(
                setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
            ),
        )
    }

    @Test
    fun `no days and no windows have explicit labels`() {
        assertEquals("No days", ScheduleFormat.daysSummary(emptySet()))
        assertEquals("No windows", ScheduleFormat.windowsSummary(emptyList()))
    }

    @Test
    fun `windows render start and end clocks`() {
        val summary = ScheduleFormat.windowsSummary(
            listOf(TimeWindow(9 * 60, 17 * 60), TimeWindow(22 * 60, 2 * 60)),
        )

        assertEquals("09:00-17:00, 22:00-02:00", summary)
    }

    @Test
    fun `midnight renders as zero zero zero zero`() {
        assertEquals("00:00", ScheduleFormat.clock(0))
    }
}
