package com.cj.tapblok.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BlockedApp::class, AppGroup::class, ScopeUsage::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun blockedAppDao(): BlockedAppDao
    abstract fun appGroupDao(): AppGroupDao
    abstract fun usageDao(): UsageDao

    companion object {

        /**
         * v1 held a single table of bare package names. Every rule column added here is
         * nullable because null *is* the default — inheritance is resolved at evaluation
         * time — so the migration needs no default values and existing blocklists survive
         * untouched.
         *
         * The DDL must match Room's generated schema exactly or `validateMigration` fails
         * at open: no SQL DEFAULT clauses, since Kotlin default arguments don't produce
         * them.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE blocked_apps ADD COLUMN groupId TEXT")
                db.execSQL("ALTER TABLE blocked_apps ADD COLUMN sessionMinutes INTEGER")
                db.execSQL("ALTER TABLE blocked_apps ADD COLUMN dailyMinutes INTEGER")
                db.execSQL("ALTER TABLE blocked_apps ADD COLUMN resetMinutes INTEGER")
                db.execSQL("ALTER TABLE blocked_apps ADD COLUMN tagUnlockMode TEXT")
                db.execSQL("ALTER TABLE blocked_apps ADD COLUMN graceMinutes INTEGER")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `app_groups` (
                        `groupId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `sessionMinutes` INTEGER,
                        `dailyMinutes` INTEGER,
                        `resetMinutes` INTEGER,
                        `tagUnlockMode` TEXT,
                        `graceMinutes` INTEGER,
                        PRIMARY KEY(`groupId`)
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `usage` (
                        `scopeId` TEXT NOT NULL,
                        `sessionUsedMs` INTEGER NOT NULL,
                        `dailyUsedMs` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL,
                        `dailyPeriodStart` INTEGER NOT NULL,
                        `graceUntil` INTEGER NOT NULL,
                        PRIMARY KEY(`scopeId`)
                    )
                    """.trimIndent()
                )
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                    // Deliberately no fallbackToDestructiveMigration: usage counters and
                    // cooldowns are the app's whole point, and silently dropping them on a
                    // schema change would hand the user a free reset.
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
