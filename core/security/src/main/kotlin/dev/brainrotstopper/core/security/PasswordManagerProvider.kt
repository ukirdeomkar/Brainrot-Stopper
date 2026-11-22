package dev.brainrotstopper.core.security

import android.content.Context

/**
 * Process-wide [PasswordManager] shared by the block overlay and the settings screens.
 *
 * Both the AccessibilityService (emergency unlock) and the rule editors (settings gate) must
 * resolve the *same* credential store; a second manager built from a different store would let
 * a screen verify against a stale value. The managed instance is created lazily from
 * [EncryptedCredentialStore].
 *
 * [setManagerForTests] is a test seam for instrumentation tests. Production code must never
 * call it.
 */
object PasswordManagerProvider {

    @Volatile
    private var manager: PasswordManager? = null

    @Volatile
    private var testManager: PasswordManager? = null

    fun manager(context: Context): PasswordManager {
        testManager?.let { return it }
        return manager ?: synchronized(this) {
            manager ?: PasswordManager(
                EncryptedCredentialStore(context.applicationContext),
            ).also { manager = it }
        }
    }

    fun setManagerForTests(manager: PasswordManager?) {
        testManager = manager
    }
}
