package dev.brainrotstopper.core.model.shortform

import dev.brainrotstopper.core.model.AppRule

/**
 * Whether on-device short-form detection may run for one app.
 *
 * Detection is deliberately guarded by three independent switches so a single toggle can never
 * silently turn it on: the global kill switch, the per-app rule flag, and the rule's own
 * enabled state. An app with no rule is never observed.
 */
object ShortFormDetectionPolicy {

    fun shouldDetect(globalEnabled: Boolean, rule: AppRule?): Boolean =
        globalEnabled && rule != null && rule.enabled && rule.shortFormDetection

    /**
     * `METADATA_KEY_DURATION` is a `long` in milliseconds where a non-positive value means
     * unknown or infinite (live). Only a positive value is a usable short-form signal.
     */
    fun usableDurationMs(rawDurationMs: Long): Long? =
        rawDurationMs.takeIf { it > 0L }
}
