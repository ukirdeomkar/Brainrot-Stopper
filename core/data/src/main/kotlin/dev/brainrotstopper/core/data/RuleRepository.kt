package dev.brainrotstopper.core.data

import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule

/**
 * Storage boundary for user rules and schedules.
 *
 * The scaffold ships [InMemoryRuleRepository]; the Room-backed implementation lands in M2.
 */
interface RuleRepository {
    fun rules(): List<AppRule>
    fun upsertRule(rule: AppRule)
    fun removeRule(packageName: String)

    fun schedules(): List<BlockSchedule>
    fun upsertSchedule(schedule: BlockSchedule)
    fun removeSchedule(id: String)
}
