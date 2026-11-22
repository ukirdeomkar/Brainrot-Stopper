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
import dev.brainrotstopper.core.model.PasswordMode
import dev.brainrotstopper.core.model.TimeWindow
import dev.brainrotstopper.core.security.CredentialStore
import dev.brainrotstopper.core.security.PasswordCredential
import dev.brainrotstopper.core.security.PasswordKdf
import dev.brainrotstopper.core.security.PasswordManager
import dev.brainrotstopper.feature.blocking.BlockOverlayController
import dev.brainrotstopper.feature.blocking.BlockingRuntime
import dev.brainrotstopper.feature.blocking.BrainrotAccessibilityService
import dev.brainrotstopper.feature.blocking.UnlockResult
import dev.brainrotstopper.feature.settings.PasswordStore
import java.time.DayOfWeek
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator proof for the blocking overlay and the M8 emergency-unlock gate on API 31 and
 * API 36.
 *
 * Enables the real AccessibilityService through the secure settings (the same grant the user
 * gives in Settings), blocks a real system app with a rule, and asserts:
 * 1. the `TYPE_ACCESSIBILITY_OVERLAY` window is attached;
 * 2. a back key press does not remove it;
 * 3. a wrong password is rejected and leaves the block screen up;
 * 4. the correct password writes a time-boxed UnlockGrant and removes the overlay;
 * 5. when a short grant expires, the overlay re-arms with no window change.
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
    private lateinit var fakeStore: FakeCredentialStore
    private lateinit var fakeManager: PasswordManager

    @Before
    fun setUp() {
        database = BrainrotDatabase.create(context)
        rules = RoomRuleRepository(database)
        grants = RoomUnlockGrantRepository(database)

        // The real gate resolves the process-wide manager, so install a fast in-memory
        // credential before the AccessibilityService binds and captures it.
        fakeStore = FakeCredentialStore()
        fakeManager = PasswordManager(fakeStore, PasswordKdf(iterations = 1_000))
        fakeManager.configure(PASSWORD.toCharArray(), PasswordMode.SELF_SET)
        PasswordStore.setManagerForTests(fakeManager)

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
        assertTrue(
            "the AccessibilityService must be bound before the flow runs",
            waitUntil { BlockingRuntime.isAccessibilityServiceAttached() },
        )
    }

    @After
    fun tearDown() {
        BlockingRuntime.setSessionUnlockMillisForTests(null)
        PasswordStore.setManagerForTests(null)
        runBlocking {
            rules.removeRule(BLOCKED_PACKAGE)
            rules.removeSchedule(SCHEDULE_ID)
            grants.active(nowEpochMillis = 0L).forEach { grants.revoke(it.id) }
        }
        shell("input keyevent KEYCODE_HOME")
        disableAccessibilityService()
    }

    @Test
    fun blockedAppShowsOverlayBackDoesNotDismissAndWrongPasswordIsRejected() {
        blockSettingsApp()
        showBlockedApp()

        assertTrue("overlay must appear for a blocked package", waitUntil { overlayPresent() })

        shell("input keyevent KEYCODE_BACK")
        SystemClock.sleep(WINDOW_SETTLE_MILLIS)
        assertTrue("back must not dismiss the overlay", overlayPresent())

        // A wrong password must not create a grant and must keep the block screen up.
        assertEquals(
            "a wrong password must be rejected",
            UnlockResult.Rejected,
            runBlocking {
                BlockingRuntime.requestUnlock(BLOCKED_PACKAGE, "0000".toCharArray())
            },
        )
        assertTrue("a rejected unlock must not remove the overlay", overlayPresent())
        assertTrue(
            "a rejected unlock must not leave a grant",
            !BlockingRuntime.isUnlocked(BLOCKED_PACKAGE),
        )

        // The correct password is awaited: Success means the grant is persisted and in the
        // cache, so the grant assertion below is a real regression guard rather than a race.
        assertEquals(
            "the correct password must unlock and persist a visible grant",
            UnlockResult.Success,
            runBlocking {
                BlockingRuntime.requestUnlock(BLOCKED_PACKAGE, PASSWORD.toCharArray())
            },
        )
        assertTrue("unlock must remove the overlay", waitUntil { !overlayPresent() })
        assertTrue(
            "unlock must leave an active grant",
            waitUntil { BlockingRuntime.isUnlocked(BLOCKED_PACKAGE) },
        )
    }

    @Test
    fun shortGrantExpiresAndTheOverlayReArms() {
        BlockingRuntime.setSessionUnlockMillisForTests(SHORT_GRANT_MILLIS)
        blockSettingsApp()
        showBlockedApp()
        assertTrue("overlay must appear for a blocked package", waitUntil { overlayPresent() })

        assertEquals(
            UnlockResult.Success,
            runBlocking {
                BlockingRuntime.requestUnlock(BLOCKED_PACKAGE, PASSWORD.toCharArray())
            },
        )
        assertTrue("unlock must remove the overlay", waitUntil { !overlayPresent() })

        // The service schedules one refresh at the grant expiry; no window change is sent
        // between the unlock and this assertion, so re-arming here is the auto behaviour.
        assertTrue(
            "the overlay must come back when the grant expires",
            waitUntil(timeoutMillis = 35_000L) { overlayPresent() },
        )
        assertTrue(
            "the expired grant must no longer cover the app",
            !BlockingRuntime.isUnlocked(BLOCKED_PACKAGE),
        )
    }

    private fun blockSettingsApp() {
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
    }

    private fun showBlockedApp() {
        // Leave the current window, then enter the blocked app so a window state change fires.
        // Retry the launch: the first assertions can race the AccessibilityService binding if
        // a previous owner kept the process (and engine) warm.
        repeat(3) {
            shell("input keyevent KEYCODE_HOME")
            SystemClock.sleep(WINDOW_SETTLE_MILLIS)
            // Launch as the shell user: an instrumentation process has no visible activity and
            // is subject to background-activity-start restrictions on API 31+.
            shell("am start -W -a android.settings.SETTINGS")
            if (waitUntil(timeoutMillis = 6_000L) { overlayPresent() }) return
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

    private class FakeCredentialStore : CredentialStore {
        private var credential: PasswordCredential? = null

        override fun read(): PasswordCredential? = credential

        override fun write(credential: PasswordCredential) {
            this.credential = credential
        }

        override fun clear() {
            credential = null
        }
    }

    private companion object {
        const val BLOCKED_PACKAGE = "com.android.settings"
        const val SCHEDULE_ID = "qa-always-on"
        const val PASSWORD = "2468"
        const val SHORT_GRANT_MILLIS = 20_000L
        const val POLL_INTERVAL_MILLIS = 200L
        const val WINDOW_SETTLE_MILLIS = 500L
    }
}
