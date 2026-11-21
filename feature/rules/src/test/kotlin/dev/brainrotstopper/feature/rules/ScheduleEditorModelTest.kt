package dev.brainrotstopper.feature.rules

import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.ScheduleProblem
import dev.brainrotstopper.core.model.TimeWindow
import dev.brainrotstopper.core.model.WindowDraft
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleEditorModelTest {

    @Test
    fun `a new schedule starts with one usable window and no days`() {
        val model = ScheduleEditorModel("new", initial = null)

        assertEquals(1, model.windows().size)
        assertTrue(model.days.isEmpty())
        assertEquals(listOf(ScheduleProblem.NoDays), model.problems())
    }

    @Test
    fun `an existing schedule is loaded for editing`() {
        val schedule = BlockSchedule(
            id = "work",
            label = "Work",
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            windows = listOf(TimeWindow(9 * 60, 17 * 60)),
            enabled = false,
        )

        val model = ScheduleEditorModel("work", schedule)

        assertEquals("Work", model.label)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), model.days)
        assertEquals(listOf(WindowDraft(9 * 60, 17 * 60)), model.windows())
        assertEquals(false, model.enabled)
        assertTrue(model.problems().isEmpty())
    }

    @Test
    fun `removing every window produces the empty-window problem`() {
        val model = ScheduleEditorModel("new", initial = null)
        model.days += DayOfWeek.MONDAY

        model.removeWindow(0)

        assertEquals(listOf(ScheduleProblem.NoWindows), model.problems())
    }

    @Test
    fun `overlapping windows are reported before saving`() {
        val model = ScheduleEditorModel("new", initial = null)
        model.days += DayOfWeek.MONDAY
        model.setWindow(0, 9 * 60, 12 * 60)
        model.addWindow()
        model.setWindow(1, 11 * 60, 13 * 60)

        assertEquals(listOf(ScheduleProblem.OverlappingWindows(0, 1)), model.problems())
        assertThrows(IllegalArgumentException::class.java) { model.build() }
    }

    @Test
    fun `a valid model builds the domain schedule`() {
        val model = ScheduleEditorModel("new", initial = null)
        model.label = "  Lunch  "
        model.days += setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY)
        model.setWindow(0, 12 * 60, 13 * 60)

        val schedule = model.build()

        assertEquals("new", schedule.id)
        assertEquals("Lunch", schedule.label)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), schedule.daysOfWeek)
        assertEquals(listOf(TimeWindow(12 * 60, 13 * 60)), schedule.windows)
        assertTrue(schedule.enabled)
    }

    @Test
    fun `adding a window starts after the previous one but can be edited`() {
        val model = ScheduleEditorModel("new", initial = null)
        model.setWindow(0, 9 * 60, 10 * 60)

        model.addWindow()

        assertEquals(2, model.windows().size)
        assertEquals(10 * 60, model.windows()[1].startMinuteOfDay)
        assertEquals(11 * 60, model.windows()[1].endMinuteOfDay)
    }

    @Test
    fun `zero-length windows are reported`() {
        val model = ScheduleEditorModel("new", initial = null)
        model.days += DayOfWeek.MONDAY
        model.setWindow(0, 700, 700)

        assertEquals(listOf(ScheduleProblem.ZeroLengthWindow(0)), model.problems())
    }
}
