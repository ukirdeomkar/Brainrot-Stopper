package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.data.UnlockGrantRepository
import dev.brainrotstopper.core.model.UnlockGrant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Outcome of one unlock attempt.
 *
 * Callers must treat only [Success] as "the app is unlocked": the grant is durable and
 * visible in the cached snapshot at that point. [Rejected] means the gate said no, [Failed]
 * means the grant could not be persisted or made visible, and [NotAttached] means no
 * AccessibilityService currently owns the engine.
 */
sealed interface UnlockResult {
    data object Success : UnlockResult
    data object Rejected : UnlockResult
    data class Failed(val cause: Throwable) : UnlockResult
    data object NotAttached : UnlockResult
}

/**
 * End-to-end unlock flow: password gate -> persist grant -> refresh cache -> dismiss overlay.
 *
 * The flow is awaitable and only reports [UnlockResult.Success] after
 * [BlockingStateCache.isUnlocked] is true, so a caller can no longer observe "success" while
 * the grant is still in flight. A failed grant write is surfaced through
 * [UnlockResult.Failed] instead of being swallowed, and the overlay is only dismissed after
 * the grant is visible.
 *
 * Password verification is CPU-expensive (PBKDF2), so the gate runs on [ioDispatcher] too.
 * The entered characters are wiped after the attempt.
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
    private val sessionUnlockMillis: () -> Long = { SESSION_UNLOCK_MILLIS },
) {

    suspend fun unlock(packageName: String, password: CharArray? = null): UnlockResult {
        val now = clock()
        val request = UnlockRequest(
            packageName = packageName,
            reason = cache.decisionFor(packageName).reason,
            nowEpochMillis = now,
            password = password,
        )

        val authorized = try {
            withContext(ioDispatcher) { gate().authorize(request) }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            return UnlockResult.Failed(t)
        } finally {
            password?.fill('\u0000')
        }
        if (!authorized) return UnlockResult.Rejected

        try {
            withContext(ioDispatcher) {
                unlockGrants.save(
                    UnlockGrant(
                        id = newGrantId(),
                        packageName = packageName,
                        expiresAtEpochMillis = now + sessionUnlockMillis(),
                        reason = "Session unlock from block screen",
                    ),
                )
                cache.refresh()
            }
        } catch (c: CancellationException) {
            throw c
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
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // The grant is already durable. A failed dismissal is recovered by the next
            // window change, which re-evaluates the decision and hides the overlay.
        }
        return UnlockResult.Success
    }

    companion object {
        /** Default emergency-bypass length. Grants auto-expire and blocking re-arms. */
        const val SESSION_UNLOCK_MILLIS: Long = BlockOverlayController.UNLOCK_MINUTES * 60_000L
    }
}
