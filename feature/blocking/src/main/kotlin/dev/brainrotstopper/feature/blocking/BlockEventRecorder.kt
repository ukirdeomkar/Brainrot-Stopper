package dev.brainrotstopper.feature.blocking

import android.util.Log
import dev.brainrotstopper.core.data.BlockEventRepository
import dev.brainrotstopper.core.model.BlockReason
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Writes local block diagnostics off the blocking hot path, in call order.
 *
 * The accessibility decision path is main-thread code and must not touch disk. Commands are
 * queued and processed by one consumer, so a release is applied after the matching record even
 * when an unlock arrives immediately after the overlay appears. A release that a previous
 * process never got to write cannot leave a permanent "still blocking" row: the engine asks for
 * [releaseStale] once at process start.
 *
 * Records are capped through [BlockEventRepository.trimTo]; nothing here is uploaded.
 */
internal class BlockEventRecorder(
    private val repository: BlockEventRepository,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private sealed interface Command {
        data class Record(
            val packageName: String,
            val startedAtEpochMillis: Long,
            val reason: BlockReason,
        ) : Command

        data class Release(
            val packageName: String,
            val releasedAtEpochMillis: Long,
        ) : Command

        data class ReleaseStale(val releasedAtEpochMillis: Long) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val pending = AtomicInteger(0)

    init {
        scope.launch {
            for (command in commands) {
                try {
                    process(command)
                } catch (t: Throwable) {
                    // Diagnostics must never take the blocking path down.
                    Log.w(TAG, "Could not write a local block event", t)
                } finally {
                    pending.decrementAndGet()
                }
            }
        }
    }

    fun record(packageName: String, startedAtEpochMillis: Long, reason: BlockReason) {
        enqueue(Command.Record(packageName, startedAtEpochMillis, reason))
    }

    fun release(packageName: String, releasedAtEpochMillis: Long) {
        enqueue(Command.Release(packageName, releasedAtEpochMillis))
    }

    fun releaseStale(releasedAtEpochMillis: Long) {
        enqueue(Command.ReleaseStale(releasedAtEpochMillis))
    }

    /** Test seam: waits until every queued command has been processed. */
    internal suspend fun awaitIdleForTests(timeoutMillis: Long = 5_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (pending.get() == 0) return true
            delay(10L)
        }
        return pending.get() == 0
    }

    private fun enqueue(command: Command) {
        pending.incrementAndGet()
        if (commands.trySend(command).isFailure) {
            // The consumer is gone (process shutdown); drop the command rather than block.
            pending.decrementAndGet()
        }
    }

    private suspend fun process(command: Command) {
        when (command) {
            is Command.Record -> {
                repository.record(command.packageName, command.startedAtEpochMillis, command.reason)
                repository.trimTo()
            }
            is Command.Release -> repository.releaseOpen(command.packageName, command.releasedAtEpochMillis)
            is Command.ReleaseStale -> repository.releaseAllOpen(command.releasedAtEpochMillis)
        }
    }

    private companion object {
        const val TAG = "BrainrotBlockEvents"
    }
}
