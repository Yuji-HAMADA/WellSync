package com.wellsync.data.remote

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface GeminiApiService {
    @GET("v1beta/models")
    suspend fun listModels(
        @Query("key") apiKey: String
    ): ListModelsResponse

    @POST("v1beta/cachedContents")
    suspend fun createCachedContent(
        @Query("key") apiKey: String,
        @Body request: CachedContentRequest
    ): CachedContentResponse

    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}
