package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.data.RuleRepository
import dev.brainrotstopper.core.data.UnlockGrantRepository
import dev.brainrotstopper.core.data.UsageRepository
import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.UnlockGrant
import java.time.Instant
import java.time.ZoneId

/**
 * An immutable, in-memory view of everything the [DecisionEngine] needs.
 *
 * [loaded] is false until the first successful [BlockingStateCache.refresh]. The service
 * fails open on an unloaded snapshot, then re-evaluates as soon as the refresh lands.
 */
data class BlockingSnapshot(
    val rules: List<AppRule> = emptyList(),
    val schedules: List<BlockSchedule> = emptyList(),
    val timers: List<TimerState> = emptyList(),
    val grants: List<UnlockGrant> = emptyList(),
    val loaded: Boolean = false,
    val loadedAtEpochMillis: Long = 0L,
)

/**
 * Keeps [DecisionEngine] inputs in process memory so the AccessibilityService hot path does
 * no disk I/O. [refresh] is the only reader; it is safe to call from any dispatcher because
 * each refresh builds a new immutable [BlockingSnapshot] and swaps it atomically.
 *
 * The timer snapshot is rebuilt for *today* in the current device timezone on every refresh,
 * so a midnight or timezone rollover cannot carry yesterday's usage forward.
 */
class BlockingStateCache(
    private val ruleRepository: RuleRepository,
    private val unlockGrantRepository: UnlockGrantRepository,
    private val usageRepository: UsageRepository,
    private val instantSource: () -> Instant = Instant::now,
    private val zoneSource: () -> ZoneId = ZoneId::systemDefault,
    private val engine: DecisionEngine = DecisionEngine(),
) {

    @Volatile
    private var snapshot: BlockingSnapshot = BlockingSnapshot()

    fun current(): BlockingSnapshot = snapshot

    /** Reads rules, schedules, active grants, and today's usage totals for every timed rule. */
    suspend fun refresh() {
        val now = instantSource()
        val nowMillis = now.toEpochMilli()
        val epochDay = now.atZone(zoneSource()).toLocalDate().toEpochDay()

        val rules = ruleRepository.rules()
        val schedules = ruleRepository.schedules()
        val grants = unlockGrantRepository.active(nowMillis)
        val usageByPackage = usageRepository.usageForDay(epochDay).associateBy { it.packageName }
        val timers = buildList {
            for (rule in rules) {
                if (rule.dailyLimitMinutes == null) continue
                val usedMillis = usageByPackage[rule.packageName]?.usedMillis ?: continue
                add(
                    TimerState(
                        packageName = rule.packageName,
                        epochDay = epochDay,
                        usedMinutes = (usedMillis / MILLIS_PER_MINUTE).toInt(),
                    ),
                )
            }
        }

        snapshot = BlockingSnapshot(
            rules = rules,
            schedules = schedules,
            timers = timers,
            grants = grants,
            loaded = true,
            loadedAtEpochMillis = nowMillis,
        )
    }

    fun decisionFor(packageName: String): Decision {
        val current = snapshot
        if (!current.loaded) return Decision.Allow
        return engine.decide(
            packageName = packageName,
            at = instantSource(),
            zone = zoneSource(),
            rules = current.rules,
            schedules = current.schedules,
            timers = current.timers,
            grants = current.grants,
        )
    }

    /** True when a cached unlock grant covers [packageName] at [nowEpochMillis]. */
    fun isUnlocked(packageName: String, nowEpochMillis: Long = instantSource().toEpochMilli()): Boolean =
        snapshot.grants.any { it.isActiveAt(nowEpochMillis) && it.covers(packageName) }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
