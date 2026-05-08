package com.wellsync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun getSyncState(): SyncState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateSyncState(syncState: SyncState)
}
