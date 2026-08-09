package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class ViewedItem(

    val id: Long,

    val type: String,

    val value: String,

    val createdAt: Long

)