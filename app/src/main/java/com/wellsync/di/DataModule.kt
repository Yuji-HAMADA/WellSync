package com.wellsync.di

import android.content.Context
import androidx.room.Room
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.wellsync.data.local.SyncStateDao
import com.wellsync.data.local.WellSyncDatabase
import com.wellsync.data.remote.GeminiApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): WellSyncDatabase {
        return Room.databaseBuilder(
            context,
            WellSyncDatabase::class.java,
            "wellsync_db"
        ).build()
    }

    @Provides
    fun provideSyncStateDao(db: WellSyncDatabase): SyncStateDao = db.syncStateDao()

    @Provides
    @Singleton
    fun provideGeminiApi(): GeminiApiService {
        val json = Json { 
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val rateLimitInterceptor = okhttp3.Interceptor { chain ->
            val request = chain.request()
            var response = chain.proceed(request)
            var tryCount = 0
            val maxRetries = 3

            while (response.code == 429 && tryCount < maxRetries) {
                tryCount++
                android.util.Log.w("WellSync", "Rate limit hit (429). Retrying in ${tryCount * 2} seconds...")
                response.close()
                Thread.sleep((tryCount * 2000).toLong())
                response = chain.proceed(request)
            }
            response
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(rateLimitInterceptor)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiApiService::class.java)
    }
}
