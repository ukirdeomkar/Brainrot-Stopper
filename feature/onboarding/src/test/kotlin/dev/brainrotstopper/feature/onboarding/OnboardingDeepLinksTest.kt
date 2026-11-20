package dev.brainrotstopper.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingDeepLinksTest {

    private val packageName = "dev.brainrotstopper"
    private val listenerComponent =
        "dev.brainrotstopper/dev.brainrotstopper.feature.blocking.BrainrotNotificationListenerService"

    @Test
    fun accessibilityMapsToAccessibilitySettings() {
        assertEquals(
            DeepLink(DeepLinkType.ACCESSIBILITY_SETTINGS),
            OnboardingDeepLinks.linkFor(PermissionKind.ACCESSIBILITY, packageName, listenerComponent),
        )
    }

    @Test
    fun usageAccessMapsToUsageAccessSettings() {
        assertEquals(
            DeepLink(DeepLinkType.USAGE_ACCESS_SETTINGS),
            OnboardingDeepLinks.linkFor(PermissionKind.USAGE_ACCESS, packageName, listenerComponent),
        )
    }

    @Test
    fun notificationListenerMapsToTheDetailPageForOurComponent() {
        assertEquals(
            DeepLink(
                type = DeepLinkType.NOTIFICATION_LISTENER_DETAIL,
                listenerComponent = listenerComponent,
            ),
            OnboardingDeepLinks.linkFor(
                PermissionKind.NOTIFICATION_LISTENER,
                packageName,
                listenerComponent,
            ),
        )
    }

    @Test
    fun batteryMapsToTheExemptionRequestForOurPackage() {
        assertEquals(
            DeepLink(
                type = DeepLinkType.BATTERY_REQUEST_EXEMPTION,
                packageName = packageName,
            ),
            OnboardingDeepLinks.linkFor(
                PermissionKind.BATTERY_OPTIMIZATION,
                packageName,
                listenerComponent,
            ),
        )
    }

    @Test
    fun postNotificationsHasNoSettingsDeepLink() {
        assertNull(
            OnboardingDeepLinks.linkFor(
                PermissionKind.POST_NOTIFICATIONS,
                packageName,
                listenerComponent,
            ),
        )
    }
}
