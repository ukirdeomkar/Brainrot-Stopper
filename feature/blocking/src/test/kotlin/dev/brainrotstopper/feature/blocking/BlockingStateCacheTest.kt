package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.data.RuleRepository
import dev.brainrotstopper.core.data.UnlockGrantRepository
import dev.brainrotstopper.core.data.UsageRepository
import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockReason
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.TimeWindow
import dev.brainrotstopper.core.model.UnlockGrant
import dev.brainrotstopper.core.model.UsageBucket
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingStateCacheTest {

    private val rules = FakeRuleRepository()
    private val grants = FakeGrantRepository()
    private val usage = FakeUsageRepository()

    private var instant: Instant = Instant.parse("2026-11-16T12:00:00Z")
    private var zone: ZoneId = ZoneOffset.UTC

    private val cache = BlockingStateCache(
        ruleRepository = rules,
        unlockGrantRepository = grants,
        usageRepository = usage,
        instantSource = { instant },
        zoneSource = { zone },
    )

    @Test
    fun `unloaded snapshot fails open`() {
        rules.rules = listOf(rule())

        val decision = cache.decisionFor(PACKAGE)

        assertFalse(decision.block)
        assertFalse(cache.current().loaded)
    }

    @Test
    fun `refresh loads rules and schedules then blocks`() = runBlocking {
        rules.rules = listOf(rule())
        rules.schedules = listOf(schedule())

        cache.refresh()

        val decision = cache.decisionFor(PACKAGE)
        assertTrue(cache.current().loaded)
        assertTrue(decision.block)
        assertEquals(BlockReason.SCHEDULE, decision.reason)
    }

    @Test
    fun `grant saved after refresh allows the package`() = runBlocking {
        rules.rules = listOf(rule())
        rules.schedules = listOf(schedule())
        cache.refresh()
        assertTrue(cache.decisionFor(PACKAGE).block)

        grants.save(UnlockGrant("g1", PACKAGE, instant.toEpochMilli() + 60_000, "test"))
        cache.refresh()

        assertFalse(cache.decisionFor(PACKAGE).block)
        assertTrue(cache.isUnlocked(PACKAGE, instant.toEpochMilli()))
    }

    @Test
    fun `an older refresh cannot overwrite the snapshot from a newer one`() = runBlocking {
        rules.rules = listOf(rule())
        rules.schedules = listOf(schedule())
        val olderActiveEntered = CountDownLatch(1)
        val releaseOlder = CountDownLatch(1)
        grants.onActive = { call ->
            if (call == 1) {
                olderActiveEntered.countDown()
                releaseOlder.await()
            }
        }

        val older = async(Dispatchers.Default) { cache.refresh() }
        assertTrue(olderActiveEntered.await(5, TimeUnit.SECONDS))

        // The newer refresh reads the grant that the older refresh already missed.
        grants.save(UnlockGrant("g1", PACKAGE, instant.toEpochMilli() + 60_000, "test"))
        cache.refresh()
        assertTrue(cache.isUnlocked(PACKAGE, instant.toEpochMilli()))

        releaseOlder.countDown()
        older.await()

        assertTrue(
            "a stale in-flight refresh must not clobber the newer snapshot",
            cache.isUnlocked(PACKAGE, instant.toEpochMilli()),
        )
    }

    @Test
    fun `usage is converted to timer minutes and blocks at the exact limit`() = runBlocking {
        rules.rules = listOf(rule(dailyLimitMinutes = 30))
        rules.schedules = emptyList()
        usage.rewriteUsage(PACKAGE, epochDay(), 30 * 60_000L)

        cache.refresh()

        val decision = cache.decisionFor(PACKAGE)
        assertTrue(decision.block)
        assertEquals(BlockReason.DAILY_LIMIT, decision.reason)
        assertEquals(30, cache.current().timers.single().usedMinutes)
    }

    @Test
    fun `usage one millisecond below the limit does not block`() = runBlocking {
        rules.rules = listOf(rule(dailyLimitMinutes = 30))
        rules.schedules = emptyList()
        usage.rewriteUsage(PACKAGE, epochDay(), 30 * 60_000L - 1)

        cache.refresh()

        assertFalse(cache.decisionFor(PACKAGE).block)
        assertEquals(29, cache.current().timers.single().usedMinutes)
    }

    @Test
    fun `yesterday usage does not carry across a day rollover`() = runBlocking {
        rules.rules = listOf(rule(dailyLimitMinutes = 30))
        rules.schedules = emptyList()
        usage.rewriteUsage(PACKAGE, epochDay(), 60 * 60_000L)
        cache.refresh()
        assertTrue(cache.decisionFor(PACKAGE).block)

        instant = Instant.parse("2026-11-17T01:00:00Z")
        cache.refresh()

        assertFalse(cache.decisionFor(PACKAGE).block)
        assertTrue(cache.current().timers.isEmpty())
    }

    @Test
    fun `expired cached grant no longer allows without a refresh`() = runBlocking {
        rules.rules = listOf(rule())
        rules.schedules = listOf(schedule())
        grants.save(UnlockGrant("g1", PACKAGE, instant.toEpochMilli() + 60_000, "test"))
        cache.refresh()
        assertFalse(cache.decisionFor(PACKAGE).block)

        instant = instant.plusSeconds(61)

        assertTrue(cache.decisionFor(PACKAGE).block)
        assertFalse(cache.isUnlocked(PACKAGE, instant.toEpochMilli()))
    }

    @Test
    fun `timed rule with no usage bucket allows`() = runBlocking {
        rules.rules = listOf(rule(dailyLimitMinutes = 30))
        rules.schedules = emptyList()

        cache.refresh()

        assertNull(cache.current().timers.firstOrNull())
        assertFalse(cache.decisionFor(PACKAGE).block)
    }

    private fun rule(
        dailyLimitMinutes: Int? = null,
        scheduleIds: Set<String> = setOf(SCHEDULE_ID),
    ): AppRule = AppRule(
        packageName = PACKAGE,
        displayName = "Example",
        dailyLimitMinutes = dailyLimitMinutes,
        scheduleIds = scheduleIds,
    )

    private fun schedule(): BlockSchedule = BlockSchedule(
        id = SCHEDULE_ID,
        label = "Focus",
        daysOfWeek = setOf(DayOfWeek.MONDAY),
        windows = listOf(TimeWindow(9 * 60, 17 * 60)),
    )

    private fun epochDay(): Long = LocalDate.of(2026, 11, 16).toEpochDay()

    private class FakeRuleRepository : RuleRepository {
        var rules: List<AppRule> = emptyList()
        var schedules: List<BlockSchedule> = emptyList()

        override suspend fun rules(): List<AppRule> = rules
        override suspend fun upsertRule(rule: AppRule) {
            rules = rules.filterNot { it.packageName == rule.packageName } + rule
        }

        override suspend fun removeRule(packageName: String) {
            rules = rules.filterNot { it.packageName == packageName }
        }

        override suspend fun schedules(): List<BlockSchedule> = schedules
        override suspend fun upsertSchedule(schedule: BlockSchedule) {
            schedules = schedules.filterNot { it.id == schedule.id } + schedule
        }

        override suspend fun removeSchedule(id: String) {
            schedules = schedules.filterNot { it.id == id }
        }
    }

    private class FakeGrantRepository : UnlockGrantRepository {
        private var stored: List<UnlockGrant> = emptyList()
        private val activeCalls = AtomicInteger(0)

        /** Test hook that runs after the read is captured and before it is returned. */
        var onActive: ((call: Int) -> Unit)? = null

        override suspend fun save(grant: UnlockGrant) {
            stored = stored.filterNot { it.id == grant.id } + grant
        }

        override suspend fun revoke(id: String) {
            stored = stored.filterNot { it.id == id }
        }

        override suspend fun active(nowEpochMillis: Long): List<UnlockGrant> {
            val captured = stored
            onActive?.invoke(activeCalls.incrementAndGet())
            return captured.filter { it.isActiveAt(nowEpochMillis) }
                .sortedBy { it.expiresAtEpochMillis }
        }

        override suspend fun activeFor(
            packageName: String,
            nowEpochMillis: Long,
        ): UnlockGrant? = active(nowEpochMillis).firstOrNull { it.covers(packageName) }

        override suspend fun purgeExpired(nowEpochMillis: Long): Int {
            val before = stored.size
            stored = stored.filter { it.isActiveAt(nowEpochMillis) }
            return before - stored.size
        }
    }

    private class FakeUsageRepository : UsageRepository {
        private val buckets = mutableMapOf<Pair<String, Long>, UsageBucket>()

        override suspend fun usage(packageName: String, epochDay: Long): UsageBucket? =
            buckets[packageName to epochDay]

        override suspend fun usageForDay(epochDay: Long): List<UsageBucket> =
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
        const val SCHEDULE_ID = "focus"
    }
}
