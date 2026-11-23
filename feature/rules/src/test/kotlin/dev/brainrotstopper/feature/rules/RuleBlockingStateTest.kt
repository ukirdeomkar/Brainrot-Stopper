package dev.brainrotstopper.feature.rules

import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.TimeWindow
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleBlockingStateTest {

    @Test
    fun `an enabled rule with no schedule limit or always-block is flagged inert`() {
        val rule = rule(scheduleIds = emptySet())

        assertEquals(RuleBlockingState.NOT_BLOCKING, rule.blockingState(emptyList()))
        assertTrue(rule.isInert(emptyList()))
    }

    @Test
    fun `blockAll is always blocked`() {
        val rule = rule(blockAll = true, scheduleIds = emptySet())

        assertEquals(RuleBlockingState.ALWAYS_BLOCKED, rule.blockingState(emptyList()))
        assertFalse(rule.isInert(emptyList()))
    }

    @Test
    fun `blockAll wins over an assigned schedule`() {
        val rule = rule(blockAll = true)

        assertEquals(
            RuleBlockingState.ALWAYS_BLOCKED,
            rule.blockingState(listOf(schedule(enabled = true))),
        )
    }

    @Test
    fun `an assigned enabled schedule is schedule blocked`() {
        val rule = rule(dailyLimitMinutes = 30)

        assertEquals(
            RuleBlockingState.SCHEDULE_BLOCKED,
            rule.blockingState(listOf(schedule(enabled = true))),
        )
    }

    @Test
    fun `an assigned disabled schedule does not block`() {
        val rule = rule(scheduleIds = setOf(SCHEDULE_ID))

        assertEquals(
            RuleBlockingState.NOT_BLOCKING,
            rule.blockingState(listOf(schedule(enabled = false))),
        )
    }

    @Test
    fun `a deleted schedule does not block`() {
        val rule = rule(scheduleIds = setOf(SCHEDULE_ID))

        assertEquals(RuleBlockingState.NOT_BLOCKING, rule.blockingState(emptyList()))
    }

    @Test
    fun `a disabled schedule falls back to the daily limit`() {
        val rule = rule(dailyLimitMinutes = 30)

        assertEquals(
            RuleBlockingState.DAILY_LIMIT_ONLY,
            rule.blockingState(listOf(schedule(enabled = false))),
        )
    }

    @Test
    fun `a daily limit with no schedule is daily limit only`() {
        val rule = rule(dailyLimitMinutes = 30, scheduleIds = emptySet())

        assertEquals(RuleBlockingState.DAILY_LIMIT_ONLY, rule.blockingState(emptyList()))
    }

    @Test
    fun `a disabled rule is not counted as blocking`() {
        val rule = rule(enabled = false, blockAll = true)

        assertEquals(RuleBlockingState.DISABLED, rule.blockingState(emptyList()))
        assertFalse(rule.isInert(emptyList()))
    }

    @Test
    fun `a newly picked app is always blocked`() {
        val rule = newGuardedRule("com.example.new", "New app")

        assertEquals(RuleBlockingState.ALWAYS_BLOCKED, rule.blockingState(emptyList()))
        assertTrue(rule.blockAll)
        assertTrue(rule.enabled)
    }

    @Test
    fun `a newly picked short-form app has detection on by default`() {
        val youtube = newGuardedRule("com.google.android.youtube", "YouTube")
        val other = newGuardedRule("com.example.new", "New app")

        assertTrue(youtube.shortFormDetection)
        assertEquals(false, other.shortFormDetection)
        assertTrue(youtube.blockAll)
    }

    private fun rule(
        enabled: Boolean = true,
        dailyLimitMinutes: Int? = null,
        scheduleIds: Set<String> = setOf(SCHEDULE_ID),
        blockAll: Boolean = false,
    ): AppRule = AppRule(
        packageName = "com.example.app",
        displayName = "Example",
        enabled = enabled,
        dailyLimitMinutes = dailyLimitMinutes,
        scheduleIds = scheduleIds,
        blockAll = blockAll,
    )

    private fun schedule(enabled: Boolean): BlockSchedule = BlockSchedule(
        id = SCHEDULE_ID,
        label = "Focus",
        daysOfWeek = setOf(DayOfWeek.MONDAY),
        windows = listOf(TimeWindow(9 * 60, 17 * 60)),
        enabled = enabled,
    )

    private companion object {
        const val SCHEDULE_ID = "focus"
    }
}
