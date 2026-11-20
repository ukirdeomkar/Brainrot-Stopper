package dev.brainrotstopper.feature.onboarding

import android.content.Context

/** SharedPreferences-backed [OnboardingRepository]. Stored in app-private storage. */
class SharedPreferencesOnboardingRepository(context: Context) : OnboardingRepository {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun isCompleted(): Boolean = prefs.getBoolean(KEY_COMPLETED, false)

    override fun complete(completion: OnboardingCompletion) {
        prefs.edit()
            .putBoolean(KEY_COMPLETED, true)
            .putLong(KEY_COMPLETED_AT, completion.completedAtEpochMillis)
            .putBoolean(KEY_BLOCKING_ENABLED, completion.blockingEnabled)
            .putBoolean(KEY_TIMERS_ENABLED, completion.timersEnabled)
            .putBoolean(KEY_SHORT_FORM_ENABLED, completion.shortFormDetectionEnabled)
            .putBoolean(KEY_STATUS_NOTIFICATIONS_VISIBLE, completion.statusNotificationsVisible)
            .putBoolean(KEY_BATTERY_EXEMPT, completion.batteryExempt)
            .apply()
    }

    override fun completion(): OnboardingCompletion? {
        if (!isCompleted()) return null
        return OnboardingCompletion(
            completedAtEpochMillis = prefs.getLong(KEY_COMPLETED_AT, 0L),
            blockingEnabled = prefs.getBoolean(KEY_BLOCKING_ENABLED, false),
            timersEnabled = prefs.getBoolean(KEY_TIMERS_ENABLED, false),
            shortFormDetectionEnabled = prefs.getBoolean(KEY_SHORT_FORM_ENABLED, false),
            statusNotificationsVisible = prefs.getBoolean(KEY_STATUS_NOTIFICATIONS_VISIBLE, true),
            batteryExempt = prefs.getBoolean(KEY_BATTERY_EXEMPT, false),
        )
    }

    override fun reset() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "brainrot_onboarding"
        const val KEY_COMPLETED = "completed"
        const val KEY_COMPLETED_AT = "completed_at_epoch_millis"
        const val KEY_BLOCKING_ENABLED = "blocking_enabled"
        const val KEY_TIMERS_ENABLED = "timers_enabled"
        const val KEY_SHORT_FORM_ENABLED = "short_form_detection_enabled"
        const val KEY_STATUS_NOTIFICATIONS_VISIBLE = "status_notifications_visible"
        const val KEY_BATTERY_EXEMPT = "battery_exempt"
    }
}
