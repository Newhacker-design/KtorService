package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class VideoDeleteResponse(
    val success: Boolean,
    val fileName: String? = null,
    val message: String? = null
)