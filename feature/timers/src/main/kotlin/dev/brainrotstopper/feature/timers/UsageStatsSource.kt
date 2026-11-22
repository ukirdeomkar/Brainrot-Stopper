package dev.brainrotstopper.feature.timers

/**
 * Source of authoritative foreground totals for a time interval.
 *
 * The production implementation reads `UsageStatsManager`; tests supply a fake. A source
 * never returns increments: the tracker rewrites the day's bucket with the reported total.
 */
interface UsageStatsSource {
    /**
     * Foreground milliseconds per package between [beginEpochMillis] and [endEpochMillis].
     *
     * An empty map is a valid answer: it can mean "no usage yet" or "Usage Access was
     * revoked". The caller decides which by asking [UsageAccessChecker].
     */
    fun foregroundMillisByPackage(
        beginEpochMillis: Long,
        endEpochMillis: Long,
    ): Map<String, Long>
}
