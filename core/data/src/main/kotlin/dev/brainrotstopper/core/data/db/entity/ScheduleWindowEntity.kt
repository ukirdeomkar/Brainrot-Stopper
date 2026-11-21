package dev.brainrotstopper.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * One window of a [ScheduleEntity], ordered by [ordinal].
 *
 * Windows are minutes from midnight and support overnight ranges (`start` > `end`).
 */
@Entity(
    tableName = "schedule_window",
    primaryKeys = ["schedule_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ScheduleWindowEntity(
    @ColumnInfo(name = "schedule_id")
    val scheduleId: String,
    @ColumnInfo(name = "start_min")
    val startMinuteOfDay: Int,
    @ColumnInfo(name = "end_min")
    val endMinuteOfDay: Int,
    val ordinal: Int,
)
