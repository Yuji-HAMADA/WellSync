package com.wellsync.di

import android.content.Context
import androidx.room.Room
import retrofit2.converter.kotlinx.serialization.asConverterFactory
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
        ).fallbackToDestructiveMigration(dropAllTables = true).build()
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
            
            // Only retry once if Retry-After is explicitly provided and is reasonable (<= 5 seconds).
            // Otherwise, fail fast to provide immediate feedback to the user.
            if (response.code == 429) {
                val retryAfterHeader = response.header("Retry-After")
                if (retryAfterHeader != null) {
                    try {
                        val waitTimeSec = retryAfterHeader.toLong()
                        if (waitTimeSec <= 5) {
                            android.util.Log.w("WellSync", "Rate limit hit (429). Retrying in $waitTimeSec seconds based on header...")
                            response.close()
                            Thread.sleep(waitTimeSec * 1000)
                            response = chain.proceed(request)
                        }
                    } catch (e: NumberFormatException) {
                        // Ignore
                    }
                }
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
