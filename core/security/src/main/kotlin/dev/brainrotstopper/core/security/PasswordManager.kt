package dev.brainrotstopper.core.security

import dev.brainrotstopper.core.model.PasswordMode

/** Result of setting the credential for the first time. */
sealed interface ConfigureResult {
    data object Saved : ConfigureResult
    data object AlreadyConfigured : ConfigureResult
    data class Invalid(val problem: PasswordValidationProblem) : ConfigureResult
}

/** Result of changing an existing credential. A change always needs the old password. */
sealed interface ChangeCredentialResult {
    data object Changed : ChangeCredentialResult
    data object WrongOldPassword : ChangeCredentialResult
    data object NotConfigured : ChangeCredentialResult
    data class Invalid(val problem: PasswordValidationProblem) : ChangeCredentialResult
}

/**
 * Owns the unblock credential: initial setup, verification, and password changes.
 *
 * Rules enforced here (and covered by JVM unit tests, see `PasswordManagerTest`):
 * - setup only succeeds once; there is no overwrite path that skips verification;
 * - verification compares PBKDF2 bytes in constant time via [PasswordKdf];
 * - changing the password refuses unless the old password verifies;
 * - the [PasswordMode] recorded at setup is preserved across a change; the mode is what the
 *   settings UI uses to say "a friend holds this password".
 *
 * There is intentionally no recovery or reset method. Clearing app data removes the
 * credential, which is the documented escape hatch in `docs/design.md` section 4.3.
 */
class PasswordManager(
    private val store: CredentialStore,
    private val kdf: PasswordKdf = PasswordKdf(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun isConfigured(): Boolean = store.read() != null

    /** The recorded mode, or null when no credential is set. */
    fun mode(): PasswordMode? = store.read()?.mode

    /** Sets the credential once. Fails instead of overwriting when one already exists. */
    fun configure(password: CharArray, mode: PasswordMode): ConfigureResult {
        if (store.read() != null) return ConfigureResult.AlreadyConfigured
        PasswordPolicy.validate(password)?.let { return ConfigureResult.Invalid(it) }

        store.write(
            PasswordCredential.from(
                mode = mode,
                hash = kdf.hash(password),
                createdAtEpochMillis = clock(),
            ),
        )
        return ConfigureResult.Saved
    }

    /** True only when [password] matches the stored credential. No credential means false. */
    fun verify(password: CharArray): Boolean {
        val credential = store.read() ?: return false
        return kdf.verify(password, credential.hash)
    }

    /** Changes the credential only when [oldPassword] verifies. */
    fun changePassword(oldPassword: CharArray, newPassword: CharArray): ChangeCredentialResult {
        val credential = store.read() ?: return ChangeCredentialResult.NotConfigured
        if (!kdf.verify(oldPassword, credential.hash)) {
            return ChangeCredentialResult.WrongOldPassword
        }
        PasswordPolicy.validate(newPassword)?.let { return ChangeCredentialResult.Invalid(it) }

        store.write(
            PasswordCredential.from(
                mode = credential.mode,
                hash = kdf.hash(newPassword),
                createdAtEpochMillis = clock(),
            ),
        )
        return ChangeCredentialResult.Changed
    }
}
