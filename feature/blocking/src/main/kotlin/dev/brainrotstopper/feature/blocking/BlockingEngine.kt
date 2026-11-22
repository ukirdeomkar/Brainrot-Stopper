package dev.brainrotstopper.feature.blocking

import android.content.Context
import dev.brainrotstopper.core.data.RoomRuleRepository
import dev.brainrotstopper.core.data.RoomUnlockGrantRepository
import dev.brainrotstopper.core.data.RoomUsageRepository
import dev.brainrotstopper.core.data.db.BrainrotDatabase

/**
 * The live blocking engine: one cached snapshot plus the unlock flow.
 *
 * The engine is process-scoped. It is created once by [BlockingEngineProvider] and shared by
 * the foreground service (which keeps the process alive) and the AccessibilityService (which
 * turns decisions into the overlay). Neither owner depends on an Activity being alive.
 */
class BlockingEngine internal constructor(
    private val cache: BlockingStateCache,
    private val unlockFlow: UnlockFlow,
) {

    fun snapshot(): BlockingSnapshot = cache.current()

    fun decisionFor(packageName: String): Decision = cache.decisionFor(packageName)

    fun isUnlocked(
        packageName: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean = cache.isUnlocked(packageName, nowEpochMillis)

    /** Reloads rules, schedules, grants, and usage. Safe to call from any dispatcher. */
    suspend fun refresh() = cache.refresh()

    /** Runs the unlock flow; only [UnlockResult.Success] means the grant is durable. */
    suspend fun unlock(packageName: String): UnlockResult = unlockFlow.unlock(packageName)
}

/**
 * Creates the process-wide [BlockingEngine] on first use and attaches it to
 * [BlockingRuntime].
 *
 * The foreground service and the AccessibilityService both call this, so whichever Android
 * starts first wins and the other reuses the same cache. A second database/cache would make
 * an unlock grant written by one owner invisible to the other.
 */
internal object BlockingEngineProvider {

    private val lock = Any()

    @Volatile
    private var engine: BlockingEngine? = null

    fun get(context: Context): BlockingEngine = synchronized(lock) {
        val existing = engine
        if (existing != null) {
            // A previous owner may have detached after the other owner stopped; re-attach the
            // same cache rather than building a second one.
            if (!BlockingRuntime.isAttached()) BlockingRuntime.attach(existing)
            return existing
        }
        build(context.applicationContext).also { created ->
            engine = created
            BlockingRuntime.attach(created)
        }
    }

    private fun build(context: Context): BlockingEngine {
        val database = BrainrotDatabase.create(context)
        val grants = RoomUnlockGrantRepository(database)
        val cache = BlockingStateCache(
            ruleRepository = RoomRuleRepository(database),
            unlockGrantRepository = grants,
            usageRepository = RoomUsageRepository(database),
        )
        val flow = UnlockFlow(
            unlockGrants = grants,
            cache = cache,
            gate = { BlockingRuntime.unlockGate },
            onUnlocked = { packageName -> BlockingRuntime.notifyUnlocked(packageName) },
        )
        return BlockingEngine(cache, unlockFlow = flow)
    }
}
