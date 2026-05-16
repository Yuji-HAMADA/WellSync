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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class FormattedHealthRecord(
    val type: String,
    val value: Double,
    val unit: String,
    val date: String
)

@Singleton
class HealthRepository @Inject constructor(
    private val geminiApi: GeminiApiService,
    private val syncStateDao: SyncStateDao
) {
    private val apiKey = com.wellsync.BuildConfig.API_KEY

    private val modelName = "gemini-2.5-flash-lite" // Updated to currently supported model

    private fun getSystemInstruction(syncState: SyncState, additionalContext: String): String {
        val baseRole = when (syncState.promptType) {
            1 -> "You are a kind and gentle health coach. Praise the user's efforts enthusiastically, provide gentle encouragement, and offer mild suggestions for improvement. Keep the tone very warm and supportive."
            2 -> "You are a strict and demanding fitness trainer. Point out the user's shortcomings bluntly, use tough love to motivate them, and give firm, no-nonsense advice for improvement. Keep the tone intense and challenging."
            3 -> syncState.customPrompt?.takeIf { it.isNotBlank() } ?: "You are a health analysis expert."
            else -> "You are a health analysis expert." // Default
        }
        return "$baseRole $additionalContext Always respond in Japanese."
    }

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
                            createNewCache(historicalData, deltaData, syncState)
                        } catch (e: retrofit2.HttpException) {
                            if (e.code() == 400) {
                                val errorBody = e.response()?.errorBody()?.string() ?: ""
                                if (errorBody.contains("too small")) {
                                    Log.w("WellSync", "Payload too small for caching, falling back to standard API.")
                                    generateStandardAnalysis(historicalData + deltaData, syncState)
                                } else {
                                    throw Exception("Gemini API Error: $errorBody")
                                }
                            } else {
                                throw e
                            }
                        }
                    } else {
                        Log.i("WellSync", "Historical data too small for cache (${historicalJson.length} chars). Using standard API.")
                        generateStandardAnalysis(historicalData + deltaData, syncState)
                    }
                }
                // Case 3: Fallback for empty/little data
                else -> {
                    generateStandardAnalysis(historicalData + deltaData, syncState)
                }
            }
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string() ?: ""
            if (e.code() == 429) {
                Log.e("WellSync", "Gemini API Rate Limit Exceeded (429)")
                throw Exception("Rate limit exceeded. Please wait a minute and try again.")
            }
            Log.e("WellSync", "Gemini API Error (${e.code()}): $errorBody")
            throw Exception("Gemini API Error: $errorBody")
        }
    }

    private suspend fun useCache(cacheId: String, deltaData: List<HealthDataRecord>): String {
        // Convert timestamps to human-readable strings for the AI
        val formattedDelta = deltaData.map { 
            FormattedHealthRecord(it.type, it.value, it.unit, Instant.ofEpochMilli(it.timestamp).atOffset(OffsetDateTime.now().offset).toString())
        }
        val deltaJson = Json.encodeToString(formattedDelta)
        val now = OffsetDateTime.now()
        val prompt = "Current date and time: ${now}. Here is the latest health data since the last sync: $deltaJson. Please update your analysis based on the historical data you have cached and this new information. Always respond in Japanese."
        
        val request = GenerateContentRequest(
            contents = listOf(Content(role = "user", parts = listOf(Part(text = prompt)))),
            cachedContent = cacheId
        )
        
        val response = geminiApi.generateContent(modelName, apiKey, request)
        val result = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "No analysis generated."

        Log.d("WellSync", "AI Response (Cached):\n$result")

        // Update last sync time and analysis
        val currentState = syncStateDao.getSyncState() ?: SyncState()
        syncStateDao.updateSyncState(currentState.copy(
            lastSyncedTimestamp = Instant.now().toEpochMilli(),
            lastAnalysis = result
        ))
        
        return result
    }

    private suspend fun createNewCache(
        historicalData: List<HealthDataRecord>,
        deltaData: List<HealthDataRecord>,
        syncState: SyncState
    ): String {
        val formattedHistorical = historicalData.map { 
            FormattedHealthRecord(it.type, it.value, it.unit, Instant.ofEpochMilli(it.timestamp).atOffset(OffsetDateTime.now().offset).toString())
        }
        val historicalJson = Json.encodeToString(formattedHistorical)
        val now = OffsetDateTime.now()
        val context = "Current date and time: ${now}. You have access to the user's historical health data (Weight, Steps, BP). Provide deep insights and trends."
        val systemInstruction = getSystemInstruction(syncState, context)
        
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
        val currentState = syncStateDao.getSyncState() ?: SyncState()
        syncStateDao.updateSyncState(currentState.copy(
            cacheId = cacheResponse.name,
            cacheExpiryTimestamp = expiry,
            lastSyncedTimestamp = Instant.now().toEpochMilli()
        ))
        
        // Generate initial analysis
        return useCache(cacheResponse.name, deltaData)
    }

    private suspend fun generateStandardAnalysis(data: List<HealthDataRecord>, syncState: SyncState): String {
        val formattedData = data.map { 
            FormattedHealthRecord(it.type, it.value, it.unit, Instant.ofEpochMilli(it.timestamp).atOffset(OffsetDateTime.now().offset).toString())
        }
        val dataJson = Json.encodeToString(formattedData)
        val now = OffsetDateTime.now()
        val context = "Current date and time: ${now}. Provide insights based on this data: $dataJson."
        val systemInstruction = getSystemInstruction(syncState, context)
        
        val request = GenerateContentRequest(
            contents = listOf(Content(role = "user", parts = listOf(Part(text = systemInstruction))))
        )
        
        val response = geminiApi.generateContent(modelName, apiKey, request)
        val result = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "No analysis generated (standard)."

        Log.d("WellSync", "AI Response (Standard):\n$result")

        val currentState = syncStateDao.getSyncState() ?: SyncState()
        syncStateDao.updateSyncState(currentState.copy(
            lastSyncedTimestamp = Instant.now().toEpochMilli(),
            lastAnalysis = result
        ))

        return result
    }
}
