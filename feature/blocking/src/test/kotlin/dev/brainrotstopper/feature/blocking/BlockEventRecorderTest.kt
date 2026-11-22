package dev.brainrotstopper.feature.blocking

import dev.brainrotstopper.core.data.BlockEventRepository
import dev.brainrotstopper.core.model.BlockEvent
import dev.brainrotstopper.core.model.BlockReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockEventRecorderTest {

    @Test
    fun releaseQueuedRightAfterRecordStillClosesTheRow() = runBlocking {
        val repository = FakeBlockEventRepository()
        val recorder = BlockEventRecorder(repository)

        recorder.record("com.example.app", 1_000L, BlockReason.DAILY_LIMIT)
        recorder.release("com.example.app", 2_000L)

        assertTrue(recorder.awaitIdleForTests())
        assertEquals(1, repository.events.size)
        assertEquals(2_000L, repository.events.single().releasedAtEpochMillis)
    }

    @Test
    fun releaseStaleClosesRowsLeftOpenByAPreviousProcess() = runBlocking {
        val repository = FakeBlockEventRepository()
        val recorder = BlockEventRecorder(repository)

        recorder.record("com.example.app", 1_000L, BlockReason.SCHEDULE)
        recorder.record("com.example.other", 2_000L, BlockReason.MANUAL)
        recorder.releaseStale(3_000L)

        assertTrue(recorder.awaitIdleForTests())
        assertEquals(listOf(3_000L, 3_000L), repository.events.map { it.releasedAtEpochMillis })
        assertTrue(repository.events.none { it.releasedAtEpochMillis == null })
    }

    @Test
    fun recordsAreTrimmedToTheRepositoryCap() = runBlocking {
        val repository = FakeBlockEventRepository()
        val recorder = BlockEventRecorder(repository)

        repeat(BlockEventRepository.DEFAULT_LIMIT + 5) { index ->
            recorder.record("com.example.app", 1_000L + index, BlockReason.MANUAL)
        }

        assertTrue(recorder.awaitIdleForTests(timeoutMillis = 10_000L))
        assertEquals(BlockEventRepository.DEFAULT_LIMIT, repository.events.size)
        assertEquals(
            "the newest record must survive the trim",
            1_000L + BlockEventRepository.DEFAULT_LIMIT + 4,
            repository.events.maxOf { it.startedAtEpochMillis },
        )
        assertNull("recording alone never releases a block", repository.events.first().releasedAtEpochMillis)
    }
}

private class FakeBlockEventRepository : BlockEventRepository {
    val events = mutableListOf<BlockEvent>()
    private var nextId = 1L

    override suspend fun record(
        packageName: String,
        startedAtEpochMillis: Long,
        reason: BlockReason,
    ): Long {
        val id = nextId++
        events += BlockEvent(id, packageName, startedAtEpochMillis, reason)
        return id
    }

    override suspend fun releaseOpen(packageName: String, releasedAtEpochMillis: Long): Int {
        var released = 0
        events.replaceAll { event ->
            if (event.packageName == packageName && event.releasedAtEpochMillis == null) {
                released++
                event.copy(releasedAtEpochMillis = releasedAtEpochMillis)
            } else {
                event
            }
        }
        return released
    }

    override suspend fun releaseAllOpen(releasedAtEpochMillis: Long): Int {
        var released = 0
        events.replaceAll { event ->
            if (event.releasedAtEpochMillis == null) {
                released++
                event.copy(releasedAtEpochMillis = releasedAtEpochMillis)
            } else {
                event
            }
        }
        return released
    }

    override suspend fun recent(limit: Int): List<BlockEvent> =
        events.sortedWith(compareByDescending<BlockEvent> { it.startedAtEpochMillis }.thenByDescending { it.id })
            .take(limit)

    override suspend fun trimTo(keep: Int): Int {
        val newest = recent(keep).toSet()
        val removed = events.count { it !in newest }
        events.retainAll(newest)
        return removed
    }
}
