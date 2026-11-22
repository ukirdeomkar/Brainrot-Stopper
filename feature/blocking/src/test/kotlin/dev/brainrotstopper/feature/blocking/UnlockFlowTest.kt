package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.data.RuleRepository
import dev.brainrotstopper.core.data.UnlockGrantRepository
import dev.brainrotstopper.core.data.UsageRepository
import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.PasswordMode
import dev.brainrotstopper.core.model.TimeWindow
import dev.brainrotstopper.core.model.UnlockGrant
import dev.brainrotstopper.core.model.UsageBucket
import dev.brainrotstopper.core.security.CredentialStore
import dev.brainrotstopper.core.security.PasswordCredential
import dev.brainrotstopper.core.security.PasswordKdf
import dev.brainrotstopper.core.security.PasswordManager
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the unlock race from OMK-577: the flow must not report success before the grant is
 * durable and visible, and a failed write must surface instead of looking like an unlock.
 */
class UnlockFlowTest {

    private val rules = FakeRuleRepository()
    private val grants = FakeGrantRepository()
    private val usage = FakeUsageRepository()

    private var instant: Instant = Instant.parse("2026-11-16T12:00:00Z")

    private val cache = BlockingStateCache(
        ruleRepository = rules,
        unlockGrantRepository = grants,
        usageRepository = usage,
        instantSource = { instant },
        zoneSource = { ZoneOffset.UTC },
    )

    private var unlockedPackages = mutableListOf<String>()
    private var overlayDismissedWhileLocked = false

