package dev.brainrotstopper.feature.onboarding

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.brainrotstopper.feature.blocking.BrainrotNotificationListenerService

/**
 * First-run permission walkthrough. Each step detects the current grant state, deep-links to
 * the matching Settings screen, and re-checks when the user comes back. A denied permission
 * never blocks finishing: the flow persists the ready state and states what is disabled.
 */
class OnboardingActivity : Activity() {

    private lateinit var coordinator: OnboardingCoordinator
    private lateinit var stepsContainer: LinearLayout
    private lateinit var degradedView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        coordinator = OnboardingCoordinator(
            checker = AndroidPermissionChecker(applicationContext),
            repository = SharedPreferencesOnboardingRepository(applicationContext),
        )

        stepsContainer = findViewById(R.id.onboarding_steps)
        degradedView = findViewById(R.id.onboarding_degraded)
        findViewById<Button>(R.id.onboarding_recheck).setOnClickListener { refreshPermissions() }
        findViewById<Button>(R.id.onboarding_finish).setOnClickListener { completeOnboarding() }

        refreshPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (::coordinator.isInitialized) {
            // Re-check after the user returns from a Settings screen.
            refreshPermissions()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) refreshPermissions()
    }

    private fun refreshPermissions() {
        val snapshot = coordinator.snapshot()
        stepsContainer.removeAllViews()
        PermissionStepCatalog.steps.forEach { step ->
            stepsContainer.addView(buildStepRow(step, snapshot.status(step.kind)))
        }
        degradedView.text = degradedSummary(snapshot)
    }

    private fun buildStepRow(step: PermissionStep, status: PermissionStatus): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            tag = rowTag(step.kind)
        }

        val title = TextView(this).apply {
            text = getString(step.titleRes)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        val statusView = TextView(this).apply {
            text = statusLabel(status)
            tag = statusTag(step.kind)
        }
        val explanation = TextView(this).apply {
            text = getString(step.explanationRes)
            textSize = 14f
        }
        val impact = TextView(this).apply {
            text = getString(step.deniedImpactRes)
            textSize = 14f
            visibility = if (status == PermissionStatus.DENIED) View.VISIBLE else View.GONE
        }
        val action = Button(this).apply {
            text = getString(step.actionRes)
            tag = actionTag(step.kind)
            isEnabled = status == PermissionStatus.DENIED
            setOnClickListener { onStepAction(step) }
        }

        row.addView(title)
        row.addView(statusView)
        row.addView(explanation)
        row.addView(impact)
        row.addView(action)
        return row
    }

    private fun onStepAction(step: PermissionStep) {
        when (step.requestKind) {
            PermissionRequestKind.RUNTIME_PERMISSION -> requestNotifications()
            PermissionRequestKind.SETTINGS -> openSettings(step.kind)
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= PostNotificationRules.FIRST_RUNTIME_SDK) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS,
            )
        } else {
            refreshPermissions()
        }
    }

    private fun openSettings(kind: PermissionKind) {
        val listenerComponent = BrainrotNotificationListenerService.component(this).flattenToString()
        val link = OnboardingDeepLinks.linkFor(kind, packageName, listenerComponent) ?: return
        try {
            startActivity(SettingsIntents.toIntent(this, link))
        } catch (missing: ActivityNotFoundException) {
            val fallback = SettingsIntents.fallbackIntent(link)
            if (fallback == null) {
                showSettingsUnavailable()
                return
            }
            try {
                startActivity(fallback)
            } catch (alsoMissing: ActivityNotFoundException) {
                showSettingsUnavailable()
            }
        }
    }

    private fun completeOnboarding() {
        coordinator.complete()
        setResult(RESULT_OK, Intent())
        finish()
    }

    private fun degradedSummary(snapshot: PermissionSnapshot): String {
        val disabled = snapshot.disabledFeatures()
        val recommendations = snapshot.recommendations()
        if (disabled.isEmpty() && recommendations.isEmpty()) {
            return getString(R.string.degraded_all_granted)
        }
        val builder = StringBuilder(getString(R.string.degraded_intro))
        disabled.forEach { kind ->
            builder.append('\n')
                .append(getString(R.string.degraded_disabled_prefix, getString(featureLabel(kind))))
        }
        recommendations.forEach { kind ->
            builder.append('\n')
                .append(getString(R.string.degraded_recommended_prefix, getString(featureLabel(kind))))
        }
        return builder.toString()
    }

    private fun statusLabel(status: PermissionStatus): String = when (status) {
        PermissionStatus.GRANTED -> getString(R.string.status_granted)
        PermissionStatus.DENIED -> getString(R.string.status_denied)
        PermissionStatus.NOT_APPLICABLE -> getString(R.string.status_not_applicable)
    }

    private fun featureLabel(kind: PermissionKind): Int = when (kind) {
        PermissionKind.ACCESSIBILITY -> R.string.feature_blocking
        PermissionKind.USAGE_ACCESS -> R.string.feature_timers
        PermissionKind.NOTIFICATION_LISTENER -> R.string.feature_short_form
        PermissionKind.POST_NOTIFICATIONS -> R.string.feature_status_notification
        PermissionKind.BATTERY_OPTIMIZATION -> R.string.feature_background_reliability
    }

    private fun showSettingsUnavailable() {
        Toast.makeText(this, R.string.onboarding_settings_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_POST_NOTIFICATIONS = 1001

        fun rowTag(kind: PermissionKind): String = "row_${kind.name}"
        fun statusTag(kind: PermissionKind): String = "status_${kind.name}"
        fun actionTag(kind: PermissionKind): String = "action_${kind.name}"
    }
}
