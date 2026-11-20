package dev.brainrotstopper.core.model.shortform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortFormTrackerTest {

    private val pkg = "com.example.shortform"

    private fun obs(
        timeMs: Long,
        durationMs: Long?,
        clipId: String? = null,
        playing: Boolean = true,
    ) = MediaObservation(
        packageName = pkg,
        durationMs = durationMs,
        isPlaying = playing,
        clipId = clipId,
        timestampMs = timeMs,
    )

    @Test
    fun `unknown duration never confirms`() {
        val tracker = ShortFormTracker()

        val verdicts = (0..30).map { second ->
            tracker.onObservation(obs(second * 1_000L, durationMs = null, clipId = "clip-$second")).verdict
        }

        assertTrue(verdicts.all { it == Verdict.UNKNOWN })
    }

    @Test
    fun `long form playback is never short form`() {
        val tracker = ShortFormTracker()

        val verdicts = (0..60).map { second ->
            tracker.onObservation(obs(second * 1_000L, durationMs = 600_000, clipId = "movie")).verdict
        }

        assertTrue(verdicts.all { it == Verdict.NOT_SHORT_FORM })
    }

    @Test
    fun `two distinct short clips with sustained playback confirm`() {
        val tracker = ShortFormTracker()

        var verdict = Verdict.UNKNOWN
        for (second in 0..10) {
            verdict = tracker.onObservation(obs(second * 1_000L, 30_000, clipId = "clip-a")).verdict
        }
        assertEquals(Verdict.SUSPECTED, verdict)

        val confirmed = tracker.onObservation(obs(11_000, 15_000, clipId = "clip-b"))
        assertEquals(Verdict.CONFIRMED, confirmed.verdict)
        assertEquals(2, confirmed.distinctClips)
        assertTrue(confirmed.shortPlaybackMs >= 10_000)
    }

    @Test
    fun `a short ad inside a long video does not confirm and is cleared by long form`() {
        val tracker = ShortFormTracker()

        tracker.onObservation(obs(0, 600_000, clipId = "movie"))
        var adVerdict = Verdict.UNKNOWN
        for (second in 1..20) {
            adVerdict = tracker.onObservation(obs(second * 1_000L, 20_000, clipId = "ad")).verdict
        }
        assertEquals(Verdict.SUSPECTED, adVerdict)

        val afterAd = tracker.onObservation(obs(21_000, 600_000, clipId = "movie"))
        assertEquals(Verdict.NOT_SHORT_FORM, afterAd.verdict)
        assertEquals(0, afterAd.shortPlaybackMs)
        assertEquals(0, afterAd.distinctClips)
    }

    @Test
    fun `a looped short clip confirms on its own after the single clip threshold`() {
        val tracker = ShortFormTracker(ShortFormConfig(singleClipConfirmMs = 45_000))

        val verdicts = (0..10).map { step ->
            tracker.onObservation(obs(step * 5_000L, 30_000, clipId = "looped-reel")).verdict
        }

        assertTrue(verdicts.contains(Verdict.CONFIRMED))
    }

    @Test
    fun `cooldown suppresses an immediate second confirmation then expires`() {
        val tracker = ShortFormTracker(ShortFormConfig(cooldownMs = 5_000))

        for (second in 0..10) {
            tracker.onObservation(obs(second * 1_000L, 30_000, clipId = "clip-a"))
        }
        assertEquals(Verdict.CONFIRMED, tracker.onObservation(obs(11_000, 15_000, clipId = "clip-b")).verdict)

        assertEquals(Verdict.COOLDOWN, tracker.onObservation(obs(12_000, 15_000, clipId = "clip-c")).verdict)

        var later = Verdict.COOLDOWN
        for (second in 17..27) {
            later = tracker.onObservation(
                obs(second * 1_000L, 30_000, clipId = if (second < 22) "clip-c" else "clip-d"),
            ).verdict
        }
        assertEquals(Verdict.CONFIRMED, later)
    }

    @Test
    fun `custom threshold changes the verdict`() {
        val tracker = ShortFormTracker(ShortFormConfig(durationThresholdMs = 60_000))

        val verdict = tracker.onObservation(obs(0, 100_000, clipId = "long-ish")).verdict

        assertEquals(Verdict.NOT_SHORT_FORM, verdict)
    }

    @Test
    fun `a gap longer than reset clears the accumulated evidence`() {
        val tracker = ShortFormTracker(ShortFormConfig(resetAfterMs = 45_000))

        for (second in 0..10) {
            tracker.onObservation(obs(second * 1_000L, 30_000, clipId = "clip-a"))
        }
        val afterGap = tracker.onObservation(obs(70_000, 30_000, clipId = "clip-b"))

        assertEquals(Verdict.SUSPECTED, afterGap.verdict)
        assertEquals(1, afterGap.distinctClips)
        assertTrue(afterGap.shortPlaybackMs < 10_000)
    }

    @Test
    fun `paused playback reports unknown and stops accrual`() {
        val tracker = ShortFormTracker()

        tracker.onObservation(obs(0, 30_000, clipId = "clip-a"))
        tracker.onObservation(obs(1_000, 30_000, clipId = "clip-a"))
        val paused = tracker.onObservation(obs(2_000, 30_000, clipId = "clip-a", playing = false))

        assertEquals(Verdict.UNKNOWN, paused.verdict)
    }

    @Test
    fun `packages are tracked independently`() {
        val tracker = ShortFormTracker()
        val other = "com.example.other"

        for (second in 0..10) {
            tracker.onObservation(obs(second * 1_000L, 30_000, clipId = "a"))
            tracker.onObservation(
                MediaObservation(other, 600_000, true, "movie", second * 1_000L),
            )
        }
        val otherVerdict = tracker.onObservation(
            MediaObservation(other, 600_000, true, "movie", 11_000L),
        ).verdict
        val confirmed = tracker.onObservation(obs(11_000, 15_000, clipId = "b"))

        assertEquals(Verdict.NOT_SHORT_FORM, otherVerdict)
        assertEquals(Verdict.CONFIRMED, confirmed.verdict)
    }

    @Test
    fun `reset removes a tracked package`() {
        val tracker = ShortFormTracker()
        for (second in 0..10) {
            tracker.onObservation(obs(second * 1_000L, 30_000, clipId = "a"))
        }

        tracker.reset(pkg)
        val afterReset = tracker.onObservation(obs(11_000, 15_000, clipId = "b"))

        assertNotEquals(Verdict.CONFIRMED, afterReset.verdict)
        assertEquals(1, afterReset.distinctClips)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `config rejects a non positive threshold`() {
        ShortFormConfig(durationThresholdMs = 0)
    }
}
