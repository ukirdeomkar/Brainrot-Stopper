package dev.brainrotstopper.core.data

import dev.brainrotstopper.core.model.UsageBucket

/**
 * Authoritative per-app usage totals keyed by local epoch day.
 *
 * Every write is a full rewrite of one bucket, never an increment, so retries and duplicate
 * poll ticks are idempotent (design §3.2).
 */
interface UsageRepository {
    suspend fun usage(packageName: String, epochDay: Long): UsageBucket?
    suspend fun usageForDay(epochDay: Long): List<UsageBucket>

    /** Replaces the bucket for `(packageName, epochDay)` with the authoritative total. */
    suspend fun rewriteUsage(packageName: String, epochDay: Long, usedMillis: Long)

    /** Drops buckets older than `epochDay` (rolling-retention housekeeping). */
    suspend fun deleteBefore(epochDay: Long)
}
