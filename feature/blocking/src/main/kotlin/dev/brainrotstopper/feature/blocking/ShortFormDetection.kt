package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.shortform.MediaObservation
import dev.brainrotstopper.core.model.shortform.ShortFormDetectionPolicy
import dev.brainrotstopper.core.model.shortform.ShortFormTracker
import dev.brainrotstopper.core.model.shortform.Verdict

/**
 * One local, on-device record of what short-form detection saw. Never uploaded.
 *
 * [durationMs] is the usable duration (null when the session reported an unknown/infinite or
 * absent duration), so the diagnostics row can show "duration present" versus "duration absent"
 * without re-deriving it from the raw metadata.
 */
data class ShortFormDiagnosticEntry(
    val atEpochMillis: Long,
    val packageName: String,
    val durationMs: Long?,
    val verdict: Verdict,
) {
    val hasDuration: Boolean get() = durationMs != null
}

/**
 * Process-wide, bounded ring buffer for short-form detection diagnostics.
 *
 * The notification-listener service and the Settings screen share one process, so a plain
 * singleton is enough. It only keeps the newest [CAPACITY] entries, which is what the field
 * behaviour needs; there is no export path and nothing leaves the device.
 */
object ShortFormDiagnostics {

    const val CAPACITY: Int = 50

    private val lock = Any()
    private val entries = ArrayDeque<ShortFormDiagnosticEntry>(CAPACITY)

    /** True while Android has bound our notification listener (i.e. notification access). */
    @Volatile
    var listenerConnected: Boolean = false

    /** Wall-clock time of the newest observation, or null when none has been seen. */
    @Volatile
    var lastObservationAtEpochMillis: Long? = null
        private set

    @Volatile
    private var lastObserverConnectedAtEpochMillis: Long = 0L

    fun record(entry: ShortFormDiagnosticEntry) {
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > CAPACITY) entries.removeFirst()
        }
        lastObservationAtEpochMillis = entry.atEpochMillis
    }

    fun snapshot(): List<ShortFormDiagnosticEntry> = synchronized(lock) { entries.toList() }

    fun onListenerConnected() {
        listenerConnected = true
        lastObserverConnectedAtEpochMillis = System.currentTimeMillis()
    }

    fun onListenerDisconnected() {
        listenerConnected = false
    }

    fun lastConnectedAtEpochMillis(): Long = lastObserverConnectedAtEpochMillis

    /** Test seam: clears the buffer and connection state. */
    fun resetForTests() {
        synchronized(lock) { entries.clear() }
        listenerConnected = false
        lastObservationAtEpochMillis = null
        lastObserverConnectedAtEpochMillis = 0L
    }
}

/**
 * Turns [MediaObservation]s into short-form block decisions.
 *
 * Pure JVM (no Android imports) so the gating and the block trigger are unit-tested. The
 * Android adapter ([MediaSessionSignalSource]) only reads a `MediaController` and calls
 * [onObservation]; the [ShortFormTracker] owns the thresholds and hysteresis.
 *
 * A block is requested only when all of these hold:
 * 1. the global kill switch and the per-app rule allow detection,
 * 2. the tracker returns [Verdict.CONFIRMED],
 * 3. the target package is the foreground app, and
 * 4. the package is not currently unlocked through the password gate.
 *
 * Every observation is recorded in [ShortFormDiagnostics], whatever the verdict.
 */
class ShortFormDetectionCoordinator(
    private val isGloballyEnabled: () -> Boolean,
    private val ruleFor: (String) -> AppRule?,
    private val isForeground: (String) -> Boolean,
    private val isUnlocked: (String) -> Boolean,
    private val onBlock: (String) -> Unit,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val tracker: ShortFormTracker = ShortFormTracker(),
    private val diagnostics: ShortFormDiagnostics = ShortFormDiagnostics,
) {

    fun onObservation(observation: MediaObservation): Verdict {
        val enabled = ShortFormDetectionPolicy.shouldDetect(
            globalEnabled = isGloballyEnabled(),
            rule = ruleFor(observation.packageName),
        )

        val verdict = if (enabled) {
            tracker.onObservation(observation).verdict
        } else {
            // A disabled app must not keep stale evidence that would fire the moment it is
            // re-enabled. Forgetting the package is cheap and predictable.
            tracker.reset(observation.packageName)
            Verdict.UNKNOWN
        }

        diagnostics.record(
            ShortFormDiagnosticEntry(
                atEpochMillis = nowEpochMillis(),
                packageName = observation.packageName,
                durationMs = observation.durationMs,
                verdict = verdict,
            ),
        )

        if (verdict == Verdict.CONFIRMED &&
            isForeground(observation.packageName) &&
            !isUnlocked(observation.packageName)
        ) {
            onBlock(observation.packageName)
        }
        return verdict
    }

    fun resetAll() = tracker.resetAll()
}
