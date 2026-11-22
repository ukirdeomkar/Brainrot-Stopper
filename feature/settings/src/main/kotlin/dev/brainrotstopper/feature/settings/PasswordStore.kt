package dev.brainrotstopper.feature.settings

import android.content.Context
import dev.brainrotstopper.core.security.PasswordManager
import dev.brainrotstopper.core.security.PasswordManagerProvider

/**
 * Process-wide password manager.
 *
 * One manager serves the home screen, every password screen, the rule editors, and the block
 * overlay so the "configured?" state is read from the same store. This is a thin alias over
 * [PasswordManagerProvider], kept so the existing screens and tests keep one entry point.
 */
object PasswordStore {

    fun manager(context: Context): PasswordManager = PasswordManagerProvider.manager(context)

    fun setManagerForTests(manager: PasswordManager?) {
        PasswordManagerProvider.setManagerForTests(manager)
    }
}
