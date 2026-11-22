package dev.brainrotstopper.core.security

import dev.brainrotstopper.core.model.PasswordMode

/**
 * One stored credential: who set it and the PBKDF2 parameters + hash.
 *
 * [version] lets a future build detect an older stored shape before it tries to decode fields.
 * Version 1 is the PBKDF2-HMAC-SHA256 shape described in `docs/design.md` section 4.1.
 */
class PasswordCredential(
    val version: Int,
    val mode: PasswordMode,
    val hash: PasswordHash,
    val createdAtEpochMillis: Long,
) {
    companion object {
        const val CURRENT_VERSION: Int = 1

        fun from(
            mode: PasswordMode,
            hash: PasswordHash,
            createdAtEpochMillis: Long,
        ): PasswordCredential = PasswordCredential(
            version = CURRENT_VERSION,
            mode = mode,
            hash = hash,
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }
}
