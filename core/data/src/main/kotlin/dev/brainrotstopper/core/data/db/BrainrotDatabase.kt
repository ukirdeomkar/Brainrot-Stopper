package dev.brainrotstopper.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import dev.brainrotstopper.core.data.db.dao.AppRuleDao
import dev.brainrotstopper.core.data.db.dao.BlockEventDao
import dev.brainrotstopper.core.data.db.dao.ScheduleDao
import dev.brainrotstopper.core.data.db.dao.UnlockGrantDao
import dev.brainrotstopper.core.data.db.dao.UsageBucketDao
import dev.brainrotstopper.core.data.db.entity.AppRuleEntity
import dev.brainrotstopper.core.data.db.entity.BlockEventEntity
import dev.brainrotstopper.core.data.db.entity.RuleScheduleEntity
import dev.brainrotstopper.core.data.db.entity.ScheduleEntity
import dev.brainrotstopper.core.data.db.entity.ScheduleWindowEntity
import dev.brainrotstopper.core.data.db.entity.UnlockGrantEntity
import dev.brainrotstopper.core.data.db.entity.UsageBucketEntity

/**
 * On-device store for rules, schedules, usage, block diagnostics, and unlock grants.
 *
 * Credentials are deliberately absent: the password hash lives in EncryptedSharedPreferences
 * (design §4.1, built in M7), never in Room.
 *
 * Version 1 is the initial schema. The exported schema in `core/data/schemas` is the baseline
 * every future migration must be written and tested against; add new [Migration] instances to
 * [MIGRATIONS] and bump [VERSION] together.
 */
@Database(
    entities = [
        AppRuleEntity::class,
        ScheduleEntity::class,
        ScheduleWindowEntity::class,
        RuleScheduleEntity::class,
        UsageBucketEntity::class,
        BlockEventEntity::class,
        UnlockGrantEntity::class,
    ],
    version = BrainrotDatabase.VERSION,
    exportSchema = true,
)
abstract class BrainrotDatabase : RoomDatabase() {

    abstract fun appRuleDao(): AppRuleDao

    abstract fun scheduleDao(): ScheduleDao

    abstract fun usageBucketDao(): UsageBucketDao

    abstract fun blockEventDao(): BlockEventDao

    abstract fun unlockGrantDao(): UnlockGrantDao

    companion object {
        const val VERSION: Int = 1
        const val NAME: String = "brainrot.db"

        /** No migrations yet: version 1 is the baseline. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun create(context: Context): BrainrotDatabase =
            Room.databaseBuilder(context.applicationContext, BrainrotDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()

        fun inMemory(context: Context): BrainrotDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, BrainrotDatabase::class.java)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
