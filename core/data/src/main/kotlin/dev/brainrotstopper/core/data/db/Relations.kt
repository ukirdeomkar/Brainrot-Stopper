package dev.brainrotstopper.core.data.db

import androidx.room.Embedded
import androidx.room.Relation
import dev.brainrotstopper.core.data.db.entity.AppRuleEntity
import dev.brainrotstopper.core.data.db.entity.RuleScheduleEntity
import dev.brainrotstopper.core.data.db.entity.ScheduleEntity
import dev.brainrotstopper.core.data.db.entity.ScheduleWindowEntity

/** An app rule with the ids of the schedules it belongs to. */
data class AppRuleWithSchedules(
    @Embedded
    val rule: AppRuleEntity,
    @Relation(
        parentColumn = "package_name",
        entityColumn = "package_name",
        entity = RuleScheduleEntity::class,
    )
    val scheduleLinks: List<RuleScheduleEntity>,
)

/** A schedule with its windows ordered by [ScheduleWindowEntity.ordinal]. */
data class ScheduleWithWindows(
    @Embedded
    val schedule: ScheduleEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "schedule_id",
        entity = ScheduleWindowEntity::class,
    )
    val windows: List<ScheduleWindowEntity>,
)
