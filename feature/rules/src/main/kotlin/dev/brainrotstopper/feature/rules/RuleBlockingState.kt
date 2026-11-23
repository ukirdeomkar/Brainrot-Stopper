package dev.brainrotstopper.feature.rules

import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule

/** The effective blocking behaviour of an [AppRule], shown on the rules list. */
enum class RuleBlockingState {
    ALWAYS_BLOCKED,
    SCHEDULE_BLOCKED,
    DAILY_LIMIT_ONLY,
    NOT_BLOCKING,
    DISABLED,
}

/**
 * Resolves what [this] rule actually does with the stored [schedules].
 *
 * The order matches `DecisionEngine`: an always-block rule wins, then an assigned schedule
 * that still exists and is enabled, then a daily limit. An enabled rule that reaches none of
 * them is [RuleBlockingState.NOT_BLOCKING]; the list must flag that instead of leaving it
 * silently inert.
 */
fun AppRule.blockingState(schedules: List<BlockSchedule>): RuleBlockingState {
    if (!enabled) return RuleBlockingState.DISABLED
    if (blockAll) return RuleBlockingState.ALWAYS_BLOCKED
    if (hasEnabledSchedule(schedules)) return RuleBlockingState.SCHEDULE_BLOCKED
    if (dailyLimitMinutes != null) return RuleBlockingState.DAILY_LIMIT_ONLY
    return RuleBlockingState.NOT_BLOCKING
}

/** True when the rule is enabled but cannot block anything as configured. */
fun AppRule.isInert(schedules: List<BlockSchedule>): Boolean =
    blockingState(schedules) == RuleBlockingState.NOT_BLOCKING

/**
 * A rule created by the app picker. Newly selected apps block the whole app at once, so
 * selecting an app can never create an inert rule.
 *
 * Short-form detection is switched on by default for the three apps the owner named, while the
 * whole-app rule stays on as the guaranteed fallback. The user turns whole-app blocking off in
 * the rule editor to rely on detection alone; both switches are editable.
 */
fun newGuardedRule(packageName: String, displayName: String): AppRule =
    AppRule(
        packageName = packageName,
        displayName = displayName,
        blockAll = true,
        shortFormDetection = packageName in DEFAULT_SHORT_FORM_PACKAGES,
    )

/** Instagram, YouTube, and TikTok, per owner direction (OMK-599). */
val DEFAULT_SHORT_FORM_PACKAGES: Set<String> = setOf(
    "com.instagram.android",
    "com.google.android.youtube",
    "com.zhiliaoapp.musically",
)

private fun AppRule.hasEnabledSchedule(schedules: List<BlockSchedule>): Boolean {
    if (scheduleIds.isEmpty()) return false
    val enabledIds = schedules.asSequence().filter { it.enabled }.map { it.id }.toSet()
    return scheduleIds.any { it in enabledIds }
}
