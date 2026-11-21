package dev.brainrotstopper.feature.rules

import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.ScheduleProblem
import dev.brainrotstopper.core.model.ScheduleValidator
import dev.brainrotstopper.core.model.TimeWindow
import dev.brainrotstopper.core.model.WindowDraft
import java.time.DayOfWeek

/**
 * Editable state for one schedule. The activity only renders this and forwards user actions,
 * so every validation rule is exercised by JVM unit tests.
 */
class ScheduleEditorModel(
    val id: String,
    initial: BlockSchedule?,
) {

    var label: String = initial?.label.orEmpty()

    val days: MutableSet<DayOfWeek> =
        initial?.daysOfWeek?.toMutableSet() ?: linkedSetOf()

    val enabled: Boolean = initial?.enabled ?: true

    private val windows: MutableList<WindowDraft> = initial
        ?.windows
        ?.mapTo(mutableListOf()) { WindowDraft.of(it) }
        ?: mutableListOf(WindowDraft(DEFAULT_START, DEFAULT_END))

    fun windows(): List<WindowDraft> = windows.toList()

    fun addWindow() {
        windows += nextWindow()
    }

    fun removeWindow(index: Int) {
        require(index in windows.indices) { "window index $index out of bounds" }
        windows.removeAt(index)
    }

    fun setWindow(index: Int, startMinuteOfDay: Int, endMinuteOfDay: Int) {
        require(index in windows.indices) { "window index $index out of bounds" }
        windows[index] = WindowDraft(startMinuteOfDay, endMinuteOfDay)
    }

    fun problems(): List<ScheduleProblem> = ScheduleValidator.validate(days, windows)

    /** Builds the domain schedule. Call only after [problems] is empty. */
    fun build(): BlockSchedule {
        val problems = problems()
        require(problems.isEmpty()) { "cannot build an invalid schedule: $problems" }
        return BlockSchedule(
            id = id,
            label = label.trim(),
            daysOfWeek = days.toSet(),
            windows = windows.map { it.toTimeWindow() },
            enabled = enabled,
        )
    }

    private fun nextWindow(): WindowDraft {
        val lastEnd = windows.lastOrNull()?.endMinuteOfDay ?: DEFAULT_START
        val start = lastEnd.coerceAtMost(TimeWindow.MINUTES_PER_DAY - 30)
        val end = (start + 60).coerceAtMost(TimeWindow.MINUTES_PER_DAY - 1)
        return WindowDraft(start, if (end == start) DEFAULT_END else end)
    }

    private companion object {
        const val DEFAULT_START = 9 * 60
        const val DEFAULT_END = 17 * 60
    }
}
