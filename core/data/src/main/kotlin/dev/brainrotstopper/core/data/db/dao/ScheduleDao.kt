package dev.brainrotstopper.core.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.brainrotstopper.core.data.db.ScheduleWithWindows
import dev.brainrotstopper.core.data.db.entity.ScheduleEntity
import dev.brainrotstopper.core.data.db.entity.ScheduleWindowEntity

@Dao
interface ScheduleDao {

    @Transaction
    @Query("SELECT * FROM schedule ORDER BY label, id")
    suspend fun snapshot(): List<ScheduleWithWindows>

    @Query("SELECT * FROM schedule WHERE id = :id")
    suspend fun get(id: String): ScheduleEntity?

    @Upsert
    suspend fun upsert(schedule: ScheduleEntity)

    @Upsert
    suspend fun upsertWindows(windows: List<ScheduleWindowEntity>)

    @Query("DELETE FROM schedule_window WHERE schedule_id = :scheduleId")
    suspend fun deleteWindows(scheduleId: String)

    @Query("DELETE FROM schedule WHERE id = :id")
    suspend fun delete(id: String)

    /** Replaces a schedule and its complete window list atomically. */
    @Transaction
    suspend fun upsertWithWindows(
        schedule: ScheduleEntity,
        windows: List<ScheduleWindowEntity>,
    ) {
        upsert(schedule)
        deleteWindows(schedule.id)
        upsertWindows(windows)
    }
}
