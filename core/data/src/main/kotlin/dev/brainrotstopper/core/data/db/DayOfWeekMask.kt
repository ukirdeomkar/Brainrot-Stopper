package dev.brainrotstopper.core.data.db

import java.time.DayOfWeek

/**
 * Encodes the days a schedule applies to as a 7-bit mask, Monday in bit 0 through Sunday in
 * bit 6. This mirrors [DayOfWeek.value] (1..7) with a one-bit shift and keeps the blocking
 * engine's hot path join-free.
 */
object DayOfWeekMask {

    const val ALL: Int = 0b111_1111

    fun encode(days: Set<DayOfWeek>): Int =
        days.fold(0) { mask, day -> mask or bit(day) }

    fun decode(mask: Int): Set<DayOfWeek> {
        require(mask and ALL.inv() == 0) { "days mask has bits outside Monday..Sunday: $mask" }
        return DayOfWeek.entries.filterTo(LinkedHashSet()) { mask and bit(it) != 0 }
    }

    private fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)
}
