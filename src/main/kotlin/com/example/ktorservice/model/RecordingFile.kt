package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class RecordingFile(
    val fileName: String,
    val size: Long,
    val lastModified: Long
)