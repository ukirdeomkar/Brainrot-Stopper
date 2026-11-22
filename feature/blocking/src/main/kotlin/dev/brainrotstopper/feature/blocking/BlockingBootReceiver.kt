package dev.brainrotstopper.feature.blocking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts blocking after a reboot or an app update.
 *
 * Android restarts enabled AccessibilityServices itself, but nothing re-creates our
 * foreground service, so the engine would otherwise wait for the user to open the app. The
 * receiver starts the service explicitly and asks the notification listener to rebind.
 *
 * `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are explicit foreground-start exemptions on
 * API 31–36; if a start is still denied, [BlockingForegroundService.start] posts a reopen
 * notification instead of failing silently.
 */
class BlockingBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> BlockingServiceStartReason.BOOT_COMPLETED
            Intent.ACTION_MY_PACKAGE_REPLACED -> BlockingServiceStartReason.PACKAGE_REPLACED
            ACTION_QUICKBOOT_POWERON -> BlockingServiceStartReason.BOOT_COMPLETED
            else -> return
        }

        Log.i(TAG, "Restarting blocking after ${intent.action}")
        BlockingForegroundService.start(context, reason)
        BrainrotNotificationListenerService.requestRebind(context)
        // The AccessibilityService is rebound by the system from the secure-settings grant;
        // its onServiceConnected re-asserts the service flags and refreshes the engine.
    }

    private companion object {
        const val TAG = "BrainrotBlocking"
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
