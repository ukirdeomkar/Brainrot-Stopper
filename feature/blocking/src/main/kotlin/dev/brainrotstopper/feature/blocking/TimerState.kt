package dev.brainrotstopper.feature.blocking

/**
 * A caller-supplied daily usage total for one app.
 *
 * [epochDay] is [java.time.LocalDate.toEpochDay] in the device timezone. The engine ignores a
 * snapshot whose day does not match the instant being evaluated, so a stale total can never
 * leak across a midnight or timezone rollover.
 */
data class TimerState(
    val packageName: String,
    val epochDay: Long,
    val usedMinutes: Int,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(usedMinutes >= 0) { "usedMinutes must not be negative, was $usedMinutes" }
    }
}
