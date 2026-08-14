package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class LatestRecording(

    val name: String,

    val size: Long,

    val lastModified: Long
)