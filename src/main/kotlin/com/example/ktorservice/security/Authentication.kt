package com.example.ktorservice.security

import com.example.ktorservice.service.AuthService
import io.ktor.server.application.*

fun ApplicationCall.getBearerToken(): String? {

    val header =
        request.headers[
            "Authorization"
        ]

    if (
        header.isNullOrBlank()
    ) {
        return null
    }

    if (
        !header.startsWith(
            "Bearer ",
            ignoreCase = true
        )
    ) {
        return null
    }

    return header
        .substring(7)
        .trim()
        .takeIf {
            it.isNotEmpty()
        }
}

fun ApplicationCall.requireUserId(
    authService: AuthService
): Int? {

    val token =
        getBearerToken()
            ?: return null

    return authService.getUserId(
        token
    )
}