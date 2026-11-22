package dev.brainrotstopper.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * A derived password hash plus the exact parameters needed to verify it later.
 *
 * The parameters are stored with the hash so a future build can raise [iterations] or change
 * the algorithm without invalidating credentials already on a device.
 */
class PasswordHash(
    val algorithm: String,
    val iterations: Int,
    val salt: ByteArray,
    val hash: ByteArray,
) {
    fun sameHashAs(other: PasswordHash): Boolean =
        algorithm == other.algorithm &&
            iterations == other.iterations &&
            salt.contentEquals(other.salt) &&
            hash.contentEquals(other.hash)

    override fun equals(other: Any?): Boolean = other is PasswordHash && sameHashAs(other)

    override fun hashCode(): Int {
        var result = algorithm.hashCode()
        result = 31 * result + iterations
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + hash.contentHashCode()
        return result
    }
}

/**
 * PBKDF2-HMAC-SHA256 password derivation for the local-only unblock credential.
 *
 * Parameters follow `docs/design.md` section 4.1 and the M7 acceptance criteria:
 * - 128-bit (16 byte) random salt per credential, from [SecureRandom];
 * - 600,000 iterations by default (OWASP 2023 floor for PBKDF2-SHA256);
 * - 256-bit derived key;
 * - verification uses [MessageDigest.isEqual] so the compare itself is constant-time.
 *
 * The iteration count is a constructor parameter so tests can use a small, fast value while
 * one test still exercises the real default. The algorithm name is stored with every hash, so
 * changing it later is a migration, not a flag day.
 */
class PasswordKdf(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val keyLengthBits: Int = DEFAULT_KEY_LENGTH_BITS,
    private val saltLengthBytes: Int = SALT_LENGTH_BYTES,
    private val secureRandom: SecureRandom = SecureRandom(),
) {

    /** A fresh random salt. Every credential gets its own; a reused salt would break the KDF. */
    fun newSalt(): ByteArray = ByteArray(saltLengthBytes).also(secureRandom::nextBytes)

    /** Derives the key bytes for [password] and [salt] with this instance's parameters. */
    fun derive(
        password: CharArray,
        salt: ByteArray,
        iterations: Int = this.iterations,
    ): ByteArray {
        require(salt.isNotEmpty()) { "salt must not be empty" }
        require(iterations > 0) { "iterations must be positive" }
        val spec = PBEKeySpec(password, salt, iterations, keyLengthBits)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            // Do not keep the password characters in the key spec after derivation.
            spec.clearPassword()
        }
    }

    /** Hashes [password] with a fresh salt. */
    fun hash(password: CharArray): PasswordHash {
        val salt = newSalt()
        return PasswordHash(
            algorithm = ALGORITHM,
            iterations = iterations,
            salt = salt,
            hash = derive(password, salt, iterations),
        )
    }

    /**
     * Constant-time verification against a stored hash. Unknown algorithms are rejected
     * instead of being silently treated as a match or a crash.
     */
    fun verify(password: CharArray, stored: PasswordHash): Boolean {
        if (stored.algorithm != ALGORITHM) return false
        val candidate = derive(password, stored.salt, stored.iterations)
        return MessageDigest.isEqual(candidate, stored.hash)
    }

    companion object {
        const val ALGORITHM: String = "PBKDF2WithHmacSHA256"
        const val DEFAULT_ITERATIONS: Int = 600_000
        const val SALT_LENGTH_BYTES: Int = 16
        const val DEFAULT_KEY_LENGTH_BITS: Int = 256
    }
}
