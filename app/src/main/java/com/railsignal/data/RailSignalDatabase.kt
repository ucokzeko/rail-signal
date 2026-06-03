package com.railsignal.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [Trip::class, Sample::class, RecoveryEvent::class],
    version = 2,
    exportSchema = false,
)
abstract class RailSignalDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun sampleDao(): SampleDao
    abstract fun recoveryDao(): RecoveryDao
}
