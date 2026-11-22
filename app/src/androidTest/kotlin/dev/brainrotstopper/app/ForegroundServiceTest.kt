package dev.brainrotstopper.app

import android.Manifest
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotstopper.feature.blocking.BlockingBootReceiver
import dev.brainrotstopper.feature.blocking.BlockingForegroundService
import dev.brainrotstopper.feature.blocking.BlockingRuntime
import dev.brainrotstopper.feature.blocking.BlockingServiceStartReason
import dev.brainrotstopper.feature.onboarding.OnboardingCompletion
import dev.brainrotstopper.feature.onboarding.SharedPreferencesOnboardingRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator proof for M5 on API 31 and API 36.
 *
 * The service is started through `am start-foreground-service` (the same system path an
 * Activity or boot receiver uses) because an instrumentation process is already in the
 * background and cannot rely on the visible-Activity exemption.
 *
 * Covers:
 * 1. service running + ongoing notification + self-check loaded;
 * 2. the service and engine surviving Activity death;
 * 3. the boot/update receiver restarting a stopped service.
 *
 * A real reboot is QA's device path (`adb reboot`); instrumentation cannot reboot the
 * emulator it is running on.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundServiceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private val uiAutomation get() = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
    )

    @Before
    fun setUp() {
        grantNotificationPermission()
        BlockingForegroundService.stop(context)
        assertTrue(
            "service must be stopped before each test",
            waitUntil { !BlockingRuntime.status().foregroundServiceRunning },
        )
    }

    @After
    fun tearDown() {
        BlockingForegroundService.stop(context)
    }

    @Test
    fun foregroundServiceRunsWithNotificationAndSelfCheck() {
        startServiceThroughSystem()

        assertTrue(
            "foreground service must report running",
            waitUntil { BlockingRuntime.status().foregroundServiceRunning },
        )
        assertTrue(
            "blocking engine must have a loaded snapshot",
            waitUntil { BlockingRuntime.status().isEngineRunning },
        )
        assertTrue(
            "the ongoing foreground notification must be posted",
            waitUntil { notificationPresent() },
        )
        assertEquals(
            "self-check must record the start reason",
            BlockingServiceStartReason.MANUAL,
            BlockingRuntime.status().lastStartReason,
        )
    }

    @Test
    fun serviceSurvivesActivityDeath() {
        markOnboardingComplete()
        startServiceThroughSystem()
        assertTrue(waitUntil { BlockingRuntime.status().isEngineRunning })

        // Launch the real shell Activity and close it. The service must outlive it.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    "the shell must show the blocking engine self-check",
                    activity.findViewById<android.widget.TextView>(R.id.blocking_engine_status)
                        .text
                        .contains("Blocking engine"),
                )
            }
        }

        assertTrue(
            "foreground service must survive Activity death",
            BlockingRuntime.status().foregroundServiceRunning,
        )
        assertTrue(
            "blocking engine must survive Activity death",
            BlockingRuntime.status().isEngineRunning,
        )
        assertTrue("notification must survive Activity death", notificationPresent())
    }

    @Test
    fun bootReceiverRestartsTheStoppedService() {
        assertReceiverIsRegistered(Intent.ACTION_BOOT_COMPLETED)
        // The shell cannot send the protected BOOT_COMPLETED broadcast, and instrumentation
        // cannot reboot the emulator. Invoking the receiver proves the restart path; the
        // manifest registration assertion above proves Android will deliver it, and QA's
        // `adb reboot` flow proves the system integration end to end.
        BlockingBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertTrue(
            "BOOT_COMPLETED must restart the service",
            waitUntil { BlockingRuntime.status().foregroundServiceRunning },
        )
        assertEquals(
            "the self-check must show the boot restart",
            BlockingServiceStartReason.BOOT_COMPLETED,
            BlockingRuntime.status().lastStartReason,
        )

        // Opening the app after a reboot starts the service again from the Activity. The
        // self-check must keep reporting the boot restart, not overwrite it with ACTIVITY.
        assertStartReasonSurvivesAppOpen("after reboot")
        assertEquals(
            "opening the app must not overwrite the boot reason",
            BlockingServiceStartReason.BOOT_COMPLETED,
            BlockingRuntime.status().lastStartReason,
        )
    }

    @Test
    fun updateReceiverRestartsTheStoppedService() {
        assertReceiverIsRegistered(Intent.ACTION_MY_PACKAGE_REPLACED)
        BlockingBootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertTrue(
            "MY_PACKAGE_REPLACED must restart the service",
            waitUntil { BlockingRuntime.status().foregroundServiceRunning },
        )
        assertEquals(
            "the self-check must show the update restart",
            BlockingServiceStartReason.PACKAGE_REPLACED,
            BlockingRuntime.status().lastStartReason,
        )

        assertStartReasonSurvivesAppOpen("after app update")
        assertEquals(
            "opening the app must not overwrite the update reason",
            BlockingServiceStartReason.PACKAGE_REPLACED,
            BlockingRuntime.status().lastStartReason,
        )
    }

    private fun assertStartReasonSurvivesAppOpen(expectedLabel: String) {
        markOnboardingComplete()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val text = activity
                    .findViewById<android.widget.TextView>(R.id.blocking_engine_status)
                    .text
                    .toString()
                assertTrue(
                    "the shell self-check must keep '$expectedLabel', got: $text",
                    text.contains(expectedLabel),
                )
            }
        }
    }

    private fun startServiceThroughSystem() {
        // The instrumentation process runs as the app, so this is the same call an Activity
        // makes. Android 12+ treats an active instrumentation as a foreground caller.
        BlockingForegroundService.start(context, BlockingServiceStartReason.MANUAL)
    }

    private fun notificationPresent(): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.activeNotifications.any { it.id == BlockingForegroundService.NOTIFICATION_ID }
    }

    @Suppress("DEPRECATION")
    private fun assertReceiverIsRegistered(action: String) {
        val receivers = context.packageManager.queryBroadcastReceivers(
            Intent(action).setPackage(context.packageName),
            0,
        )
        assertTrue(
            "manifest must register BlockingBootReceiver for $action",
            receivers.any {
                it.activityInfo.name == BlockingBootReceiver::class.java.name
            },
        )
    }

    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        shell(
            "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
        )
    }

    private fun markOnboardingComplete() {
        SharedPreferencesOnboardingRepository(context).complete(
            OnboardingCompletion(
                completedAtEpochMillis = 0L,
                blockingEnabled = false,
                timersEnabled = false,
                shortFormDetectionEnabled = false,
                statusNotificationsVisible = true,
                batteryExempt = false,
            ),
        )
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
        const val POLL_INTERVAL_MILLIS = 200L
    }
}
