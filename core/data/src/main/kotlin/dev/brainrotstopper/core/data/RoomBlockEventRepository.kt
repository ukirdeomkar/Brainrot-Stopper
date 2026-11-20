package dev.brainrotstopper.core.data

import dev.brainrotstopper.core.data.db.BrainrotDatabase
import dev.brainrotstopper.core.data.db.entity.BlockEventEntity
import dev.brainrotstopper.core.data.db.toModel
import dev.brainrotstopper.core.model.BlockEvent
import dev.brainrotstopper.core.model.BlockReason

/** Room-backed [BlockEventRepository]. */
class RoomBlockEventRepository(
    database: BrainrotDatabase,
) : BlockEventRepository {

    private val dao = database.blockEventDao()

    override suspend fun record(
        packageName: String,
        startedAtEpochMillis: Long,
        reason: BlockReason,
    ): Long = dao.insert(
        BlockEventEntity(
            packageName = packageName,
            startedAtEpochMillis = startedAtEpochMillis,
            reason = reason.name,
            releasedAtEpochMillis = null,
        ),
    )

    override suspend fun releaseOpen(packageName: String, releasedAtEpochMillis: Long): Int =
        dao.releaseOpen(packageName, releasedAtEpochMillis)

    override suspend fun recent(limit: Int): List<BlockEvent> =
        dao.recent(limit).map { it.toModel() }

    override suspend fun trimTo(keep: Int): Int = dao.trimTo(keep)
}
