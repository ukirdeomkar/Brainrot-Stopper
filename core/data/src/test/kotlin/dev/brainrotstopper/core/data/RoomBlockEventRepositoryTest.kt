package dev.brainrotstopper.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.brainrotstopper.core.data.db.BrainrotDatabase
import dev.brainrotstopper.core.model.BlockReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RoomBlockEventRepositoryTest {

    private lateinit var database: BrainrotDatabase
    private lateinit var repository: RoomBlockEventRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BrainrotDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomBlockEventRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `recording a block persists the reason and stays open`() = runBlocking {
        val id = repository.record("com.example.app", 1_000, BlockReason.SCHEDULE)

        val event = repository.recent().single()
        assertEquals(id, event.id)
        assertEquals("com.example.app", event.packageName)
        assertEquals(BlockReason.SCHEDULE, event.reason)
        assertNull(event.releasedAtEpochMillis)
    }

    @Test
    fun `releaseOpen closes open events for the package`() = runBlocking {
        repository.record("com.example.app", 1_000, BlockReason.DAILY_LIMIT)
        repository.record("com.example.other", 2_000, BlockReason.MANUAL)

        val released = repository.releaseOpen("com.example.app", 3_000)

        assertEquals(1, released)
        val appEvent = repository.recent().single { it.packageName == "com.example.app" }
        assertEquals(3_000L, appEvent.releasedAtEpochMillis)
        val otherEvent = repository.recent().single { it.packageName == "com.example.other" }
        assertNull(otherEvent.releasedAtEpochMillis)
    }

    @Test
    fun `releaseAllOpen closes stale events from a previous process`() = runBlocking {
        repository.record("com.example.app", 1_000, BlockReason.SCHEDULE)
        repository.record("com.example.other", 2_000, BlockReason.MANUAL)

        val released = repository.releaseAllOpen(3_000)

        assertEquals(2, released)
        assertEquals(
            listOf(3_000L, 3_000L),
            repository.recent().map { it.releasedAtEpochMillis },
        )
    }

    @Test
    fun `recent returns newest first`() = runBlocking {
        repository.record("com.example.app", 1_000, BlockReason.MANUAL)
        repository.record("com.example.app", 2_000, BlockReason.MANUAL)

        assertEquals(listOf(2_000L, 1_000L), repository.recent().map { it.startedAtEpochMillis })
    }

    @Test
    fun `trimTo keeps only the newest rows`() = runBlocking {
        repeat(5) { index ->
            repository.record("com.example.app", 1_000L + index, BlockReason.MANUAL)
        }

        assertEquals(3, repository.trimTo(keep = 2))
        assertEquals(listOf(1_004L, 1_003L), repository.recent().map { it.startedAtEpochMillis })
    }
}
