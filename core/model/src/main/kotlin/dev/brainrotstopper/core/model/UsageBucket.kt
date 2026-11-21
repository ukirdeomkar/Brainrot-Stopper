package dev.brainrotstopper.core.model

/**
 * Authoritative foreground time observed for one app on one local calendar day.
 *
 * [epochDay] is [java.time.LocalDate.toEpochDay] in the device timezone. The stored value is
 * always the running total reported by the platform, never an increment, so rewriting the
 * same bucket is idempotent.
 */
data class UsageBucket(
    val packageName: String,
    val epochDay: Long,
    val usedMillis: Long,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(usedMillis >= 0) { "usedMillis must not be negative, was $usedMillis" }
    }
}
