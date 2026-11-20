package dev.brainrotstopper.core.model.shortform

/**
 * Pure, Android-free decision logic for short-form detection by media playback duration.
 *
 * Input is a stream of [MediaObservation]s produced by an Android adapter
 * (see the M10 design in OMK-535). The adapter reads the active
 * `MediaSession` and maps it to this type. Keeping the logic here means the
 * thresholds and false-positive rules are unit-testable on the JVM.
 *
 * Design rules, in order of importance:
 * 1. An unknown duration (`durationMs == null`) never produces a short-form verdict.
 *    Absent metadata must not block anything.
 * 2. A single short clip is not enough on its own unless it plays for
 *    [ShortFormConfig.singleClipConfirmMs] (catches looped reels, avoids most ads).
 * 3. Normal short-form browsing is confirmed by [ShortFormConfig.minDistinctClips]
 *    distinct short clips plus [ShortFormConfig.minShortPlaybackMs] of playback.
 * 4. A long-form observation (duration above the threshold) clears the evidence,
 *    so a short ad inside a long video does not stay "confirmed" after the ad.
 */
class ShortFormTracker(
    private val config: ShortFormConfig = ShortFormConfig(),
) {

    private val states = mutableMapOf<String, PackageState>()

    fun onObservation(observation: MediaObservation): ShortFormSignal {
        val now = observation.timestampMs
        val state = states.getOrPut(observation.packageName) { PackageState(now) }

        if (now < state.cooldownUntilMs) {
            return signal(observation.packageName, Verdict.COOLDOWN, state, now)
        }

        val duration = observation.durationMs
        val gap = now - state.lastObservedAtMs
        if (gap > config.resetAfterMs) {
            state.resetEvidence(now)
        }

        if (!observation.isPlaying || duration == null) {
            state.lastObservedAtMs = now
            // A pause stops accrual without wiping the evidence; unknown duration
            // is simply not a short-form claim.
            state.lastShortPlaybackAtMs = NO_OBSERVATION
            return signal(observation.packageName, Verdict.UNKNOWN, state, now)
        }

        if (duration > config.durationThresholdMs) {
            state.resetEvidence(now)
            return signal(observation.packageName, Verdict.NOT_SHORT_FORM, state, now)
        }

        val wasShortAndPlaying = state.lastShortPlaybackAtMs != NO_OBSERVATION &&
            now - state.lastShortPlaybackAtMs <= config.resetAfterMs
        if (wasShortAndPlaying) {
            val delta = (now - state.lastShortPlaybackAtMs).coerceIn(0, config.resetAfterMs)
            state.shortPlaybackMs += delta
        }

        observation.clipId?.let { clipId ->
            state.clipLastSeenMs[clipId] = now
        }
        state.pruneClips(now, config.evidenceWindowMs)
        state.lastShortPlaybackAtMs = now
        state.lastObservedAtMs = now

        val distinctClips = state.clipLastSeenMs.size
        val confirmed = (distinctClips >= config.minDistinctClips &&
            state.shortPlaybackMs >= config.minShortPlaybackMs) ||
            state.shortPlaybackMs >= config.singleClipConfirmMs

        if (confirmed) {
            val evidence = state.snapshot(now)
            state.resetEvidence(now)
            state.cooldownUntilMs = now + config.cooldownMs
            return ShortFormSignal(
                packageName = observation.packageName,
                verdict = Verdict.CONFIRMED,
                shortPlaybackMs = evidence.shortPlaybackMs,
                distinctClips = evidence.distinctClips,
                lastDurationMs = duration,
                observedAtMs = now,
            )
        }

        return signal(observation.packageName, Verdict.SUSPECTED, state, now, duration)
    }

    fun reset(packageName: String) {
        states.remove(packageName)
    }

    fun resetAll() {
        states.clear()
    }

    private fun signal(
        packageName: String,
        verdict: Verdict,
        state: PackageState,
        now: Long,
        durationMs: Long? = null,
    ) = ShortFormSignal(
        packageName = packageName,
        verdict = verdict,
        shortPlaybackMs = state.shortPlaybackMs,
        distinctClips = state.clipLastSeenMs.size,
        lastDurationMs = durationMs,
        observedAtMs = now,
    )

    private class PackageState(var lastObservedAtMs: Long) {
        var shortPlaybackMs: Long = 0
        var lastShortPlaybackAtMs: Long = NO_OBSERVATION
        var cooldownUntilMs: Long = 0
        val clipLastSeenMs = mutableMapOf<String, Long>()

        fun resetEvidence(now: Long) {
            shortPlaybackMs = 0
            lastShortPlaybackAtMs = NO_OBSERVATION
            clipLastSeenMs.clear()
            lastObservedAtMs = now
        }

        fun pruneClips(now: Long, windowMs: Long) {
            clipLastSeenMs.entries.removeAll { now - it.value > windowMs }
        }

        fun snapshot(now: Long): Evidence = Evidence(
            shortPlaybackMs = shortPlaybackMs,
            distinctClips = clipLastSeenMs.size,
            atMs = now,
        )
    }

    private companion object {
        const val NO_OBSERVATION = Long.MIN_VALUE
    }

    private data class Evidence(
        val shortPlaybackMs: Long,
        val distinctClips: Int,
        val atMs: Long,
    )
}

