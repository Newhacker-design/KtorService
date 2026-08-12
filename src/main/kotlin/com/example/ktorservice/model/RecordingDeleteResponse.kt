package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class RecordingDeleteResponse(
    val success: Boolean,
    val fileName: String,
    val message: String
)