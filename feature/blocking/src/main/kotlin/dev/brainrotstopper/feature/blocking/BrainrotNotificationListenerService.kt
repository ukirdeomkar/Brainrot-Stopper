package dev.brainrotstopper.feature.blocking

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/**
 * Scaffold shell for the M10 short-form detector.
 *
 * M1 (onboarding) needs the listener to exist so the "Notification access" grant can be detected
 * and deep-linked on a real device or emulator. The MediaSession/duration logic and the local
 * diagnostics ring buffer belong to M10; this class intentionally does nothing yet.
 */
class BrainrotNotificationListenerService : NotificationListenerService() {

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, BrainrotNotificationListenerService::class.java)
    }
}
