package dev.brainrotstopper.feature.onboarding

/** The persisted "onboarding is ready" record, including what was available at that moment. */
data class OnboardingCompletion(
    val completedAtEpochMillis: Long,
    val blockingEnabled: Boolean,
    val timersEnabled: Boolean,
    val shortFormDetectionEnabled: Boolean,
    val statusNotificationsVisible: Boolean,
    val batteryExempt: Boolean,
) {
    companion object {
        fun from(snapshot: PermissionSnapshot, completedAtEpochMillis: Long): OnboardingCompletion =
            OnboardingCompletion(
                completedAtEpochMillis = completedAtEpochMillis,
                blockingEnabled = snapshot.isBlockingEnabled,
                timersEnabled = snapshot.areTimersEnabled,
                shortFormDetectionEnabled = snapshot.isShortFormDetectionEnabled,
                statusNotificationsVisible = snapshot.isStatusNotificationVisible,
                batteryExempt = snapshot.isBatteryExempt,
            )
    }
}
