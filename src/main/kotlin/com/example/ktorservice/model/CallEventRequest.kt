package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class CallEventRequest(
    val event: String,
    val deviceName: String,
    val timestamp: Long
)