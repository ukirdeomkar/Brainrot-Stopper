package dev.brainrotstopper.core.data

import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule

/**
 * Storage boundary for user rules and schedules.
 *
 * Implemented by [RoomRuleRepository] on top of [dev.brainrotstopper.core.data.db.BrainrotDatabase].
 * All calls are `suspend` because they touch disk; callers choose the dispatcher.
 */
interface RuleRepository {
    suspend fun rules(): List<AppRule>
    suspend fun upsertRule(rule: AppRule)
    suspend fun removeRule(packageName: String)

    suspend fun schedules(): List<BlockSchedule>
    suspend fun upsertSchedule(schedule: BlockSchedule)
    suspend fun removeSchedule(id: String)
}
