package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.shortform.ShortFormDetectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortFormDetectionPolicyTest {

    private val rule = AppRule(
        packageName = "com.instagram.android",
        displayName = "Instagram",
        shortFormDetection = true,
    )

    @Test
    fun `detection needs the global switch, an enabled rule, and the flag`() {
        assertTrue(ShortFormDetectionPolicy.shouldDetect(globalEnabled = true, rule = rule))
        assertFalse(ShortFormDetectionPolicy.shouldDetect(globalEnabled = false, rule = rule))
        assertFalse(
            ShortFormDetectionPolicy.shouldDetect(
                globalEnabled = true,
                rule = rule.copy(enabled = false),
            ),
        )
        assertFalse(
            ShortFormDetectionPolicy.shouldDetect(
                globalEnabled = true,
                rule = rule.copy(shortFormDetection = false),
            ),
        )
        assertFalse(ShortFormDetectionPolicy.shouldDetect(globalEnabled = true, rule = null))
    }

    @Test
    fun `an absent or negative duration is unknown and not usable`() {
        assertNull(ShortFormDetectionPolicy.usableDurationMs(0L))
        assertNull(ShortFormDetectionPolicy.usableDurationMs(-1L))
        assertNull(ShortFormDetectionPolicy.usableDurationMs(Long.MIN_VALUE))
    }

    @Test
    fun `a positive duration is usable unchanged`() {
        assertEquals(30_000L, ShortFormDetectionPolicy.usableDurationMs(30_000L))
        assertEquals(1L, ShortFormDetectionPolicy.usableDurationMs(1L))
    }
}
