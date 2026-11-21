package dev.brainrotstopper.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.brainrotstopper.core.data.db.BrainrotDatabase
import dev.brainrotstopper.core.model.UnlockGrant
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
class RoomUnlockGrantRepositoryTest {

    private lateinit var database: BrainrotDatabase
    private lateinit var repository: RoomUnlockGrantRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BrainrotDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoomUnlockGrantRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `global grant covers any app and sorts by expiry`() = runBlocking {
        repository.save(grant("later", packageName = null, expiresAt = 5_000))
        repository.save(grant("sooner", packageName = null, expiresAt = 3_000))

        val active = repository.active(nowEpochMillis = 1_000)

        assertEquals(listOf("sooner", "later"), active.map { it.id })
        assertEquals("sooner", repository.activeFor("com.example.app", 1_000)?.id)
        assertTrue(active.all { it.packageName == null })
    }

    @Test
    fun `package grant covers only its package`() = runBlocking {
        repository.save(grant("one", packageName = "com.example.one", expiresAt = 5_000))

        assertEquals("one", repository.activeFor("com.example.one", 1_000)?.id)
        assertNull(repository.activeFor("com.example.two", 1_000))
    }

    @Test
    fun `expired grants are never returned`() = runBlocking {
        repository.save(grant("expired", packageName = null, expiresAt = 1_000))

        assertTrue(repository.active(nowEpochMillis = 1_000).isEmpty())
        assertNull(repository.activeFor("com.example.app", 1_000))
    }

    @Test
    fun `revoke removes a grant`() = runBlocking {
        repository.save(grant("one", packageName = null, expiresAt = 5_000))

        repository.revoke("one")

        assertTrue(repository.active(nowEpochMillis = 1_000).isEmpty())
    }

    @Test
    fun `purgeExpired deletes only expired grants`() = runBlocking {
        repository.save(grant("expired", packageName = null, expiresAt = 1_000))
        repository.save(grant("active", packageName = null, expiresAt = 5_000))

        assertEquals(1, repository.purgeExpired(nowEpochMillis = 2_000))
        assertEquals(listOf("active"), repository.active(2_000).map { it.id })
    }

    private fun grant(id: String, packageName: String?, expiresAt: Long): UnlockGrant =
        UnlockGrant(
            id = id,
            packageName = packageName,
            expiresAtEpochMillis = expiresAt,
            reason = "test",
        )
}
