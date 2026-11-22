package dev.brainrotstopper.feature.rules

import android.app.Activity
import dev.brainrotstopper.feature.settings.PasswordGate

/**
 * Authorization seam for edits that change rules, schedules, or timers.
 *
 * These are the settings that weaken blocking, so every mutating action asks for the unblock
 * password first. When no credential is configured yet the request is allowed (first-run
 * setup), otherwise [PasswordGate] verifies the entry before the caller writes anything. A
 * refusal — wrong password, cancelled prompt, or unreadable credential store — aborts the
 * write.
 */
object RuleChangeGate {

    fun authorize(activity: Activity, onResult: (Boolean) -> Unit) {
        PasswordGate.request(activity, onResult)
    }
}
