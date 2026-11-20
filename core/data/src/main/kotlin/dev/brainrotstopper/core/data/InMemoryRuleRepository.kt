package dev.brainrotstopper.core.data

import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule

/**
 * Thread-safe, process-local implementation used by the scaffold.
 *
 * It is intentionally not persistent: it exists so the app shell and tests can exercise the
 * repository contract before Room is introduced (M2).
 */
class InMemoryRuleRepository : RuleRepository {

    private val lock = Any()
    private val rules = LinkedHashMap<String, AppRule>()
    private val schedules = LinkedHashMap<String, BlockSchedule>()

    override fun rules(): List<AppRule> = synchronized(lock) { rules.values.toList() }

    override fun upsertRule(rule: AppRule) {
        synchronized(lock) { rules[rule.packageName] = rule }
    }

    override fun removeRule(packageName: String) {
        synchronized(lock) { rules.remove(packageName) }
    }

    override fun schedules(): List<BlockSchedule> = synchronized(lock) { schedules.values.toList() }

    override fun upsertSchedule(schedule: BlockSchedule) {
        synchronized(lock) { schedules[schedule.id] = schedule }
    }

    override fun removeSchedule(id: String) {
        synchronized(lock) { schedules.remove(id) }
    }
}
