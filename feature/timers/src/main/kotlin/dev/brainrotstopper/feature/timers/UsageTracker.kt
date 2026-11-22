package dev.brainrotstopper.feature.timers

import dev.brainrotstopper.core.data.UsageRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reconciles the authoritative per-app daily total from `UsageStatsManager` into Room.
 *
 * One [poll] computes the *current* local epoch day and rewrites each tracked bucket with the
 * total reported for that day. It never increments, so a duplicate poll, a missed tick, a
 * service restart, or a timezone change cannot inflate usage.
 *
 * The day key and the query window come from the same [instantSource]/[zoneSource] pair, so
 * a midnight or timezone rollover starts a new bucket instead of carrying yesterday's total
 * forward. The [BlockingStateCache][dev.brainrotstopper.feature.blocking.BlockingStateCache]
 * refreshes timers for the same day key, which is what makes the daily reset take effect.
 *
 * When Usage Access is revoked (or has not been checked yet) the tracker writes nothing and
 * publishes [UsageAccessStatus.REVOKED] so the blocking path can pause daily timers without
 * losing the last authoritative totals.
 */
class UsageTracker(
    private val usageRepository: UsageRepository,
    private val accessChecker: UsageAccessChecker,
    private val statsSource: UsageStatsSource,
    private val instantSource: () -> Instant = Instant::now,
    private val zoneSource: () -> ZoneId = ZoneId::systemDefault,
    private val retentionDays: Long = RETENTION_DAYS,
) {

    /**
     * Serialises overlapping polls. A screen event and the 60 s loop can both fire; without
     * this, a slow older query could rewrite a newer higher total with a lower one.
     */
    private val pollMutex = Mutex()

    @Volatile
    private var current = UsageTrackingSnapshot()

    fun snapshot(): UsageTrackingSnapshot = current

    /** True while the latest check says the daily timer inputs may be enforced. */
    val timersAvailable: Boolean
        get() = snapshot().timersAvailable

    /**
     * Polls usage for [packageNames] and rewrites today's buckets.
     *
     * Never throws for a platform or database failure: the failure is recorded in the
     * returned snapshot ([UsageTrackingSnapshot.lastPollFailed]) and the caller/UI can show
     * it. Cancellation is the only exception that propagates.
     */
    suspend fun poll(packageNames: Collection<String>): UsageTrackingSnapshot =
        pollMutex.withLock { pollLocked(packageNames) }

    private suspend fun pollLocked(packageNames: Collection<String>): UsageTrackingSnapshot {
        val now = instantSource()
        val nowEpochMillis = now.toEpochMilli()
        val zone = zoneSource()
        val epochDay = LocalDate.ofInstant(now, zone).toEpochDay()
        val access = readAccess()

        if (access != UsageAccessStatus.GRANTED) {
            // Revoked (or not checked yet): keep the last stored totals untouched so a
            // re-grant resumes from authoritative data instead of a fabricated zero.
            return publish(
                UsageTrackingSnapshot(
                    accessStatus = access,
                    lastPollAtEpochMillis = nowEpochMillis,
                    epochDay = epochDay,
                ),
            )
        }

        val trackedPackages = packageNames
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()

        if (trackedPackages.isEmpty()) {
            deleteOldBuckets(epochDay)
            return publish(
                UsageTrackingSnapshot(
                    accessStatus = access,
                    lastPollAtEpochMillis = nowEpochMillis,
                    epochDay = epochDay,
                ),
            )
        }

        val dayStart = LocalDate.ofInstant(now, zone)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        return try {
            val totals = statsSource.foregroundMillisByPackage(dayStart, nowEpochMillis)
            for (packageName in trackedPackages) {
                usageRepository.rewriteUsage(
                    packageName = packageName,
                    epochDay = epochDay,
                    usedMillis = (totals[packageName] ?: 0L).coerceAtLeast(0L),
                )
            }
            deleteOldBuckets(epochDay)
            publish(
                UsageTrackingSnapshot(
                    accessStatus = access,
                    lastPollAtEpochMillis = nowEpochMillis,
                    epochDay = epochDay,
                    trackedPackages = trackedPackages.size,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // A transient UsageStats or Room failure must not crash the service or zero the
            // buckets; the next tick retries and the self-check reports the failed poll.
            publish(
                UsageTrackingSnapshot(
                    accessStatus = access,
                    lastPollAtEpochMillis = nowEpochMillis,
                    epochDay = epochDay,
                    trackedPackages = trackedPackages.size,
                    lastPollFailed = true,
                ),
            )
        }
    }

    private fun readAccess(): UsageAccessStatus = try {
        accessChecker.status()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        UsageAccessStatus.REVOKED
    }

    private suspend fun deleteOldBuckets(epochDay: Long) {
        try {
            usageRepository.deleteBefore(epochDay - retentionDays)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // Retention housekeeping is best-effort; a failure must not fail the poll.
        }
    }

    private fun publish(next: UsageTrackingSnapshot): UsageTrackingSnapshot {
        current = next
        return next
    }

    companion object {
        /** Local diagnostics retention for usage buckets (design §3.4). */
        const val RETENTION_DAYS: Long = 30L
    }
}
