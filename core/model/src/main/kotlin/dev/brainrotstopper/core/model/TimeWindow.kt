package dev.brainrotstopper.core.model

/**
 * A time window measured in minutes from local midnight, half-open: `[start, end)`.
 *
 * If [startMinuteOfDay] is greater than [endMinuteOfDay] the window wraps past midnight
 * (for example 22:00-02:00). A zero-length window is rejected.
 */
data class TimeWindow(
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
) {
    init {
        require(startMinuteOfDay in VALID_MINUTE_OF_DAY) {
            "startMinuteOfDay must be in 0..1439, was $startMinuteOfDay"
        }
        require(endMinuteOfDay in VALID_MINUTE_OF_DAY) {
            "endMinuteOfDay must be in 0..1439, was $endMinuteOfDay"
        }
        require(startMinuteOfDay != endMinuteOfDay) {
            "A zero-length window is not allowed"
        }
    }

    val isOvernight: Boolean
        get() = startMinuteOfDay > endMinuteOfDay

    fun contains(minuteOfDay: Int): Boolean {
        require(minuteOfDay in VALID_MINUTE_OF_DAY) {
            "minuteOfDay must be in 0..1439, was $minuteOfDay"
        }
        return if (isOvernight) {
            minuteOfDay >= startMinuteOfDay || minuteOfDay < endMinuteOfDay
        } else {
            minuteOfDay >= startMinuteOfDay && minuteOfDay < endMinuteOfDay
        }
    }

    companion object {
        const val MINUTES_PER_DAY: Int = 24 * 60
        val VALID_MINUTE_OF_DAY: IntRange = 0 until MINUTES_PER_DAY
    }
}
