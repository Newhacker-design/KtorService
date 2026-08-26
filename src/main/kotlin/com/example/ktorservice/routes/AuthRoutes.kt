package com.example.ktorservice.routes

import com.example.ktorservice.model.LoginRequest
import com.example.ktorservice.model.LoginResponse
import com.example.ktorservice.service.AuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.example.ktorservice.model.RegisterRequest
import com.example.ktorservice.model.RegisterResponse
fun Route.authRoutes(
    authService: AuthService
) {

    post("/auth/login") {

        try {

            val request =
                call.receive<LoginRequest>()

            println(
                "LOGIN REQUEST: username=${request.username}"
            )

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
                    username = request.username.trim(),
                    password = request.password
                )

            if (!result.success) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    LoginResponse(
                        success = false,
                        message = result.message
                    )
                )

                return@post
            }

            call.respond(
                HttpStatusCode.OK,
                LoginResponse(
                    success = true,
                    token = result.token,
                    userId = result.userId
                )
            )

        } catch (e: Exception) {

            e.printStackTrace()

            println(
                "LOGIN ERROR: ${e::class.qualifiedName}: ${e.message}"
            )

            call.respond(
                HttpStatusCode.BadRequest,
                LoginResponse(
                    success = false,
                    message =
                        "Invalid request: ${e.message}"
                )
            )
        }
    }
    post("/auth/register") {

        try {

            val request =
                call.receive<RegisterRequest>()

            val username =
                request.username.trim()

            val password =
                request.password

            println(
                "REGISTER REQUEST: username=$username"
            )

            if (username.isBlank()) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    RegisterResponse(
                        success = false,
                        message = "Username is required"
                    )
                )

                return@post
            }

            if (password.isBlank()) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    RegisterResponse(
                        success = false,
                        message = "Password is required"
                    )
                )

                return@post
            }

            if (username.length < 3) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    RegisterResponse(
                        success = false,
                        message =
                            "Username must be at least 3 characters"
                    )
                )

                return@post
            }

            if (password.length < 6) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    RegisterResponse(
                        success = false,
                        message =
                            "Password must be at least 6 characters"
                    )
                )

                return@post
            }

            val result =
                authService.register(
                    username = username,
                    password = password
                )

            if (!result.success) {

                call.respond(
                    HttpStatusCode.Conflict,
                    RegisterResponse(
                        success = false,
                        message = result.message
                    )
                )

                return@post
            }

            call.respond(
                HttpStatusCode.Created,
                RegisterResponse(
                    success = true,
                    userId = result.userId,
                    username = result.username,
                    message = "User created successfully"
                )
            )

        } catch (e: Exception) {

            e.printStackTrace()

            println(
                "REGISTER ERROR: " +
                        "${e::class.qualifiedName}: ${e.message}"
            )

            call.respond(
                HttpStatusCode.BadRequest,
                RegisterResponse(
                    success = false,
                    message =
                        "Invalid request: ${e.message}"
                )
            )
        }
    }
}