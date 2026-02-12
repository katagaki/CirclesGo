package com.tsubuzaki.circlesgo.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CirclesFavoriteEntity::class, CirclesVisitEntryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class CirclesDatabase : RoomDatabase() {

    abstract fun favoriteDao(): CirclesFavoriteDao
    abstract fun visitEntryDao(): CirclesVisitEntryDao

    companion object {
        @Volatile
        private var INSTANCE: CirclesDatabase? = null

        fun getInstance(context: Context): CirclesDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CirclesDatabase::class.java,
                    "circles_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
