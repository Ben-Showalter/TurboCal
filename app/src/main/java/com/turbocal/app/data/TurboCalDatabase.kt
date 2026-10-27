package com.turbocal.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FeedEntity::class, EventEntity::class], version = 1, exportSchema = true)
abstract class TurboCalDatabase : RoomDatabase() {
    abstract fun dao(): TurboCalDao

    companion object {
        fun create(context: Context): TurboCalDatabase =
            Room.databaseBuilder(context, TurboCalDatabase::class.java, "turbocal.db").build()
    }
}
