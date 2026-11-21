package dev.brainrotstopper.feature.blocking

/**
 * Process-wide handle to the live blocking engine.
 *
 * The AccessibilityService attaches its [BlockingStateCache] when it is created. Other
 * components (and instrumentation tests) use this to read the current verdict without
 * reaching into the service instance.
 *
 * [requestUnlock] is the small verification hook the overlay button and tests share. It asks
 * the attached service to run the unlock flow; the flow still goes through the password gate,
 * which M7 replaces with the real password check.
 */
object BlockingRuntime {

    @Volatile
    private var cache: BlockingStateCache? = null

    @Volatile
    private var unlockHandler: ((String) -> Boolean)? = null

    internal fun attach(cache: BlockingStateCache) {
        this.cache = cache
    }

    internal fun detach() {
        cache = null
        unlockHandler = null
    }

    internal fun setUnlockHandler(handler: ((String) -> Boolean)?) {
        unlockHandler = handler
    }

    /** True while an AccessibilityService instance owns the engine. */
    fun isAttached(): Boolean = cache != null

    /** The current cached verdict, or null when no service is attached. */
    fun decisionFor(packageName: String): Decision? = cache?.decisionFor(packageName)

    /** True when a cached unlock grant covers [packageName] at [nowEpochMillis]. */
    fun isUnlocked(
        packageName: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean = cache?.isUnlocked(packageName, nowEpochMillis) ?: false

    /**
     * Starts the unlock flow for [packageName]. Returns false when no service is attached or
     * the request was not accepted.
     */
    fun requestUnlock(packageName: String): Boolean = unlockHandler?.invoke(packageName) ?: false
}