enum class Verdict {
    /** Duration is absent or the session is paused: no short-form claim. */
    UNKNOWN,

    /** Duration is known and above the threshold, or playback is not short-form. */
    NOT_SHORT_FORM,

    /** Short playback observed, but not yet enough evidence to block. */
    SUSPECTED,

    /** Enough evidence: the caller may block. */
    CONFIRMED,

    /** Recently confirmed; stay quiet until the cooldown elapses. */
    COOLDOWN,
}

data class ShortFormSignal(
    val packageName: String,
    val verdict: Verdict,
    val shortPlaybackMs: Long,
    val distinctClips: Int,
    val lastDurationMs: Long?,
    val observedAtMs: Long,
)

data class MediaObservation(
    val packageName: String,
    val durationMs: Long?,
    val isPlaying: Boolean,
    /**
     * A stable identifier for the current clip when the source exposes one
     * (for example a hash of title + duration + artwork URI). A new value means
     * the user moved to a different clip. `null` is allowed: callers that cannot
     * identify clips still accrue playback time.
     */
    val clipId: String?,
    val timestampMs: Long,
)

data class ShortFormConfig(
    /** Clips at or below this duration count as short-form. */
    val durationThresholdMs: Long = 90_000,
    /** Distinct short clips required before confirming (with [minShortPlaybackMs]). */
    val minDistinctClips: Int = 2,
    /** Minimum accumulated short playback before confirming two or more clips. */
    val minShortPlaybackMs: Long = 10_000,
    /** A single clip playing this long confirms on its own (looped reels). */
    val singleClipConfirmMs: Long = 45_000,
    /** Clip identities older than this are forgotten. */
    val evidenceWindowMs: Long = 120_000,
    /** A gap in observations longer than this clears the evidence. */
    val resetAfterMs: Long = 45_000,
    /** After a confirmation, suppress further alerts for this long. */
    val cooldownMs: Long = 60_000,
) {
    init {
        require(durationThresholdMs > 0) { "durationThresholdMs must be positive" }
        require(minDistinctClips >= 1) { "minDistinctClips must be at least 1" }
        require(minShortPlaybackMs >= 0) { "minShortPlaybackMs must not be negative" }
        require(singleClipConfirmMs > 0) { "singleClipConfirmMs must be positive" }
        require(evidenceWindowMs > 0) { "evidenceWindowMs must be positive" }
        require(resetAfterMs > 0) { "resetAfterMs must be positive" }
        require(cooldownMs >= 0) { "cooldownMs must not be negative" }
    }
}
