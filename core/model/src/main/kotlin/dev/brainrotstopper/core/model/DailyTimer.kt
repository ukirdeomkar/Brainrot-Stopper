package dev.brainrotstopper.core.model

/** Today's usage against a daily budget for one app. */
data class DailyTimer(
    val packageName: String,
    val limitMinutes: Int,
    val usedMinutes: Int = 0,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(limitMinutes > 0) { "limitMinutes must be positive, was $limitMinutes" }
        require(usedMinutes >= 0) { "usedMinutes must not be negative, was $usedMinutes" }
    }

    val remainingMinutes: Int
        get() = (limitMinutes - usedMinutes).coerceAtLeast(0)

    val isExhausted: Boolean
        get() = usedMinutes >= limitMinutes
}
