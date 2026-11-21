package dev.brainrotstopper.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for [dev.brainrotstopper.core.model.BlockSchedule].
 *
 * [daysMask] is a bitmask with Monday in bit 0 through Sunday in bit 6. Keeping the days on the
 * schedule row avoids a join on the blocking engine's hot path.
 */
@Entity(tableName = "schedule")
data class ScheduleEntity(
    @PrimaryKey
    val id: String,
    val label: String,
    val enabled: Boolean,
    @ColumnInfo(name = "days_mask")
    val daysMask: Int,
)
