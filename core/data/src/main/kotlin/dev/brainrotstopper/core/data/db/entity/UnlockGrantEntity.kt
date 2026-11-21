package dev.brainrotstopper.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Time-boxed bypass. A null `package_name` is a global grant; otherwise it covers one app.
 */
@Entity(
    tableName = "unlock_grant",
    indices = [Index("package_name"), Index("expires_at")],
)
data class UnlockGrantEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "package_name")
    val packageName: String?,
    @ColumnInfo(name = "expires_at")
    val expiresAtEpochMillis: Long,
    val reason: String,
)
