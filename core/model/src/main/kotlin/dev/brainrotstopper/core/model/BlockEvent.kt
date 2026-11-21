package dev.brainrotstopper.core.model

/**
 * A local diagnostic record of one block. Never uploaded.
 *
 * [releasedAtEpochMillis] is null while the block is still showing, and set when the overlay is
 * dismissed (unlock grant, schedule end, or timer reset).
 */
data class BlockEvent(
    val id: Long,
    val packageName: String,
    val startedAtEpochMillis: Long,
    val reason: BlockReason,
    val releasedAtEpochMillis: Long? = null,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(startedAtEpochMillis > 0) {
            "startedAtEpochMillis must be positive, was $startedAtEpochMillis"
        }
        require(releasedAtEpochMillis == null || releasedAtEpochMillis >= startedAtEpochMillis) {
            "releasedAtEpochMillis must not precede startedAtEpochMillis"
        }
    }
}
