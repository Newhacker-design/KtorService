package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class RegisterChildRequest(
    val username: String,
    val password: String
)

@Serializable
data class ChildAccountResponse(
    val userId: Int,
    val username: String,
    val status: String
)

@Serializable
data class RegisterChildResponse(
    val success: Boolean,
    val child: ChildAccountResponse? = null,
    val message: String? = null
)

@Serializable
data class ChildrenResponse(
    val success: Boolean,
    val children: List<ChildAccountResponse> = emptyList(),
    val message: String? = null
)