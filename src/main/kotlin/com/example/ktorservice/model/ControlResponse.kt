package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class ControlResponse(
    val command: String,
    val text: String,
    val videoUrl: String? = null
)