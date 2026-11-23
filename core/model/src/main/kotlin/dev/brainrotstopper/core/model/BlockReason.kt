package dev.brainrotstopper.core.model

/** Why the blocking engine is holding an overlay. */
enum class BlockReason {
    ALWAYS_BLOCK,
    SCHEDULE,
    DAILY_LIMIT,
    MANUAL,

    /**
     * Best-effort short-form detection confirmed a sub-threshold clip while the app was in the
     * foreground (M10). This is an event-driven block, not a rule decision: there is no natural
     * release time, so it ends only through the password unlock or leaving the app.
     */
    SHORT_FORM,
}
