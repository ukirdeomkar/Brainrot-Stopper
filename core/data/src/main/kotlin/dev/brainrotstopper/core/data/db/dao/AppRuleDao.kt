package dev.brainrotstopper.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.brainrotstopper.core.data.db.AppRuleWithSchedules
import dev.brainrotstopper.core.data.db.entity.AppRuleEntity
import dev.brainrotstopper.core.data.db.entity.RuleScheduleEntity

@Dao
interface AppRuleDao {

    @Transaction
    @Query("SELECT * FROM app_rule ORDER BY package_name")
    suspend fun snapshot(): List<AppRuleWithSchedules>

    @Query("SELECT * FROM app_rule WHERE package_name = :packageName")
    suspend fun get(packageName: String): AppRuleEntity?

    @Upsert
    suspend fun upsert(rule: AppRuleEntity)

    @Query("DELETE FROM app_rule WHERE package_name = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM rule_schedule WHERE package_name = :packageName")
    suspend fun clearScheduleLinks(packageName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScheduleLinks(links: List<RuleScheduleEntity>)

    /** Replaces the rule and its schedule links atomically. */
    @Transaction
    suspend fun upsertWithSchedules(rule: AppRuleEntity, scheduleIds: Collection<String>) {
        upsert(rule)
        clearScheduleLinks(rule.packageName)
        insertScheduleLinks(scheduleIds.map { RuleScheduleEntity(rule.packageName, it) })
    }
}
