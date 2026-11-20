package dev.brainrotstopper.feature.onboarding

/**
 * Coordinates the live permission state and the persisted "ready" record. Pure Kotlin apart
 * from the injected implementations, so the state machine is unit-tested without a device.
 */
class OnboardingCoordinator(
    private val checker: PermissionChecker,
    private val repository: OnboardingRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun snapshot(): PermissionSnapshot =
        PermissionSnapshot(PermissionKind.values().associateWith { kind -> checker.status(kind) })

    fun isCompleted(): Boolean = repository.isCompleted()

    fun completion(): OnboardingCompletion? = repository.completion()

    /**
     * Persists the ready state even when permissions are denied. A denied permission is a
     * degraded mode, not a dead end: onboarding completes and the app names what is disabled.
     */
    fun complete(): OnboardingCompletion {
        val completion = OnboardingCompletion.from(snapshot(), clock())
        repository.complete(completion)
        return completion
    }
}
