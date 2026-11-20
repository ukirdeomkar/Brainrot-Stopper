package dev.brainrotstopper.core.model

/** Why the blocking engine is holding an overlay. */
enum class BlockReason {
    SCHEDULE,
    DAILY_LIMIT,
    MANUAL,
}
