package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val success: Boolean,
    val token: String? = null,
    val userId: Int? = null,
    val message: String? = null
)

@Serializable
data class RegisterDeviceRequest(
    val deviceId: String,
    val deviceName: String,
    val appVersion: String
)

@Serializable
data class DeviceResponse(
    val id: Int,
    val deviceId: String,
    val deviceName: String,
    val appVersion: String,
    val status: String,
    val lastSeen: Long
)

@Serializable
data class LicenseResponse(
    val active: Boolean,
    val licenseKey: String? = null,
    val type: String? = null,
    val expiresAt: Long? = null,
    val message: String? = null
)