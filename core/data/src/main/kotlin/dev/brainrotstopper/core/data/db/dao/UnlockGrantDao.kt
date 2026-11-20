package dev.brainrotstopper.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.brainrotstopper.core.data.db.entity.UnlockGrantEntity

@Dao
interface UnlockGrantDao {

    @Upsert
    suspend fun upsert(grant: UnlockGrantEntity)

    @Query("DELETE FROM unlock_grant WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM unlock_grant WHERE expires_at > :now ORDER BY expires_at ASC, id ASC")
    suspend fun active(now: Long): List<UnlockGrantEntity>

    @Query(
        "SELECT * FROM unlock_grant WHERE expires_at > :now " +
            "AND (package_name IS NULL OR package_name = :packageName) " +
            "ORDER BY expires_at ASC, id ASC LIMIT 1",
    )
    suspend fun activeFor(packageName: String, now: Long): UnlockGrantEntity?

    @Query("DELETE FROM unlock_grant WHERE expires_at <= :now")
    suspend fun purgeExpired(now: Long): Int
}
