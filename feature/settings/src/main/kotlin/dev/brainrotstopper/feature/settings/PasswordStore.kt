package dev.brainrotstopper.feature.settings

import android.content.Context
import dev.brainrotstopper.core.security.EncryptedCredentialStore
import dev.brainrotstopper.core.security.PasswordManager

/**
 * Process-wide password manager.
 *
 * One manager serves the home screen and every password screen so the "configured?" state is
 * read from the same store. [setManagerForTests] is a test seam for instrumentation tests;
 * production code must never call it.
 */
object PasswordStore {

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
