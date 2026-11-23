package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.shortform.MediaObservation
import dev.brainrotstopper.core.model.shortform.Verdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortFormDetectionCoordinatorTest {

    private val packageName = "com.google.android.youtube"
    private var clock = 1_000_000L

    private var globalEnabled = true
    private var foreground = true
    private var unlocked = false
    private var rule: AppRule? = rule()

    private val blocked = mutableListOf<String>()

    private val coordinator = ShortFormDetectionCoordinator(
        isGloballyEnabled = { globalEnabled },
        ruleFor = { rule },
        isForeground = { foreground },
        isUnlocked = { unlocked },
        onBlock = { blocked.add(it) },
        nowEpochMillis = { clock },
    )

    @After
    fun tearDown() {
        ShortFormDiagnostics.resetForTests()
    }

    @Test
    fun `unknown duration never blocks even with many clips`() {
        var t = 0L
        repeat(5) { index ->
            val verdict = coordinator.onObservation(
                observation(durationMs = null, clipId = "clip-$index", timestampMs = t),
            )
            assertEquals(Verdict.UNKNOWN, verdict)
            t += 20_000L
        }
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun `long-form playback never blocks`() {
        var t = 0L
        repeat(5) { index ->
            val verdict = coordinator.onObservation(
                observation(durationMs = 600_000L, clipId = "long-$index", timestampMs = t),
            )
            assertEquals(Verdict.NOT_SHORT_FORM, verdict)
            t += 20_000L
        }
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun `a single short ad stays suspected`() {
        assertEquals(
            Verdict.SUSPECTED,
            coordinator.onObservation(observation(durationMs = 30_000L, clipId = "ad", timestampMs = 0L)),
        )
        assertEquals(
            Verdict.SUSPECTED,
            coordinator.onObservation(observation(durationMs = 30_000L, clipId = "ad", timestampMs = 20_000L)),
        )
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun `two distinct short clips confirm and block the foreground app`() {
        coordinator.onObservation(observation(durationMs = 30_000L, clipId = "clip-a", timestampMs = 0L))
        val verdict =
            coordinator.onObservation(observation(durationMs = 30_000L, clipId = "clip-b", timestampMs = 11_000L))

        assertEquals(Verdict.CONFIRMED, verdict)
        assertEquals(listOf(packageName), blocked)
    }

    @Test
    fun `a background session confirms but never blocks`() {
        foreground = false
        coordinator.onObservation(observation(durationMs = 30_000L, clipId = "clip-a", timestampMs = 0L))
        val verdict =
            coordinator.onObservation(observation(durationMs = 30_000L, clipId = "clip-b", timestampMs = 11_000L))

        assertEquals(Verdict.CONFIRMED, verdict)
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun `an active unlock grant suppresses the block`() {
        unlocked = true
        coordinator.onObservation(observation(durationMs = 30_000L, clipId = "clip-a", timestampMs = 0L))
        coordinator.onObservation(observation(durationMs = 30_000L, clipId = "clip-b", timestampMs = 11_000L))

        assertTrue(blocked.isEmpty())
    }

    @Test
    fun `the global kill switch disables detection and records unknown`() {
        globalEnabled = false
        coordinator.onObservation(observation(durationMs = 30_000L, clipId = "clip-a", timestampMs = 0L))
        val verdict =
            coordinator.onObservation(observation(durationMs = 30_000L, clipId = "clip-b", timestampMs = 11_000L))

        assertEquals(Verdict.UNKNOWN, verdict)
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun `a rule without the short-form flag is not observed`() {
        rule = rule(shortFormDetection = false)
        coordinator.onObservation(observation(durationMs = 30_000L, clipId = "clip-a", timestampMs = 0L))
        val verdict =
            coordinator.onObservation(observation(durationMs = 30_000L, clipId = "clip-b", timestampMs = 11_000L))

        assertEquals(Verdict.UNKNOWN, verdict)
        assertTrue(blocked.isEmpty())
    }

    @Test
    fun `every observation is recorded in the diagnostics ring buffer`() {
        coordinator.onObservation(observation(durationMs = 30_000L, clipId = "clip-a", timestampMs = 0L))
        coordinator.onObservation(observation(durationMs = null, clipId = "clip-b", timestampMs = 1_000L))

        val entries = ShortFormDiagnostics.snapshot()
        assertEquals(2, entries.size)
        assertEquals(packageName, entries[0].packageName)
        assertTrue(entries[0].hasDuration)
        assertEquals(30_000L, entries[0].durationMs)
        assertEquals(Verdict.SUSPECTED, entries[0].verdict)
        assertFalse(entries[1].hasDuration)
        assertEquals(Verdict.UNKNOWN, entries[1].verdict)
    }

    private fun observation(durationMs: Long?, clipId: String?, timestampMs: Long): MediaObservation {
        clock = 1_000_000L + timestampMs
        return MediaObservation(
            packageName = packageName,
            durationMs = durationMs,
            isPlaying = true,
            clipId = clipId,
            timestampMs = timestampMs,
        )
    }

    private fun rule(shortFormDetection: Boolean = true): AppRule =
        AppRule(
            packageName = packageName,
            displayName = "YouTube",
            shortFormDetection = shortFormDetection,
        )
}
