package dev.brainrotstopper.feature.timers

/** Temporary no-op used by the scaffold. Replaced by the real tracker in M6. */
class NoOpTimerController : TimerController {

    override val name: String = "NoOpTimerController (usage tracking lands in M6)"

    override fun remainingMinutes(packageName: String): Int? = null

    override fun recordUsage(packageName: String, usedMinutes: Int) = Unit
}
