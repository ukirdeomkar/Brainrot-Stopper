package dev.brainrotstopper.core.security

import dev.brainrotstopper.core.model.PasswordMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Small work factor keeps the suite fast; [PasswordKdfTest] covers the shipped 600k default. */
class PasswordManagerTest {

    private val store = InMemoryCredentialStore()
    private val manager = PasswordManager(store, PasswordKdf(iterations = 1_000))

    @Test
    fun `setup stores a self-set credential`() {
        assertEquals(ConfigureResult.Saved, manager.configure("1357".toCharArray(), PasswordMode.SELF_SET))

        assertTrue(manager.isConfigured())
        assertEquals(PasswordMode.SELF_SET, manager.mode())
        assertTrue(manager.verify("1357".toCharArray()))
    }

    @Test
    fun `setup records friend mode`() {
        assertEquals(ConfigureResult.Saved, manager.configure("2468".toCharArray(), PasswordMode.FRIEND_SET))

        assertEquals(PasswordMode.FRIEND_SET, manager.mode())
        assertTrue(manager.verify("2468".toCharArray()))
    }

    @Test
    fun `wrong password is rejected`() {
        manager.configure("1357".toCharArray(), PasswordMode.SELF_SET)

        assertFalse(manager.verify("1358".toCharArray()))
    }

    @Test
    fun `verify is false before setup`() {
        assertFalse(manager.isConfigured())
        assertNull(manager.mode())
        assertFalse(manager.verify("1357".toCharArray()))
    }

    @Test
    fun `setup cannot overwrite an existing credential`() {
        manager.configure("1357".toCharArray(), PasswordMode.SELF_SET)

        assertEquals(
            ConfigureResult.AlreadyConfigured,
            manager.configure("9999".toCharArray(), PasswordMode.FRIEND_SET),
        )
        assertTrue(manager.verify("1357".toCharArray()))
        assertEquals(PasswordMode.SELF_SET, manager.mode())
        assertEquals(1, store.writes)
    }

    @Test
    fun `a too-short password is rejected and nothing is stored`() {
        assertEquals(
            ConfigureResult.Invalid(PasswordValidationProblem.TooShort),
            manager.configure("123".toCharArray(), PasswordMode.SELF_SET),
        )

        assertFalse(manager.isConfigured())
        assertEquals(0, store.writes)
    }

    @Test
    fun `changing the password needs the old one`() {
        manager.configure("1357".toCharArray(), PasswordMode.SELF_SET)

        assertEquals(
            ChangeCredentialResult.WrongOldPassword,
            manager.changePassword("0000".toCharArray(), "2468".toCharArray()),
        )

        // The failed attempt must not change anything.
        assertTrue(manager.verify("1357".toCharArray()))
        assertFalse(manager.verify("2468".toCharArray()))
    }

    @Test
    fun `a correct old password changes the credential and keeps the mode`() {
        manager.configure("1357".toCharArray(), PasswordMode.FRIEND_SET)

        assertEquals(
            ChangeCredentialResult.Changed,
            manager.changePassword("1357".toCharArray(), "2468".toCharArray()),
        )

        assertFalse(manager.verify("1357".toCharArray()))
        assertTrue(manager.verify("2468".toCharArray()))
        assertEquals(PasswordMode.FRIEND_SET, manager.mode())
    }

    @Test
    fun `change is rejected when no credential exists`() {
        assertEquals(
            ChangeCredentialResult.NotConfigured,
            manager.changePassword("1357".toCharArray(), "2468".toCharArray()),
        )

        assertFalse(manager.isConfigured())
    }

    @Test
    fun `change rejects a too-short new password after verifying the old one`() {
        manager.configure("1357".toCharArray(), PasswordMode.SELF_SET)

        assertEquals(
            ChangeCredentialResult.Invalid(PasswordValidationProblem.TooShort),
            manager.changePassword("1357".toCharArray(), "12".toCharArray()),
        )

        assertTrue(manager.verify("1357".toCharArray()))
    }
}
