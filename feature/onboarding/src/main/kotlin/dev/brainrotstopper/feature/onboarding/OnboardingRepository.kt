package dev.brainrotstopper.feature.onboarding

/** Persists whether first-run onboarding finished, so the app does not ask again. */
interface OnboardingRepository {

    fun isCompleted(): Boolean

    fun complete(completion: OnboardingCompletion)

    /** The last completion record, or null when onboarding never finished. */
    fun completion(): OnboardingCompletion?

    /** Clears the record. Used by the onboarding UI and tests to re-run the flow. */
    fun reset()
}
