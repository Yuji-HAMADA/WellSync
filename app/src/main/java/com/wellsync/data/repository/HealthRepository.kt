package com.wellsync.data.repository

import android.util.Log
import com.wellsync.data.local.SyncState
import com.wellsync.data.local.SyncStateDao
import com.wellsync.data.model.HealthDataRecord
import com.wellsync.data.remote.CachedContentRequest
import com.wellsync.data.remote.Content
import com.wellsync.data.remote.GeminiApiService
import com.wellsync.data.remote.GenerateContentRequest
import com.wellsync.data.remote.Part
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthRepository @Inject constructor(
    private val geminiApi: GeminiApiService,
    private val syncStateDao: SyncStateDao
) {
    private val apiKey = com.wellsync.BuildConfig.API_KEY

    private val modelName = "gemini-2.5-flash" // Updated to currently supported model

    suspend fun getAnalysis(
        historicalData: List<HealthDataRecord>,
        deltaData: List<HealthDataRecord>
    ): String {
        return try {
            val syncState = syncStateDao.getSyncState() ?: SyncState()
            val now = Instant.now().toEpochMilli()

            when {
                // Case 1: Valid existing cache
                syncState.cacheId != null && syncState.cacheExpiryTimestamp > now -> {
                    useCache(syncState.cacheId, deltaData)
                }
                // Case 2: Enough historical data to justify a new cache
                historicalData.isNotEmpty() -> {
                    val historicalJson = Json.encodeToString(historicalData)
                    // A token is roughly 4 characters. Gemini requires minimum 4096 tokens for caching (~16,000 chars).
                    // If the payload is too small, skip the cache creation to save API calls and prevent Rate Limits.
                    if (historicalJson.length > 15000) {
                        try {
                            createNewCache(historicalData, deltaData)
                        } catch (e: retrofit2.HttpException) {
                            if (e.code() == 400) {
                                val errorBody = e.response()?.errorBody()?.string() ?: ""
                                if (errorBody.contains("too small")) {
                                    Log.w("WellSync", "Payload too small for caching, falling back to standard API.")
                                    generateStandardAnalysis(historicalData + deltaData)
                                } else {
                                    throw Exception("Gemini API Error: $errorBody")
                                }
                            } else {
                                throw e
                            }
                        }
                    } else {
                        Log.i("WellSync", "Historical data too small for cache (${historicalJson.length} chars). Using standard API.")
                        generateStandardAnalysis(historicalData + deltaData)
                    }
                }
                // Case 3: Fallback for empty/little data
                else -> {
                    generateStandardAnalysis(historicalData + deltaData)
                }
            }
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string() ?: ""
            if (e.code() == 429) {
                Log.e("WellSync", "Gemini API Rate Limit Exceeded (429)")
                throw Exception("Rate limit exceeded. You are making too many requests. Please wait a minute and try again.")
            }
            Log.e("WellSync", "Gemini API Error (${e.code()}): $errorBody")
            throw Exception("Gemini API Error: $errorBody")
        }
    }

    private suspend fun useCache(cacheId: String, deltaData: List<HealthDataRecord>): String {
        val deltaJson = Json.encodeToString(deltaData)
        val prompt = "Here is the latest health data since the last sync: $deltaJson. Please update your analysis based on the historical data you have cached and this new information."
        
        val request = GenerateContentRequest(
            contents = listOf(Content(role = "user", parts = listOf(Part(text = prompt)))),
            cachedContent = cacheId
        )
        
        val response = geminiApi.generateContent(modelName, apiKey, request)
        
        // Update last sync time
        val currentState = syncStateDao.getSyncState() ?: SyncState()
        syncStateDao.updateSyncState(currentState.copy(lastSyncedTimestamp = Instant.now().toEpochMilli()))
        
        return response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "No analysis generated."
    }

    private suspend fun createNewCache(
        historicalData: List<HealthDataRecord>,
        deltaData: List<HealthDataRecord>
    ): String {
        val historicalJson = Json.encodeToString(historicalData)
        val systemInstruction = "You are a health analysis expert. You have access to the user's historical health data (Weight, Steps, BP). Provide deep insights and trends."
        
        val cacheRequest = CachedContentRequest(
            model = "models/$modelName",
            displayName = "WellSyncHistoricalData",
            systemInstruction = Content(role = "system", parts = listOf(Part(text = systemInstruction))),
            contents = listOf(Content(role = "user", parts = listOf(Part(text = "Historical health data: $historicalJson")))),
            ttl = "86400s" // 24 hours
        )
        
        val cacheResponse = geminiApi.createCachedContent(apiKey, cacheRequest)
        
        // Save cache info
        val expiry = OffsetDateTime.parse(cacheResponse.expireTime).toInstant().toEpochMilli()
        syncStateDao.updateSyncState(SyncState(
            cacheId = cacheResponse.name,
            cacheExpiryTimestamp = expiry,
            lastSyncedTimestamp = Instant.now().toEpochMilli()
        ))
        
        // Generate initial analysis
        return useCache(cacheResponse.name, deltaData)
    }

    private suspend fun generateStandardAnalysis(data: List<HealthDataRecord>): String {
        val dataJson = Json.encodeToString(data)
        val systemInstruction = "You are a health analysis expert. Provide insights based on this data: $dataJson"
        
        val request = GenerateContentRequest(
            contents = listOf(Content(role = "user", parts = listOf(Part(text = systemInstruction))))
        )
        
        val response = geminiApi.generateContent(modelName, apiKey, request)
        return response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "No analysis generated (standard)."
    }
}
