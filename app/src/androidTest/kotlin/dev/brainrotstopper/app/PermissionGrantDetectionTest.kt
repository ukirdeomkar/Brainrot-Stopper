package dev.brainrotstopper.app

import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotstopper.feature.blocking.BrainrotAccessibilityService
import dev.brainrotstopper.feature.blocking.BrainrotNotificationListenerService
import dev.brainrotstopper.feature.onboarding.AndroidPermissionChecker
import dev.brainrotstopper.feature.onboarding.PermissionKind
import dev.brainrotstopper.feature.onboarding.PermissionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves that the production checker reads the real Android grant state. The tests set and
 * clear the same system state that the Settings screens toggle, then assert the checker flips.
 *
 * These run on the CI emulator (API 31 + API 36); no owner phone is involved.
 */
@RunWith(AndroidJUnit4::class)
class PermissionGrantDetectionTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val packageName get() = context.packageName
    private lateinit var checker: AndroidPermissionChecker

    @Before
    fun setUp() {
        checker = AndroidPermissionChecker(context)
        resetSystemState()
    }

    @After
    fun tearDown() {
        resetSystemState()
    }

    @Test
    fun usageAccessFollowsAppOps() {
        shell("appops set $packageName GET_USAGE_STATS allow")
        awaitStatus(PermissionKind.USAGE_ACCESS, PermissionStatus.GRANTED)

        shell("appops set $packageName GET_USAGE_STATS deny")
        awaitStatus(PermissionKind.USAGE_ACCESS, PermissionStatus.DENIED)
    }

    @Test
    fun batteryExemptionFollowsTheDeviceIdleWhitelist() {
        shell("dumpsys deviceidle whitelist +$packageName")
        awaitStatus(PermissionKind.BATTERY_OPTIMIZATION, PermissionStatus.GRANTED)

        shell("dumpsys deviceidle whitelist -$packageName")
        awaitStatus(PermissionKind.BATTERY_OPTIMIZATION, PermissionStatus.DENIED)
    }

    @Test
    fun notificationAccessFollowsTheEnabledListenerList() {
        val component = BrainrotNotificationListenerService.component(context).flattenToString()

        shell("settings put secure enabled_notification_listeners $component")
        awaitStatus(PermissionKind.NOTIFICATION_LISTENER, PermissionStatus.GRANTED)

        shell("settings delete secure enabled_notification_listeners")
        awaitStatus(PermissionKind.NOTIFICATION_LISTENER, PermissionStatus.DENIED)
    }

    @Test
    fun accessibilityFollowsTheEnabledServiceList() {
        val component = BrainrotAccessibilityService.component(context).flattenToString()

        shell("settings put secure enabled_accessibility_services $component")
        shell("settings put secure accessibility_enabled 1")
        awaitStatus(PermissionKind.ACCESSIBILITY, PermissionStatus.GRANTED)

        shell("settings delete secure enabled_accessibility_services")
        shell("settings put secure accessibility_enabled 0")
        awaitStatus(PermissionKind.ACCESSIBILITY, PermissionStatus.DENIED)
    }

    @Test
    fun postNotificationsUsesTheSdkRule() {
        val status = checker.status(PermissionKind.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < 33) {
            assertEquals(PermissionStatus.NOT_APPLICABLE, status)
        } else {
            // A fresh install has not asked yet, so DENIED is expected. The test asserts the
            // check runs and returns a concrete runtime state, not a crash or NOT_APPLICABLE.
            assertTrue(status == PermissionStatus.GRANTED || status == PermissionStatus.DENIED)
        }
    }

    private fun awaitStatus(expectedKind: PermissionKind, expected: PermissionStatus) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var actual = checker.status(expectedKind)
        while (actual != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
            actual = checker.status(expectedKind)
        }
        assertEquals("$expectedKind did not become $expected", expected, actual)
    }

    private fun resetSystemState() {
        shell("appops set $packageName GET_USAGE_STATS default")
        shell("dumpsys deviceidle whitelist -$packageName")
        shell("settings delete secure enabled_notification_listeners")
        shell("settings delete secure enabled_accessibility_services")
        shell("settings put secure accessibility_enabled 0")
    }

    private fun shell(command: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val POLL_INTERVAL_MILLIS = 100L
    }
}
