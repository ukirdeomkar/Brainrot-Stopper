package dev.brainrotstopper.feature.onboarding

/** How the user grants a permission. */
enum class PermissionRequestKind {
    /** Open the matching Android Settings screen, then re-check on return. */
    SETTINGS,

    /** Show the Android runtime permission dialog. */
    RUNTIME_PERMISSION,
}

/**
 * Everything the UI needs to explain one permission. Text lives in string resources; this
 * object only binds the strings to the [PermissionKind] state machine.
 */
data class PermissionStep(
    val kind: PermissionKind,
    val titleRes: Int,
    val explanationRes: Int,
    val deniedImpactRes: Int,
    val actionRes: Int,
    val requestKind: PermissionRequestKind,
)

object PermissionStepCatalog {

    val steps: List<PermissionStep> = listOf(
        PermissionStep(
            kind = PermissionKind.ACCESSIBILITY,
            titleRes = R.string.step_accessibility_title,
            explanationRes = R.string.step_accessibility_explanation,
            deniedImpactRes = R.string.step_accessibility_impact,
            actionRes = R.string.step_accessibility_action,
            requestKind = PermissionRequestKind.SETTINGS,
        ),
        PermissionStep(
            kind = PermissionKind.USAGE_ACCESS,
            titleRes = R.string.step_usage_title,
            explanationRes = R.string.step_usage_explanation,
            deniedImpactRes = R.string.step_usage_impact,
            actionRes = R.string.step_usage_action,
            requestKind = PermissionRequestKind.SETTINGS,
        ),
        PermissionStep(
            kind = PermissionKind.NOTIFICATION_LISTENER,
            titleRes = R.string.step_notification_access_title,
            explanationRes = R.string.step_notification_access_explanation,
            deniedImpactRes = R.string.step_notification_access_impact,
            actionRes = R.string.step_notification_access_action,
            requestKind = PermissionRequestKind.SETTINGS,
        ),
        PermissionStep(
            kind = PermissionKind.POST_NOTIFICATIONS,
            titleRes = R.string.step_post_notifications_title,
            explanationRes = R.string.step_post_notifications_explanation,
            deniedImpactRes = R.string.step_post_notifications_impact,
            actionRes = R.string.step_post_notifications_action,
            requestKind = PermissionRequestKind.RUNTIME_PERMISSION,
        ),
        PermissionStep(
            kind = PermissionKind.BATTERY_OPTIMIZATION,
            titleRes = R.string.step_battery_title,
            explanationRes = R.string.step_battery_explanation,
            deniedImpactRes = R.string.step_battery_impact,
            actionRes = R.string.step_battery_action,
            requestKind = PermissionRequestKind.SETTINGS,
        ),
    )

    init {
        val catalogKinds = steps.map { it.kind }.toSet()
        check(catalogKinds == PermissionKind.values().toSet()) {
            "Every PermissionKind must have exactly one onboarding step; missing: " +
                (PermissionKind.values().toSet() - catalogKinds) +
                ", extra: " + (catalogKinds - PermissionKind.values().toSet())
        }
        check(steps.size == catalogKinds.size) { "Duplicate PermissionKind in the step catalog" }
    }
}
