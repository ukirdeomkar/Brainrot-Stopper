package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.model.BlockReason
import java.time.DayOfWeek

/** The blocking engine's verdict for one foreground check. */
data class Decision(
    val block: Boolean,
    val reason: BlockReason? = null,
) {
    companion object {
        val Allow: Decision = Decision(block = false)
    }
}

/**
 * Contract the AccessibilityService and the UI share.
 *
 * The real implementation (M4) evaluates rules, schedules, timers, and active unlock grants.
 * The scaffold ships [NoOpBlockingController] so the module graph and UI wiring can compile.
 */
interface BlockingController {
    val name: String

    fun decisionFor(packageName: String, day: DayOfWeek, minuteOfDay: Int): Decision
}
