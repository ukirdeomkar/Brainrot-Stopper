package dev.brainrotstopper.feature.settings

import android.content.Context
import dev.brainrotstopper.core.data.BlockEventRepository
import dev.brainrotstopper.core.data.RoomBlockEventRepository
import dev.brainrotstopper.core.data.RoomRuleRepository
import dev.brainrotstopper.core.data.RoomUsageRepository
import dev.brainrotstopper.core.data.RuleRepository
import dev.brainrotstopper.core.data.UsageRepository
import dev.brainrotstopper.core.data.db.BrainrotDatabase
import dev.brainrotstopper.feature.blocking.BlockingEngineStatus
import dev.brainrotstopper.feature.blocking.BlockingRuntime
import dev.brainrotstopper.feature.onboarding.AndroidPermissionChecker
import dev.brainrotstopper.feature.onboarding.PermissionChecker

/**
 * Data access for the settings & diagnostics screen.
 *
 * One Room instance is shared by every diagnostics read, and the engine state is read live from
 * [BlockingRuntime.status] so the screen reports the same state the blocking service does.
 * [setForTests] is an instrumentation-test seam only; production code never calls it.
 */
object DiagnosticsStore {

    @Volatile
    private var database: BrainrotDatabase? = null

    @Volatile
    private var testPermissionChecker: PermissionChecker? = null

    @Volatile
    private var testEngineStatus: (() -> BlockingEngineStatus)? = null

    @Volatile
    private var testRules: RuleRepository? = null

    @Volatile
    private var testUsage: UsageRepository? = null

    @Volatile
    private var testBlockEvents: BlockEventRepository? = null

    fun permissionChecker(context: Context): PermissionChecker =
        testPermissionChecker ?: AndroidPermissionChecker(context.applicationContext)

    fun engineStatus(): BlockingEngineStatus = testEngineStatus?.invoke() ?: BlockingRuntime.status()

    fun ruleRepository(context: Context): RuleRepository =
        testRules ?: RoomRuleRepository(database(context))

    fun usageRepository(context: Context): UsageRepository =
        testUsage ?: RoomUsageRepository(database(context))

    fun blockEventRepository(context: Context): BlockEventRepository =
        testBlockEvents ?: RoomBlockEventRepository(database(context))

    /** Replaces every dependency for instrumentation tests. Pass null to restore production. */
    fun setForTests(
        permissionChecker: PermissionChecker? = null,
        engineStatus: (() -> BlockingEngineStatus)? = null,
        rules: RuleRepository? = null,
        usage: UsageRepository? = null,
        blockEvents: BlockEventRepository? = null,
    ) {
        testPermissionChecker = permissionChecker
        testEngineStatus = engineStatus
        testRules = rules
        testUsage = usage
        testBlockEvents = blockEvents
    }

    private fun database(context: Context): BrainrotDatabase {
        database?.let { return it }
        return synchronized(this) {
            database ?: BrainrotDatabase.create(context.applicationContext).also { database = it }
        }
    }
}
