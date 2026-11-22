package dev.brainrotstopper.core.security

/**
 * Storage seam for the unblock credential.
 *
 * Production uses [EncryptedCredentialStore]. Tests use an in-memory implementation so the KDF
 * and change-password rules run as fast JVM unit tests without an Android keystore.
 */
interface CredentialStore {
    fun read(): PasswordCredential?

    fun write(credential: PasswordCredential)

    fun clear()
}
