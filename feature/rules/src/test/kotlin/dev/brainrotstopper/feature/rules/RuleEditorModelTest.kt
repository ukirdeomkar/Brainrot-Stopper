package dev.brainrotstopper.feature.rules

import dev.brainrotstopper.core.model.AppRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEditorModelTest {

    private val noLimit = AppRule(packageName = "com.example.app", displayName = "Example")

    @Test
    fun `a rule without a limit opens with an empty field`() {
        val model = RuleEditorModel(noLimit)

        assertEquals("", model.dailyLimitInput)
        assertEquals(DailyLimitResult.Valid(null), model.dailyLimit())
    }

    @Test
    fun `an existing limit is loaded for editing`() {
        val model = RuleEditorModel(noLimit.copy(dailyLimitMinutes = 45))

        assertEquals("45", model.dailyLimitInput)
        assertEquals(DailyLimitResult.Valid(45), model.dailyLimit())
    }

    @Test
    fun `blank input clears the limit`() {
        val model = RuleEditorModel(noLimit.copy(dailyLimitMinutes = 45))

        model.dailyLimitInput = "   "

        assertEquals(DailyLimitResult.Valid(null), model.dailyLimit())
        assertEquals(null, model.build().dailyLimitMinutes)
    }

    @Test
    fun `a positive whole number is accepted and trimmed`() {
        val model = RuleEditorModel(noLimit)

        model.dailyLimitInput = " 30 "

        assertEquals(DailyLimitResult.Valid(30), model.dailyLimit())
        assertEquals(30, model.build().dailyLimitMinutes)
    }

    @Test
    fun `the bounds are inclusive`() {
        assertEquals(
            DailyLimitResult.Valid(RuleEditorModel.MIN_DAILY_LIMIT_MINUTES),
            RuleEditorModel.parseDailyLimit("1"),
        )
        assertEquals(
            DailyLimitResult.Valid(RuleEditorModel.MAX_DAILY_LIMIT_MINUTES),
            RuleEditorModel.parseDailyLimit("1440"),
        )
    }

    @Test
    fun `zero and negative values are rejected`() {
        assertEquals(
            DailyLimitResult.Invalid(DailyLimitProblem.NotPositive),
            RuleEditorModel.parseDailyLimit("0"),
        )
        assertEquals(
            DailyLimitResult.Invalid(DailyLimitProblem.NotPositive),
            RuleEditorModel.parseDailyLimit("-5"),
        )
    }

    @Test
    fun `non-numeric values are rejected`() {
        assertEquals(
            DailyLimitResult.Invalid(DailyLimitProblem.NotANumber),
            RuleEditorModel.parseDailyLimit("thirty"),
        )
        assertEquals(
            DailyLimitResult.Invalid(DailyLimitProblem.NotANumber),
            RuleEditorModel.parseDailyLimit("30.5"),
        )
    }

    @Test
    fun `values above a day are rejected, including values larger than Int`() {
        assertEquals(
            DailyLimitResult.Invalid(DailyLimitProblem.TooLarge),
            RuleEditorModel.parseDailyLimit("1441"),
        )
        assertEquals(
            DailyLimitResult.Invalid(DailyLimitProblem.TooLarge),
            RuleEditorModel.parseDailyLimit("999999999999"),
        )
    }

    @Test
    fun `clearing the field drops the stored limit`() {
        val model = RuleEditorModel(noLimit.copy(dailyLimitMinutes = 45))

        model.clearDailyLimit()

        assertEquals("", model.dailyLimitInput)
        assertEquals(null, model.build().dailyLimitMinutes)
    }

    @Test
    fun `build keeps the other rule fields`() {
        val initial = noLimit.copy(
            enabled = false,
            blockAll = true,
            scheduleIds = setOf("work", "night"),
        )
        val model = RuleEditorModel(initial)
        model.dailyLimitInput = "60"

        val built = model.build()

        assertEquals("com.example.app", built.packageName)
        assertEquals("Example", built.displayName)
        assertEquals(false, built.enabled)
        assertTrue(built.blockAll)
        assertEquals(setOf("work", "night"), built.scheduleIds)
        assertEquals(60, built.dailyLimitMinutes)
    }

    @Test
    fun `build refuses an invalid limit`() {
        val model = RuleEditorModel(noLimit)
        model.dailyLimitInput = "0"

        assertThrows(IllegalArgumentException::class.java) { model.build() }
    }
}
