package dev.brainrotstopper.feature.rules

import dev.brainrotstopper.core.model.TimeWindow
import java.time.DayOfWeek
import java.util.Locale

/** Compact, locale-stable summaries shown in the rules list. */
object ScheduleFormat {

    private val WEEKDAYS = setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    )
    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    private val SHORT_NAMES = mapOf(
        DayOfWeek.MONDAY to "Mon",
        DayOfWeek.TUESDAY to "Tue",
        DayOfWeek.WEDNESDAY to "Wed",
        DayOfWeek.THURSDAY to "Thu",
        DayOfWeek.FRIDAY to "Fri",
        DayOfWeek.SATURDAY to "Sat",
        DayOfWeek.SUNDAY to "Sun",
    )

    fun daysSummary(days: Set<DayOfWeek>): String = when {
        days.isEmpty() -> "No days"
        days.size == DayOfWeek.entries.size -> "Every day"
        days == WEEKDAYS -> "Weekdays"
        days == WEEKEND -> "Weekends"
        else -> DayOfWeek.entries
            .filter { it in days }
            .joinToString(", ") { SHORT_NAMES.getValue(it) }
    }

    fun windowsSummary(windows: List<TimeWindow>): String =
        if (windows.isEmpty()) {
            "No windows"
        } else {
            windows.joinToString(", ") { "${clock(it.startMinuteOfDay)}-${clock(it.endMinuteOfDay)}" }
        }

    fun clock(minuteOfDay: Int): String {
        require(minuteOfDay in TimeWindow.VALID_MINUTE_OF_DAY) {
            "minuteOfDay must be in 0..1439, was $minuteOfDay"
        }
        return String.format(Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)
    }
}
