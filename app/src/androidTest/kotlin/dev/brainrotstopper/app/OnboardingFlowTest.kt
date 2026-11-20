package dev.brainrotstopper.app

import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotstopper.feature.blocking.BrainrotNotificationListenerService
import dev.brainrotstopper.feature.onboarding.OnboardingActivity
import dev.brainrotstopper.feature.onboarding.OnboardingDeepLinks
import dev.brainrotstopper.feature.onboarding.PermissionKind
import dev.brainrotstopper.feature.onboarding.SettingsIntents
import dev.brainrotstopper.feature.onboarding.SharedPreferencesOnboardingRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import dev.brainrotstopper.feature.onboarding.R as OnboardingR

/**
 * Emulator tests for the M1 onboarding flow. They run on the real app package on the CI
 * emulator matrix (API 31 + API 36) and on the VPS KVM harness.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun resetOnboarding() {
        SharedPreferencesOnboardingRepository(context).reset()
    }

    @Test
    fun rendersEveryPermissionStepWithStatusAndAction() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                PermissionKind.values().forEach { kind ->
                    assertNotNull("row for $kind", activity.findViewWithTag<View>("row_${kind.name}"))
                    assertNotNull("status for $kind", activity.findViewWithTag<View>("status_${kind.name}"))
                    assertNotNull("action for $kind", activity.findViewWithTag<View>("action_${kind.name}"))
                }
                val summary = activity.findViewById<TextView>(OnboardingR.id.onboarding_degraded)
                assertTrue("degraded summary must be filled", summary.text.isNotEmpty())
            }
        }
    }

    @Test
    fun finishPersistsReadyState() {
        val repository = SharedPreferencesOnboardingRepository(context)
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<Button>(OnboardingR.id.onboarding_finish).performClick()
            }
        }

        assertTrue("onboarding must persist the ready state", repository.isCompleted())
        assertNotNull("completion record must be stored", repository.completion())
    }

    @Test
    fun deepLinkIntentsTargetTheRightSettingsScreens() {
        val component = BrainrotNotificationListenerService.component(context).flattenToString()

        val accessibilityIntent = SettingsIntents.toIntent(
            context,
            OnboardingDeepLinks.linkFor(PermissionKind.ACCESSIBILITY, context.packageName, component)!!,
        )
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, accessibilityIntent.action)

        val usageIntent = SettingsIntents.toIntent(
            context,
            OnboardingDeepLinks.linkFor(PermissionKind.USAGE_ACCESS, context.packageName, component)!!,
        )
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, usageIntent.action)

        val notificationIntent = SettingsIntents.toIntent(
            context,
            OnboardingDeepLinks.linkFor(
                PermissionKind.NOTIFICATION_LISTENER,
                context.packageName,
                component,
            )!!,
        )
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, notificationIntent.action)
        assertEquals(
            component,
            notificationIntent.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME),
        )

        val batteryIntent = SettingsIntents.toIntent(
            context,
            OnboardingDeepLinks.linkFor(
                PermissionKind.BATTERY_OPTIMIZATION,
                context.packageName,
                component,
            )!!,
        )
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, batteryIntent.action)
        assertEquals("package:${context.packageName}", batteryIntent.dataString)
    }

    @Test
    fun usageAccessStatusRefreshesWhenTheGrantChanges() {
        val packageName = context.packageName
        shell("appops set $packageName GET_USAGE_STATS deny")
        try {
            ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(
                        activity.getString(OnboardingR.string.status_denied),
                        activity.findViewWithTag<TextView>("status_USAGE_ACCESS").text,
                    )
                }

                shell("appops set $packageName GET_USAGE_STATS allow")
                scenario.recreate()

                scenario.onActivity { activity ->
                    assertEquals(
                        activity.getString(OnboardingR.string.status_granted),
                        activity.findViewWithTag<TextView>("status_USAGE_ACCESS").text,
                    )
                }
            }
        } finally {
            shell("appops set $packageName GET_USAGE_STATS default")
        }
    }

    private fun shell(command: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
