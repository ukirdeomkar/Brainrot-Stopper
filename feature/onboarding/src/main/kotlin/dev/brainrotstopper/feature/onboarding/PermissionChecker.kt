package dev.brainrotstopper.feature.onboarding

/** Reads the live grant state of each permission. Implemented per-Android in production. */
interface PermissionChecker {
    fun status(kind: PermissionKind): PermissionStatus
}
