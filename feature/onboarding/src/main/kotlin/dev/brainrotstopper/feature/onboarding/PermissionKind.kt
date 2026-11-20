package dev.brainrotstopper.feature.onboarding

/**
 * Every permission the first-run flow can ask for. The order here is the order the user sees,
 * from the permission the app cannot work without to the ones that only enable optional
 * features or improve reliability.
 */
enum class PermissionKind {
    ACCESSIBILITY,
    USAGE_ACCESS,
    NOTIFICATION_LISTENER,
    POST_NOTIFICATIONS,
    BATTERY_OPTIMIZATION,
}
