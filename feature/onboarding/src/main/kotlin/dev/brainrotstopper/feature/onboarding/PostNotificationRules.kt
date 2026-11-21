package dev.brainrotstopper.feature.onboarding

/**
 * Pure rule for the POST_NOTIFICATIONS runtime permission. Android 13 (API 33) introduced the
 * runtime prompt; Android 12 and below grant it at install time, so it is not applicable there.
 */
object PostNotificationRules {

    const val FIRST_RUNTIME_SDK: Int = 33

    fun statusFor(sdkInt: Int, permissionGranted: Boolean): PermissionStatus =
        when {
            sdkInt < FIRST_RUNTIME_SDK -> PermissionStatus.NOT_APPLICABLE
            permissionGranted -> PermissionStatus.GRANTED
            else -> PermissionStatus.DENIED
        }
}
