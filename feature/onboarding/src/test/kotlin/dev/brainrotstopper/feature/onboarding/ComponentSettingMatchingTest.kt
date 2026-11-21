package dev.brainrotstopper.feature.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentSettingMatchingTest {

    private val packageName = "dev.brainrotstopper"
    private val className = "dev.brainrotstopper.feature.blocking.BrainrotNotificationListenerService"

    @Test
    fun findsTheComponentInAColonSeparatedList() {
        assertTrue(
            ComponentSettingMatching.containsComponent(
                "com.other/Listener:$packageName/$className",
                packageName,
                className,
            ),
        )
    }

    @Test
    fun findsTheShortDotForm() {
        assertTrue(
            ComponentSettingMatching.containsComponent(
                "$packageName/.feature.blocking.BrainrotNotificationListenerService",
                packageName,
                className,
            ),
        )
    }

    @Test
    fun rejectsNullBlankAndOtherPackages() {
        assertFalse(ComponentSettingMatching.containsComponent(null, packageName, className))
        assertFalse(ComponentSettingMatching.containsComponent("", packageName, className))
        assertFalse(
            ComponentSettingMatching.containsComponent(
                "com.other/$className",
                packageName,
                className,
            ),
        )
    }

    @Test
    fun rejectsAnotherServiceInTheSamePackage() {
        assertFalse(
            ComponentSettingMatching.containsComponent(
                "$packageName/dev.brainrotstopper.feature.blocking.OtherService",
                packageName,
                className,
            ),
        )
    }
}
