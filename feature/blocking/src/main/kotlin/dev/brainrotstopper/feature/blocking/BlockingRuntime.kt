package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.feature.timers.UsageAccessStatus
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide handle to the live blocking engine.
 *
 * The engine is owned by the process, not by an Activity. In production the foreground
 * service keeps the process alive and installs the engine; the AccessibilityService reuses the
 * same cache so an unlock grant is visible to both. The AccessibilityService still falls back
 * to creating the engine when it is started on its own (tests, or a service start the system
 * denied), so a foreground-service failure degrades instead of breaking blocking entirely.
 *
 * [status] is the self-check the UI reads ("blocking engine running"). [requestUnlock] is the
 * verification hook the overlay button and tests share; the flow goes through the real
 * password gate.
 */
object BlockingRuntime {

    private val unlockListeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile
    private var engine: BlockingEngine? = null

    /** Fails closed until the AccessibilityService installs the credential-backed gate. */
    @Volatile
    internal var unlockGate: UnlockGate = DenyAllUnlockGate

    @Volatile
    private var sessionUnlockMillisOverride: Long? = null

    @Volatile
    private var foregroundServiceRunning: Boolean = false

    @Volatile
    private var accessibilityServiceAttached: Boolean = false

    @Volatile
    private var lastStartReason: BlockingServiceStartReason? = null

    @Volatile
    private var lastStartAtEpochMillis: Long = 0L

    internal fun attach(engine: BlockingEngine) {
        this.engine = engine
    }

    internal fun onAccessibilityServiceStarted() {
        accessibilityServiceAttached = true
    }

    internal fun onAccessibilityServiceStopped() {
        accessibilityServiceAttached = false
        detachIfNoOwner()
    }

    /** True while a blocking engine is installed in this process. */
    fun isAttached(): Boolean = engine != null

    /** True while the AccessibilityService is bound (the component that draws the overlay). */
    fun isAccessibilityServiceAttached(): Boolean = accessibilityServiceAttached

    /** The self-check the UI reads. Safe to call before the engine exists. */
    fun status(): BlockingEngineStatus {
        val attached = engine
        val snapshot = attached?.snapshot()
        val usage = attached?.usageSnapshot()
        return BlockingEngineStatus(
            engineAttached = attached != null,
            stateLoaded = snapshot?.loaded == true,
            lastRefreshAtEpochMillis = snapshot?.loadedAtEpochMillis ?: 0L,
            foregroundServiceRunning = foregroundServiceRunning,
            lastStartReason = lastStartReason,
            lastStartAtEpochMillis = lastStartAtEpochMillis,
            usageAccessStatus = usage?.accessStatus ?: UsageAccessStatus.UNDETERMINED,
            usageLastPollAtEpochMillis = usage?.lastPollAtEpochMillis ?: 0L,
            usageLastPollFailed = usage?.lastPollFailed ?: false,
            usageTrackedPackages = usage?.trackedPackages ?: 0,
            timersAvailable = usage?.timersAvailable ?: true,
        )
    }

    /** The current cached verdict, or null when no engine is attached. */
    fun decisionFor(packageName: String): Decision? = engine?.decisionFor(packageName)

    /** True when a cached unlock grant covers [packageName] at [nowEpochMillis]. */
    fun isUnlocked(
        packageName: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean = engine?.isUnlocked(packageName, nowEpochMillis) ?: false

    /** Earliest active grant expiry for [packageName], or null when it is not unlocked. */
    fun activeGrantExpiry(
        packageName: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Long? = engine?.activeGrantExpiry(packageName, nowEpochMillis)

    /**
     * Runs the unlock flow for [packageName] and suspends until it completes.
     *
     * Returns [UnlockResult.Success] only after the grant is persisted and visible through
     * [isUnlocked]. Returns [UnlockResult.NotAttached] when no engine is attached.
     */
    suspend fun requestUnlock(packageName: String, password: CharArray? = null): UnlockResult =
        engine?.unlock(packageName, password) ?: UnlockResult.NotAttached

    /** Installs the gate the unlock flow consults. */
    fun installUnlockGate(gate: UnlockGate) {
        unlockGate = gate
    }

    /** Grant length used by the unlock flow. */
    fun sessionUnlockMillis(): Long =
        sessionUnlockMillisOverride ?: UnlockFlow.SESSION_UNLOCK_MILLIS

    /** Test seam: shorten the emergency grant so auto re-arm can be observed on an emulator. */
    fun setSessionUnlockMillisForTests(millis: Long?) {
        sessionUnlockMillisOverride = millis
    }

    /** Registers a callback for a completed unlock; returns a remover. */
    internal fun addUnlockListener(listener: (String) -> Unit): () -> Unit {
        unlockListeners.add(listener)
        return { unlockListeners.remove(listener) }
    }

    internal fun notifyUnlocked(packageName: String) {
        for (listener in unlockListeners) {
            runCatching { listener(packageName) }
        }
    }

    internal fun onForegroundServiceStarted(reason: BlockingServiceStartReason) {
        foregroundServiceRunning = true
        lastStartReason = reason
        lastStartAtEpochMillis = System.currentTimeMillis()
    }

    internal fun onForegroundServiceStopped() {
        foregroundServiceRunning = false
        detachIfNoOwner()
    }

    /**
     * Drops the engine only when neither an AccessibilityService nor the foreground service
     * still owns it, so the self-check cannot claim "running" after both are gone.
     */
    private fun detachIfNoOwner() {
        if (!foregroundServiceRunning && !accessibilityServiceAttached) {
            engine = null
        }
    }
}
