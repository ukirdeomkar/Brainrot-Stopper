package dev.brainrotstopper.core.data

import dev.brainrotstopper.core.data.db.BrainrotDatabase
import dev.brainrotstopper.core.data.db.toEntity
import dev.brainrotstopper.core.data.db.toModel
import dev.brainrotstopper.core.model.UnlockGrant

/** Room-backed [UnlockGrantRepository]. */
class RoomUnlockGrantRepository(
    database: BrainrotDatabase,
) : UnlockGrantRepository {

    private val dao = database.unlockGrantDao()

    override suspend fun save(grant: UnlockGrant) {
        dao.upsert(grant.toEntity())
    }

    override suspend fun revoke(id: String) {
        dao.delete(id)
    }

    override suspend fun active(nowEpochMillis: Long): List<UnlockGrant> =
        dao.active(nowEpochMillis).map { it.toModel() }

    override suspend fun activeFor(packageName: String, nowEpochMillis: Long): UnlockGrant? =
        dao.activeFor(packageName, nowEpochMillis)?.toModel()

    override suspend fun purgeExpired(nowEpochMillis: Long): Int = dao.purgeExpired(nowEpochMillis)
}
