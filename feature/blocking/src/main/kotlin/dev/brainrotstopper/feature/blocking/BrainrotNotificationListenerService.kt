package dev.brainrotstopper.feature.blocking

import android.service.notification.NotificationListenerService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Scaffold shell for the M10 short-form detector.
 *
 * M1 (onboarding) needs the listener to exist so the "Notification access" grant can be detected
 * and deep-linked on a real device or emulator. The MediaSession/duration logic and the local
 * diagnostics ring buffer belong to M10; this class intentionally does nothing yet.
 */
class BrainrotNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // A package update or an OEM restart can drop the binding; ask for it back.
        Log.i(TAG, "Notification listener disconnected; requesting rebind")
        runCatching { requestRebind(component(applicationContext)) }
            .onFailure { Log.w(TAG, "Could not request a notification-listener rebind", it) }
    }

    companion object {
        private const val TAG = "BrainrotBlocking"

        fun component(context: Context): ComponentName =
            ComponentName(context, BrainrotNotificationListenerService::class.java)

        /** Asks Android to rebind the listener after boot or a package replacement. */
        fun requestRebind(context: Context) {
            runCatching { NotificationListenerService.requestRebind(component(context)) }
                .onFailure { Log.w(TAG, "Could not request a notification-listener rebind", it) }
        }
    }
}
