package dev.brainrotstopper.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class PostNotificationRulesTest {

    @Test
    fun android12DoesNotHaveTheRuntimePermission() {
        assertEquals(
            PermissionStatus.NOT_APPLICABLE,
            PostNotificationRules.statusFor(sdkInt = 31, permissionGranted = false),
        )
    }

    @Test
    fun android13AndAboveUseTheRuntimePermission() {
        assertEquals(
            PermissionStatus.DENIED,
            PostNotificationRules.statusFor(sdkInt = 33, permissionGranted = false),
        )
        assertEquals(
            PermissionStatus.GRANTED,
            PostNotificationRules.statusFor(sdkInt = 33, permissionGranted = true),
        )
        assertEquals(
            PermissionStatus.GRANTED,
            PostNotificationRules.statusFor(sdkInt = 36, permissionGranted = true),
        )
    }
}
