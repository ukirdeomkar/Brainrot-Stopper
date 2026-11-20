package dev.brainrotstopper.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionSnapshotTest {

    @Test
    fun disabledFeaturesListsOnlyDeniedGates() {
        val snapshot = PermissionSnapshot(
            mapOf(
                PermissionKind.ACCESSIBILITY to PermissionStatus.GRANTED,
                PermissionKind.USAGE_ACCESS to PermissionStatus.DENIED,
                PermissionKind.NOTIFICATION_LISTENER to PermissionStatus.GRANTED,
                PermissionKind.POST_NOTIFICATIONS to PermissionStatus.NOT_APPLICABLE,
                PermissionKind.BATTERY_OPTIMIZATION to PermissionStatus.GRANTED,
            ),
        )

        assertEquals(listOf(PermissionKind.USAGE_ACCESS), snapshot.disabledFeatures())
        assertTrue(snapshot.isBlockingEnabled)
        assertFalse(snapshot.areTimersEnabled)
        assertTrue(snapshot.isShortFormDetectionEnabled)
        assertTrue(snapshot.isStatusNotificationVisible)
        assertTrue(snapshot.isDegraded)
        assertEquals(emptyList<PermissionKind>(), snapshot.recommendations())
    }

    @Test
    fun notApplicablePostNotificationsDoesNotDisable() {
        val snapshot = PermissionSnapshot(
            mapOf(
                PermissionKind.POST_NOTIFICATIONS to PermissionStatus.NOT_APPLICABLE,
            ),
        )

        assertFalse(PermissionKind.POST_NOTIFICATIONS in snapshot.disabledFeatures())
        assertTrue(snapshot.isStatusNotificationVisible)
    }

    @Test
    fun deniedPostNotificationsDisablesStatusNotification() {
        val snapshot = PermissionSnapshot(
            mapOf(
                PermissionKind.POST_NOTIFICATIONS to PermissionStatus.DENIED,
            ),
        )

        assertTrue(PermissionKind.POST_NOTIFICATIONS in snapshot.disabledFeatures())
        assertFalse(snapshot.isStatusNotificationVisible)
    }

    @Test
    fun batteryDenialIsARecommendationNotADisabledFeature() {
        val snapshot = PermissionSnapshot(
            mapOf(
                PermissionKind.ACCESSIBILITY to PermissionStatus.GRANTED,
                PermissionKind.USAGE_ACCESS to PermissionStatus.GRANTED,
                PermissionKind.NOTIFICATION_LISTENER to PermissionStatus.GRANTED,
                PermissionKind.POST_NOTIFICATIONS to PermissionStatus.NOT_APPLICABLE,
                PermissionKind.BATTERY_OPTIMIZATION to PermissionStatus.DENIED,
            ),
        )

        assertEquals(emptyList<PermissionKind>(), snapshot.disabledFeatures())
        assertEquals(listOf(PermissionKind.BATTERY_OPTIMIZATION), snapshot.recommendations())
        assertFalse(snapshot.isFullyGranted)
        assertFalse(snapshot.isDegraded)
    }

    @Test
    fun fullyGrantedWhenEveryFeatureAndRecommendationIsSatisfied() {
        val snapshot = PermissionSnapshot(
            mapOf(
                PermissionKind.ACCESSIBILITY to PermissionStatus.GRANTED,
                PermissionKind.USAGE_ACCESS to PermissionStatus.GRANTED,
                PermissionKind.NOTIFICATION_LISTENER to PermissionStatus.GRANTED,
                PermissionKind.POST_NOTIFICATIONS to PermissionStatus.NOT_APPLICABLE,
                PermissionKind.BATTERY_OPTIMIZATION to PermissionStatus.GRANTED,
            ),
        )

        assertTrue(snapshot.isFullyGranted)
        assertFalse(snapshot.isDegraded)
        assertEquals(emptyList<PermissionKind>(), snapshot.disabledFeatures())
        assertEquals(emptyList<PermissionKind>(), snapshot.recommendations())
    }

    @Test
    fun unknownKindDefaultsToDenied() {
        val snapshot = PermissionSnapshot(emptyMap())

        assertEquals(PermissionStatus.DENIED, snapshot.status(PermissionKind.ACCESSIBILITY))
        assertEquals(4, snapshot.disabledFeatures().size)
        assertEquals(listOf(PermissionKind.BATTERY_OPTIMIZATION), snapshot.recommendations())
    }
}
