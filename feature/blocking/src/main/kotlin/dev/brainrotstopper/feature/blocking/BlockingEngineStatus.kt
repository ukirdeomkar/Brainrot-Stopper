package dev.brainrotstopper.feature.blocking

/**
 * Snapshot of the live blocking engine, read by the shell/settings UI and by QA.
 *
 * [isEngineRunning] is the "blocking engine running" self-check: the engine exists in this
 * process and has a loaded state snapshot. [foregroundServiceRunning] is reported separately
 * because a service restart or a stuck start is exactly the failure this check must expose.
 */
data class BlockingEngineStatus(
    val engineAttached: Boolean = false,
    val stateLoaded: Boolean = false,
    val lastRefreshAtEpochMillis: Long = 0L,
    val foregroundServiceRunning: Boolean = false,
    val lastStartReason: BlockingServiceStartReason? = null,
    val lastStartAtEpochMillis: Long = 0L,
) {

    val isEngineRunning: Boolean
        get() = engineAttached && stateLoaded
}

/** Why the foreground service last started. Surfaced in the self-check after boot/update. */
enum class BlockingServiceStartReason {
    BOOT_COMPLETED,
    PACKAGE_REPLACED,
    ACTIVITY,
    ACCESSIBILITY,
    MANUAL,
    UNKNOWN,
    ;

    companion object {
        fun fromExtra(value: String?): BlockingServiceStartReason =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}
