package com.wellsync.data.model

import kotlinx.serialization.Serializable

@Serializable
data class HealthDataRecord(
    val type: String,
    val value: Double,
    val unit: String,
    val timestamp: Long
)

@Serializable
data class HealthDataBatch(
    val records: List<HealthDataRecord>
)
