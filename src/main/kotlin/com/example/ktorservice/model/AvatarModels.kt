package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class AvatarUploadRequest(
    val imageBase64: String,
    val contentType: String = "image/jpeg"
)

@Serializable
data class AvatarUploadResponse(
    val success: Boolean,
    val message: String? = null
)