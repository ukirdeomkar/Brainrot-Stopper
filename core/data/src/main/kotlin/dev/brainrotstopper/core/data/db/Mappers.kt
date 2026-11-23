package dev.brainrotstopper.core.data.db

import dev.brainrotstopper.core.data.db.entity.AppRuleEntity
import dev.brainrotstopper.core.data.db.entity.BlockEventEntity
import dev.brainrotstopper.core.data.db.entity.ScheduleEntity
import dev.brainrotstopper.core.data.db.entity.ScheduleWindowEntity
import dev.brainrotstopper.core.data.db.entity.UnlockGrantEntity
import dev.brainrotstopper.core.data.db.entity.UsageBucketEntity
import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockEvent
import dev.brainrotstopper.core.model.BlockReason
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.TimeWindow
import dev.brainrotstopper.core.model.UnlockGrant
import dev.brainrotstopper.core.model.UsageBucket

internal fun AppRule.toEntity(): AppRuleEntity = AppRuleEntity(
    packageName = packageName,
    displayName = displayName,
    enabled = enabled,
    blockAll = blockAll,
    shortFormDetection = shortFormDetection,
    dailyLimitMinutes = dailyLimitMinutes,
)

internal fun AppRuleWithSchedules.toModel(): AppRule =
    AppRule(
        packageName = rule.packageName,
        displayName = rule.displayName,
        enabled = rule.enabled,
        dailyLimitMinutes = rule.dailyLimitMinutes,
        scheduleIds = scheduleLinks.mapTo(LinkedHashSet()) { it.scheduleId },
        blockAll = rule.blockAll,
        shortFormDetection = rule.shortFormDetection,
    )

internal fun BlockSchedule.toEntity(): ScheduleEntity = ScheduleEntity(
    id = id,
    label = label,
    enabled = enabled,
    daysMask = DayOfWeekMask.encode(daysOfWeek),
)

internal fun BlockSchedule.toWindowEntities(): List<ScheduleWindowEntity> =
    windows.mapIndexed { ordinal, window ->
        ScheduleWindowEntity(
            scheduleId = id,
            startMinuteOfDay = window.startMinuteOfDay,
            endMinuteOfDay = window.endMinuteOfDay,
            ordinal = ordinal,
        )
    }

internal fun ScheduleWithWindows.toModel(): BlockSchedule =
    BlockSchedule(
        id = schedule.id,
        label = schedule.label,
        daysOfWeek = DayOfWeekMask.decode(schedule.daysMask),
        windows = windows
            .sortedBy { it.ordinal }
            .map { TimeWindow(it.startMinuteOfDay, it.endMinuteOfDay) },
        enabled = schedule.enabled,
    )

internal fun UsageBucket.toEntity(): UsageBucketEntity = UsageBucketEntity(
    packageName = packageName,
    epochDay = epochDay,
    usedMillis = usedMillis,
)

internal fun UsageBucketEntity.toModel(): UsageBucket =
    UsageBucket(packageName = packageName, epochDay = epochDay, usedMillis = usedMillis)

internal fun UnlockGrant.toEntity(): UnlockGrantEntity = UnlockGrantEntity(
    id = id,
    packageName = packageName,
    expiresAtEpochMillis = expiresAtEpochMillis,
    reason = reason,
)

internal fun UnlockGrantEntity.toModel(): UnlockGrant = UnlockGrant(
    id = id,
    packageName = packageName,
    expiresAtEpochMillis = expiresAtEpochMillis,
    reason = reason,
)

internal fun BlockEventEntity.toModel(): BlockEvent = BlockEvent(
    id = id,
    packageName = packageName,
    startedAtEpochMillis = startedAtEpochMillis,
    reason = BlockReason.valueOf(reason),
    releasedAtEpochMillis = releasedAtEpochMillis,
)
