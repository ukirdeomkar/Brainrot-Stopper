package dev.brainrotstopper.feature.rules

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleChangeGateTest {

    @After
    fun reset() {
        RuleChangeGate.authorizer = RuleChangeGate.AllowAll
    }

    @Test
    fun `the M3 default authorizer allows edits`() {
        assertTrue(RuleChangeGate.isAuthorized())
    }

    @Test
    fun `a replacement authorizer can refuse edits for M7`() {
        RuleChangeGate.authorizer = RuleChangeAuthorizer { false }

        assertFalse(RuleChangeGate.isAuthorized())
    }
}
