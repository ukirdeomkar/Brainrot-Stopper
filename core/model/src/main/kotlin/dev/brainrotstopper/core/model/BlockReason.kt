package dev.brainrotstopper.core.model

/** Why the blocking engine is holding an overlay. */
enum class BlockReason {
    ALWAYS_BLOCK,
    SCHEDULE,
    DAILY_LIMIT,
    MANUAL,
}