    private fun flow(gate: UnlockGate = UnlockGate { true }): UnlockFlow = UnlockFlow(
        unlockGrants = grants,
        cache = cache,
        gate = { gate },
        onUnlocked = { packageName ->
            if (!cache.isUnlocked(packageName, instant.toEpochMilli())) {
                overlayDismissedWhileLocked = true
            }
            unlockedPackages += packageName
        },
        clock = { instant.toEpochMilli() },
        newGrantId = { "g-test" },
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `success is reported only after the grant is visible in the cache`() = runBlocking {
        rules.rules = listOf(blockingRule())
        rules.schedules = listOf(schedule())
        cache.refresh()
        assertTrue(cache.decisionFor(PACKAGE).block)

        val result = flow().unlock(PACKAGE)

        assertEquals(UnlockResult.Success, result)
        assertTrue("grant must be in the cached snapshot", cache.isUnlocked(PACKAGE, instant.toEpochMilli()))
        assertEquals(listOf(PACKAGE), unlockedPackages)
        assertFalse("overlay must not be dismissed before the grant is visible", overlayDismissedWhileLocked)
    }

    @Test
    fun `a failed grant save is reported as failure and does not dismiss the overlay`() = runBlocking {
        rules.rules = listOf(blockingRule())
        rules.schedules = listOf(schedule())
        cache.refresh()
        grants.saveError = IllegalStateException("disk full")

        val result = flow().unlock(PACKAGE)

        assertTrue("failed save must surface as Failed", result is UnlockResult.Failed)
        assertEquals(1, grants.saveCalls)
        assertTrue(unlockedPackages.isEmpty())
        assertFalse(cache.isUnlocked(PACKAGE, instant.toEpochMilli()))
    }

    @Test
    fun `a failed refresh after the write surfaces instead of reporting success`() = runBlocking {
        rules.rules = listOf(blockingRule())
        rules.schedules = listOf(schedule())
        cache.refresh()
        rules.failOnRules = true

        val result = flow().unlock(PACKAGE)

        assertTrue(result is UnlockResult.Failed)
        assertEquals(1, grants.saveCalls)
        assertTrue(unlockedPackages.isEmpty())
        assertFalse(cache.isUnlocked(PACKAGE, instant.toEpochMilli()))
    }

    @Test
    fun `a gate rejection writes no grant and reports rejected`() = runBlocking {
        rules.rules = listOf(blockingRule())
        rules.schedules = listOf(schedule())
        cache.refresh()

        val result = flow(gate = { false }).unlock(PACKAGE)

        assertEquals(UnlockResult.Rejected, result)
        assertEquals(0, grants.saveCalls)
        assertTrue(unlockedPackages.isEmpty())
        assertFalse(cache.isUnlocked(PACKAGE, instant.toEpochMilli()))
    }

    @Test
    fun `wrong password is rejected and writes no grant`() = runBlocking {
        rules.rules = listOf(blockingRule())
        rules.schedules = listOf(schedule())
        cache.refresh()
        val manager = passwordManager("2468")
        val unlock = flow(gate = PasswordUnlockGate { manager })

        assertEquals(
            UnlockResult.Rejected,
            unlock.unlock(PACKAGE, "0000".toCharArray()),
        )
        assertEquals(0, grants.saveCalls)
        assertFalse(cache.isUnlocked(PACKAGE, instant.toEpochMilli()))

        assertEquals(
            UnlockResult.Success,
            unlock.unlock(PACKAGE, "2468".toCharArray()),
        )
        assertEquals(1, grants.saveCalls)
        assertTrue(cache.isUnlocked(PACKAGE, instant.toEpochMilli()))
    }

    @Test
    fun `a missing password is rejected by the password gate`() = runBlocking {
        rules.rules = listOf(blockingRule())
        rules.schedules = listOf(schedule())
        cache.refresh()
        val manager = passwordManager("2468")

        val result = flow(gate = PasswordUnlockGate { manager }).unlock(PACKAGE, null)

        assertEquals(UnlockResult.Rejected, result)
        assertEquals(0, grants.saveCalls)
        assertFalse(cache.isUnlocked(PACKAGE, instant.toEpochMilli()))
    }

    @Test
    fun `a grant expires and blocking re-arms at its expiry`() = runBlocking {
        rules.rules = listOf(blockingRule())
        rules.schedules = listOf(schedule())
        cache.refresh()
        assertTrue(cache.decisionFor(PACKAGE).block)

        assertEquals(UnlockResult.Success, flow().unlock(PACKAGE))
        val grantedAt = instant.toEpochMilli()
        assertFalse("a fresh grant must allow the app", cache.decisionFor(PACKAGE).block)
        val expiry = cache.nextGrantExpiry(PACKAGE, grantedAt)
        assertEquals(
            "the default grant is time-boxed",
            grantedAt + UnlockFlow.SESSION_UNLOCK_MILLIS,
            expiry,
        )

        instant = Instant.ofEpochMilli(expiry!! - 1)
        assertTrue("the grant is active just before expiry", cache.isUnlocked(PACKAGE, instant.toEpochMilli()))
        assertFalse(cache.decisionFor(PACKAGE).block)

        instant = Instant.ofEpochMilli(expiry)
        assertFalse(
            "the grant is inactive at its exact expiry",
            cache.isUnlocked(PACKAGE, instant.toEpochMilli()),
        )
        assertTrue("blocking must re-arm at expiry", cache.decisionFor(PACKAGE).block)
        assertTrue(grants.active(instant.toEpochMilli()).isEmpty())

        cache.refresh()
        assertTrue("blocking stays armed after the refresh", cache.decisionFor(PACKAGE).block)
        assertEquals(1, grants.purgeExpired(instant.toEpochMilli()))
    }

    private fun passwordManager(password: String): PasswordManager {
        val store = FakeCredentialStore()
        val manager = PasswordManager(store, PasswordKdf(iterations = 1_000))
        manager.configure(password.toCharArray(), PasswordMode.SELF_SET)
        return manager
    }

    private fun blockingRule(): AppRule = AppRule(
        packageName = PACKAGE,
        displayName = "Example",
        scheduleIds = setOf(SCHEDULE_ID),
    )

    private fun schedule(): BlockSchedule = BlockSchedule(
        id = SCHEDULE_ID,
        label = "Focus",
        daysOfWeek = setOf(DayOfWeek.MONDAY),
        windows = listOf(TimeWindow(9 * 60, 17 * 60)),
    )

    private class FakeRuleRepository : RuleRepository {
        var rules: List<AppRule> = emptyList()
        var schedules: List<BlockSchedule> = emptyList()
        var failOnRules = false

        override suspend fun rules(): List<AppRule> {
            if (failOnRules) throw IllegalStateException("rules read failed")
            return rules
        }

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
        var saveCalls = 0
        var saveError: Throwable? = null

        override suspend fun save(grant: UnlockGrant) {
            saveCalls++
            saveError?.let { throw it }
            stored = stored.filterNot { it.id == grant.id } + grant
        }

        override suspend fun revoke(id: String) {
            stored = stored.filterNot { it.id == id }
        }

        override suspend fun active(nowEpochMillis: Long): List<UnlockGrant> =
            stored.filter { it.isActiveAt(nowEpochMillis) }
                .sortedBy { it.expiresAtEpochMillis }

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

    private class FakeCredentialStore : CredentialStore {
        private var credential: PasswordCredential? = null

        override fun read(): PasswordCredential? = credential

        override fun write(credential: PasswordCredential) {
            this.credential = credential
        }

        override fun clear() {
            credential = null
        }
    }

    private companion object {
        const val PACKAGE = "com.example.app"
        const val SCHEDULE_ID = "focus"
    }
}
