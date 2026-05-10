package com.wellsync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wellsync.data.health.HealthConnectManager
import com.wellsync.data.local.SyncStateDao
import com.wellsync.data.repository.HealthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class UiState(
    val isLoading: Boolean = false,
    val analysis: String? = null,
    val lastSyncTime: Long = 0L,
    val error: String? = null,
    val isHealthConnectAvailable: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val healthConnectManager: HealthConnectManager,
    private val healthRepository: HealthRepository,
    private val syncStateDao: SyncStateDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    init {
        checkHealthConnect()
        loadSyncState()
    }

    private fun checkHealthConnect() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isHealthConnectAvailable = healthConnectManager.isAvailable()
            )
        }
    }

    private fun loadSyncState() {
        viewModelScope.launch {
            syncStateDao.getSyncState()?.let {
                _uiState.value = _uiState.value.copy(
                    lastSyncTime = it.lastSyncedTimestamp,
                    analysis = it.lastAnalysis
                )
            }
        }
    }

    fun refreshAndAnalyze() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val syncState = syncStateDao.getSyncState()
                // Default to 365 days ago if no last sync to capture earlier data like January
                val lastSync = syncState?.lastSyncedTimestamp?.let { Instant.ofEpochMilli(it) } 
                    ?: Instant.now().minus(365, ChronoUnit.DAYS)
                
                val now = Instant.now()
                
                // Fetch historical (e.g., past 365 days for full context)
                val oneYearAgo = now.minus(365, ChronoUnit.DAYS)
                val historicalData = healthConnectManager.readHealthData(oneYearAgo, lastSync)
                
                // Fetch delta (since last sync)
                val deltaData = healthConnectManager.readHealthData(lastSync, now)
                
                val result = healthRepository.getAnalysis(historicalData, deltaData)
                
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    analysis = result,
                    lastSyncTime = now.toEpochMilli()
                )
            } catch (e: Exception) {
                android.util.Log.e("WellSync", "Error during refreshAndAnalyze", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.localizedMessage ?: "Unknown error"
                )
            }
        }
    }
}
