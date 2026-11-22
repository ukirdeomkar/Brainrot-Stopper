package dev.brainrotstopper.core.data

import dev.brainrotstopper.core.model.BlockEvent
import dev.brainrotstopper.core.model.BlockReason

/**
 * Local-only block diagnostics. Records are capped so the table cannot grow without bound and
 * are never uploaded (design Appendix B).
 */
interface BlockEventRepository {

    /** Records a new open block and returns its generated id. */
    suspend fun record(
        packageName: String,
        startedAtEpochMillis: Long,
        reason: BlockReason,
    ): Long

    /** Marks every open block for [packageName] as released at [releasedAtEpochMillis]. */
    suspend fun releaseOpen(packageName: String, releasedAtEpochMillis: Long): Int

    /**
     * Marks every open block as released at [releasedAtEpochMillis].
     *
     * Used once per process start: an open row can only be stale when the process that owned
     * the overlay is gone, so a diagnostics row must not keep claiming "still blocking".
     */
    suspend fun releaseAllOpen(releasedAtEpochMillis: Long): Int

    suspend fun recent(limit: Int = DEFAULT_LIMIT): List<BlockEvent>

    /** Deletes all but the newest [keep] rows. */
    suspend fun trimTo(keep: Int = DEFAULT_LIMIT): Int

    companion object {
        const val DEFAULT_LIMIT: Int = 500
    }
}
