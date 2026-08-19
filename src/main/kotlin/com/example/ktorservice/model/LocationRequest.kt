package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class LocationRequest(
    val latitude: Double,
    val longitude: Double,
    val deviceName: String,
    val timestamp: Long
)