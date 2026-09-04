package com.example.ktorservice.routes

import com.example.ktorservice.model.ChildAccountResponse
import com.example.ktorservice.model.ChildrenResponse
import com.example.ktorservice.model.RegisterChildRequest
import com.example.ktorservice.model.RegisterChildResponse
import com.example.ktorservice.security.requireUserId
import com.example.ktorservice.service.AuthService
import com.example.ktorservice.service.ParentChildService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.parentRoutes(
    authService: AuthService,
    parentChildService: ParentChildService
) {

    // ============================================================
    // REGISTER CHILD
    // ============================================================

    post("/parent/children") {

        try {

            // ====================================================
            // GET PARENT USER ID FROM TOKEN
            // ====================================================

            val parentUserId =
                call.requireUserId(
                    authService
                )

            if (parentUserId == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    RegisterChildResponse(
                        success = false,
                        message =
                            "Invalid or expired token"
                    )
                )

                return@post
            }

            // ====================================================
            // REQUEST
            // ====================================================

            val request =
                call.receive<RegisterChildRequest>()

            val username =
                request.username.trim()

            val password =
                request.password

            println(
                "REGISTER CHILD REQUEST: parentUserId=$parentUserId username=$username"
            )

            // ====================================================
            // VALIDATE USERNAME
            // ====================================================

            if (username.isBlank()) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    RegisterChildResponse(
                        success = false,
                        message =
                            "Username is required"
                    )
                )

                return@post
            }

            if (username.length < 3) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    RegisterChildResponse(
                        success = false,
                        message =
                            "Username must be at least 3 characters"
                    )
                )

                return@post
            }

            // ====================================================
            // VALIDATE PASSWORD
            // ====================================================

            if (password.isBlank()) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    RegisterChildResponse(
                        success = false,
                        message =
                            "Password is required"
                    )
                )

                return@post
            }

            if (password.length < 6) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    RegisterChildResponse(
                        success = false,
                        message =
                            "Password must be at least 6 characters"
                    )
                )

                return@post
            }

            // ====================================================
            // CREATE CHILD
            // ====================================================

            val result =
                parentChildService.registerChild(
                    parentUserId = parentUserId,
                    username = username,
                    password = password
                )

            if (!result.success) {

                val statusCode =
                    when {

                        result.message
                            ?.contains(
                                "already exists",
                                ignoreCase = true
                            ) == true ->
                            HttpStatusCode.Conflict

                        result.message
                            ?.contains(
                                "Maximum",
                                ignoreCase = true
                            ) == true ->
                            HttpStatusCode.Conflict

                        result.message
                            ?.contains(
                                "not found",
                                ignoreCase = true
                            ) == true ->
                            HttpStatusCode.NotFound

                        else ->
                            HttpStatusCode.BadRequest
                    }

                call.respond(
                    statusCode,
                    RegisterChildResponse(
                        success = false,
                        message = result.message
                    )
                )

                return@post
            }

            // ====================================================
            // SUCCESS
            // ====================================================

            call.respond(
                HttpStatusCode.Created,
                RegisterChildResponse(
                    success = true,
                    child =
                        ChildAccountResponse(
                            userId =
                                result.childUserId!!,

                            username =
                                result.username!!,

                            status =
                                result.status!!
                        ),
                    message =
                        "Child account created successfully"
                )
            )

        } catch (e: Exception) {

            e.printStackTrace()

            println(
                "========== REGISTER CHILD ERROR =========="
            )

            println(
                "TYPE = ${e::class.qualifiedName}"
            )

            println(
                "MESSAGE = ${e.message}"
            )

            println(
                "=========================================="
            )

            call.respond(
                HttpStatusCode.BadRequest,
                RegisterChildResponse(
                    success = false,
                    message =
                        "Invalid request: ${e.message}"
                )
            )
        }
    }

    // ============================================================
    // GET CHILDREN
    // ============================================================

    get("/parent/children") {

        try {

            // ====================================================
            // GET PARENT USER ID FROM TOKEN
            // ====================================================

            val parentUserId =
                call.requireUserId(
                    authService
                )

            if (parentUserId == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    ChildrenResponse(
                        success = false,
                        message =
                            "Invalid or expired token"
                    )
                )

                return@get
            }

            // ====================================================
            // GET CHILDREN
            // ====================================================

            val children =
                parentChildService.getChildren(
                    parentUserId
                )

            val responseChildren =
                children.map {

                    ChildAccountResponse(
                        userId =
                            it.userId,

                        username =
                            it.username,

                        status =
                            it.status
                    )
                }

            // ====================================================
            // RESPONSE
            // ====================================================

            call.respond(
                HttpStatusCode.OK,
                ChildrenResponse(
                    success = true,
                    children = responseChildren
                )
            )

        } catch (e: Exception) {

            e.printStackTrace()

            println(
                "========== GET CHILDREN ERROR =========="
            )

            println(
                "TYPE = ${e::class.qualifiedName}"
            )

            println(
                "MESSAGE = ${e.message}"
            )

            println(
                "========================================"
            )

            call.respond(
                HttpStatusCode.InternalServerError,
                ChildrenResponse(
                    success = false,
                    message =
                        "Failed to load child accounts"
                )
            )
        }
    }
}