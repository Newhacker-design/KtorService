package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class ViewedIdsBatchRequest(
    val ids: List<Long>
)