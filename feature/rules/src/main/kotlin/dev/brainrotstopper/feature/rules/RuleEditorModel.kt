package dev.brainrotstopper.feature.rules

import dev.brainrotstopper.core.model.AppRule

/** Parsed daily-limit field. A [Valid] result with null minutes means no limit is set. */
sealed interface DailyLimitResult {
    data class Valid(val minutes: Int?) : DailyLimitResult
    data class Invalid(val problem: DailyLimitProblem) : DailyLimitResult
}

/** Why a daily-limit field was rejected. */
enum class DailyLimitProblem {
    NotANumber,
    NotPositive,
    TooLarge,
}

/**
 * Editable state for one app rule. The daily-limit parsing and `AppRule` construction live
 * here so the editor's validation is exercised by fast JVM unit tests, not the emulator.
 */
class RuleEditorModel(initial: AppRule) {

    private val packageName: String = initial.packageName
    private val displayName: String = initial.displayName

    var enabled: Boolean = initial.enabled
    var blockAll: Boolean = initial.blockAll
    var scheduleIds: Set<String> = initial.scheduleIds
    var dailyLimitInput: String = initial.dailyLimitMinutes?.toString().orEmpty()

    fun dailyLimit(): DailyLimitResult = parseDailyLimit(dailyLimitInput)

    fun clearDailyLimit() {
        dailyLimitInput = ""
    }

    /** Builds the domain rule. Call only after [dailyLimit] is [DailyLimitResult.Valid]. */
    fun build(): AppRule {
        val result = dailyLimit()
        require(result is DailyLimitResult.Valid) {
            "cannot build a rule with an invalid daily limit: $result"
        }
        return AppRule(
            packageName = packageName,
            displayName = displayName,
            enabled = enabled,
            dailyLimitMinutes = result.minutes,
            scheduleIds = scheduleIds,
            blockAll = blockAll,
        )
    }

    companion object {
        const val MIN_DAILY_LIMIT_MINUTES = 1
        const val MAX_DAILY_LIMIT_MINUTES = 24 * 60

        /**
         * An empty or blank field clears the limit. Otherwise the value must be a whole number
         * within [MIN_DAILY_LIMIT_MINUTES]..[MAX_DAILY_LIMIT_MINUTES].
         */
        fun parseDailyLimit(input: String): DailyLimitResult {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return DailyLimitResult.Valid(null)
            val minutes = trimmed.toLongOrNull()
                ?: return DailyLimitResult.Invalid(DailyLimitProblem.NotANumber)
            return when {
                minutes < MIN_DAILY_LIMIT_MINUTES ->
                    DailyLimitResult.Invalid(DailyLimitProblem.NotPositive)
                minutes > MAX_DAILY_LIMIT_MINUTES ->
                    DailyLimitResult.Invalid(DailyLimitProblem.TooLarge)
                else -> DailyLimitResult.Valid(minutes.toInt())
            }
        }
    }
}
