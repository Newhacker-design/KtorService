package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class VideoUploadResponse(
    val success: Boolean,
    val fileName: String,
    val size: Long,
    val videoUrl: String
)