package dev.brainrotstopper.feature.timers

import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Reads authoritative per-package foreground totals from `UsageStatsManager`.
 *
 * `queryAndAggregateUsageStats` returns one aggregated `UsageStats` per package for the
 * interval, so the reported foreground time is already a total. The tracker rewrites the
 * bucket from this value; it never adds to a previous one.
 */
class AndroidUsageStatsSource(context: Context) : UsageStatsSource {

    private val appContext = context.applicationContext

    override fun foregroundMillisByPackage(
        beginEpochMillis: Long,
        endEpochMillis: Long,
    ): Map<String, Long> {
        if (endEpochMillis <= beginEpochMillis) return emptyMap()
        val manager = appContext.getSystemService(UsageStatsManager::class.java)
            ?: return emptyMap()
        val stats = manager.queryAndAggregateUsageStats(beginEpochMillis, endEpochMillis)
            ?: return emptyMap()
        return stats.mapValues { (_, usage) ->
            usage.totalTimeInForeground.coerceAtLeast(0L)
        }
    }
}
