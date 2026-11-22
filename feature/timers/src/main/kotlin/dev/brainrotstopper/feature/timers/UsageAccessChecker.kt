package dev.brainrotstopper.feature.timers

/** Reads whether Usage Access is currently granted. */
interface UsageAccessChecker {
    fun status(): UsageAccessStatus
}
