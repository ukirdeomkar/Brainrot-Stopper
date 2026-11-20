package dev.brainrotstopper.feature.timers

/**
 * Contract for daily per-app budgets backed by UsageStatsManager.
 *
 * The real implementation (M6) polls usage, reconciles the authoritative daily total, and
 * resets at local midnight. The scaffold ships [NoOpTimerController].
 */
interface TimerController {
    val name: String

    /** Remaining budget in minutes for today, or null when the app has no timer. */
    fun remainingMinutes(packageName: String): Int?

    /** Record the authoritative usage total observed for today. */
    fun recordUsage(packageName: String, usedMinutes: Int)
}
