package dev.brainrotstopper.core.model

import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.TUESDAY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleValidationTest {

    private fun validate(
        days: Set<DayOfWeek> = setOf(MONDAY),
        windows: List<WindowDraft>,
    ): List<ScheduleProblem> = ScheduleValidator.validate(days, windows)

    @Test
    fun `a schedule with no days is rejected`() {
        val problems = validate(days = emptySet(), windows = listOf(WindowDraft(540, 600)))

        assertEquals(listOf(ScheduleProblem.NoDays), problems)
    }

    @Test
    fun `a schedule with no windows is rejected`() {
        val problems = validate(windows = emptyList())

        assertEquals(listOf(ScheduleProblem.NoWindows), problems)
    }

    @Test
    fun `a zero-length window is rejected with its index`() {
        val problems = validate(
            windows = listOf(WindowDraft(540, 600), WindowDraft(700, 700)),
        )

        assertEquals(listOf(ScheduleProblem.ZeroLengthWindow(1)), problems)
    }

    @Test
    fun `adjacent windows do not overlap`() {
        val problems = validate(
            windows = listOf(WindowDraft(540, 720), WindowDraft(720, 1020)),
        )

        assertTrue(problems.isEmpty())
    }

    @Test
    fun `overlapping windows on the same day are rejected`() {
        val problems = validate(
            windows = listOf(WindowDraft(540, 720), WindowDraft(600, 780)),
        )

        assertEquals(listOf(ScheduleProblem.OverlappingWindows(0, 1)), problems)
    }

    @Test
    fun `duplicate windows overlap`() {
        val problems = validate(
            windows = listOf(WindowDraft(540, 600), WindowDraft(540, 600)),
        )

        assertEquals(listOf(ScheduleProblem.OverlappingWindows(0, 1)), problems)
    }

    @Test
    fun `overnight window and next-morning window overlap when the next day is selected`() {
        val problems = validate(
            days = setOf(MONDAY, TUESDAY),
            windows = listOf(WindowDraft(22 * 60, 2 * 60), WindowDraft(60, 120)),
        )

        assertEquals(listOf(ScheduleProblem.OverlappingWindows(0, 1)), problems)
    }

    @Test
    fun `overnight window and next-morning window do not overlap when only the start day is selected`() {
        val problems = validate(
            days = setOf(MONDAY),
            windows = listOf(WindowDraft(22 * 60, 2 * 60), WindowDraft(60, 120)),
        )

        assertTrue(problems.isEmpty())
    }

    @Test
    fun `sunday overnight window overlaps monday early window across the week boundary`() {
        val problems = validate(
            days = setOf(DayOfWeek.SUNDAY, MONDAY),
            windows = listOf(WindowDraft(23 * 60, 60), WindowDraft(30, 90)),
        )

        assertEquals(listOf(ScheduleProblem.OverlappingWindows(0, 1)), problems)
    }

    @Test
    fun `windows adjacent to an overnight window are accepted`() {
        val problems = validate(
            days = setOf(MONDAY, FRIDAY),
            windows = listOf(WindowDraft(22 * 60, 2 * 60), WindowDraft(2 * 60, 3 * 60)),
        )

        assertTrue(problems.isEmpty())
    }

    @Test
    fun `zero-length windows are not compared for overlap`() {
        val problems = validate(
            windows = listOf(WindowDraft(540, 540), WindowDraft(540, 600)),
        )

        assertEquals(listOf(ScheduleProblem.ZeroLengthWindow(0)), problems)
    }

    @Test
    fun `a valid overnight window converts to the domain type`() {
        val window = WindowDraft(22 * 60, 2 * 60).toTimeWindow()

        assertTrue(window.isOvernight)
    }

    @Test
    fun `draft keeps the same bounds as the domain window`() {
        val window = TimeWindow(9 * 60, 17 * 60)

        assertEquals(WindowDraft.of(window), WindowDraft(9 * 60, 17 * 60))
    }
}
