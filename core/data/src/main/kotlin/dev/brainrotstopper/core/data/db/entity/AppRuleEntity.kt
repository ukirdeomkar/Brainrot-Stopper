package dev.brainrotstopper.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Room row for [dev.brainrotstopper.core.model.AppRule]. */
@Entity(tableName = "app_rule")
data class AppRuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    val enabled: Boolean,
    @ColumnInfo(name = "block_all")
    val blockAll: Boolean,
    @ColumnInfo(name = "short_form_detection")
    val shortFormDetection: Boolean,
    @ColumnInfo(name = "daily_limit_minutes")
    val dailyLimitMinutes: Int?,
)
