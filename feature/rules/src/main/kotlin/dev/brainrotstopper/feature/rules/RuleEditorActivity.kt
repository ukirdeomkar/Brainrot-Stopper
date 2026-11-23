package dev.brainrotstopper.feature.rules

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.brainrotstopper.core.data.RuleRepository
import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule
import kotlinx.coroutines.runBlocking

/** Edits one app rule: enabled state, whole-app blocking, and the schedules it belongs to. */
class RuleEditorActivity : Activity() {

    private lateinit var repository: RuleRepository
    private lateinit var appName: TextView
    private lateinit var enabled: Switch
    private lateinit var blockAll: Switch
    private lateinit var shortForm: Switch
    private lateinit var dailyLimitField: EditText
    private lateinit var schedulesContainer: LinearLayout
    private lateinit var noSchedules: TextView
    private lateinit var error: TextView

    private val scheduleBoxes = linkedMapOf<String, CheckBox>()
    private var rule: AppRule? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rule_editor)

        repository = RulesStore.repository(this)
        appName = findViewById(R.id.rule_app_name)
        enabled = findViewById(R.id.rule_enabled)
        blockAll = findViewById(R.id.rule_block_all)
        shortForm = findViewById(R.id.rule_short_form)
        dailyLimitField = findViewById(R.id.rule_daily_limit)
        schedulesContainer = findViewById(R.id.rule_schedules_container)
        noSchedules = findViewById(R.id.rule_no_schedules)
        error = findViewById(R.id.rule_error)

        findViewById<Button>(R.id.rule_daily_limit_clear).setOnClickListener {
            dailyLimitField.setText("")
        }
        findViewById<Button>(R.id.rule_save).setOnClickListener { save() }
        findViewById<Button>(R.id.rule_delete).setOnClickListener { confirmDelete() }

        load()
    }

    private fun load() {
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: return finish()
        io {
            val rule = runBlocking { repository.rules() }.firstOrNull { it.packageName == packageName }
            val schedules = runBlocking { repository.schedules() }
            onUi {
                if (rule == null) {
                    Toast.makeText(this, R.string.rule_editor_missing, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    this.rule = rule
                    render(rule, schedules)
                }
            }
        }
    }

    private fun render(rule: AppRule, schedules: List<BlockSchedule>) {
        appName.text = rule.displayName
        enabled.isChecked = rule.enabled
        blockAll.isChecked = rule.blockAll
        shortForm.isChecked = rule.shortFormDetection
        dailyLimitField.setText(rule.dailyLimitMinutes?.toString().orEmpty())

        schedulesContainer.removeAllViews()
        scheduleBoxes.clear()
        noSchedules.visibility = if (schedules.isEmpty()) View.VISIBLE else View.GONE
        schedules.forEach { schedule ->
            val box = CheckBox(this).apply {
                text = schedule.label
                tag = "rule_schedule_${schedule.id}"
                isChecked = schedule.id in rule.scheduleIds
                setPadding(0, dp(8), 0, dp(8))
            }
            scheduleBoxes[schedule.id] = box
            schedulesContainer.addView(box)
        }
    }

    private fun save() {
        val current = rule ?: return
        RuleChangeGate.authorize(this) { granted ->
            if (!granted) {
                error.text = getString(R.string.rules_change_denied)
                return@authorize
            }
            error.text = ""

            val model = RuleEditorModel(current).apply {
                enabled = this@RuleEditorActivity.enabled.isChecked
                blockAll = this@RuleEditorActivity.blockAll.isChecked
                shortFormDetection = this@RuleEditorActivity.shortForm.isChecked
                scheduleIds = scheduleBoxes.filterValues { it.isChecked }.keys.toSet()
                dailyLimitInput = dailyLimitField.text.toString()
            }
            val limit = model.dailyLimit()
            if (limit is DailyLimitResult.Invalid) {
                error.text = dailyLimitMessage(limit.problem)
                return@authorize
            }
            val updated = model.build()

            io {
                runBlocking { repository.upsertRule(updated) }
                onUi {
                    Toast.makeText(this, R.string.rules_app_saved, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun confirmDelete() {
        val current = rule ?: return
        RuleChangeGate.authorize(this) { granted ->
            if (!granted) {
                error.text = getString(R.string.rules_change_denied)
                return@authorize
            }
            AlertDialog.Builder(this)
                .setMessage(R.string.rule_editor_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> delete(current) }
                .show()
        }
    }

    private fun delete(rule: AppRule) {
        io {
            runBlocking { repository.removeRule(rule.packageName) }
            onUi {
                Toast.makeText(this, R.string.rule_editor_deleted, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun dailyLimitMessage(problem: DailyLimitProblem): String = when (problem) {
        DailyLimitProblem.NotANumber -> getString(R.string.rule_editor_daily_limit_error_number)
        DailyLimitProblem.NotPositive -> getString(R.string.rule_editor_daily_limit_error_positive)
        DailyLimitProblem.TooLarge -> getString(
            R.string.rule_editor_daily_limit_error_too_large,
            RuleEditorModel.MAX_DAILY_LIMIT_MINUTES,
        )
    }

    private fun io(block: () -> Unit) {
        Thread(block).apply {
            name = "rule-editor-io"
            isDaemon = true
        }.start()
    }

    private fun onUi(block: () -> Unit) = runOnUiThread(block)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_PACKAGE = "extra_package"

        fun intent(context: Context, packageName: String): Intent =
            Intent(context, RuleEditorActivity::class.java).putExtra(EXTRA_PACKAGE, packageName)
    }
}
