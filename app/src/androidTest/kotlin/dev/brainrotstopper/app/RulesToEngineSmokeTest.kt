package dev.brainrotstopper.app

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotstopper.core.data.RuleRepository
import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockReason
import dev.brainrotstopper.feature.blocking.BlockOverlayController
import dev.brainrotstopper.feature.blocking.BlockingRuntime
import dev.brainrotstopper.feature.blocking.BrainrotAccessibilityService
import dev.brainrotstopper.feature.rules.RulesStore
import dev.brainrotstopper.feature.rules.ScheduleEditorModel
import java.time.DayOfWeek
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Combined M3 + M4 smoke test: prove that a schedule and rule written through the M3 editor
 * model and store are the same rows the M4 engine reads and enforces.
 *
 * Each feature was QA-passed in isolation, but the merged tree had never been built or run
 * together. This test covers the seam the separate suites miss: [ScheduleEditorModel] (the
 * object the editor's save path builds) -> [RulesStore] (the Room database every rules screen
 * shares) -> [BlockingRuntime] (the cached engine in the AccessibilityService). It stops at
 * "the overlay is attached and the cached verdict says SCHEDULE"; the back key and unlock
 * dismissal are covered by `BlockingOverlayTest`.
 */
@RunWith(AndroidJUnit4::class)
class RulesToEngineSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    // The default test UiAutomation suppresses other accessibility services; opt out so the
    // system can bind BrainrotAccessibilityService while this test drives it.
    private val uiAutomation get() = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
    )

    private lateinit var rules: RuleRepository

    @Before
    fun setUp() {
        rules = RulesStore.repository(context)
        cleanUpRows()
        enableAccessibilityService()
        assertTrue(
            "blocking runtime must attach to the running service",
            waitUntil { BlockingRuntime.isAttached() },
        )
    }

    @After
    fun tearDown() {
        cleanUpRows()
        shell("input keyevent KEYCODE_HOME")
        disableAccessibilityService()
    }

    @Test
    fun editorScheduleIsEnforcedByTheBlockingEngine() {
        // Build the schedule exactly as ScheduleEditorActivity.save() does.
        val model = ScheduleEditorModel(SCHEDULE_ID, initial = null).apply {
            label = "Combined smoke"
            days.addAll(DayOfWeek.entries)
            setWindow(0, 0, 12 * 60)
            addWindow()
            setWindow(1, 12 * 60, 23 * 60 + 59)
            addWindow()
            setWindow(2, 23 * 60 + 59, 0)
        }
        assertTrue("editor model produced problems: ${model.problems()}", model.problems().isEmpty())
        val schedule = model.build()

        runBlocking {
            rules.upsertSchedule(schedule)
            rules.upsertRule(
                AppRule(
                    packageName = BLOCKED_PACKAGE,
                    displayName = "Settings",
                    scheduleIds = setOf(SCHEDULE_ID),
                ),
            )
            assertTrue(
                "the editor store must read back the saved rule",
                rules.rules().any { it.packageName == BLOCKED_PACKAGE },
            )
        }

        // Entering the blocked app must make the engine block it and attach the overlay.
        shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(WINDOW_SETTLE_MILLIS)
        shell("am start -W -a android.settings.SETTINGS")

        assertTrue("overlay must appear for the editor-created rule", waitUntil { overlayPresent() })

        val decision = BlockingRuntime.decisionFor(BLOCKED_PACKAGE)
        assertNotNull("engine must have a cached verdict for the blocked package", decision)
        assertTrue("engine must block the package", decision!!.block)
        assertEquals("the active schedule must win", BlockReason.SCHEDULE, decision.reason)
    }

    private fun cleanUpRows() {
        runBlocking {
            rules.removeRule(BLOCKED_PACKAGE)
            rules.removeSchedule(SCHEDULE_ID)
        }
    }

    private fun overlayPresent(): Boolean =
        shell("dumpsys window windows").contains(BlockOverlayController.WINDOW_TITLE)

    private fun enableAccessibilityService() {
        val component = BrainrotAccessibilityService.component(context).flattenToString()
        shell("settings put --user 0 secure enabled_accessibility_services $component")
        shell("settings put --user 0 secure accessibility_enabled 1")
    }

    private fun disableAccessibilityService() {
        shell("settings delete --user 0 secure enabled_accessibility_services")
        shell("settings put --user 0 secure accessibility_enabled 0")
    }

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor = uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader()
            .use { it.readText() }
    }

    private fun waitUntil(timeoutMillis: Long = 15_000L, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        return condition()
    }

    private companion object {
        const val BLOCKED_PACKAGE = "com.android.settings"
        const val SCHEDULE_ID = "qa-editor-to-engine"
        const val POLL_INTERVAL_MILLIS = 200L
        const val WINDOW_SETTLE_MILLIS = 500L
    }
}
