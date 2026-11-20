package dev.brainrotstopper.feature.onboarding

/** In-memory repository for unit tests. */
class FakeOnboardingRepository : OnboardingRepository {

    private var stored: OnboardingCompletion? = null

    override fun isCompleted(): Boolean = stored != null

    override fun complete(completion: OnboardingCompletion) {
        stored = completion
    }

    override fun completion(): OnboardingCompletion? = stored

    override fun reset() {
        stored = null
    }
}
