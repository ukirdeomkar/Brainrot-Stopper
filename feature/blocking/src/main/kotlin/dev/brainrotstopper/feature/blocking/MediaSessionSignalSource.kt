package dev.brainrotstopper.feature.blocking

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.brainrotstopper.core.model.shortform.MediaObservation
import dev.brainrotstopper.core.model.shortform.ShortFormDetectionPolicy

/**
 * Android adapter that reads active `MediaSession`s and feeds [ShortFormDetectionCoordinator].
 *
 * The supported route for a normal app is a `NotificationListenerService`: the enabled listener
 * grant allows [MediaSessionManager.getActiveSessions], and `MediaController` exposes package,
 * duration, playback state, and clip metadata. There is no polling — the active-session listener
 * and per-controller callbacks are event-driven.
 *
 * Only a component that the user granted notification access may be bound to the listener
 * service, so this class assumes the grant exists when [start] is called. If the grant is
 * revoked, Android disconnects the service; [stop] detaches cleanly and the whole-app fallback
 * keeps working.
 */
class MediaSessionSignalSource(
    private val context: Context,
    private val coordinator: ShortFormDetectionCoordinator,
) {

    private val mediaSessionManager: MediaSessionManager =
        context.getSystemService(MediaSessionManager::class.java)

    private val component = BrainrotNotificationListenerService.component(context)

    private val callbacks = linkedMapOf<MediaController, MediaController.Callback>()

    private val activeSessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateControllers(controllers)
        }

    private var started = false

    fun start() {
        if (started) return
        started = true
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(activeSessionsListener, component)
            updateControllers(mediaSessionManager.getActiveSessions(component))
        } catch (denied: SecurityException) {
            // Access can be revoked between the bind and this call on some OEMs.
            Log.w(TAG, "Notification access is missing; short-form detection stays off", denied)
            stop()
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching {
            mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener)
        }.onFailure { Log.w(TAG, "Could not remove the active-session listener", it) }
        val current = callbacks.entries.toList()
        callbacks.clear()
        current.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
    }

    private fun updateControllers(controllers: List<MediaController>?) {
        val active = controllers?.toSet() ?: emptySet()

        val removed = callbacks.keys.filter { it !in active }
        removed.forEach(::detach)

        for (controller in active) {
            if (controller !in callbacks) attach(controller)
        }
    }

    private fun attach(controller: MediaController) {
        val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = observe(controller)

            override fun onPlaybackStateChanged(state: PlaybackState?) = observe(controller)

            override fun onSessionDestroyed() = detach(controller)
        }
        try {
            controller.registerCallback(callback, Handler(Looper.getMainLooper()))
            callbacks[controller] = callback
            observe(controller)
        } catch (denied: SecurityException) {
            Log.w(TAG, "Could not observe ${controller.packageName}", denied)
        }
    }

    private fun detach(controller: MediaController) {
        val callback = callbacks.remove(controller) ?: return
        runCatching { controller.unregisterCallback(callback) }
    }

    private fun observe(controller: MediaController) {
        val packageName = controller.packageName ?: return
        val rawDurationMs = controller.metadata
            ?.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ?: UNKNOWN_DURATION
        val observation = MediaObservation(
            packageName = packageName,
            durationMs = ShortFormDetectionPolicy.usableDurationMs(rawDurationMs),
            isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
            clipId = clipId(controller, rawDurationMs),
            timestampMs = SystemClock.elapsedRealtime(),
        )
        coordinator.onObservation(observation)
    }

    /**
     * A stable identity for the current clip. Prefer the media id; otherwise hash the human
     * fields that change when the user swipes to another clip. Null is allowed: the tracker
     * still accrues playback time but cannot count distinct clips.
     */
    private fun clipId(controller: MediaController, rawDurationMs: Long): String? {
        val description = controller.metadata?.description
        val mediaId = description?.mediaId
        if (!mediaId.isNullOrBlank()) return mediaId

        val title = description?.title?.toString()
        val artUri = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        if (title.isNullOrBlank() && artUri.isNullOrBlank() && rawDurationMs <= 0L) return null
        return "$title|$rawDurationMs|$artUri"
    }

    private companion object {
        const val TAG = "BrainrotShortForm"
        const val UNKNOWN_DURATION = -1L
    }
}
