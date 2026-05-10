package com.wellsync.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.wellsync.data.model.HealthDataRecord
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    val requiredPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class)
    )

    suspend fun isAvailable(): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    suspend fun hasAllPermissions(): Boolean {
        if (!isAvailable()) return false
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        return granted.containsAll(requiredPermissions)
    }

    suspend fun readHealthData(startTime: Instant, endTime: Instant): List<HealthDataRecord> {
        val records = mutableListOf<HealthDataRecord>()

        // Read Steps
        try {
            val stepsRequest = ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            healthConnectClient.readRecords(stepsRequest).records.forEach {
                records.add(HealthDataRecord("steps", it.count.toDouble(), "count", it.startTime.toEpochMilli()))
            }
        } catch (e: Exception) {
            // Log or ignore if permission missing or read fails
        }

        // Read Weight
        try {
            val weightRequest = ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            healthConnectClient.readRecords(weightRequest).records.forEach {
                records.add(HealthDataRecord("weight", it.weight.inKilograms, "kg", it.time.toEpochMilli()))
            }
        } catch (e: Exception) {
            // Log or ignore
        }

        // Read Blood Pressure
        try {
            val bpRequest = ReadRecordsRequest(
                recordType = BloodPressureRecord::class,
                timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
            )
            healthConnectClient.readRecords(bpRequest).records.forEach {
                records.add(HealthDataRecord("systolic_bp", it.systolic.inMillimetersOfMercury, "mmHg", it.time.toEpochMilli()))
                records.add(HealthDataRecord("diastolic_bp", it.diastolic.inMillimetersOfMercury, "mmHg", it.time.toEpochMilli()))
            }
        } catch (e: Exception) {
            // Log or ignore
        }

        return records.sortedBy { it.timestamp }
    }
}
