package dev.brainrotstopper.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.brainrotstopper.core.data.db.BrainrotDatabase
import dev.brainrotstopper.core.data.db.DayOfWeekMask
import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.TimeWindow
import java.time.DayOfWeek
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RoomRuleRepositoryTest {

    private lateinit var database: BrainrotDatabase
    private lateinit var repository: RoomRuleRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BrainrotDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomRuleRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `rule round trips with schedule links and block_all`() = runBlocking {
        repository.upsertSchedule(schedule("work", DayOfWeek.MONDAY))
        repository.upsertRule(
            AppRule(
                packageName = "com.example.app",
                displayName = "Example",
                dailyLimitMinutes = 30,
                scheduleIds = setOf("work"),
                blockAll = true,
                shortFormDetection = true,
            ),
        )

        val stored = repository.rules().single()
        assertEquals("com.example.app", stored.packageName)
        assertEquals("Example", stored.displayName)
        assertEquals(30, stored.dailyLimitMinutes)
        assertEquals(setOf("work"), stored.scheduleIds)
        assertTrue(stored.blockAll)
        assertTrue(stored.shortFormDetection)
        assertTrue(stored.enabled)
    }

    @Test
    fun `daily limit can be set then cleared in place`() = runBlocking {
        repository.upsertRule(AppRule("com.example.app", "Example"))
        assertNull(repository.rules().single().dailyLimitMinutes)

        repository.upsertRule(repository.rules().single().copy(dailyLimitMinutes = 45))
        assertEquals(45, repository.rules().single().dailyLimitMinutes)

        repository.upsertRule(repository.rules().single().copy(dailyLimitMinutes = null))
        assertNull(repository.rules().single().dailyLimitMinutes)
    }

    @Test
    fun `upsert replaces an existing rule by package name`() = runBlocking {
        repository.upsertRule(AppRule("com.example.app", "Example"))
        repository.upsertRule(AppRule("com.example.app", "Example renamed"))

        assertEquals(1, repository.rules().size)
        assertEquals("Example renamed", repository.rules().single().displayName)
    }

    @Test
    fun `upsert replaces schedule links instead of appending`() = runBlocking {
        repository.upsertSchedule(schedule("work", DayOfWeek.MONDAY))
        repository.upsertSchedule(schedule("night", DayOfWeek.FRIDAY))
        repository.upsertRule(
            AppRule("com.example.app", "Example", scheduleIds = setOf("work")),
        )

        repository.upsertRule(
            AppRule("com.example.app", "Example", scheduleIds = setOf("night")),
        )

        assertEquals(setOf("night"), repository.rules().single().scheduleIds)
    }

    @Test
    fun `removing a rule cascades its schedule links`() = runBlocking {
        repository.upsertSchedule(schedule("work", DayOfWeek.MONDAY))
        repository.upsertRule(
            AppRule("com.example.app", "Example", scheduleIds = setOf("work")),
        )

        repository.removeRule("com.example.app")

        assertTrue(repository.rules().isEmpty())
        // The schedule survives; only the link is gone.
        assertEquals(1, repository.schedules().size)
    }

    @Test
    fun `schedule round trips day mask and windows`() = runBlocking {
        val schedule = BlockSchedule(
            id = "work",
            label = "Work",
            daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
            windows = listOf(TimeWindow(9 * 60, 12 * 60), TimeWindow(13 * 60, 17 * 60)),
        )

        repository.upsertSchedule(schedule)

        assertEquals(listOf(schedule), repository.schedules())
    }

    @Test
    fun `every day mask bit round trips independently`() = runBlocking {
        for (day in DayOfWeek.entries) {
            val id = "day-${day.value}"
            repository.upsertSchedule(schedule(id, day))

            val stored = repository.schedules().single { it.id == id }
            assertEquals(setOf(day), stored.daysOfWeek)
        }
    }

    @Test
    fun `overnight windows round trip in order`() = runBlocking {
        val overnight = BlockSchedule(
            id = "night",
            label = "Monday night",
            daysOfWeek = setOf(DayOfWeek.MONDAY),
            windows = listOf(TimeWindow(22 * 60, 2 * 60), TimeWindow(3 * 60, 4 * 60)),
        )

        repository.upsertSchedule(overnight)

        val stored = repository.schedules().single()
        assertEquals(overnight.windows, stored.windows)
        assertEquals(TimeWindow(22 * 60, 2 * 60), stored.windows.first())
        assertTrue(stored.windows.first().isOvernight)
        assertTrue(stored.isActiveAt(DayOfWeek.TUESDAY, 1 * 60))
    }

    @Test
    fun `editing a schedule replaces its windows`() = runBlocking {
        repository.upsertSchedule(
            BlockSchedule(
                id = "work",
                label = "Work",
                daysOfWeek = setOf(DayOfWeek.MONDAY),
                windows = listOf(TimeWindow(9 * 60, 17 * 60)),
            ),
        )
        repository.upsertSchedule(
            BlockSchedule(
                id = "work",
                label = "Work v2",
                daysOfWeek = setOf(DayOfWeek.TUESDAY),
                windows = listOf(TimeWindow(10 * 60, 11 * 60)),
            ),
        )

        val stored = repository.schedules().single()
        assertEquals("Work v2", stored.label)
        assertEquals(setOf(DayOfWeek.TUESDAY), stored.daysOfWeek)
        assertEquals(listOf(TimeWindow(10 * 60, 11 * 60)), stored.windows)
    }

    @Test
    fun `removing a schedule cascades links and keeps the rule`() = runBlocking {
        repository.upsertSchedule(schedule("work", DayOfWeek.MONDAY))
        repository.upsertRule(
            AppRule("com.example.app", "Example", scheduleIds = setOf("work")),
        )

        repository.removeSchedule("work")

        assertTrue(repository.schedules().isEmpty())
        assertEquals(emptySet<String>(), repository.rules().single().scheduleIds)
        assertNull(database.appRuleDao().get("missing"))
    }

    @Test
    fun `days mask encodes Monday in bit zero and Sunday in bit six`() {
        assertEquals(0b000_0001, DayOfWeekMask.encode(setOf(DayOfWeek.MONDAY)))
        assertEquals(0b100_0000, DayOfWeekMask.encode(setOf(DayOfWeek.SUNDAY)))
        assertEquals(DayOfWeekMask.ALL, DayOfWeekMask.encode(DayOfWeek.entries.toSet()))
        assertEquals(0, DayOfWeekMask.encode(emptySet()))
        assertEquals(setOf(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY), DayOfWeekMask.decode(0b010_0010))
    }

    private fun schedule(id: String, day: DayOfWeek): BlockSchedule =
        BlockSchedule(
            id = id,
            label = id,
            daysOfWeek = setOf(day),
            windows = listOf(TimeWindow(9 * 60, 17 * 60)),
        )
}
