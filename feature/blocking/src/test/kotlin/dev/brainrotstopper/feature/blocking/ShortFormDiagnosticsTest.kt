package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.model.shortform.Verdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortFormDiagnosticsTest {

    @After
    fun tearDown() {
        ShortFormDiagnostics.resetForTests()
    }

    @Test
    fun `ring buffer keeps the newest entries in order`() {
        repeat(ShortFormDiagnostics.CAPACITY + 5) { index ->
            ShortFormDiagnostics.record(entry(index.toLong()))
        }

        val snapshot = ShortFormDiagnostics.snapshot()
        assertEquals(ShortFormDiagnostics.CAPACITY, snapshot.size)
        assertEquals(5L, snapshot.first().atEpochMillis)
        assertEquals((ShortFormDiagnostics.CAPACITY + 4).toLong(), snapshot.last().atEpochMillis)
    }

    @Test
    fun `listener connection state is exposed`() {
        assertFalse(ShortFormDiagnostics.listenerConnected)
        ShortFormDiagnostics.onListenerConnected()
        assertTrue(ShortFormDiagnostics.listenerConnected)
        ShortFormDiagnostics.onListenerDisconnected()
        assertFalse(ShortFormDiagnostics.listenerConnected)
    }

    @Test
    fun `reset clears entries and the last observation time`() {
        ShortFormDiagnostics.record(entry(1L))
        ShortFormDiagnostics.resetForTests()

        assertTrue(ShortFormDiagnostics.snapshot().isEmpty())
        assertEquals(null, ShortFormDiagnostics.lastObservationAtEpochMillis)
    }

    private fun entry(at: Long) = ShortFormDiagnosticEntry(
        atEpochMillis = at,
        packageName = "com.google.android.youtube",
        durationMs = 30_000L,
        verdict = Verdict.SUSPECTED,
    )
}
