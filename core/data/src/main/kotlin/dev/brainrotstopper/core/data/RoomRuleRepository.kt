package dev.brainrotstopper.core.data

import dev.brainrotstopper.core.data.db.BrainrotDatabase
import dev.brainrotstopper.core.data.db.toEntity
import dev.brainrotstopper.core.data.db.toModel
import dev.brainrotstopper.core.data.db.toWindowEntities
import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule

/** Room-backed [RuleRepository]. Replaces the scaffold's in-memory store. */
class RoomRuleRepository(
    private val database: BrainrotDatabase,
) : RuleRepository {

    private val appRuleDao = database.appRuleDao()
    private val scheduleDao = database.scheduleDao()

    override suspend fun rules(): List<AppRule> = appRuleDao.snapshot().map { it.toModel() }

    override suspend fun upsertRule(rule: AppRule) {
        appRuleDao.upsertWithSchedules(rule.toEntity(), rule.scheduleIds)
    }

    override suspend fun removeRule(packageName: String) {
        appRuleDao.delete(packageName)
    }

    override suspend fun schedules(): List<BlockSchedule> = scheduleDao.snapshot().map { it.toModel() }

    override suspend fun upsertSchedule(schedule: BlockSchedule) {
        scheduleDao.upsertWithWindows(schedule.toEntity(), schedule.toWindowEntities())
    }

    override suspend fun removeSchedule(id: String) {
        scheduleDao.delete(id)
    }
}
