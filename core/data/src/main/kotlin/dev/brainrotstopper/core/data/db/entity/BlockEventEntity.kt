package dev.brainrotstopper.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local-only block diagnostics row. Capped and rotated by the repository. */
@Entity(
    tableName = "block_event",
    indices = [Index("package_name"), Index("started_at")],
)
data class BlockEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "started_at")
    val startedAtEpochMillis: Long,
    /** Stored as [dev.brainrotstopper.core.model.BlockReason] name. */
    val reason: String,
    @ColumnInfo(name = "released_at")
    val releasedAtEpochMillis: Long?,
)
