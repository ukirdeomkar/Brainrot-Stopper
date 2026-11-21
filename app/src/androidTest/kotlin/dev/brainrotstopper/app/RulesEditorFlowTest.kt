package dev.brainrotstopper.app

import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotstopper.core.data.RuleRepository
import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.feature.rules.AppPickerActivity
import dev.brainrotstopper.feature.rules.RuleEditorActivity
import dev.brainrotstopper.feature.rules.RulesActivity
import dev.brainrotstopper.feature.rules.RulesStore
import dev.brainrotstopper.feature.rules.ScheduleEditorActivity
import java.time.DayOfWeek
import java.util.concurrent.ConcurrentHashMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator tests for the M3 rules editor. They run on the CI API 31 + API 36 matrix and the
 * VPS KVM harness. Repository state is injected so these tests never touch the app database
 * used by [MainActivitySmokeTest].
 */
@RunWith(AndroidJUnit4::class)
class RulesEditorFlowTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var repository: FakeRuleRepository

    @Before
    fun installFakeRepository() {
        repository = FakeRuleRepository()
        RulesStore.setRepositoryForTests(repository)
    }

    @After
    fun clearFakeRepository() {
        RulesStore.setRepositoryForTests(null)
    }

    @Test
    fun createsAScheduleThroughTheEditorAndKeepsItAfterReopening() {
        ActivityScenario.launch<ScheduleEditorActivity>(
            ScheduleEditorActivity.newScheduleIntent(context),
        ).use { scenario ->
            await("schedule editor renders") { tagExists(scenario, "day_MONDAY") }

            scenario.onActivity { activity ->
                activity.window.decorView.findViewWithTag<EditText>("schedule_label")
                    .setText("Deep work")
                activity.window.decorView.findViewWithTag<CheckBox>("day_MONDAY").isChecked = true
                activity.window.decorView.findViewWithTag<CheckBox>("day_FRIDAY").isChecked = true
            }
            scenario.onActivity { activity ->
                activity.window.decorView.findViewWithTag<android.widget.Button>("schedule_save")
                    .performClick()
            }

            await("schedule is stored") { repository.snapshotSchedules().isNotEmpty() }
        }

        val stored = repository.snapshotSchedules().single()
        assertEquals("Deep work", stored.label)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), stored.daysOfWeek)

        // Reopening the list reads the stored state back, which is the UI half of the
        // restart requirement; Room persistence across process death is covered by
        // RoomRuleRepositoryTest.
        ActivityScenario.launch(RulesActivity::class.java).use { scenario ->
            await("rules list renders the schedule") {
                tagExists(scenario, "schedule_row_${stored.id}")
            }
            scenario.onActivity { activity ->
                val row = activity.window.decorView.findViewWithTag<LinearLayout>("schedule_row_${stored.id}")
                assertTrue("schedule row must show the label", rowText(row).contains("Deep work"))
            }
        }
    }

    @Test
    fun refusesToSaveAScheduleWithNoWindows() {
        ActivityScenario.launch<ScheduleEditorActivity>(
            ScheduleEditorActivity.newScheduleIntent(context),
        ).use { scenario ->
            await("schedule editor renders") { tagExists(scenario, "day_MONDAY") }

            scenario.onActivity { activity ->
                activity.window.decorView.findViewWithTag<EditText>("schedule_label")
                    .setText("Empty")
                activity.window.decorView.findViewWithTag<CheckBox>("day_MONDAY").isChecked = true
                activity.window.decorView.findViewWithTag<android.widget.Button>("window_remove_0")
                    .performClick()
                activity.window.decorView.findViewWithTag<android.widget.Button>("schedule_save")
                    .performClick()
            }

            var error: String? = null
            scenario.onActivity { activity ->
                error = activity.window.decorView.findViewWithTag<TextView>("schedule_error")
                    .text
                    ?.toString()
            }
            assertFalse("empty-window error must be shown", error.isNullOrBlank())
            assertTrue(repository.snapshotSchedules().isEmpty())
        }
    }

    @Test
    fun disablesAnAppRuleFromTheList() {
        repository.putRule(AppRule(packageName = PACKAGE, displayName = "Instagram"))

        ActivityScenario.launch(RulesActivity::class.java).use { scenario ->
            await("rule row renders") { tagExists(scenario, "rule_enabled_$PACKAGE") }
            scenario.onActivity { activity ->
                activity.window.decorView.findViewWithTag<Switch>("rule_enabled_$PACKAGE")
                    .performClick()
            }
            await("rule is disabled") { repository.snapshotRules().single().enabled.not() }
        }

        assertFalse(repository.snapshotRules().single().enabled)
    }

    @Test
    fun editScreenShowsAssignedSchedulesAndSavesTheSelection() {
        repository.putSchedule(
            BlockSchedule(
                id = "work",
                label = "Work hours",
                daysOfWeek = setOf(DayOfWeek.MONDAY),
                windows = listOf(dev.brainrotstopper.core.model.TimeWindow(9 * 60, 17 * 60)),
            ),
        )
        repository.putRule(AppRule(packageName = PACKAGE, displayName = "YouTube"))

        ActivityScenario.launch<RuleEditorActivity>(
            RuleEditorActivity.intent(context, PACKAGE),
        ).use { scenario ->
            await("rule editor renders schedules") { tagExists(scenario, "rule_schedule_work") }
            scenario.onActivity { activity ->
                activity.window.decorView.findViewWithTag<CheckBox>("rule_schedule_work").isChecked = true
                activity.window.decorView.findViewWithTag<android.widget.Button>("rule_save")
                    .performClick()
            }
            await("assignment is saved") {
                repository.snapshotRules().single().scheduleIds == setOf("work")
            }
        }

        assertEquals(setOf("work"), repository.snapshotRules().single().scheduleIds)
    }

    @Test
    fun appPickerListsInstalledLauncherApps() {
        ActivityScenario.launch(AppPickerActivity::class.java).use { scenario ->
            await("picker renders installed apps") {
                var count = 0
                scenario.onActivity { activity ->
                    count = activity.window.decorView
                        .findViewById<LinearLayout>(dev.brainrotstopper.feature.rules.R.id.app_picker_container)
                        .childCount
                }
                count > 0
            }
        }
    }

    private fun tagExists(scenario: ActivityScenario<*>, tag: String): Boolean {
        var exists = false
        scenario.onActivity { activity -> exists = activity.window.decorView.findViewWithTag<View>(tag) != null }
        return exists
    }

    private fun rowText(row: LinearLayout): String = buildString {
        for (index in 0 until row.childCount) {
            val child = row.getChildAt(index)
            if (child is TextView) append(child.text).append('\n')
        }
    }

    private fun await(what: String, timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private companion object {
        const val PACKAGE = "com.instagram.android"
    }
}

/** In-memory [RuleRepository] so editor tests are independent of the app database. */
private class FakeRuleRepository : RuleRepository {

    private val rules = ConcurrentHashMap<String, AppRule>()
    private val schedules = ConcurrentHashMap<String, BlockSchedule>()

    override suspend fun rules(): List<AppRule> = snapshotRules()

    override suspend fun upsertRule(rule: AppRule) {
        rules[rule.packageName] = rule
    }

    override suspend fun removeRule(packageName: String) {
        rules.remove(packageName)
    }

    override suspend fun schedules(): List<BlockSchedule> = snapshotSchedules()

    override suspend fun upsertSchedule(schedule: BlockSchedule) {
        schedules[schedule.id] = schedule
    }

    override suspend fun removeSchedule(id: String) {
        schedules.remove(id)
    }

    fun snapshotRules(): List<AppRule> = rules.values.toList()

    fun snapshotSchedules(): List<BlockSchedule> = schedules.values.toList()

    fun putRule(rule: AppRule) {
        rules[rule.packageName] = rule
    }

    fun putSchedule(schedule: BlockSchedule) {
        schedules[schedule.id] = schedule
    }
}
