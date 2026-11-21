package dev.brainrotstopper.feature.onboarding

/** In-memory checker for unit tests. Unset kinds are DENIED, matching a fresh install. */
class FakePermissionChecker : PermissionChecker {

    private val statuses = mutableMapOf<PermissionKind, PermissionStatus>()

    operator fun set(kind: PermissionKind, status: PermissionStatus) {
        statuses[kind] = status
    }

    override fun status(kind: PermissionKind): PermissionStatus =
        statuses[kind] ?: PermissionStatus.DENIED
}
