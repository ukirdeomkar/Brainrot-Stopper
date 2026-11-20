package dev.brainrotstopper.feature.blocking

import java.time.DayOfWeek

/** Temporary no-op used by the scaffold. Replaced by the real engine in M4. */
class NoOpBlockingController : BlockingController {

    override val name: String = "NoOpBlockingController (real engine lands in M4)"

    override fun decisionFor(packageName: String, day: DayOfWeek, minuteOfDay: Int): Decision =
        Decision.Allow
}
