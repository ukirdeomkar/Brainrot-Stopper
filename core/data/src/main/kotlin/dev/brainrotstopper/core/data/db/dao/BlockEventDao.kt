package dev.brainrotstopper.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.brainrotstopper.core.data.db.entity.BlockEventEntity

@Dao
interface BlockEventDao {

    @Insert
    suspend fun insert(event: BlockEventEntity): Long

    @Query("SELECT * FROM block_event ORDER BY started_at DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<BlockEventEntity>

    @Query(
        "UPDATE block_event SET released_at = :releasedAt " +
            "WHERE package_name = :packageName AND released_at IS NULL",
    )
    suspend fun releaseOpen(packageName: String, releasedAt: Long): Int

    @Query("UPDATE block_event SET released_at = :releasedAt WHERE released_at IS NULL")
    suspend fun releaseAllOpen(releasedAt: Long): Int

    @Query(
        "DELETE FROM block_event WHERE id NOT IN " +
            "(SELECT id FROM block_event ORDER BY started_at DESC, id DESC LIMIT :keep)",
    )
    suspend fun trimTo(keep: Int): Int
}
