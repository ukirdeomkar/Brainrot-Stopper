package dev.brainrotstopper.core.security

import dev.brainrotstopper.core.model.PasswordMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordSetupModelTest {

    private val store = InMemoryCredentialStore()
    private val manager = PasswordManager(store, PasswordKdf(iterations = 1_000))

    @Test
    fun `self mode masks input and shows no handover`() {
        val screen = PasswordSetupModel(PasswordMode.SELF_SET).screen()

        assertTrue(screen.masksInput)
        assertFalse(screen.requiresHandover)
        assertFalse(screen.ownerMustNotSeePassword)
    }

    @Test
    fun `friend mode masks input and requires a handover`() {
        val screen = PasswordSetupModel(PasswordMode.FRIEND_SET).screen()

        assertTrue(screen.masksInput)
        assertTrue(screen.requiresHandover)
        assertTrue(screen.ownerMustNotSeePassword)
    }

    @Test
    fun `a mismatched confirmation is rejected before hashing`() {
        val outcome = PasswordSetupModel(PasswordMode.SELF_SET)
            .submit("1357".toCharArray(), "1358".toCharArray(), manager)

        assertEquals(SetupOutcome.Mismatch, outcome)
        assertFalse(manager.isConfigured())
        assertEquals(0, store.writes)
    }

    @Test
    fun `a too-short password is rejected`() {
        val outcome = PasswordSetupModel(PasswordMode.SELF_SET)
            .submit("123".toCharArray(), "123".toCharArray(), manager)

        assertEquals(SetupOutcome.Invalid(PasswordValidationProblem.TooShort), outcome)
        assertFalse(manager.isConfigured())
    }

    @Test
    fun `friend setup stores friend mode`() {
        val outcome = PasswordSetupModel(PasswordMode.FRIEND_SET)
            .submit("2468".toCharArray(), "2468".toCharArray(), manager)

        assertEquals(SetupOutcome.Saved, outcome)
        assertEquals(PasswordMode.FRIEND_SET, manager.mode())
        assertTrue(manager.verify("2468".toCharArray()))
    }

    @Test
    fun `setup is refused when a credential already exists`() {
        manager.configure("1357".toCharArray(), PasswordMode.SELF_SET)

        val outcome = PasswordSetupModel(PasswordMode.SELF_SET)
            .submit("2468".toCharArray(), "2468".toCharArray(), manager)

        assertEquals(SetupOutcome.AlreadyConfigured, outcome)
        assertTrue(manager.verify("1357".toCharArray()))
    }

    @Test
    fun `change rejects a mismatched confirmation`() {
        manager.configure("1357".toCharArray(), PasswordMode.SELF_SET)

        val outcome = PasswordSetupModel(PasswordMode.SELF_SET)
            .submitChange("1357".toCharArray(), "2468".toCharArray(), "2469".toCharArray(), manager)

        assertEquals(ChangeOutcome.Mismatch, outcome)
        assertTrue(manager.verify("1357".toCharArray()))
    }

    @Test
    fun `change rejects a wrong old password`() {
        manager.configure("1357".toCharArray(), PasswordMode.SELF_SET)

        val outcome = PasswordSetupModel(PasswordMode.SELF_SET)
            .submitChange("0000".toCharArray(), "2468".toCharArray(), "2468".toCharArray(), manager)

        assertEquals(ChangeOutcome.WrongOldPassword, outcome)
        assertTrue(manager.verify("1357".toCharArray()))
    }

    @Test
    fun `change with the correct old password succeeds`() {
        manager.configure("1357".toCharArray(), PasswordMode.SELF_SET)

        val outcome = PasswordSetupModel(PasswordMode.SELF_SET)
            .submitChange("1357".toCharArray(), "2468".toCharArray(), "2468".toCharArray(), manager)

        assertEquals(ChangeOutcome.Changed, outcome)
        assertTrue(manager.verify("2468".toCharArray()))
    }
}
