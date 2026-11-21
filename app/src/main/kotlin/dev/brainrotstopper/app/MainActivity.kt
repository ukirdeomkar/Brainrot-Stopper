package dev.brainrotstopper.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import dev.brainrotstopper.core.data.RoomRuleRepository
import dev.brainrotstopper.core.data.db.BrainrotDatabase
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.TimeWindow
import dev.brainrotstopper.feature.blocking.NoOpBlockingController
import dev.brainrotstopper.feature.onboarding.AndroidPermissionChecker
import dev.brainrotstopper.feature.onboarding.OnboardingActivity
import dev.brainrotstopper.feature.onboarding.OnboardingRepository
import dev.brainrotstopper.feature.onboarding.PermissionChecker
import dev.brainrotstopper.feature.onboarding.PermissionKind
import dev.brainrotstopper.feature.onboarding.PermissionSnapshot
import dev.brainrotstopper.feature.onboarding.SharedPreferencesOnboardingRepository
import dev.brainrotstopper.feature.rules.RulesActivity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusView = findViewById<TextView>(R.id.scaffold_status)
        val database = BrainrotDatabase.create(applicationContext)
        val repository = RoomRuleRepository(database)

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

    private fun onOff(value: Boolean): String =
        getString(if (value) R.string.state_on else R.string.state_off)

    private companion object {
        const val REQUEST_ONBOARDING = 2001
    }
}
