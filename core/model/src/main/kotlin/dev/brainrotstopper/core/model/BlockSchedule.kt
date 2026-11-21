package dev.brainrotstopper.core.model

import java.time.DayOfWeek

/**
 * A named set of [windows] that apply on selected [daysOfWeek].
 *
 * Overnight windows belong to the day they start on: a 22:00-02:00 window on Monday also
 * covers Tuesday 00:00-02:00, matching how a person thinks about "Monday night".
 *
 * A disabled schedule never blocks, but its windows are kept so the user can re-enable it.
 */
data class BlockSchedule(
    val id: String,
    val label: String,
    val daysOfWeek: Set<DayOfWeek>,
    val windows: List<TimeWindow>,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(label.isNotBlank()) { "label must not be blank" }
    }

    fun isActiveAt(day: DayOfWeek, minuteOfDay: Int): Boolean {
        require(minuteOfDay in TimeWindow.VALID_MINUTE_OF_DAY) {
            "minuteOfDay must be in 0..1439, was $minuteOfDay"
        }
        if (!enabled) return false
        return windows.any { window ->
            if (!window.isOvernight) {
                day in daysOfWeek && window.contains(minuteOfDay)
            } else {
                (minuteOfDay >= window.startMinuteOfDay && day in daysOfWeek) ||
                    (minuteOfDay < window.endMinuteOfDay && previousDay(day) in daysOfWeek)
            }
        }
    }

    private fun previousDay(day: DayOfWeek): DayOfWeek = day.minus(1)
}
