package dev.brainrotstopper.feature.onboarding

/** The Settings screen a step should open. Pure data so the mapping is unit-testable. */
enum class DeepLinkType {
    ACCESSIBILITY_SETTINGS,
    USAGE_ACCESS_SETTINGS,
    NOTIFICATION_LISTENER_SETTINGS,
    NOTIFICATION_LISTENER_DETAIL,
    BATTERY_REQUEST_EXEMPTION,
    BATTERY_SETTINGS,
}

data class DeepLink(
    val type: DeepLinkType,
    val packageName: String? = null,
    val listenerComponent: String? = null,
)

object OnboardingDeepLinks {

    /**
     * Returns the Settings target for a step, or null when the grant comes from a runtime
     * dialog instead of a Settings screen.
     */
    fun linkFor(
        kind: PermissionKind,
        packageName: String,
        listenerComponent: String,
    ): DeepLink? = when (kind) {
        PermissionKind.ACCESSIBILITY -> DeepLink(DeepLinkType.ACCESSIBILITY_SETTINGS)
        PermissionKind.USAGE_ACCESS -> DeepLink(DeepLinkType.USAGE_ACCESS_SETTINGS)
        PermissionKind.NOTIFICATION_LISTENER -> DeepLink(
            type = DeepLinkType.NOTIFICATION_LISTENER_DETAIL,
            listenerComponent = listenerComponent,
        )
        PermissionKind.BATTERY_OPTIMIZATION -> DeepLink(
            type = DeepLinkType.BATTERY_REQUEST_EXEMPTION,
            packageName = packageName,
        )
        PermissionKind.POST_NOTIFICATIONS -> null
    }
}
