package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class LocationRequest(
    val latitude: String,
    val longitude: String,
    val deviceName: String,
    val timestamp: Long
)