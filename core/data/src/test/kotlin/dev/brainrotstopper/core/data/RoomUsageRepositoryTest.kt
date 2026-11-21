package dev.brainrotstopper.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.brainrotstopper.core.data.db.BrainrotDatabase
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
class RoomUsageRepositoryTest {

    private lateinit var database: BrainrotDatabase
    private lateinit var repository: RoomUsageRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BrainrotDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomUsageRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `rewriting a bucket replaces the total instead of incrementing`() = runBlocking {
        repository.rewriteUsage("com.example.app", epochDay = 20_000, usedMillis = 1_000)
        repository.rewriteUsage("com.example.app", epochDay = 20_000, usedMillis = 5_000)

        assertEquals(5_000L, repository.usage("com.example.app", 20_000)?.usedMillis)
        assertEquals(1, repository.usageForDay(20_000).size)
    }

    @Test
    fun `rewriting the same total twice is idempotent`() = runBlocking {
        repository.rewriteUsage("com.example.app", epochDay = 20_000, usedMillis = 5_000)
        repository.rewriteUsage("com.example.app", epochDay = 20_000, usedMillis = 5_000)

        assertEquals(5_000L, repository.usage("com.example.app", 20_000)?.usedMillis)
        assertEquals(1, repository.usageForDay(20_000).size)
    }

    @Test
    fun `buckets are keyed by package and day`() = runBlocking {
        repository.rewriteUsage("com.example.one", epochDay = 20_000, usedMillis = 1_000)
        repository.rewriteUsage("com.example.one", epochDay = 20_001, usedMillis = 2_000)
        repository.rewriteUsage("com.example.two", epochDay = 20_000, usedMillis = 3_000)

        assertEquals(2, repository.usageForDay(20_000).size)
        assertEquals(1_000L, repository.usage("com.example.one", 20_000)?.usedMillis)
        assertEquals(2_000L, repository.usage("com.example.one", 20_001)?.usedMillis)
        assertEquals(3_000L, repository.usage("com.example.two", 20_000)?.usedMillis)
        assertNull(repository.usage("com.example.two", 20_001))
    }

    @Test
    fun `deleteBefore drops only older days`() = runBlocking {
        repository.rewriteUsage("com.example.app", epochDay = 19_999, usedMillis = 1)
        repository.rewriteUsage("com.example.app", epochDay = 20_000, usedMillis = 2)

        repository.deleteBefore(20_000)

        assertNull(repository.usage("com.example.app", 19_999))
        assertEquals(2L, repository.usage("com.example.app", 20_000)?.usedMillis)
    }
}
