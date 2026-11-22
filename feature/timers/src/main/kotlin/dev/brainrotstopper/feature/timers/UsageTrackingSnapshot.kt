package dev.brainrotstopper.feature.timers

/**
 * Last observed state of usage tracking, read by the service self-check and the UI.
 *
 * [timersAvailable] is deliberately true before the first check and after a transient read
 * failure while access is still granted. Flipping timers off on every hiccup would let a
 * user walk through an exhausted budget until the next successful poll. A confirmed
 * [UsageAccessStatus.REVOKED], however, pauses timers immediately.
 */
data class UsageTrackingSnapshot(
    val accessStatus: UsageAccessStatus = UsageAccessStatus.UNDETERMINED,
    val lastPollAtEpochMillis: Long = 0L,
    val epochDay: Long? = null,
    val trackedPackages: Int = 0,
    val lastPollFailed: Boolean = false,
) {

    val timersAvailable: Boolean
        get() = accessStatus != UsageAccessStatus.REVOKED
}
