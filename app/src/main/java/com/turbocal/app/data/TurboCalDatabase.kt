package com.turbocal.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [FeedEntity::class, EventEntity::class, LocalEventEntity::class], version = 2, exportSchema = true)
abstract class TurboCalDatabase : RoomDatabase() {
    abstract fun dao(): TurboCalDao

    companion object {
        fun create(context: Context): TurboCalDatabase =
            Room.databaseBuilder(context, TurboCalDatabase::class.java, "turbocal.db")
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * v2 adds the "On this phone" calendar: feeds.source and local_events.
         * Hand-written (not destructive) so existing installs keep their feeds.
         * The SQL must match what Room generates for the entities exactly.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `feeds` ADD COLUMN `source` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `local_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`feedId` INTEGER NOT NULL, `uid` TEXT NOT NULL, `title` TEXT NOT NULL, `location` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, `allDay` INTEGER NOT NULL, `start` TEXT NOT NULL, `end` TEXT NOT NULL, " +
                        "`rrule` TEXT, `exdates` TEXT NOT NULL, " +
                        "FOREIGN KEY(`feedId`) REFERENCES `feeds`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_local_events_feedId` ON `local_events` (`feedId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_local_events_uid` ON `local_events` (`uid`)")
            }
        }
    }
}
