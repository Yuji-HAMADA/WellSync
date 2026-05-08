package com.wellsync.data.remote

import kotlinx.serialization.Serializable

@Serializable
data class CachedContentRequest(
    val model: String,
    val name: String? = null,
    val displayName: String? = null,
    val systemInstruction: Content? = null,
    val contents: List<Content>,
    val ttl: String? = null // e.g. "86400s"
)

@Serializable
data class CachedContentResponse(
    val name: String,
    val expireTime: String
)

@Serializable
data class GenerateContentRequest(
    val contents: List<Content>,
    val cachedContent: String? = null
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate>
)

@Serializable
data class Candidate(
    val content: Content
)

@Serializable
data class Content(
    val role: String? = null,
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String
)

@Serializable
data class ListModelsResponse(
    val models: List<ModelInfo>
)

@Serializable
data class ModelInfo(
    val name: String,
    val displayName: String,
    val supportedGenerationMethods: List<String>
)
