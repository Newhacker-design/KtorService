package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class LocationResponse(
    val success: Boolean,
    val message: String,
    val deviceName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timestamp: Long? = null
)