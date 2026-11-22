package dev.brainrotstopper.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import dev.brainrotstopper.core.data.RoomRuleRepository
import dev.brainrotstopper.core.data.db.BrainrotDatabase
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.TimeWindow
import dev.brainrotstopper.feature.blocking.BlockingForegroundService
import dev.brainrotstopper.feature.blocking.BlockingRuntime
import dev.brainrotstopper.feature.blocking.BlockingServiceStartReason
import dev.brainrotstopper.feature.blocking.NoOpBlockingController
import dev.brainrotstopper.feature.onboarding.AndroidPermissionChecker
import dev.brainrotstopper.feature.onboarding.OnboardingActivity
import dev.brainrotstopper.feature.onboarding.OnboardingRepository
import dev.brainrotstopper.feature.onboarding.PermissionChecker
import dev.brainrotstopper.feature.onboarding.PermissionKind
import dev.brainrotstopper.feature.onboarding.PermissionSnapshot
import dev.brainrotstopper.feature.onboarding.SharedPreferencesOnboardingRepository
import dev.brainrotstopper.feature.rules.RulesActivity
import dev.brainrotstopper.feature.timers.UsageAccessStatus
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking

/**
 * Minimal scaffold shell. It exists to prove the module graph resolves and the domain,
 * data, and blocking contracts are wired together. Real UI arrives with the MVP issues.
 *
 * First run launches [OnboardingActivity]; the permission summary here re-reads the live grant
 * state on every resume so a change made in Android Settings is reflected when the user returns.
 * The schedule status comes from the Room-backed repository, so it survives process restarts.
 */
class MainActivity : Activity() {

    private lateinit var onboardingRepository: OnboardingRepository
    private lateinit var permissionChecker: PermissionChecker
    private val statusHandler = Handler(Looper.getMainLooper())

    /**
     * The engine loads asynchronously after the service starts, so poll the self-check while
     * the shell is resumed instead of showing a stale "off" on first launch.
     */
    private val statusRefresh = object : Runnable {
        override fun run() {
            refreshBlockingEngineStatus()
            statusHandler.postDelayed(this, STATUS_REFRESH_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusView = findViewById<TextView>(R.id.scaffold_status)
        val database = BrainrotDatabase.create(applicationContext)
        val repository = RoomRuleRepository(database)

        // The visible Activity is a user-initiated start path for the foreground service.
        BlockingForegroundService.start(applicationContext, BlockingServiceStartReason.ACTIVITY)

        // The first Room-backed read runs off the main thread; the scaffold UI is a plain View.
        Thread {
            runBlocking {
                if (repository.schedules().isEmpty()) {
                    repository.upsertSchedule(
                        BlockSchedule(
                            id = "demo-focus",
                            label = "Demo focus hours (09:00-17:00)",
                            daysOfWeek = DayOfWeek.entries.toSet(),
                            windows = listOf(
                                TimeWindow(startMinuteOfDay = 9 * 60, endMinuteOfDay = 17 * 60),
                            ),
                        ),
                    )
                }

                val schedules = repository.schedules()
                val now = LocalTime.now()
                val minuteOfDay = now.hour * 60 + now.minute
                val scheduleActive =
                    schedules.firstOrNull()?.isActiveAt(LocalDate.now().dayOfWeek, minuteOfDay) == true
                val blockingController = NoOpBlockingController()

                runOnUiThread {
                    statusView.text = getString(
                        R.string.scaffold_status,
                        schedules.size,
                        scheduleActive.toString(),
                        blockingController.name,
                    )
                }
            }
        }.apply {
            name = "scaffold-db-read"
            isDaemon = true
        }.start()

        onboardingRepository = SharedPreferencesOnboardingRepository(applicationContext)
        permissionChecker = AndroidPermissionChecker(applicationContext)

        findViewById<Button>(R.id.review_permissions).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        findViewById<Button>(R.id.manage_rules).setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }

        if (!onboardingRepository.isCompleted()) {
            startActivityForResult(
                Intent(this, OnboardingActivity::class.java),
                REQUEST_ONBOARDING,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        statusHandler.removeCallbacks(statusRefresh)
        statusRefresh.run()
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusRefresh)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ONBOARDING) refreshPermissionStatus()
    }

    private fun refreshPermissionStatus() {
        val snapshot = PermissionSnapshot(
            PermissionKind.values().associateWith { kind -> permissionChecker.status(kind) },
        )
        findViewById<TextView>(R.id.permission_status).text = getString(
            R.string.permission_status,
            onOff(snapshot.isBlockingEnabled),
            onOff(snapshot.areTimersEnabled),
            onOff(snapshot.isShortFormDetectionEnabled),
            onOff(snapshot.isStatusNotificationVisible),
        )
    }

    private fun refreshBlockingEngineStatus() {
        val status = BlockingRuntime.status()
        findViewById<TextView>(R.id.blocking_engine_status).text = getString(
            R.string.blocking_engine_status,
            onOff(status.isEngineRunning),
            onOff(status.foregroundServiceRunning),
            lastRefreshText(status.lastRefreshAtEpochMillis),
            startReasonText(status.lastStartReason),
            usageAccessText(status.usageAccessStatus, status.usageLastPollFailed),
            onOff(status.timersAvailable),
        )
    }

    private fun usageAccessText(status: UsageAccessStatus, pollFailed: Boolean): String = when (status) {
        UsageAccessStatus.GRANTED ->
            getString(
                if (pollFailed) {
                    R.string.usage_access_granted_poll_failed
                } else {
                    R.string.usage_access_granted
                },
            )
        UsageAccessStatus.REVOKED -> getString(R.string.usage_access_revoked)
        UsageAccessStatus.UNDETERMINED -> getString(R.string.usage_access_undetermined)
    }

    private fun lastRefreshText(epochMillis: Long): String =
        if (epochMillis <= 0L) {
            getString(R.string.never)
        } else {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
        }

    private fun startReasonText(reason: BlockingServiceStartReason?): String = when (reason) {
        BlockingServiceStartReason.BOOT_COMPLETED -> getString(R.string.start_reason_boot)
        BlockingServiceStartReason.PACKAGE_REPLACED -> getString(R.string.start_reason_update)
        BlockingServiceStartReason.ACTIVITY -> getString(R.string.start_reason_activity)
        BlockingServiceStartReason.ACCESSIBILITY -> getString(R.string.start_reason_accessibility)
        BlockingServiceStartReason.MANUAL -> getString(R.string.start_reason_manual)
        BlockingServiceStartReason.UNKNOWN, null -> getString(R.string.start_reason_unknown)
    }

    private fun onOff(value: Boolean): String =
        getString(if (value) R.string.state_on else R.string.state_off)

    private companion object {
        const val REQUEST_ONBOARDING = 2001
        const val STATUS_REFRESH_MILLIS = 1_000L
    }
}
