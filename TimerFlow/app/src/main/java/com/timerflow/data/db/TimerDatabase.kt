package com.timerflow.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.timerflow.data.model.TimerSequence
import com.timerflow.data.model.TimerStep

@Database(entities = [TimerSequence::class, TimerStep::class], version = 1, exportSchema = false)
abstract class TimerDatabase : RoomDatabase() {
    abstract fun timerDao(): TimerDao

    companion object {
        @Volatile private var INSTANCE: TimerDatabase? = null

        fun getInstance(context: Context): TimerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TimerDatabase::class.java,
                    "timerflow.db"
                ).build().also { INSTANCE = it }
            }
    }
}
