package dev.brainrotstopper.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the PBKDF2 parameters and round-trip. Small iteration counts keep the suite
 * fast; one test proves the shipped 600k default still derives and verifies.
 */
class PasswordKdfTest {

    @Test
    fun `matches the PBKDF2-HMAC-SHA256 reference vector`() {
        // Widely published test vector: P="password", S="salt", c=1, dkLen=32.
        val derived = PasswordKdf(iterations = 1).derive(
            password = "password".toCharArray(),
            salt = "salt".toByteArray(Charsets.UTF_8),
            iterations = 1,
        )

        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            derived.toHex(),
        )
    }

    @Test
    fun `default work factor is the OWASP 600k floor`() {
        assertEquals("PBKDF2WithHmacSHA256", PasswordKdf.ALGORITHM)
        assertEquals(600_000, PasswordKdf.DEFAULT_ITERATIONS)
        assertEquals(16, PasswordKdf.SALT_LENGTH_BYTES)
        assertEquals(256, PasswordKdf.DEFAULT_KEY_LENGTH_BITS)
    }

    @Test
    fun `a fresh salt is 16 bytes and never repeats`() {
        val kdf = PasswordKdf(iterations = 1)
        val first = kdf.newSalt()
        val second = kdf.newSalt()

        assertEquals(16, first.size)
        assertEquals(16, second.size)
        assertNotEquals(first.toHex(), second.toHex())
    }

    @Test
    fun `hash stores a 16 byte salt and a 32 byte key`() {
        val hash = PasswordKdf(iterations = 1).hash("1357".toCharArray())

        assertEquals(PasswordKdf.ALGORITHM, hash.algorithm)
        assertEquals(1, hash.iterations)
        assertEquals(16, hash.salt.size)
        assertEquals(32, hash.hash.size)
    }

    @Test
    fun `a correct password verifies`() {
        val kdf = PasswordKdf(iterations = 1_000)
        val stored = kdf.hash("correct horse".toCharArray())

        assertTrue(kdf.verify("correct horse".toCharArray(), stored))
    }

    @Test
    fun `a wrong password is rejected`() {
        val kdf = PasswordKdf(iterations = 1_000)
        val stored = kdf.hash("2468".toCharArray())

        assertFalse(kdf.verify("2469".toCharArray(), stored))
        assertFalse(kdf.verify("24680".toCharArray(), stored))
        assertFalse(kdf.verify("".toCharArray(), stored))
    }

    @Test
    fun `the same password under a different salt is rejected`() {
        val kdf = PasswordKdf(iterations = 1_000)
        val stored = kdf.hash("same-password".toCharArray())
        val other = PasswordHash(
            algorithm = stored.algorithm,
            iterations = stored.iterations,
            salt = kdf.newSalt(),
            hash = stored.hash,
        )

        assertFalse(kdf.verify("same-password".toCharArray(), other))
    }

    @Test
    fun `an unknown algorithm is rejected rather than crashing`() {
        val kdf = PasswordKdf(iterations = 1_000)
        val stored = kdf.hash("1357".toCharArray())
        val other = PasswordHash(
            algorithm = "scrypt",
            iterations = stored.iterations,
            salt = stored.salt,
            hash = stored.hash,
        )

        assertFalse(kdf.verify("1357".toCharArray(), other))
    }

    @Test
    fun `the shipped 600k default derives and verifies`() {
        val kdf = PasswordKdf()
        val stored = kdf.hash("friend-set-4321".toCharArray())

        assertEquals(600_000, stored.iterations)
        assertTrue(kdf.verify("friend-set-4321".toCharArray(), stored))
        assertFalse(kdf.verify("friend-set-4322".toCharArray(), stored))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
