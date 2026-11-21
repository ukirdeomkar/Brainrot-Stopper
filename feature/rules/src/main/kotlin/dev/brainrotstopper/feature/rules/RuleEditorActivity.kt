package dev.brainrotstopper.feature.rules

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
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
        schedulesContainer = findViewById(R.id.rule_schedules_container)
        noSchedules = findViewById(R.id.rule_no_schedules)
        error = findViewById(R.id.rule_error)

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
        if (!authorizeChange()) return
        error.text = ""
        val updated = current.copy(
            enabled = enabled.isChecked,
            blockAll = blockAll.isChecked,
            scheduleIds = scheduleBoxes.filterValues { it.isChecked }.keys.toSet(),
        )
        io {
            runBlocking { repository.upsertRule(updated) }
            onUi {
                Toast.makeText(this, R.string.rules_app_saved, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun confirmDelete() {
        val current = rule ?: return
        if (!authorizeChange()) return
        AlertDialog.Builder(this)
            .setMessage(R.string.rule_editor_delete_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> delete(current) }
            .show()
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

    private fun authorizeChange(): Boolean {
        if (RuleChangeGate.isAuthorized()) return true
        error.text = getString(R.string.rules_change_denied)
        return false
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
