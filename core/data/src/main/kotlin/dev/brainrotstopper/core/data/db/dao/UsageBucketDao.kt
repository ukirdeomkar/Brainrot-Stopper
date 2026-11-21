package dev.brainrotstopper.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.brainrotstopper.core.data.db.entity.UsageBucketEntity

@Dao
interface UsageBucketDao {

    @Query("SELECT * FROM usage_bucket WHERE package_name = :packageName AND epoch_day = :epochDay")
    suspend fun get(packageName: String, epochDay: Long): UsageBucketEntity?

    @Query("SELECT * FROM usage_bucket WHERE epoch_day = :epochDay ORDER BY package_name")
    suspend fun forDay(epochDay: Long): List<UsageBucketEntity>

    @Upsert
    suspend fun upsert(bucket: UsageBucketEntity)

    @Query("DELETE FROM usage_bucket WHERE epoch_day < :epochDay")
    suspend fun deleteBefore(epochDay: Long)
}
