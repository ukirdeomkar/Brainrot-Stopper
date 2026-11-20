package dev.brainrotstopper.feature.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityMatchingTest {

    private val packageName = "dev.brainrotstopper"
    private val className = "dev.brainrotstopper.feature.blocking.BrainrotAccessibilityService"

    @Test
    fun matchesTheFullFlattenedComponent() {
        assertTrue(
            AccessibilityMatching.isGranted(
                listOf("$packageName/$className"),
                packageName,
                className,
            ),
        )
    }

    @Test
    fun matchesTheShortDotComponent() {
        assertTrue(
            AccessibilityMatching.isGranted(
                listOf("$packageName/.feature.blocking.BrainrotAccessibilityService"),
                packageName,
                className,
            ),
        )
    }

    @Test
    fun ignoresOtherPackages() {
        assertFalse(
            AccessibilityMatching.isGranted(
                listOf("com.example/$className"),
                packageName,
                className,
            ),
        )
    }

    @Test
    fun ignoresOtherServicesInTheSamePackage() {
        assertFalse(
            AccessibilityMatching.isGranted(
                listOf("$packageName/dev.brainrotstopper.feature.blocking.OtherService"),
                packageName,
                className,
            ),
        )
    }

    @Test
    fun ignoresEmptyAndMalformedIds() {
        assertFalse(AccessibilityMatching.isGranted(emptyList(), packageName, className))
        assertFalse(AccessibilityMatching.isGranted(listOf(""), packageName, className))
        assertFalse(AccessibilityMatching.isGranted(listOf(packageName), packageName, className))
    }
}
