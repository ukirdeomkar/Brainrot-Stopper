package dev.brainrotstopper.app

import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotstopper.feature.onboarding.OnboardingCompletion
import dev.brainrotstopper.feature.onboarding.SharedPreferencesOnboardingRepository
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator smoke test for the scaffold shell. This is the instrumentation gate the QA
 * harness runs on CI (API 31 and API 36) and on the VPS KVM emulator. Keep it fast and
 * deterministic: launch the real activity and assert the wired domain modules rendered.
 */
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Before
    fun markOnboardingComplete() {
        // First run opens OnboardingActivity. Mark setup complete so this smoke test asserts
        // the home shell without another activity on top.
        SharedPreferencesOnboardingRepository(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ).complete(
            OnboardingCompletion(
                completedAtEpochMillis = 0L,
                blockingEnabled = false,
                timersEnabled = false,
                shortFormDetectionEnabled = false,
                statusNotificationsVisible = true,
                batteryExempt = false,
            ),
        )
    }

    @Test
    fun launchesAndRendersWiredDomainStatus() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The scaffold reads Room on a background thread, so poll until the status renders
            // instead of asserting immediately after launch.
            val deadline = System.currentTimeMillis() + STATUS_TIMEOUT_MILLIS
            var lastText: String? = null
            while (System.currentTimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    lastText = activity.findViewById<TextView>(R.id.scaffold_status)?.text?.toString()
                }
                if (lastText?.contains("1 schedule(s) loaded") == true) break
                Thread.sleep(STATUS_POLL_INTERVAL_MILLIS)
            }

            assertNotNull("scaffold_status view must exist", lastText)
            assertTrue(
                "status text must be populated by the wired repository, got: $lastText",
                lastText!!.contains("1 schedule(s) loaded"),
            )
        }
    }

    private companion object {
        const val STATUS_TIMEOUT_MILLIS = 10_000L
        const val STATUS_POLL_INTERVAL_MILLIS = 100L
    }
}
