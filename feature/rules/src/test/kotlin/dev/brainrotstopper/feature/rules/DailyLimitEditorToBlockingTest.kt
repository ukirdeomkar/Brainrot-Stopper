package dev.brainrotstopper.feature.rules

import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockReason
import dev.brainrotstopper.feature.blocking.DecisionEngine
import dev.brainrotstopper.feature.blocking.TimerState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end logic proof for OMK-585: a daily limit produced by the editor model is what the
 * M6 decision engine enforces, at the exact `used >= limit` boundary.
 */
class DailyLimitEditorToBlockingTest {

    private val engine = DecisionEngine()
    private val utc = ZoneOffset.UTC
    private val mondayNoon = Instant.parse("2026-11-16T12:00:00Z")
    private val epochDay = LocalDate.parse("2026-11-16").toEpochDay()

    @Test
    fun `a saved 30 minute limit blocks exactly when 30 minutes are used`() {
        val rule = savedRule("30")

        val atLimit = decide(rule, usedMinutes = 30)

        assertTrue(atLimit.block)
        assertEquals(BlockReason.DAILY_LIMIT, atLimit.reason)
    }

    @Test
    fun `one minute below the saved limit allows`() {
        assertFalse(decide(savedRule("30"), usedMinutes = 29).block)
    }

    @Test
    fun `a cleared limit never blocks however much is used`() {
        val rule = savedRule("")
        assertEquals(null, rule.dailyLimitMinutes)

        assertFalse(decide(rule, usedMinutes = 10_000).block)
    }

    private fun savedRule(dailyLimitInput: String): AppRule {
        val model = RuleEditorModel(
            AppRule(packageName = PACKAGE, displayName = "Example", enabled = true),
        )
        model.dailyLimitInput = dailyLimitInput
        return model.build()
    }

    private fun decide(rule: AppRule, usedMinutes: Int) = engine.decide(
        packageName = PACKAGE,
        at = mondayNoon,
        zone = utc,
        rules = listOf(rule),
        schedules = emptyList(),
        timers = listOf(TimerState(PACKAGE, epochDay, usedMinutes)),
        grants = emptyList(),
    )

    private companion object {
        const val PACKAGE = "com.example.app"
    }
}
