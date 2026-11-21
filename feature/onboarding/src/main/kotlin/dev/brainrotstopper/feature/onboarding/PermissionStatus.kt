package dev.brainrotstopper.feature.onboarding

/** The current grant state of one permission. */
enum class PermissionStatus {
    GRANTED,
    DENIED,

    /**
     * The permission does not exist on this Android version, so nothing is disabled. Only
     * [PermissionKind.POST_NOTIFICATIONS] uses this today (Android 12 and below grant it at
     * install time).
     */
    NOT_APPLICABLE,
}
