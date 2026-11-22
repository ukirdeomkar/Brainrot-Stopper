package dev.brainrotstopper.app

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotstopper.feature.blocking.BlockingForegroundService
import dev.brainrotstopper.feature.blocking.BlockingRuntime
import dev.brainrotstopper.feature.blocking.BlockingServiceStartReason
import dev.brainrotstopper.feature.timers.UsageAccessStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator proof for the M6 usage-tracking state on API 31 and API 36.
 *
 * The Usage Access grant is toggled through `appops`, which is exactly the app-op the Settings
 * screen changes. The test does not try to synthesise foreground time: authoritative totals
 * are covered by `UsageTrackerTest`, and QA's long-running flow can compare a real app's
 * bucket with `dumpsys usagestats`.
 *
 * Covers:
 * 1. granted access is observed and the first usage poll runs;
 * 2. revoked access pauses timers, keeps the service alive, and does not crash.
 */
@RunWith(AndroidJUnit4::class)
class UsageTrackingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private val uiAutomation get() = instrumentation.getUiAutomation(
        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
    )

    @Before
    fun setUp() {
        BlockingForegroundService.stop(context)
        assertTrue(
            "service must be stopped before each test",
            waitUntil { !BlockingRuntime.status().foregroundServiceRunning },
        )
    }

    @After
    fun tearDown() {
        BlockingForegroundService.stop(context)
        setUsageAccess("allow")
    }

    @Test
    fun grantedUsageAccessIsSurfacedAndPolled() {
        setUsageAccess("allow")
        BlockingForegroundService.start(context, BlockingServiceStartReason.MANUAL)

        assertTrue(
            "foreground service must report running",
            waitUntil { BlockingRuntime.status().foregroundServiceRunning },
        )
        assertTrue(
            "granted Usage Access must be reported",
            waitUntil { BlockingRuntime.status().usageAccessStatus == UsageAccessStatus.GRANTED },
        )
        assertTrue(
            "the first authoritative usage poll must run",
            waitUntil { BlockingRuntime.status().usageLastPollAtEpochMillis > 0L },
        )
        assertTrue(BlockingRuntime.status().timersAvailable)
        assertFalse(BlockingRuntime.status().usageLastPollFailed)
    }

    @Test
    fun revokedUsageAccessPausesTimersWithoutCrashing() {
        setUsageAccess("deny")
        BlockingForegroundService.start(context, BlockingServiceStartReason.MANUAL)

        assertTrue(
            "foreground service must keep running after revocation",
            waitUntil { BlockingRuntime.status().foregroundServiceRunning },
        )
        assertTrue(
            "revoked Usage Access must be reported",
            waitUntil { BlockingRuntime.status().usageAccessStatus == UsageAccessStatus.REVOKED },
        )
        assertFalse(
            "timers must pause while Usage Access is revoked",
            BlockingRuntime.status().timersAvailable,
        )
        assertEquals(UsageAccessStatus.REVOKED, BlockingRuntime.status().usageAccessStatus)
        assertTrue(
            "the engine must still be running for schedule blocking",
            BlockingRuntime.status().isEngineRunning,
        )
    }

    private fun setUsageAccess(value: String) {
        shell("appops set ${context.packageName} GET_USAGE_STATS $value")
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
