package dev.brainrotstopper.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionStepCatalogTest {

    @Test
    fun everyPermissionKindHasExactlyOneStep() {
        val kinds = PermissionStepCatalog.steps.map { it.kind }

        assertEquals(kinds.size, kinds.toSet().size)
        assertEquals(PermissionKind.values().toSet(), kinds.toSet())
    }

    @Test
    fun onlyPostNotificationsUsesTheRuntimeDialog() {
        PermissionStepCatalog.steps.forEach { step ->
            val expected = if (step.kind == PermissionKind.POST_NOTIFICATIONS) {
                PermissionRequestKind.RUNTIME_PERMISSION
            } else {
                PermissionRequestKind.SETTINGS
            }
            assertEquals("request kind for ${step.kind}", expected, step.requestKind)
        }
    }

    @Test
    fun everyStepHasTextAndAnAction() {
        PermissionStepCatalog.steps.forEach { step ->
            assertTrue("title for ${step.kind}", step.titleRes != 0)
            assertTrue("explanation for ${step.kind}", step.explanationRes != 0)
            assertTrue("impact for ${step.kind}", step.deniedImpactRes != 0)
            assertTrue("action for ${step.kind}", step.actionRes != 0)
        }
    }
}
