package com.example.ktorservice.routes

import com.example.ktorservice.model.LoginRequest
import com.example.ktorservice.model.LoginResponse
import com.example.ktorservice.service.AuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(
    authService: AuthService
) {

    post("/auth/login") {

        val request =
            call.receive<LoginRequest>()

        if (
            request.username.isBlank() ||
            request.password.isBlank()
        ) {

            call.respond(
                HttpStatusCode.BadRequest,
                LoginResponse(
                    success = false,
                    message =
                        "Username and password are required"
                )
            )

            return@post
        }

        val result =
            authService.login(
                username =
                    request.username.trim(),
                password =
                    request.password
            )

        if (!result.success) {

            call.respond(
                HttpStatusCode.Unauthorized,
                LoginResponse(
                    success = false,
                    message =
                        result.message
                )
            )

            return@post
        }

        call.respond(
            LoginResponse(
                success = true,
                token =
                    result.token,
                userId =
                    result.userId
            )
        )
    }
}