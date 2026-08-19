package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class CallEventResponse(
    val success: Boolean,
    val message: String,
    val deviceName: String? = null,
    val event: String? = null
)