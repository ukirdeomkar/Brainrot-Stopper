package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.model.BlockReason

/**
 * One request to remove a block.
 *
 * [password] is the value the user typed into the block screen. It is optional so a caller can
 * express "no password supplied"; a gate that requires a credential must treat null as a
 * refusal, never as "not required".
 */
data class UnlockRequest(
    val packageName: String,
    val reason: BlockReason?,
    val nowEpochMillis: Long,
    val password: CharArray? = null,
)

/**
 * Gate between the block screen's password entry and the short-lived bypass grant.
 *
 * `authorize` runs on a background dispatcher from [UnlockFlow] because PBKDF2 is deliberately
 * expensive; implementations must not touch the UI. The grant write, refresh, and overlay
 * dismissal around this call are the production shape and do not change.
 */
fun interface UnlockGate {
    fun authorize(request: UnlockRequest): Boolean
}

/** Fails closed. The process default until the AccessibilityService installs the real gate. */
object DenyAllUnlockGate : UnlockGate {
    override fun authorize(request: UnlockRequest): Boolean = false
}

/**
 * Verifies the entered password against the local unblock credential.
 *
 * The manager is resolved lazily so the gate always reads the current credential, including
 * one changed after the service started. A missing password, missing credential, or broken
 * credential store is a refusal.
 */
class PasswordUnlockGate(
    private val managerProvider: () -> dev.brainrotstopper.core.security.PasswordManager,
) : UnlockGate {

    override fun authorize(request: UnlockRequest): Boolean {
        val password = request.password ?: return false
        return try {
            managerProvider().verify(password)
        } catch (t: Throwable) {
            false
        } finally {
            password.fill('\u0000')
        }
    }
}
