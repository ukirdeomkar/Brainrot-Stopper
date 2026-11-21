package dev.brainrotstopper.feature.rules

import android.content.Context
import dev.brainrotstopper.core.data.RoomRuleRepository
import dev.brainrotstopper.core.data.RuleRepository
import dev.brainrotstopper.core.data.db.BrainrotDatabase

/**
 * Process-wide rules repository.
 *
 * One Room instance serves every rules screen so a change made in an editor is visible to the
 * list on return without reopening the database file. [setRepositoryForTests] is a test seam
 * for instrumentation tests; production code must never call it.
 */
object RulesStore {

    @Volatile
    private var database: BrainrotDatabase? = null

    @Volatile
    private var testRepository: RuleRepository? = null

    fun repository(context: Context): RuleRepository {
        testRepository?.let { return it }
        return RoomRuleRepository(database(context.applicationContext))
    }

    fun setRepositoryForTests(repository: RuleRepository?) {
        testRepository = repository
    }

    private fun database(context: Context): BrainrotDatabase {
        database?.let { return it }
        return synchronized(this) {
            database ?: BrainrotDatabase.create(context).also { database = it }
        }
    }
}
