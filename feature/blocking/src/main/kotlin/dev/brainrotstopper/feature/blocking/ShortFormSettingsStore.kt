package dev.brainrotstopper.feature.blocking

import android.content.Context

/**
 * Global kill switch for short-form detection (M10).
 *
 * Default is on, matching the owner direction that detection is the primary control, but the
 * switch is a plain local preference: switching it off stops every verdict without touching the
 * per-app rules. Whole-app blocking and daily timers are unaffected either way.
 */
class ShortFormSettingsStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, true)
        set(value) {
            preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    companion object {
        const val PREFS_NAME: String = "short_form_detection"
        const val KEY_ENABLED: String = "enabled"
    }
}
