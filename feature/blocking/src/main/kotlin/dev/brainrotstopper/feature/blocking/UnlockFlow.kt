package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.data.UnlockGrantRepository
import dev.brainrotstopper.core.model.UnlockGrant
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Outcome of one unlock attempt.
 *
 * Callers must treat only [Success] as "the app is unlocked": the grant is durable and
 * visible in the cached snapshot at that point. [Rejected] means the gate said no,
 * [Failed] means the grant could not be persisted or made visible, and [NotAttached] means
 * no AccessibilityService currently owns the engine.
 */
sealed interface UnlockResult {
    data object Success : UnlockResult
    data object Rejected : UnlockResult
    data class Failed(val cause: Throwable) : UnlockResult
    data object NotAttached : UnlockResult
}

/**
 * End-to-end unlock flow: gate -> persist grant -> refresh cache -> dismiss overlay.
 *
 * The flow is awaitable and only reports [UnlockResult.Success] after
 * [BlockingStateCache.isUnlocked] is true, so a caller can no longer observe "success" while
 * the grant is still in flight. A failed grant write is surfaced through
 * [UnlockResult.Failed] instead of being swallowed, and the overlay is only dismissed after
 * the grant is visible.
 *
 * The service keeps this off the main thread: the repository write and cache refresh run on
 * [ioDispatcher]; [onUnlocked] runs wherever the caller awaits ([Dispatchers.Main] in the
 * service).
 */
class UnlockFlow(
    private val unlockGrants: UnlockGrantRepository,
    private val cache: BlockingStateCache,
    private val gate: () -> UnlockGate,
    private val onUnlocked: suspend (packageName: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newGrantId: () -> String = { "session-${UUID.randomUUID()}" },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun unlock(packageName: String): UnlockResult {
        val now = clock()
        val request = UnlockRequest(
            packageName = packageName,
            reason = cache.decisionFor(packageName).reason,
            nowEpochMillis = now,
        )
        if (!gate().authorize(request)) return UnlockResult.Rejected

        try {
            withContext(ioDispatcher) {
                unlockGrants.save(
                    UnlockGrant(
                        id = newGrantId(),
                        packageName = packageName,
                        expiresAtEpochMillis = now + SESSION_UNLOCK_MILLIS,
                        reason = "Session unlock from block screen",
                    ),
                )
                cache.refresh()
            }
        } catch (t: Throwable) {
            return UnlockResult.Failed(t)
        }

        if (!cache.isUnlocked(packageName, now)) {
            return UnlockResult.Failed(
                IllegalStateException("Unlock grant for $packageName is not visible after refresh"),
            )
        }

        try {
            onUnlocked(packageName)
        } catch (t: Throwable) {
            // The grant is already durable. A failed dismissal is recovered by the next
            // window change, which re-evaluates the decision and hides the overlay.
        }
        return UnlockResult.Success
    }

    companion object {
        /** Session bypass length written by the M4 placeholder unlock. M7 keeps the same shape. */
        const val SESSION_UNLOCK_MILLIS: Long = BlockOverlayController.UNLOCK_MINUTES * 60_000L
    }
}
