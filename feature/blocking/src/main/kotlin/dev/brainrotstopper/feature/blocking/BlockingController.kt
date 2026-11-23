package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.model.BlockReason
import java.time.DayOfWeek

/**
 * The blocking engine's verdict for one foreground check.
 *
 * [releaseAtEpochMillis] is the instant at which the block ends on its own: the end of the
 * active schedule window for [BlockReason.SCHEDULE], or the next local midnight for
 * [BlockReason.DAILY_LIMIT]. It is null for an allow decision and for blocks that have no
 * natural end: [BlockReason.ALWAYS_BLOCK] and [BlockReason.MANUAL].
 */
data class Decision(
    val block: Boolean,
    val reason: BlockReason? = null,
    val releaseAtEpochMillis: Long? = null,
) {
    init {
        require(block || reason == null) { "an allow decision cannot carry a block reason" }
        require(block || releaseAtEpochMillis == null) {
            "an allow decision cannot carry a release time"
        }
        require(!block || reason != null) { "a block decision must carry a reason" }
    }

    companion object {
        val Allow: Decision = Decision(block = false)
    }
}

/**
 * Contract the AccessibilityService and the UI share.
 *
 * The real implementation is [DecisionEngine], evaluated through [BlockingStateCache]. The
 * scaffold still ships [NoOpBlockingController] for the home-screen status text.
 */
interface BlockingController {
    val name: String

    fun decisionFor(packageName: String, day: DayOfWeek, minuteOfDay: Int): Decision
}
