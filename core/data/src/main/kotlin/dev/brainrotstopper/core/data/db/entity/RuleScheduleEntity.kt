package dev.brainrotstopper.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Many-to-many link between an app rule and a schedule. */
@Entity(
    tableName = "rule_schedule",
    primaryKeys = ["package_name", "schedule_id"],
    foreignKeys = [
        ForeignKey(
            entity = AppRuleEntity::class,
            parentColumns = ["package_name"],
            childColumns = ["package_name"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("schedule_id")],
)
data class RuleScheduleEntity(
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "schedule_id")
    val scheduleId: String,
)
