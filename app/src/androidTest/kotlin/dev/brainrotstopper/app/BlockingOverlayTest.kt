package dev.brainrotstopper.app

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotstopper.core.data.RoomRuleRepository
import dev.brainrotstopper.core.data.RoomUnlockGrantRepository
import dev.brainrotstopper.core.data.db.BrainrotDatabase
import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.TimeWindow
import dev.brainrotstopper.feature.blocking.BlockOverlayController
import dev.brainrotstopper.feature.blocking.BlockingRuntime
import dev.brainrotstopper.feature.blocking.BrainrotAccessibilityService
import java.time.DayOfWeek
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator proof for M4 on API 31 and API 36.
 *
 * Enables the real AccessibilityService through the secure settings (the same grant the user
 * gives in Settings), blocks a real system app with a rule, and asserts:
 * 1. the `TYPE_ACCESSIBILITY_OVERLAY` window is attached;
 * 2. a back key press does not remove it;
 * 3. the unlock path writes an UnlockGrant and removes the overlay.
 *
 * The unlock gate is the documented M4 placeholder; M7 replaces it with password
 * verification. The grant/dismiss flow asserted here is the same one M7 keeps.
 */
@RunWith(AndroidJUnit4::class)
class BlockingOverlayTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /**
     * The default test UiAutomation suppresses other accessibility services, which is why the
     * service never bound in the first CI attempt. Opt out for this class so the system can
     * bind [BrainrotAccessibilityService] while the test drives it.
     */
    private val uiAutomation get() = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
    )

    private lateinit var database: BrainrotDatabase
    private lateinit var rules: RoomRuleRepository
    private lateinit var grants: RoomUnlockGrantRepository

    @Before
    fun setUp() {
        database = BrainrotDatabase.create(context)
        rules = RoomRuleRepository(database)
        grants = RoomUnlockGrantRepository(database)

        enableAccessibilityService()
        assertEquals(
            "enabled_accessibility_services must store our component",
            BrainrotAccessibilityService.component(context).flattenToString(),
            readSecureSetting("enabled_accessibility_services"),
        )
        assertEquals(
            "accessibility_enabled must be 1",
            "1",
            readSecureSetting("accessibility_enabled"),
        )
        assertTrue(
            "blocking runtime must attach to the running service",
            waitUntil { BlockingRuntime.isAttached() },
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            rules.removeRule(BLOCKED_PACKAGE)
            rules.removeSchedule(SCHEDULE_ID)
            grants.active(nowEpochMillis = 0L).forEach { grants.revoke(it.id) }
        }
        shell("input keyevent KEYCODE_HOME")
        disableAccessibilityService()
    }

    @Test
    fun blockedAppShowsOverlayBackDoesNotDismissAndUnlockHidesIt() {
        runBlocking {
            rules.upsertSchedule(
                BlockSchedule(
                    id = SCHEDULE_ID,
                    label = "QA always on",
                    daysOfWeek = DayOfWeek.entries.toSet(),
                    windows = listOf(
                        TimeWindow(0, 12 * 60),
                        TimeWindow(12 * 60, 23 * 60 + 59),
                        TimeWindow(23 * 60 + 59, 0),
                    ),
                ),
            )
            rules.upsertRule(
                AppRule(
                    packageName = BLOCKED_PACKAGE,
                    displayName = "Settings",
                    scheduleIds = setOf(SCHEDULE_ID),
                ),
            )
        }

        // Leave the current window, then enter the blocked app so a window state change fires.
        shell("input keyevent KEYCODE_HOME")
        SystemClock.sleep(WINDOW_SETTLE_MILLIS)
        // Launch as the shell user: an instrumentation process has no visible activity and
        // is subject to background-activity-start restrictions on API 31+.
        shell("am start -W -a android.settings.SETTINGS")

        assertTrue("overlay must appear for a blocked package", waitUntil { overlayPresent() })

        shell("input keyevent KEYCODE_BACK")
        SystemClock.sleep(WINDOW_SETTLE_MILLIS)
        assertTrue("back must not dismiss the overlay", overlayPresent())

        assertTrue(
            "unlock request must be accepted by the attached service",
            BlockingRuntime.requestUnlock(BLOCKED_PACKAGE),
        )
        assertTrue("unlock must remove the overlay", waitUntil { !overlayPresent() })
        assertTrue(
            "unlock must leave an active grant",
            BlockingRuntime.isUnlocked(BLOCKED_PACKAGE),
        )
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

    private fun readSecureSetting(key: String): String =
        shell("settings get --user 0 secure $key").trim()

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
        const val SCHEDULE_ID = "qa-always-on"
        const val POLL_INTERVAL_MILLIS = 200L
        const val WINDOW_SETTLE_MILLIS = 500L
    }
}
