package com.wellsync.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [SyncState::class], version = 3)
abstract class WellSyncDatabase : RoomDatabase() {
    abstract fun syncStateDao(): SyncStateDao
}
