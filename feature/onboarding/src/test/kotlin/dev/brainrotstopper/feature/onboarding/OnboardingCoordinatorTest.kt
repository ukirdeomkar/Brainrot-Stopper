package dev.brainrotstopper.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingCoordinatorTest {

    private val fixedNow = 1_700_000_000_000L

    @Test
    fun completePersistsReadyStateAndCapabilities() {
        val checker = FakePermissionChecker().apply {
            this[PermissionKind.ACCESSIBILITY] = PermissionStatus.GRANTED
            this[PermissionKind.USAGE_ACCESS] = PermissionStatus.GRANTED
            this[PermissionKind.NOTIFICATION_LISTENER] = PermissionStatus.GRANTED
            this[PermissionKind.POST_NOTIFICATIONS] = PermissionStatus.NOT_APPLICABLE
            this[PermissionKind.BATTERY_OPTIMIZATION] = PermissionStatus.GRANTED
        }
        val repository = FakeOnboardingRepository()
        val coordinator = OnboardingCoordinator(checker, repository) { fixedNow }

        assertFalse(coordinator.isCompleted())

        val completion = coordinator.complete()

        assertTrue(coordinator.isCompleted())
        assertEquals(fixedNow, completion.completedAtEpochMillis)
        assertTrue(completion.blockingEnabled)
        assertTrue(completion.timersEnabled)
        assertTrue(completion.shortFormDetectionEnabled)
        assertTrue(completion.statusNotificationsVisible)
        assertTrue(completion.batteryExempt)
        assertEquals(completion, repository.completion())
    }

    @Test
    fun completePersistsDegradedStateWhenPermissionsAreDenied() {
        val checker = FakePermissionChecker().apply {
            this[PermissionKind.POST_NOTIFICATIONS] = PermissionStatus.NOT_APPLICABLE
        }
        val repository = FakeOnboardingRepository()
        val coordinator = OnboardingCoordinator(checker, repository) { fixedNow }

        val completion = coordinator.complete()

        assertTrue("onboarding must still complete", coordinator.isCompleted())
        assertFalse(completion.blockingEnabled)
        assertFalse(completion.timersEnabled)
        assertFalse(completion.shortFormDetectionEnabled)
        assertTrue(completion.statusNotificationsVisible)
        assertFalse(completion.batteryExempt)
    }

    @Test
    fun snapshotReflectsLaterPermissionChanges() {
        val checker = FakePermissionChecker()
        val coordinator = OnboardingCoordinator(checker, FakeOnboardingRepository()) { fixedNow }
        assertFalse(coordinator.snapshot().isBlockingEnabled)

        checker[PermissionKind.ACCESSIBILITY] = PermissionStatus.GRANTED

        assertTrue(coordinator.snapshot().isBlockingEnabled)
    }
}
