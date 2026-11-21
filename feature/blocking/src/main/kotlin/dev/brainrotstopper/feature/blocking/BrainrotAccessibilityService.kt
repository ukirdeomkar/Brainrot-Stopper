package dev.brainrotstopper.feature.blocking

import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.accessibilityservice.AccessibilityService

/**
 * Scaffold shell for the M4 blocking engine.
 *
 * M1 (onboarding) needs the service to exist so the Accessibility grant can be detected and
 * deep-linked on a real device or emulator. Event handling, the decision engine, and the
 * overlay belong to M4; this class intentionally does nothing yet.
 */
class BrainrotAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // M4: forward window changes to the blocking engine and show/hide the overlay.
    }

    override fun onInterrupt() {
        // M4: release any overlay owned by this service.
    }

    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, BrainrotAccessibilityService::class.java)
    }
}
