package dev.brainrotstopper.feature.timers

import dev.brainrotstopper.core.data.UsageRepository
import dev.brainrotstopper.core.model.UsageBucket
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTrackerTest {

    private val usage = FakeUsageRepository()
    private val access = FakeUsageAccessChecker()
    private val source = FakeUsageStatsSource()

    private var instant: Instant = Instant.parse("2026-11-16T12:00:00Z")
    private var zone: ZoneId = ZoneOffset.UTC

    private val tracker = UsageTracker(
        usageRepository = usage,
        accessChecker = access,
        statsSource = source,
        instantSource = { instant },
        zoneSource = { zone },
    )

    @Test
    fun `poll rewrites the authoritative total for today`() = runBlocking {
        access.status = UsageAccessStatus.GRANTED
        source.totals = mapOf(PACKAGE to 30 * MILLIS_PER_MINUTE)

        val snapshot = tracker.poll(listOf(PACKAGE))

        assertEquals(epochDayOf("2026-11-16"), snapshot.epochDay)
        assertEquals(UsageAccessStatus.GRANTED, snapshot.accessStatus)
        assertEquals(1, snapshot.trackedPackages)
        assertFalse(snapshot.lastPollFailed)
        assertTrue(snapshot.timersAvailable)
        assertEquals(instant.toEpochMilli(), snapshot.lastPollAtEpochMillis)
        assertEquals(
            30 * MILLIS_PER_MINUTE,
            usage.usage(PACKAGE, epochDayOf("2026-11-16"))?.usedMillis,
        )
    }

    @Test
    fun `a later total replaces the bucket instead of adding to it`() = runBlocking {
        access.status = UsageAccessStatus.GRANTED

        source.totals = mapOf(PACKAGE to 10 * MILLIS_PER_MINUTE)
        tracker.poll(listOf(PACKAGE))
        source.totals = mapOf(PACKAGE to 25 * MILLIS_PER_MINUTE)
        tracker.poll(listOf(PACKAGE))
        // A duplicate poll of the same value must stay idempotent.
        tracker.poll(listOf(PACKAGE))

        assertEquals(
            25 * MILLIS_PER_MINUTE,
            usage.usage(PACKAGE, epochDayOf("2026-11-16"))?.usedMillis,
        )
        assertEquals(1, usage.bucketsForDay(epochDayOf("2026-11-16")).size)
    }

    @Test
    fun `midnight starts a new bucket and keeps the previous day`() = runBlocking {
        access.status = UsageAccessStatus.GRANTED
        source.totals = mapOf(PACKAGE to 40 * MILLIS_PER_MINUTE)
        tracker.poll(listOf(PACKAGE))

        instant = Instant.parse("2026-11-17T00:01:00Z")
        source.totals = emptyMap()
        val snapshot = tracker.poll(listOf(PACKAGE))

        assertEquals(epochDayOf("2026-11-17"), snapshot.epochDay)
        assertEquals(
            40 * MILLIS_PER_MINUTE,
            usage.usage(PACKAGE, epochDayOf("2026-11-16"))?.usedMillis,
        )
        assertEquals(0L, usage.usage(PACKAGE, epochDayOf("2026-11-17"))?.usedMillis)
    }

    @Test
    fun `timezone change moves the day key and the query window`() = runBlocking {
        access.status = UsageAccessStatus.GRANTED
        instant = Instant.parse("2026-11-16T23:30:00Z")
        source.totals = mapOf(PACKAGE to 10 * MILLIS_PER_MINUTE)
        tracker.poll(listOf(PACKAGE))

        assertEquals(epochDayOf("2026-11-16"), tracker.snapshot().epochDay)
        assertEquals(
            Instant.parse("2026-11-16T00:00:00Z").toEpochMilli(),
            source.queries.single().first,
        )

        zone = ZoneId.of("Asia/Tokyo")
        source.totals = mapOf(PACKAGE to 15 * MILLIS_PER_MINUTE)
        val snapshot = tracker.poll(listOf(PACKAGE))

        // 23:30 UTC is already the next local day in Tokyo.
        assertEquals(epochDayOf("2026-11-17"), snapshot.epochDay)
        assertEquals(
            Instant.parse("2026-11-16T15:00:00Z").toEpochMilli(),
            source.queries.last().first,
        )
        assertEquals(
            10 * MILLIS_PER_MINUTE,
            usage.usage(PACKAGE, epochDayOf("2026-11-16"))?.usedMillis,
        )
        assertEquals(
            15 * MILLIS_PER_MINUTE,
            usage.usage(PACKAGE, epochDayOf("2026-11-17"))?.usedMillis,
        )
    }

    @Test
    fun `revoked access writes nothing and pauses timers until re-granted`() = runBlocking {
        access.status = UsageAccessStatus.REVOKED

        val revoked = tracker.poll(listOf(PACKAGE))

        assertEquals(UsageAccessStatus.REVOKED, revoked.accessStatus)
        assertFalse(revoked.timersAvailable)
        assertEquals(0, revoked.trackedPackages)
        assertNull(usage.usage(PACKAGE, epochDayOf("2026-11-16")))
        assertTrue(source.queries.isEmpty())

        access.status = UsageAccessStatus.GRANTED
        source.totals = mapOf(PACKAGE to 20 * MILLIS_PER_MINUTE)
        val granted = tracker.poll(listOf(PACKAGE))

        assertTrue(granted.timersAvailable)
        assertEquals(UsageAccessStatus.GRANTED, granted.accessStatus)
        assertEquals(
            20 * MILLIS_PER_MINUTE,
            usage.usage(PACKAGE, epochDayOf("2026-11-16"))?.usedMillis,
        )
    }

    @Test
    fun `source failure records the failed poll without zeroing the total`() = runBlocking {
        access.status = UsageAccessStatus.GRANTED
        usage.rewriteUsage(PACKAGE, epochDayOf("2026-11-16"), 30 * MILLIS_PER_MINUTE)
        source.failure = IllegalStateException("usage stats unavailable")

        val snapshot = tracker.poll(listOf(PACKAGE))

        assertTrue(snapshot.lastPollFailed)
        assertTrue(snapshot.timersAvailable)
        assertEquals(
            30 * MILLIS_PER_MINUTE,
            usage.usage(PACKAGE, epochDayOf("2026-11-16"))?.usedMillis,
        )
    }

    @Test
    fun `an empty tracked list is a successful poll that does not query usage`() = runBlocking {
        access.status = UsageAccessStatus.GRANTED

        val snapshot = tracker.poll(emptyList())

        assertEquals(UsageAccessStatus.GRANTED, snapshot.accessStatus)
        assertEquals(0, snapshot.trackedPackages)
        assertFalse(snapshot.lastPollFailed)
        assertTrue(snapshot.timersAvailable)
        assertTrue(source.queries.isEmpty())
    }

    @Test
    fun `polls outside the retention window are deleted`() = runBlocking {
        access.status = UsageAccessStatus.GRANTED
        source.totals = mapOf(PACKAGE to 5 * MILLIS_PER_MINUTE)
        val today = epochDayOf("2026-11-16")
        usage.rewriteUsage(PACKAGE, today - 31, 1_000)
        usage.rewriteUsage(PACKAGE, today - 29, 2_000)

        tracker.poll(listOf(PACKAGE))

        assertNull(usage.usage(PACKAGE, today - 31))
        assertEquals(2_000L, usage.usage(PACKAGE, today - 29)?.usedMillis)
        assertEquals(
            5 * MILLIS_PER_MINUTE,
            usage.usage(PACKAGE, today)?.usedMillis,
        )
    }

    @Test
    fun `initial snapshot leaves timers available before the first check`() {
        val initial = tracker.snapshot()

        assertEquals(UsageAccessStatus.UNDETERMINED, initial.accessStatus)
        assertTrue(initial.timersAvailable)
        assertEquals(0L, initial.lastPollAtEpochMillis)
        assertNull(initial.epochDay)
    }

    private fun epochDayOf(date: String): Long = LocalDate.parse(date).toEpochDay()

    private class FakeUsageAccessChecker : UsageAccessChecker {
        var status: UsageAccessStatus = UsageAccessStatus.UNDETERMINED
        override fun status(): UsageAccessStatus = status
    }

    private class FakeUsageStatsSource : UsageStatsSource {
        var totals: Map<String, Long> = emptyMap()
        var failure: Throwable? = null
        val queries = mutableListOf<Pair<Long, Long>>()

        override fun foregroundMillisByPackage(
            beginEpochMillis: Long,
            endEpochMillis: Long,
        ): Map<String, Long> {
            queries += beginEpochMillis to endEpochMillis
            failure?.let { throw it }
            return totals
        }
    }

    private class FakeUsageRepository : UsageRepository {
        private val buckets = mutableMapOf<Pair<String, Long>, UsageBucket>()

        override suspend fun usage(packageName: String, epochDay: Long): UsageBucket? =
            buckets[packageName to epochDay]

        override suspend fun usageForDay(epochDay: Long): List<UsageBucket> =
            bucketsForDay(epochDay)

        fun bucketsForDay(epochDay: Long): List<UsageBucket> =
            buckets.values.filter { it.epochDay == epochDay }

        override suspend fun rewriteUsage(
            packageName: String,
            epochDay: Long,
            usedMillis: Long,
        ) {
            buckets[packageName to epochDay] = UsageBucket(packageName, epochDay, usedMillis)
        }

        override suspend fun deleteBefore(epochDay: Long) {
            buckets.entries.removeAll { it.value.epochDay < epochDay }
        }
    }

    private companion object {
        const val PACKAGE = "com.example.app"
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
