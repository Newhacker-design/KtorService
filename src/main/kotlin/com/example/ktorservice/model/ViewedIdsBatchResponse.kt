package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class ViewedIdsBatchResponse(
    val success: Boolean,
    val count: Int,
    val message: String
)