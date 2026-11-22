package dev.brainrotstopper.core.security

/** Why a candidate password was rejected before hashing. */
enum class PasswordValidationProblem {
    TooShort,
    TooLong,
}

/**
 * Length rules for the unblock credential. A short numeric PIN is allowed by design: the
 * product is a commitment device, not a jail, and the friend-set flow needs something a
 * second person can type in a few seconds. The upper bound stops a pathological paste from
 * making PBKDF2 sluggish.
 */
object PasswordPolicy {
    const val MIN_LENGTH: Int = 4
    const val MAX_LENGTH: Int = 128

    fun validate(password: CharArray): PasswordValidationProblem? = when {
        password.size < MIN_LENGTH -> PasswordValidationProblem.TooShort
        password.size > MAX_LENGTH -> PasswordValidationProblem.TooLong
        else -> null
    }
}
