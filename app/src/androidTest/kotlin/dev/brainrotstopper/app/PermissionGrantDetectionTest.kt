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
        // Keep any listener that the emulator image already enabled (for example the launcher).
        // `settings put` replaces the whole list, so merge instead of overwriting.
        val existing = readListeners()
        val enabledValue = if (existing.isBlank()) component else "$existing:$component"

        writeListeners(enabledValue)
        awaitListenerSetting(component, enabled = true, desiredValue = enabledValue)
        awaitStatus(PermissionKind.NOTIFICATION_LISTENER, PermissionStatus.GRANTED)

        writeListeners(existing)
        awaitListenerSetting(component, enabled = false, desiredValue = existing)
        awaitStatus(PermissionKind.NOTIFICATION_LISTENER, PermissionStatus.DENIED)
    }

    @Test
    fun accessibilityFollowsTheEnabledServiceList() {
        val component = BrainrotAccessibilityService.component(context).flattenToString()

        shell("settings put --user 0 secure enabled_accessibility_services $component")
        shell("settings put --user 0 secure accessibility_enabled 1")
        val stored = shell("settings get --user 0 secure enabled_accessibility_services").trim()
        val enabled = shell("settings get --user 0 secure accessibility_enabled").trim()
        assertEquals("accessibility service setting must store our component", component, stored)
        assertEquals("accessibility_enabled must be 1", "1", enabled)
        awaitStatus(PermissionKind.ACCESSIBILITY, PermissionStatus.GRANTED)

        shell("settings delete --user 0 secure enabled_accessibility_services")
        shell("settings put --user 0 secure accessibility_enabled 0")
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

    /**
     * Waits for `enabled_notification_listeners` to list our component (or drop it).
     *
     * `settings put` commits the value, but the `settings` CLI serves reads from the
     * system_server copy of `Settings.Secure`, whose cache is invalidated asynchronously by a
     * ContentObserver. A single read straight after the write can therefore return the previous
     * list (OMK-574: API 36). Poll instead, and re-apply the write periodically so a genuinely
     * dropped write cannot masquerade as a slow one.
     */
    private fun awaitListenerSetting(component: String, enabled: Boolean, desiredValue: String) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var attempts = 0
        var stored = readListeners()
        while (isComponentListed(stored, component) != enabled &&
            System.currentTimeMillis() < deadline
        ) {
            if (attempts > 0 && attempts % REWRITE_EVERY_ATTEMPTS == 0) {
                writeListeners(desiredValue)
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
            stored = readListeners()
            attempts++
        }
        val verb = if (enabled) "contain" else "not contain"
        assertEquals(
            "listener setting must $verb our component, got: $stored",
            enabled,
            isComponentListed(stored, component),
        )
    }

    private fun isComponentListed(stored: String, component: String): Boolean =
        stored.split(':').map { it.trim() }.contains(component)

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
        removeOurListener()
        shell("settings delete --user 0 secure enabled_accessibility_services")
        shell("settings put --user 0 secure accessibility_enabled 0")
    }

    private fun readListeners(): String =
        shell("settings get --user 0 secure enabled_notification_listeners").trim()

    private fun writeListeners(value: String) {
        if (value.isBlank()) {
            shell("settings delete --user 0 secure enabled_notification_listeners")
        } else {
            shell("settings put --user 0 secure enabled_notification_listeners $value")
        }
    }

    /** Removes only our component so a pre-enabled listener on the image stays in place. */
    private fun removeOurListener() {
        val component = BrainrotNotificationListenerService.component(context).flattenToString()
        val current = readListeners()
        if (current.isBlank()) return
        val remaining = current.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != component }
        writeListeners(remaining.joinToString(":"))
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_INTERVAL_MILLIS = 100L

        /** Re-issue the write every 500 ms while the read-back has not converged. */
        const val REWRITE_EVERY_ATTEMPTS = 5
    }
}
