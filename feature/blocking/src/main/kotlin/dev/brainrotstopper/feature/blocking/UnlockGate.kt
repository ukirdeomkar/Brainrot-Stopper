package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.model.BlockReason

/** One request to remove a block. M7 adds the entered password to this request. */
data class UnlockRequest(
    val packageName: String,
    val reason: BlockReason?,
    val nowEpochMillis: Long,
)

/**
 * Gate between the block screen's unlock button and the short-lived bypass grant.
 *
 * M4 ships [PlaceholderUnlockGate] so the overlay dismissal path is wired end to end and
 * testable. **M7 replaces it with the real password verification** (PBKDF2 hash compare) and
 * must not ship v1 with the placeholder in place. The grant write, refresh, and overlay
 * dismissal around this call are already the production shape.
 */
fun interface UnlockGate {
    fun authorize(request: UnlockRequest): Boolean
}

/** M4 placeholder: always allows. Replaced by the real password gate in M7. */
class PlaceholderUnlockGate : UnlockGate {
    override fun authorize(request: UnlockRequest): Boolean = true
}
