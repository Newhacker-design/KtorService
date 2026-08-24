package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class VideoInfo(
    val name: String,
    val size: Long,
    val url: String
)