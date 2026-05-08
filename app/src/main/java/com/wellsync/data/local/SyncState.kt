package com.wellsync.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val id: Int = 0,
    val lastSyncedTimestamp: Long = 0L,
    val cacheId: String? = null,
    val cacheExpiryTimestamp: Long = 0L
)
