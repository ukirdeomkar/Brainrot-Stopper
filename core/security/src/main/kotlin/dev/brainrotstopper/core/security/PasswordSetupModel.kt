package dev.brainrotstopper.core.security

import dev.brainrotstopper.core.model.PasswordMode

/**
 * What the setup screen must show for a mode. The masking flag is true for both modes, but
 * friend mode additionally requires a hand-over step and must never echo the value back to
 * the owner, which is the M7 acceptance criterion "friend mode hides input from the owner".
 */
data class PasswordSetupScreen(
    val mode: PasswordMode,
    val requiresHandover: Boolean,
    val masksInput: Boolean,
    val ownerMustNotSeePassword: Boolean,
)

/** Result of the initial self-set / friend-set form. */
sealed interface SetupOutcome {
    data object Saved : SetupOutcome
    data object AlreadyConfigured : SetupOutcome
    data object Mismatch : SetupOutcome
    data class Invalid(val problem: PasswordValidationProblem) : SetupOutcome
}

/** Result of the change-password form; the old password is mandatory. */
sealed interface ChangeOutcome {
    data object Changed : ChangeOutcome
    data object WrongOldPassword : ChangeOutcome
    data object NotConfigured : ChangeOutcome
    data object Mismatch : ChangeOutcome
    data class Invalid(val problem: PasswordValidationProblem) : ChangeOutcome
}

/**
 * Screen logic for password setup and change, kept out of the Activity so the confirmation
 * and masking rules are exercised by JVM unit tests.
 */
class PasswordSetupModel(private val mode: PasswordMode) {

    fun screen(): PasswordSetupScreen = PasswordSetupScreen(
        mode = mode,
        requiresHandover = mode == PasswordMode.FRIEND_SET,
        masksInput = true,
        ownerMustNotSeePassword = mode == PasswordMode.FRIEND_SET,
    )

    fun submit(
        password: CharArray,
        confirmation: CharArray,
        manager: PasswordManager,
    ): SetupOutcome {
        if (manager.isConfigured()) return SetupOutcome.AlreadyConfigured
        if (!password.contentEquals(confirmation)) return SetupOutcome.Mismatch
        return when (val result = manager.configure(password, mode)) {
            ConfigureResult.Saved -> SetupOutcome.Saved
            ConfigureResult.AlreadyConfigured -> SetupOutcome.AlreadyConfigured
            is ConfigureResult.Invalid -> SetupOutcome.Invalid(result.problem)
        }
    }

    fun submitChange(
        oldPassword: CharArray,
        newPassword: CharArray,
        confirmation: CharArray,
        manager: PasswordManager,
    ): ChangeOutcome {
        // Check the confirmation locally first so a typo never pays the PBKDF2 cost.
        if (!newPassword.contentEquals(confirmation)) return ChangeOutcome.Mismatch
        return when (val result = manager.changePassword(oldPassword, newPassword)) {
            ChangeCredentialResult.Changed -> ChangeOutcome.Changed
            ChangeCredentialResult.WrongOldPassword -> ChangeOutcome.WrongOldPassword
            ChangeCredentialResult.NotConfigured -> ChangeOutcome.NotConfigured
            is ChangeCredentialResult.Invalid -> ChangeOutcome.Invalid(result.problem)
        }
    }
}
