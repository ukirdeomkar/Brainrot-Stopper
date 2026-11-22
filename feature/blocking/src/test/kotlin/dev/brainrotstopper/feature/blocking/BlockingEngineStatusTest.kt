package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.feature.timers.UsageAccessStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingEngineStatusTest {

    @Test
    fun engineIsRunningOnlyWhenAttachedAndLoaded() {
        assertFalse(BlockingEngineStatus().isEngineRunning)
        assertFalse(
            BlockingEngineStatus(engineAttached = true, stateLoaded = false).isEngineRunning,
        )
        assertFalse(
            BlockingEngineStatus(engineAttached = false, stateLoaded = true).isEngineRunning,
        )
        assertTrue(
            BlockingEngineStatus(engineAttached = true, stateLoaded = true).isEngineRunning,
        )
    }

    @Test
    fun usageTrackingDefaultsAreSafeBeforeTheFirstPoll() {
        val status = BlockingEngineStatus()

        assertEquals(UsageAccessStatus.UNDETERMINED, status.usageAccessStatus)
        assertTrue(status.timersAvailable)
        assertEquals(0L, status.usageLastPollAtEpochMillis)
        assertFalse(status.usageLastPollFailed)
    }

    @Test
    fun startReasonParsesKnownAndUnknownExtras() {
        assertEquals(
            BlockingServiceStartReason.BOOT_COMPLETED,
            BlockingServiceStartReason.fromExtra("BOOT_COMPLETED"),
        )
        assertEquals(
            BlockingServiceStartReason.PACKAGE_REPLACED,
            BlockingServiceStartReason.fromExtra("PACKAGE_REPLACED"),
        )
        assertEquals(
            BlockingServiceStartReason.UNKNOWN,
            BlockingServiceStartReason.fromExtra(null),
        )
        assertEquals(
            BlockingServiceStartReason.UNKNOWN,
            BlockingServiceStartReason.fromExtra("not-a-reason"),
        )
    }
}
