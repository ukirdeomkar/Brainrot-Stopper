package dev.brainrotstopper.core.model

import java.time.DayOfWeek

/**
 * A user-entered schedule window before it becomes a [TimeWindow].
 *
 * Unlike [TimeWindow] a draft may be zero-length, because that is an input error the editor
 * must report instead of crashing. Only drafts that pass [ScheduleValidator] are converted
 * with [toTimeWindow].
 */
data class WindowDraft(
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
) {
    init {
        require(startMinuteOfDay in TimeWindow.VALID_MINUTE_OF_DAY) {
            "startMinuteOfDay must be in 0..1439, was $startMinuteOfDay"
        }
        require(endMinuteOfDay in TimeWindow.VALID_MINUTE_OF_DAY) {
            "endMinuteOfDay must be in 0..1439, was $endMinuteOfDay"
        }
    }

    val isZeroLength: Boolean
        get() = startMinuteOfDay == endMinuteOfDay

    val isOvernight: Boolean
        get() = startMinuteOfDay > endMinuteOfDay

    fun toTimeWindow(): TimeWindow = TimeWindow(startMinuteOfDay, endMinuteOfDay)

    companion object {
        fun of(window: TimeWindow): WindowDraft =
            WindowDraft(window.startMinuteOfDay, window.endMinuteOfDay)
    }
}

/** A reason a schedule cannot be saved. [windowIndex] values are zero-based. */
sealed interface ScheduleProblem {
    data object NoDays : ScheduleProblem

    data object NoWindows : ScheduleProblem

    data class ZeroLengthWindow(val windowIndex: Int) : ScheduleProblem

    data class OverlappingWindows(val firstIndex: Int, val secondIndex: Int) : ScheduleProblem
}

/**
 * Validates a schedule draft before it is stored.
 *
 * Windows share the schedule's day set, and an overnight window belongs to the day it starts
 * on (see [BlockSchedule]). Overlap detection therefore expands every window on every selected
 * day into an absolute minute interval and compares those intervals, including the week
 * boundary. That catches a Monday 22:00-02:00 window overlapping a Tuesday 01:00-02:00 window
 * when both Monday and Tuesday are selected, while leaving the same pair alone when only
 * Monday is selected.
 */
object ScheduleValidator {

    private const val WEEK_MINUTES = 7 * TimeWindow.MINUTES_PER_DAY

    fun validate(
        days: Set<DayOfWeek>,
        windows: List<WindowDraft>,
    ): List<ScheduleProblem> {
        val problems = mutableListOf<ScheduleProblem>()
        if (days.isEmpty()) problems += ScheduleProblem.NoDays
        if (windows.isEmpty()) problems += ScheduleProblem.NoWindows

        windows.forEachIndexed { index, window ->
            if (window.isZeroLength) problems += ScheduleProblem.ZeroLengthWindow(index)
        }

        val candidates = windows.withIndex().filterNot { it.value.isZeroLength }
        for (first in candidates.indices) {
            for (second in first + 1 until candidates.size) {
                val (firstIndex, firstWindow) = candidates[first]
                val (secondIndex, secondWindow) = candidates[second]
                if (overlaps(firstWindow, secondWindow, days)) {
                    problems += ScheduleProblem.OverlappingWindows(firstIndex, secondIndex)
                }
            }
        }
        return problems
    }

    private data class Interval(val start: Int, val end: Int)

    private fun overlaps(
        first: WindowDraft,
        second: WindowDraft,
        days: Set<DayOfWeek>,
    ): Boolean {
        if (days.isEmpty()) return false
        val left = intervals(first, days)
        val right = intervals(second, days)
        return left.any { a ->
            right.any { b ->
                intersects(a, b) ||
                    intersects(a, Interval(b.start + WEEK_MINUTES, b.end + WEEK_MINUTES)) ||
                    intersects(a, Interval(b.start - WEEK_MINUTES, b.end - WEEK_MINUTES))
            }
        }
    }

    private fun intersects(first: Interval, second: Interval): Boolean =
        first.start < second.end && second.start < first.end

    private fun intervals(window: WindowDraft, days: Set<DayOfWeek>): List<Interval> =
        days.map { day ->
            val dayStart = (day.value - 1) * TimeWindow.MINUTES_PER_DAY
            val start = dayStart + window.startMinuteOfDay
            val end = if (window.isOvernight) {
                dayStart + TimeWindow.MINUTES_PER_DAY + window.endMinuteOfDay
            } else {
                dayStart + window.endMinuteOfDay
            }
            Interval(start, end)
        }
}
