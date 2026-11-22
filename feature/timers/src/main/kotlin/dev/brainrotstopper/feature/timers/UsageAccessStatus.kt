package dev.brainrotstopper.feature.timers

/**
 * State of the Usage Access grant the daily timers depend on.
 *
 * [UNDETERMINED] means no check has run yet in this process. [REVOKED] means the user has not
 * granted Usage Access, or revoked it after granting it; in that case polling stops and the
 * timer path degrades to "blocking only, no daily timers" instead of crashing.
 */
enum class UsageAccessStatus {
    UNDETERMINED,
    GRANTED,
    REVOKED,
}
