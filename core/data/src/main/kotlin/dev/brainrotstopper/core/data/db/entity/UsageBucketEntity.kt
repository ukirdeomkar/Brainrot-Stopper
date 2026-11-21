package dev.brainrotstopper.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Authoritative usage total for one app on one local day.
 *
 * The primary key makes a rewrite of the same `(package_name, epoch_day)` a replace, never an
 * increment, so a duplicate poll or service restart cannot inflate the total.
 */
@Entity(
    tableName = "usage_bucket",
    primaryKeys = ["package_name", "epoch_day"],
    indices = [Index("epoch_day")],
)
data class UsageBucketEntity(
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "epoch_day")
    val epochDay: Long,
    @ColumnInfo(name = "used_millis")
    val usedMillis: Long,
)
