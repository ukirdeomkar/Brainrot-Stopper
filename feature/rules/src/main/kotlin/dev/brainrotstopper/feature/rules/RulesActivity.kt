package dev.brainrotstopper.feature.rules

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.brainrotstopper.core.data.RuleRepository
import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule
import kotlinx.coroutines.runBlocking

/**
 * Lists guarded apps and schedules. It is the entry point of the M3 editor: rows can be
 * enabled/disabled inline, and the add/edit actions open the dedicated editors.
 */
class RulesActivity : Activity() {

    private lateinit var repository: RuleRepository
    private lateinit var rulesContainer: LinearLayout
    private lateinit var schedulesContainer: LinearLayout
    private lateinit var noApps: TextView
    private lateinit var noSchedules: TextView

    private var rules: List<AppRule> = emptyList()
    private var schedules: List<BlockSchedule> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)

        repository = RulesStore.repository(this)
        rulesContainer = findViewById(R.id.rules_container)
        schedulesContainer = findViewById(R.id.rules_schedules_container)
        noApps = findViewById(R.id.rules_no_apps)
        noSchedules = findViewById(R.id.rules_no_schedules)

        findViewById<Button>(R.id.rules_add_apps).setOnClickListener {
            startActivity(AppPickerActivity.intent(this))
        }
        findViewById<Button>(R.id.rules_new_schedule).setOnClickListener {
            if (authorizeChange()) startActivity(ScheduleEditorActivity.newScheduleIntent(this))
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        io {
            val rules = runBlocking { repository.rules() }
            val schedules = runBlocking { repository.schedules() }
            onUi {
                this.rules = rules
                this.schedules = schedules
                render()
            }
        }
    }

    private fun render() {
        rulesContainer.removeAllViews()
        noApps.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        rules.forEach { rulesContainer.addView(ruleRow(it)) }

        schedulesContainer.removeAllViews()
        noSchedules.visibility = if (schedules.isEmpty()) View.VISIBLE else View.GONE
        schedules.forEach { schedulesContainer.addView(scheduleRow(it)) }
    }

    private fun ruleRow(rule: AppRule): View {
        val row = verticalRow("rule_row_${rule.packageName}")

        row.addView(heading(rule.displayName))
        row.addView(body(scheduleSummary(rule)))
        row.addView(
            body(dailyLimitSummary(rule)).apply {
                tag = "rule_daily_limit_summary_${rule.packageName}"
            },
        )

        var suppress = false
        val enabled = Switch(this).apply {
            text = getString(if (rule.enabled) R.string.rules_enabled else R.string.rules_disabled)
            tag = "rule_enabled_${rule.packageName}"
            isChecked = rule.enabled
            setOnCheckedChangeListener { button, checked ->
                if (suppress) return@setOnCheckedChangeListener
                if (!authorizeChange()) {
                    suppress = true
                    button.isChecked = !checked
                    text = getString(if (!checked) R.string.rules_enabled else R.string.rules_disabled)
                    suppress = false
                    return@setOnCheckedChangeListener
                }
                val updated = rule.copy(enabled = checked)
                text = getString(if (checked) R.string.rules_enabled else R.string.rules_disabled)
                persistRule(updated)
            }
        }
        row.addView(enabled)

        row.addView(actionButton(R.string.rules_edit, "rule_edit_${rule.packageName}") {
            startActivity(RuleEditorActivity.intent(this, rule.packageName))
        })
        return row
    }

    private fun scheduleRow(schedule: BlockSchedule): View {
        val row = verticalRow("schedule_row_${schedule.id}")

        row.addView(heading(schedule.label))
        val state = getString(if (schedule.enabled) R.string.rules_enabled else R.string.rules_disabled)
        row.addView(
            body(
                "$state · ${ScheduleFormat.daysSummary(schedule.daysOfWeek)} · " +
                    ScheduleFormat.windowsSummary(schedule.windows),
            ),
        )

        var suppress = false
        val enabled = Switch(this).apply {
            tag = "schedule_enabled_${schedule.id}"
            isChecked = schedule.enabled
            setOnCheckedChangeListener { button, checked ->
                if (suppress) return@setOnCheckedChangeListener
                if (!authorizeChange()) {
                    suppress = true
                    button.isChecked = !checked
                    suppress = false
                    return@setOnCheckedChangeListener
                }
                persistSchedule(schedule.copy(enabled = checked))
                reload()
            }
        }
        row.addView(enabled)

        row.addView(actionButton(R.string.rules_edit, "schedule_edit_${schedule.id}") {
            startActivity(ScheduleEditorActivity.editIntent(this, schedule.id))
        })
        return row
    }

    private fun dailyLimitSummary(rule: AppRule): String {
        val limit = rule.dailyLimitMinutes
        return if (limit == null) {
            getString(R.string.rules_no_daily_limit)
        } else {
            getString(R.string.rules_daily_limit, limit)
        }
    }

    private fun scheduleSummary(rule: AppRule): String {
        if (rule.scheduleIds.isEmpty()) return getString(R.string.rules_no_schedules_assigned)
        val byId = schedules.associateBy { it.id }
        val labels = rule.scheduleIds.mapNotNull { byId[it]?.label }.sorted()
        return if (labels.isEmpty()) {
            getString(R.string.rules_no_schedules_assigned)
        } else {
            labels.joinToString(", ")
        }
    }

    private fun persistRule(rule: AppRule) {
        io {
            runBlocking { repository.upsertRule(rule) }
            onUi {
                rules = rules.map { if (it.packageName == rule.packageName) rule else it }
                Toast.makeText(this, R.string.rules_app_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun persistSchedule(schedule: BlockSchedule) {
        io {
            runBlocking { repository.upsertSchedule(schedule) }
            onUi { Toast.makeText(this, R.string.rules_schedule_saved, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun authorizeChange(): Boolean {
        if (RuleChangeGate.isAuthorized()) return true
        Toast.makeText(this, R.string.rules_change_denied, Toast.LENGTH_LONG).show()
        return false
    }

    private fun verticalRow(tag: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            this.tag = tag
        }

    private fun heading(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun body(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
        }

    private fun actionButton(label: Int, tag: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = getString(label)
            this.tag = tag
            setOnClickListener { onClick() }
        }

    private fun io(block: () -> Unit) {
        Thread(block).apply {
            name = "rules-io"
            isDaemon = true
        }.start()
    }

    private fun onUi(block: () -> Unit) {
        runOnUiThread(block)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
