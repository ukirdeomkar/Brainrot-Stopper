package dev.brainrotstopper.feature.rules

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.brainrotstopper.core.data.RuleRepository
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.ScheduleProblem
import java.time.DayOfWeek
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * Creates or edits one schedule: a name, day toggles, and one or more time windows.
 *
 * Save is refused while [ScheduleProblem]s remain, and the message names the offending
 * windows. No write happens before validation passes.
 */
class ScheduleEditorActivity : Activity() {

    private lateinit var repository: RuleRepository
    private lateinit var labelInput: EditText
    private lateinit var daysContainer: LinearLayout
    private lateinit var windowsContainer: LinearLayout
    private lateinit var errorView: TextView
    private lateinit var deleteButton: Button

    private var model: ScheduleEditorModel? = null
    private var editing: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule_editor)

        repository = RulesStore.repository(this)
        labelInput = findViewById(R.id.schedule_label)
        daysContainer = findViewById(R.id.schedule_days)
        windowsContainer = findViewById(R.id.schedule_windows)
        errorView = findViewById(R.id.schedule_error)
        deleteButton = findViewById(R.id.schedule_delete)

        editing = intent.getBooleanExtra(EXTRA_EDITING, false)
        deleteButton.visibility = if (editing) View.VISIBLE else View.GONE
        setTitle(if (editing) R.string.schedule_editor_title else R.string.schedule_editor_new_title)

        findViewById<Button>(R.id.schedule_add_window).setOnClickListener {
            model?.addWindow()
            renderWindows()
        }
        findViewById<Button>(R.id.schedule_save).setOnClickListener { save() }
        deleteButton.setOnClickListener { confirmDelete() }

        load()
    }

    private fun load() {
        val id = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: UUID.randomUUID().toString()
        io {
            val existing = runBlocking { repository.schedules() }.firstOrNull { it.id == id }
            onUi {
                val model = ScheduleEditorModel(id, existing)
                this.model = model
                editing = existing != null
                deleteButton.visibility = if (editing) View.VISIBLE else View.GONE
                labelInput.setText(model.label)
                renderDays()
                renderWindows()
            }
        }
    }

    private fun renderDays() {
        val model = model ?: return
        daysContainer.removeAllViews()
        DayOfWeek.entries.forEach { day ->
            val box = CheckBox(this).apply {
                text = getString(dayLabel(day))
                tag = "day_${day.name}"
                isChecked = day in model.days
                setPadding(0, dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) model.days.add(day) else model.days.remove(day)
                }
            }
            daysContainer.addView(box)
        }
    }

    private fun renderWindows() {
        val model = model ?: return
        windowsContainer.removeAllViews()
        model.windows().forEachIndexed { index, window ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
                tag = "window_row_$index"
            }

            val start = Button(this).apply {
                text = ScheduleFormat.clock(window.startMinuteOfDay)
                tag = "window_start_$index"
                setOnClickListener {
                    pickTime(window.startMinuteOfDay) { minute ->
                        model.setWindow(index, minute, model.windows()[index].endMinuteOfDay)
                        renderWindows()
                    }
                }
            }
            val separator = TextView(this).apply {
                text = getString(R.string.schedule_editor_to)
                setPadding(dp(8), 0, dp(8), 0)
            }
            val end = Button(this).apply {
                text = ScheduleFormat.clock(window.endMinuteOfDay)
                tag = "window_end_$index"
                setOnClickListener {
                    pickTime(window.endMinuteOfDay) { minute ->
                        model.setWindow(index, model.windows()[index].startMinuteOfDay, minute)
                        renderWindows()
                    }
                }
            }
            val remove = Button(this).apply {
                text = getString(R.string.schedule_editor_remove_window)
                tag = "window_remove_$index"
                setOnClickListener {
                    model.removeWindow(index)
                    renderWindows()
                }
            }

            row.addView(start)
            row.addView(separator)
            row.addView(end)
            row.addView(remove)
            windowsContainer.addView(row)
        }
    }

    private fun pickTime(currentMinuteOfDay: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            currentMinuteOfDay / 60,
            currentMinuteOfDay % 60,
            DateFormat.is24HourFormat(this),
        ).show()
    }

    private fun save() {
        val model = model ?: return
        RuleChangeGate.authorize(this) { granted ->
            if (!granted) {
                showError(getString(R.string.rules_change_denied))
                return@authorize
            }

            model.label = labelInput.text.toString()
            if (model.label.isBlank()) {
                showError(getString(R.string.schedule_error_label))
                return@authorize
            }

            val problems = model.problems()
            if (problems.isNotEmpty()) {
                showError(problems.joinToString("\n") { problemMessage(it) })
                return@authorize
            }

            errorView.text = ""
            val schedule = model.build()
            io {
                runBlocking { repository.upsertSchedule(schedule) }
                onUi {
                    Toast.makeText(this, R.string.rules_schedule_saved, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun confirmDelete() {
        val model = model ?: return
        RuleChangeGate.authorize(this) { granted ->
            if (!granted) {
                showError(getString(R.string.rules_change_denied))
                return@authorize
            }
            AlertDialog.Builder(this)
                .setMessage(R.string.schedule_editor_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ -> delete(model.id) }
                .show()
        }
    }

    private fun delete(id: String) {
        io {
            runBlocking { repository.removeSchedule(id) }
            onUi {
                Toast.makeText(this, R.string.schedule_editor_deleted, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun problemMessage(problem: ScheduleProblem): String = when (problem) {
        ScheduleProblem.NoDays -> getString(R.string.schedule_error_no_days)
        ScheduleProblem.NoWindows -> getString(R.string.schedule_error_no_windows)
        is ScheduleProblem.ZeroLengthWindow ->
            getString(R.string.schedule_error_zero_length, problem.windowIndex + 1)
        is ScheduleProblem.OverlappingWindows ->
            getString(
                R.string.schedule_error_overlap,
                problem.firstIndex + 1,
                problem.secondIndex + 1,
            )
    }

    private fun showError(message: String) {
        errorView.text = message
    }

    private fun dayLabel(day: DayOfWeek): Int = when (day) {
        DayOfWeek.MONDAY -> R.string.day_monday
        DayOfWeek.TUESDAY -> R.string.day_tuesday
        DayOfWeek.WEDNESDAY -> R.string.day_wednesday
        DayOfWeek.THURSDAY -> R.string.day_thursday
        DayOfWeek.FRIDAY -> R.string.day_friday
        DayOfWeek.SATURDAY -> R.string.day_saturday
        DayOfWeek.SUNDAY -> R.string.day_sunday
    }

    private fun io(block: () -> Unit) {
        Thread(block).apply {
            name = "schedule-editor-io"
            isDaemon = true
        }.start()
    }

    private fun onUi(block: () -> Unit) = runOnUiThread(block)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_SCHEDULE_ID = "extra_schedule_id"
        private const val EXTRA_EDITING = "extra_editing"

        fun newScheduleIntent(context: Context): Intent =
            Intent(context, ScheduleEditorActivity::class.java)
                .putExtra(EXTRA_SCHEDULE_ID, UUID.randomUUID().toString())
                .putExtra(EXTRA_EDITING, false)

        fun editIntent(context: Context, scheduleId: String): Intent =
            Intent(context, ScheduleEditorActivity::class.java)
                .putExtra(EXTRA_SCHEDULE_ID, scheduleId)
                .putExtra(EXTRA_EDITING, true)
    }
}
