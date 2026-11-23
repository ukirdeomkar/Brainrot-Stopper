package dev.brainrotstopper.feature.blocking

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hosts short-form detection: notification access is the supported route to active
 * `MediaSession`s and their clip duration.
 *
 * The service itself keeps no state beyond the live [MediaSessionSignalSource]. On connect it
 * installs the listener, which feeds [ShortFormDetectionCoordinator]; on disconnect it detaches
 * so a revoked grant cannot keep observing. Whole-app blocking is independent of this service
 * and keeps working when the grant is absent.
 *
 * Nothing here reads notification content: only session package, metadata duration, playback
 * state, and clip identity are used, and the diagnostics ring buffer stays on the device.
 */
class BrainrotNotificationListenerService : NotificationListenerService() {

    private lateinit var scope: CoroutineScope
    private var signalSource: MediaSessionSignalSource? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected; starting short-form detection")
        ShortFormDiagnostics.onListenerConnected()

        val context = applicationContext
        // Make sure a rule snapshot exists even when this service is the first component the
        // system starts (for example after a package update rebind).
        val engine = BlockingEngineProvider.get(context)
        scope.launch {
            runCatching { engine.refresh() }
                .onFailure { Log.w(TAG, "Could not refresh rules for short-form detection", it) }
        }

        val settings = ShortFormSettingsStore(context)
        val coordinator = ShortFormDetectionCoordinator(
            isGloballyEnabled = { settings.enabled },
            ruleFor = { packageName -> BlockingRuntime.ruleFor(packageName) },
            isForeground = { packageName -> BlockingRuntime.isForeground(packageName) },
            isUnlocked = { packageName -> BlockingRuntime.isUnlocked(packageName) },
            onBlock = { packageName -> BlockingRuntime.requestShortFormBlock(packageName) },
        )
        signalSource = MediaSessionSignalSource(context, coordinator).also { it.start() }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected; stopping short-form detection")
        ShortFormDiagnostics.onListenerDisconnected()
        signalSource?.stop()
        signalSource = null
        // A package update or an OEM restart can drop the binding; ask for it back.
        runCatching { requestRebind(component(applicationContext)) }
            .onFailure { Log.w(TAG, "Could not request a notification-listener rebind", it) }
    }

    override fun onDestroy() {
        signalSource?.stop()
        signalSource = null
        ShortFormDiagnostics.onListenerDisconnected()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BrainrotShortForm"

        fun component(context: Context): ComponentName =
            ComponentName(context, BrainrotNotificationListenerService::class.java)

        /** Asks Android to rebind the listener after boot or a package replacement. */
        fun requestRebind(context: Context) {
            runCatching { NotificationListenerService.requestRebind(component(context)) }
                .onFailure { Log.w(TAG, "Could not request a notification-listener rebind", it) }
        }
    }
}
