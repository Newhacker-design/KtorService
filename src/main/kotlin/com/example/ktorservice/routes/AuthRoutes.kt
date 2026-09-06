package com.example.ktorservice.routes

import com.example.ktorservice.model.CreateChildSessionRequest
import com.example.ktorservice.model.CreateChildSessionResponse
import com.example.ktorservice.model.LoginRequest
import com.example.ktorservice.model.LoginResponse
import com.example.ktorservice.model.MeResponse
import com.example.ktorservice.model.RegisterRequest
import com.example.ktorservice.model.RegisterResponse
import com.example.ktorservice.security.requireUserId
import com.example.ktorservice.service.AuthService
import com.example.ktorservice.service.ParentChildService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.authRoutes(
    authService: AuthService,
    parentChildService: ParentChildService
) {

    // ============================================================
    // POST /auth/login
    // ============================================================

    post("/auth/login") {

        val request =
            call.receive<LoginRequest>()

        val result =
            authService.login(
                request.username,
                request.password
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
            LoginResponse(
                success = true,
                token = result.token,
                userId = result.userId,
                role = result.role
            )
        )
    }


    // ============================================================
    // POST /auth/register
    //
    // Đăng ký thông thường luôn tạo PARENT
    // ============================================================

    post("/auth/register") {

        val request =
            call.receive<RegisterRequest>()

        val result =
            authService.register(
                request.username,
                request.password
            )

        if (!result.success) {

            call.respond(
                HttpStatusCode.BadRequest,
                RegisterResponse(
                    success = false,
                    message = result.message
                )
            )

            return@post
        }

        call.respond(
            RegisterResponse(
                success = true,
                userId = result.userId,
                username = result.username
            )
        )
    }


    // ============================================================
    // POST /auth/child-session
    //
    // Parent/Admin tạo session token cho Child
    // ============================================================

    post("/auth/child-session") {

        val callerUserId =
            call.requireUserId(authService)

        if (callerUserId == null) {

            call.respond(
                HttpStatusCode.Unauthorized,
                CreateChildSessionResponse(
                    success = false,
                    message = "Unauthorized"
                )
            )

            return@post
        }


        // --------------------------------------------------------
        // Kiểm tra role của người gọi
        // --------------------------------------------------------

        val callerRole =
            authService
                .getUserRole(callerUserId)
                ?.uppercase()

        if (
            callerRole != "PARENT" &&
            callerRole != "ADMIN"
        ) {

            call.respond(
                HttpStatusCode.Forbidden,
                CreateChildSessionResponse(
                    success = false,
                    message = "Only Parent or Admin can create child session"
                )
            )

            return@post
        }


        val request =
            call.receive<CreateChildSessionRequest>()


        // --------------------------------------------------------
        // Kiểm tra Child thuộc Parent
        //
        // ADMIN:
        //     được quản lý Child mà không cần quan hệ parent_children
        //
        // PARENT:
        //     bắt buộc Child phải thuộc Parent đó
        // --------------------------------------------------------

        if (callerRole != "ADMIN") {

            val isChild =
                parentChildService.isChildOfParent(
                    parentUserId = callerUserId,
                    childUserId = request.childUserId
                )

            if (!isChild) {

                call.respond(
                    HttpStatusCode.Forbidden,
                    CreateChildSessionResponse(
                        success = false,
                        message = "Child does not belong to this parent"
                    )
                )

                return@post
            }
        }


        // --------------------------------------------------------
        // Tạo session cho Child
        // --------------------------------------------------------

        val result =
            authService.createSession(
                request.childUserId
            )

        if (!result.success) {

            call.respond(
                HttpStatusCode.BadRequest,
                CreateChildSessionResponse(
                    success = false,
                    message = result.message
                )
            )

            return@post
        }


        call.respond(
            CreateChildSessionResponse(
                success = true,
                token = result.token,
                userId = result.userId,
                role = result.role
            )
        )
    }


    // ============================================================
    // GET /auth/me
    // ============================================================

    get("/auth/me") {

        val userId =
            call.requireUserId(authService)

        if (userId == null) {

            call.respond(
                HttpStatusCode.Unauthorized
            )

            return@get
        }


        val role =
            authService.getUserRole(userId)


        call.respond(
            MeResponse(
                success = true,
                userId = userId,
                role = role
            )
        )
    }


    // ============================================================
    // POST /auth/logout
    // ============================================================

    post("/auth/logout") {

        val token =
            call.request
                .headers["Authorization"]
                ?.removePrefix("Bearer ")
                ?.trim()

        if (token.isNullOrBlank()) {

            call.respond(
                HttpStatusCode.Unauthorized
            )

            return@post
        }


        val success =
            authService.logout(token)


        call.respond(
            mapOf(
                "success" to success
            )
        )
    }
}
