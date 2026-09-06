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
            // GET USER ID FROM TOKEN
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
            // CHECK ROLE
            //
            // PARENT -> allowed
            // ADMIN  -> allowed
            // CHILD  -> forbidden
            // ====================================================

            val role =
                authService
                    .getUserRole(parentUserId)
                    ?.uppercase()

            if (
                role != "PARENT" &&
                role != "ADMIN"
            ) {

                call.respond(
                    HttpStatusCode.Forbidden,
                    RegisterChildResponse(
                        success = false,
                        message =
                            "Only Parent or Admin can create child accounts"
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
                "REGISTER CHILD REQUEST: userId=$parentUserId role=$role username=$username"
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
                    parentUserId =
                        parentUserId,

                    username =
                        username,

                    password =
                        password
                )


            // ====================================================
            // CREATE CHILD FAILED
            // ====================================================

            if (!result.success) {

                val message =
                    result.message
                        ?: "Failed to create child account"


                val statusCode =
                    when {

                        message.contains(
                            "already exists",
                            ignoreCase = true
                        ) ->
                            HttpStatusCode.Conflict


                        message.contains(
                            "Maximum",
                            ignoreCase = true
                        ) ->
                            HttpStatusCode.Conflict


                        message.contains(
                            "not found",
                            ignoreCase = true
                        ) ->
                            HttpStatusCode.NotFound


                        message.contains(
                            "disabled",
                            ignoreCase = true
                        ) ->
                            HttpStatusCode.Forbidden


                        else ->
                            HttpStatusCode.BadRequest
                    }


                call.respond(
                    statusCode,
                    RegisterChildResponse(
                        success = false,
                        message = message
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


            // ====================================================
            // Client gửi request sai
            // ====================================================

            if (
                e is io.ktor.server.plugins.ContentTransformationException
            ) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    RegisterChildResponse(
                        success = false,
                        message =
                            "Invalid request body"
                    )
                )

                return@post
            }


            // ====================================================
            // Lỗi server
            // ====================================================

            call.respond(
                HttpStatusCode.InternalServerError,
                RegisterChildResponse(
                    success = false,
                    message =
                        "Failed to create child account"
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
            // GET USER ID FROM TOKEN
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
            // CHECK ROLE
            //
            // PARENT -> allowed
            // ADMIN  -> allowed
            // CHILD  -> forbidden
            // ====================================================

            val role =
                authService
                    .getUserRole(parentUserId)
                    ?.uppercase()

            if (
                role != "PARENT" &&
                role != "ADMIN"
            ) {

                call.respond(
                    HttpStatusCode.Forbidden,
                    ChildrenResponse(
                        success = false,
                        message =
                            "Only Parent or Admin can access child accounts"
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
                    children =
                        responseChildren
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
