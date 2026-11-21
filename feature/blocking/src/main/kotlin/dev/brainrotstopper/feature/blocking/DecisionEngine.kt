package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockReason
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.TimeWindow
import dev.brainrotstopper.core.model.UnlockGrant
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure blocking decision: `(package, instant, rules, schedules, timers, grants) -> Decision`.
 *
 * No Android types and no I/O, so the whole rule set is exercised by fast JVM unit tests.
 * The AccessibilityService evaluates the result of a cached snapshot on the hot path
 * ([BlockingStateCache]); disk reads happen only on refresh.
 *
 * Order of precedence:
 * 1. An active unlock grant covering the package always allows it.
 * 2. An app with no enabled rule is allowed.
 * 3. An active schedule window blocks with [BlockReason.SCHEDULE] and ends when the window
 *    ends (overnight windows end on the following day).
 * 4. An exhausted daily timer blocks with [BlockReason.DAILY_LIMIT] until the next local
 *    midnight.
 */
class DecisionEngine {

    fun decide(
        packageName: String,
        at: Instant,
        zone: ZoneId,
        rules: List<AppRule>,
        schedules: List<BlockSchedule>,
        timers: List<TimerState>,
        grants: List<UnlockGrant>,
    ): Decision {
        val nowMillis = at.toEpochMilli()
        if (grants.any { it.isActiveAt(nowMillis) && it.covers(packageName) }) {
            return Decision.Allow
        }

        val rule = rules.firstOrNull { it.packageName == packageName } ?: return Decision.Allow
        if (!rule.enabled) return Decision.Allow

        val zoned = at.atZone(zone)
        val minuteOfDay = zoned.hour * 60 + zoned.minute

        val activeSchedule = activeSchedule(rule, schedules, zoned.dayOfWeek, minuteOfDay)
        if (activeSchedule != null) {
            return Decision(
                block = true,
                reason = BlockReason.SCHEDULE,
                releaseAtEpochMillis = scheduleReleaseAt(activeSchedule, zoned, zone),
            )
        }

        val limitMinutes = rule.dailyLimitMinutes
        if (limitMinutes != null) {
            val epochDay = zoned.toLocalDate().toEpochDay()
            val usedMinutes = timers
                .firstOrNull { it.packageName == packageName && it.epochDay == epochDay }
                ?.usedMinutes
                ?: 0
            if (usedMinutes >= limitMinutes) {
                return Decision(
                    block = true,
                    reason = BlockReason.DAILY_LIMIT,
                    releaseAtEpochMillis = nextLocalMidnightMillis(zoned, zone),
                )
            }
        }

        return Decision.Allow
    }

    private fun activeSchedule(
        rule: AppRule,
        schedules: List<BlockSchedule>,
        day: java.time.DayOfWeek,
        minuteOfDay: Int,
    ): BlockSchedule? {
        val schedulesById = schedules.associateBy { it.id }
        return rule.scheduleIds
            .asSequence()
            .mapNotNull { schedulesById[it] }
            .firstOrNull { it.isActiveAt(day, minuteOfDay) }
    }

    /**
     * The earliest end of an active window of [schedule], or null when no window end can be
     * computed. Windows are half-open, so a window ending exactly now is not active.
     */
    private fun scheduleReleaseAt(
        schedule: BlockSchedule,
        zoned: ZonedDateTime,
        zone: ZoneId,
    ): Long? {
        val now = zoned.toInstant()
        val minuteOfDay = zoned.hour * 60 + zoned.minute
        val today = zoned.toLocalDate()

        return schedule.windows
            .asSequence()
            .filter { it.contains(minuteOfDay) }
            .map { window -> windowEnd(window, today, minuteOfDay, zone) }
            .filter { it.isAfter(now) }
            .minOrNull()
            ?.toEpochMilli()
    }

    private fun windowEnd(
        window: TimeWindow,
        today: LocalDate,
        minuteOfDay: Int,
        zone: ZoneId,
    ): Instant {
        val endsTomorrow = window.isOvernight && minuteOfDay >= window.startMinuteOfDay
        val endDate = if (endsTomorrow) today.plusDays(1) else today
        return endDate.atTime(LocalTime.MIDNIGHT)
            .plusMinutes(window.endMinuteOfDay.toLong())
            .atZone(zone)
            .toInstant()
    }

    private fun nextLocalMidnightMillis(zoned: ZonedDateTime, zone: ZoneId): Long =
        zoned.toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
}
