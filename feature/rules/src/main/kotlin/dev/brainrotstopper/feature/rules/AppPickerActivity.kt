package dev.brainrotstopper.feature.rules

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.brainrotstopper.core.data.RuleRepository
import dev.brainrotstopper.core.model.AppRule
import kotlinx.coroutines.runBlocking

/** Lists installed launcher apps and adds the selected ones as guarded app rules. */
class AppPickerActivity : Activity() {

    private lateinit var repository: RuleRepository
    private lateinit var container: LinearLayout
    private lateinit var emptyView: TextView
    private val checkBoxes = linkedMapOf<String, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        repository = RulesStore.repository(this)
        container = findViewById(R.id.app_picker_container)
        emptyView = findViewById(R.id.app_picker_empty)

        findViewById<Button>(R.id.app_picker_add).setOnClickListener { addSelected() }
        load()
    }

    private fun load() {
        io {
            val installed = PackageManagerAppCatalog(this).installedApps()
            val existing = runBlocking { repository.rules().map { it.packageName }.toSet() }
            val selectable = AppCatalogPolicy.select(installed, excludedPackages = existing)
            onUi { render(selectable) }
        }
    }

    private fun render(apps: List<InstalledApp>) {
        container.removeAllViews()
        checkBoxes.clear()
        emptyView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        apps.forEach { app ->
            val checkBox = CheckBox(this).apply {
                text = app.label
                tag = "pick_${app.packageName}"
                setPadding(0, dp(8), 0, dp(8))
            }
            checkBoxes[app.packageName] = checkBox
            container.addView(checkBox)
        }
    }

    private fun addSelected() {
        val selected = checkBoxes.filterValues { it.isChecked }.keys.toList()
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.app_picker_none_selected, Toast.LENGTH_SHORT).show()
            return
        }
        RuleChangeGate.authorize(this) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.rules_change_denied, Toast.LENGTH_LONG).show()
                return@authorize
            }
            val appsByPackage = checkBoxes.mapValues { it.value.text.toString() }
            io {
                runBlocking {
                    selected.forEach { packageName ->
                        repository.upsertRule(
                            AppRule(
                                packageName = packageName,
                                displayName = appsByPackage.getValue(packageName),
                            ),
                        )
                    }
                }
                onUi { finish() }
            }
        }
    }

    private fun io(block: () -> Unit) {
        Thread(block).apply {
            name = "app-picker-io"
            isDaemon = true
        }.start()
    }

    private fun onUi(block: () -> Unit) = runOnUiThread(block)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, AppPickerActivity::class.java)
    }
}
