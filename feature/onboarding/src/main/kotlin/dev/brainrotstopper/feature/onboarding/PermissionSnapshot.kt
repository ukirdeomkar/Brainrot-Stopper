package dev.brainrotstopper.feature.onboarding

/**
 * A point-in-time view of every permission. This is pure domain state so the degraded-mode
 * rules can be unit-tested without an Android device.
 */
data class PermissionSnapshot(
    val statuses: Map<PermissionKind, PermissionStatus>,
) {

    fun status(kind: PermissionKind): PermissionStatus =
        statuses[kind] ?: PermissionStatus.DENIED

    /** Whole-app blocking needs Accessibility. Without it the app has no control. */
    val isBlockingEnabled: Boolean
        get() = status(PermissionKind.ACCESSIBILITY) == PermissionStatus.GRANTED

    /** Daily timers need Usage Access. Blocking still works without it. */
    val areTimersEnabled: Boolean
        get() = status(PermissionKind.USAGE_ACCESS) == PermissionStatus.GRANTED

    /** Best-effort short-form detection needs Notification access. */
    val isShortFormDetectionEnabled: Boolean
        get() = status(PermissionKind.NOTIFICATION_LISTENER) == PermissionStatus.GRANTED

    /**
     * The foreground-service status notification is hidden only when Android 13+ denied
     * [PermissionKind.POST_NOTIFICATIONS]. On Android 12 the permission is not applicable.
     */
    val isStatusNotificationVisible: Boolean
        get() = status(PermissionKind.POST_NOTIFICATIONS) != PermissionStatus.DENIED

    val isBatteryExempt: Boolean
        get() = status(PermissionKind.BATTERY_OPTIMIZATION) == PermissionStatus.GRANTED

    /** Permissions whose denial disables a named feature. */
    fun disabledFeatures(): List<PermissionKind> {
        val disabled = mutableListOf<PermissionKind>()
        if (!isBlockingEnabled) disabled += PermissionKind.ACCESSIBILITY
        if (!areTimersEnabled) disabled += PermissionKind.USAGE_ACCESS
        if (!isShortFormDetectionEnabled) disabled += PermissionKind.NOTIFICATION_LISTENER
        if (status(PermissionKind.POST_NOTIFICATIONS) == PermissionStatus.DENIED) {
            disabled += PermissionKind.POST_NOTIFICATIONS
        }
        return disabled
    }

    /** Optional grants that do not disable a feature but reduce reliability. */
    fun recommendations(): List<PermissionKind> =
        if (isBatteryExempt) emptyList() else listOf(PermissionKind.BATTERY_OPTIMIZATION)

    /** True when every feature is fully available and no recommendation is outstanding. */
    val isFullyGranted: Boolean
        get() = disabledFeatures().isEmpty() && recommendations().isEmpty()

    /** True when at least one feature is disabled by a denied permission. */
    val isDegraded: Boolean
        get() = disabledFeatures().isNotEmpty()
}
