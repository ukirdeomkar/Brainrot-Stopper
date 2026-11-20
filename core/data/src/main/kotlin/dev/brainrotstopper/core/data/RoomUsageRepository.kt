package dev.brainrotstopper.core.data

import dev.brainrotstopper.core.data.db.BrainrotDatabase
import dev.brainrotstopper.core.data.db.entity.UsageBucketEntity
import dev.brainrotstopper.core.data.db.toModel
import dev.brainrotstopper.core.model.UsageBucket

/** Room-backed [UsageRepository]. */
class RoomUsageRepository(
    database: BrainrotDatabase,
) : UsageRepository {

    private val dao = database.usageBucketDao()

    override suspend fun usage(packageName: String, epochDay: Long): UsageBucket? =
        dao.get(packageName, epochDay)?.toModel()

    override suspend fun usageForDay(epochDay: Long): List<UsageBucket> =
        dao.forDay(epochDay).map { it.toModel() }

    override suspend fun rewriteUsage(packageName: String, epochDay: Long, usedMillis: Long) {
        dao.upsert(UsageBucketEntity(packageName, epochDay, usedMillis))
    }

    override suspend fun deleteBefore(epochDay: Long) {
        dao.deleteBefore(epochDay)
    }
}
