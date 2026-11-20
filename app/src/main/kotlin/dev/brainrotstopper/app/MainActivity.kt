package dev.brainrotstopper.app

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import dev.brainrotstopper.core.data.RoomRuleRepository
import dev.brainrotstopper.core.data.db.BrainrotDatabase
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.TimeWindow
import dev.brainrotstopper.feature.blocking.NoOpBlockingController
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking

/**
 * Minimal scaffold shell. It exists to prove the module graph resolves and the domain,
 * data, and blocking contracts are wired together. Real UI arrives with the MVP issues.
 */
class MainActivity : Activity() {

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
    }
}
