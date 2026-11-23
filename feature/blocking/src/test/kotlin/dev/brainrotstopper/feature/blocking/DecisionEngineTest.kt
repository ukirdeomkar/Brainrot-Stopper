package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockReason
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.TimeWindow
import dev.brainrotstopper.core.model.UnlockGrant
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionEngineTest {

    private val engine = DecisionEngine()
    private val utc = ZoneOffset.UTC

    // 2026-11-16 is a Monday, 2026-11-17 a Tuesday.
    private val mondayNoon = Instant.parse("2026-11-16T12:00:00Z")
    private val mondayLate = Instant.parse("2026-11-16T23:30:00Z")
    private val tuesdayEarly = Instant.parse("2026-11-17T01:30:00Z")
    private val tuesdayTwo = Instant.parse("2026-11-17T02:00:00Z")

    @Test
    fun `unknown package is allowed`() {
        val decision = decide(packageName = "com.example.other", at = mondayNoon)

        assertFalse(decision.block)
        assertNull(decision.reason)
        assertNull(decision.releaseAtEpochMillis)
    }

    @Test
    fun `blockAll blocks outside every schedule with no release time`() {
        val decision = decide(
            at = Instant.parse("2026-11-16T18:00:00Z"),
            rules = listOf(rule(blockAll = true)),
        )

        assertTrue(decision.block)
        assertEquals(BlockReason.ALWAYS_BLOCK, decision.reason)
        assertNull(decision.releaseAtEpochMillis)
    }

    @Test
    fun `blockAll blocks with no schedule and no daily limit`() {
        val decision = decide(
            rules = listOf(rule(blockAll = true, scheduleIds = emptySet())),
        )

        assertTrue(decision.block)
        assertEquals(BlockReason.ALWAYS_BLOCK, decision.reason)
    }

    @Test
    fun `blockAll takes precedence over an active schedule`() {
        val decision = decide(
            at = mondayNoon,
            rules = listOf(rule(blockAll = true)),
        )

        assertTrue(decision.block)
        assertEquals(BlockReason.ALWAYS_BLOCK, decision.reason)
        assertNull(decision.releaseAtEpochMillis)
    }

    @Test
    fun `an active unlock grant overrides blockAll`() {
        val decision = decide(
            rules = listOf(rule(blockAll = true)),
            grants = listOf(grant(packageName = PACKAGE, expiresAt = mondayNoon.plusSeconds(60))),
        )

        assertFalse(decision.block)
    }

    @Test
    fun `a disabled blockAll rule still allows`() {
        val decision = decide(
            rules = listOf(rule(enabled = false, blockAll = true)),
        )

        assertFalse(decision.block)
    }

    @Test
    fun `disabled rule is allowed even inside its schedule`() {
        val decision = decide(
            at = mondayNoon,
            rules = listOf(rule(enabled = false)),
        )

        assertFalse(decision.block)
    }

    @Test
    fun `active schedule blocks and releases at the window end`() {
        val decision = decide(at = mondayNoon)

        assertTrue(decision.block)
        assertEquals(BlockReason.SCHEDULE, decision.reason)
        assertEquals(
            Instant.parse("2026-11-16T17:00:00Z").toEpochMilli(),
            decision.releaseAtEpochMillis,
        )
    }

    @Test
    fun `schedule outside its window and on another day allows`() {
        assertFalse(decide(at = Instant.parse("2026-11-16T18:00:00Z")).block)
        assertFalse(decide(at = Instant.parse("2026-11-17T12:00:00Z")).block)
    }

    @Test
    fun `disabled schedule never blocks`() {
        val decision = decide(
            at = mondayNoon,
            schedules = listOf(schedule(enabled = false)),
        )

        assertFalse(decision.block)
    }

    @Test
    fun `overnight window releases on the next day`() {
        val decision = decide(
            at = mondayLate,
            schedules = listOf(nightSchedule()),
        )

        assertTrue(decision.block)
        assertEquals(
            Instant.parse("2026-11-17T02:00:00Z").toEpochMilli(),
            decision.releaseAtEpochMillis,
        )
    }

    @Test
    fun `overnight window carries into the next morning and releases that morning`() {
        val decision = decide(
            at = tuesdayEarly,
            schedules = listOf(nightSchedule()),
        )

        assertTrue(decision.block)
        assertEquals(
            Instant.parse("2026-11-17T02:00:00Z").toEpochMilli(),
            decision.releaseAtEpochMillis,
        )
    }

    @Test
    fun `overnight window is half open at its end`() {
        val decision = decide(
            at = tuesdayTwo,
            schedules = listOf(nightSchedule()),
        )

        assertFalse(decision.block)
    }

    @Test
    fun `exhausted timer blocks until next local midnight`() {
        val decision = decide(
            at = mondayNoon,
            rules = listOf(rule(dailyLimitMinutes = 30)),
            schedules = emptyList(),
            timers = listOf(timer(usedMinutes = 30)),
        )

        assertTrue(decision.block)
        assertEquals(BlockReason.DAILY_LIMIT, decision.reason)
        assertEquals(
            Instant.parse("2026-11-17T00:00:00Z").toEpochMilli(),
            decision.releaseAtEpochMillis,
        )
    }

    @Test
    fun `timer below the limit allows`() {
        val decision = decide(
            at = mondayNoon,
            rules = listOf(rule(dailyLimitMinutes = 30)),
            schedules = emptyList(),
            timers = listOf(timer(usedMinutes = 29)),
        )

        assertFalse(decision.block)
    }

    @Test
    fun `timer snapshot from another day is ignored`() {
        val decision = decide(
            at = mondayNoon,
            rules = listOf(rule(dailyLimitMinutes = 30)),
            schedules = emptyList(),
            timers = listOf(timer(usedMinutes = 120, epochDay = epochDayOf("2026-11-15"))),
        )

        assertFalse(decision.block)
    }

    @Test
    fun `active package grant allows an otherwise blocked app`() {
        val decision = decide(
            at = mondayNoon,
            grants = listOf(grant(packageName = PACKAGE, expiresAt = mondayNoon.plusSeconds(60))),
        )

        assertFalse(decision.block)
    }

    @Test
    fun `active global grant allows an otherwise blocked app`() {
        val decision = decide(
            at = mondayNoon,
            grants = listOf(grant(packageName = null, expiresAt = mondayNoon.plusSeconds(60))),
        )

        assertFalse(decision.block)
    }

    @Test
    fun `grant for another package does not allow`() {
        val decision = decide(
            at = mondayNoon,
            grants = listOf(
                grant(packageName = "com.example.other", expiresAt = mondayNoon.plusSeconds(60)),
            ),
        )

        assertTrue(decision.block)
    }

    @Test
    fun `expired grant does not allow`() {
        val decision = decide(
            at = mondayNoon,
            grants = listOf(grant(packageName = PACKAGE, expiresAt = mondayNoon.minusSeconds(1))),
        )

        assertTrue(decision.block)
    }

    @Test
    fun `schedule takes precedence over an exhausted timer`() {
        val decision = decide(
            at = mondayNoon,
            rules = listOf(rule(dailyLimitMinutes = 30)),
            timers = listOf(timer(usedMinutes = 60)),
        )

        assertTrue(decision.block)
        assertEquals(BlockReason.SCHEDULE, decision.reason)
    }

    @Test
    fun `timer midnight release follows the supplied timezone`() {
        val instant = Instant.parse("2026-03-08T06:30:00Z") // 01:30 EST, before the DST change
        val rules = listOf(rule(dailyLimitMinutes = 30))
        val timers = listOf(
            TimerState(PACKAGE, instant.atZone(nyZone).toLocalDate().toEpochDay(), 30),
        )

        val utcDecision = engine.decide(PACKAGE, instant, utc, rules, emptyList(), timers, emptyList())
        val nyDecision = engine.decide(PACKAGE, instant, nyZone, rules, emptyList(), timers, emptyList())

        assertEquals(
            Instant.parse("2026-03-09T00:00:00Z").toEpochMilli(),
            utcDecision.releaseAtEpochMillis,
        )
        assertEquals(
            Instant.parse("2026-03-09T04:00:00Z").toEpochMilli(),
            nyDecision.releaseAtEpochMillis,
        )
    }

    private fun decide(
        packageName: String = PACKAGE,
        at: Instant = mondayNoon,
        zone: ZoneId = utc,
        rules: List<AppRule> = listOf(rule()),
        schedules: List<BlockSchedule> = listOf(schedule()),
        timers: List<TimerState> = emptyList(),
        grants: List<UnlockGrant> = emptyList(),
    ): Decision = engine.decide(
        packageName = packageName,
        at = at,
        zone = zone,
        rules = rules,
        schedules = schedules,
        timers = timers,
        grants = grants,
    )

    private fun rule(
        packageName: String = PACKAGE,
        enabled: Boolean = true,
        dailyLimitMinutes: Int? = null,
        scheduleIds: Set<String> = setOf(SCHEDULE_ID),
        blockAll: Boolean = false,
    ): AppRule = AppRule(
        packageName = packageName,
        displayName = "Example",
        enabled = enabled,
        dailyLimitMinutes = dailyLimitMinutes,
        scheduleIds = scheduleIds,
        blockAll = blockAll,
    )

    private fun schedule(
        id: String = SCHEDULE_ID,
        daysOfWeek: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY),
        windows: List<TimeWindow> = listOf(TimeWindow(9 * 60, 17 * 60)),
        enabled: Boolean = true,
    ): BlockSchedule = BlockSchedule(
        id = id,
        label = "Focus",
        daysOfWeek = daysOfWeek,
        windows = windows,
        enabled = enabled,
    )

    private fun nightSchedule(): BlockSchedule = schedule(
        windows = listOf(TimeWindow(22 * 60, 2 * 60)),
    )

    private fun timer(
        usedMinutes: Int,
        epochDay: Long = epochDayOf("2026-11-16"),
    ): TimerState = TimerState(PACKAGE, epochDay, usedMinutes)

    private fun grant(packageName: String?, expiresAt: Instant): UnlockGrant = UnlockGrant(
        id = "grant-1",
        packageName = packageName,
        expiresAtEpochMillis = expiresAt.toEpochMilli(),
        reason = "Test unlock",
    )

    private fun epochDayOf(date: String): Long = LocalDate.parse(date).toEpochDay()

    private companion object {
        const val PACKAGE = "com.example.app"
        const val SCHEDULE_ID = "focus"
        val nyZone: ZoneId = ZoneId.of("America/New_York")
    }
}
