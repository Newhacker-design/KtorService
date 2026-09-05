package com.example.ktorservice.routes

import com.example.ktorservice.model.CreateChildSessionRequest
import com.example.ktorservice.model.CreateChildSessionResponse
import com.example.ktorservice.model.LoginRequest
import com.example.ktorservice.model.LoginResponse
import com.example.ktorservice.model.RegisterRequest
import com.example.ktorservice.service.AuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.example.ktorservice.model.RegisterResponse
import com.example.ktorservice.security.getBearerToken
import com.example.ktorservice.security.requireUserId
import com.example.ktorservice.service.ParentChildService

fun Route.authRoutes(
    authService: AuthService,
    parentChildService: ParentChildService
) {

    post("/auth/login") {

        val request =
            call.receive<LoginRequest>()

        val result =
            authService.login(
                username = request.username,
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
            LoginResponse(
                success = true,
                token = result.token,
                userId = result.userId
            )
        )
    }


    post("/auth/register") {

        val request =
            call.receive<RegisterRequest>()

        val result =
            authService.register(
                username = request.username,
                password = request.password
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


    /*
     * ============================================================
     * CREATE SESSION FOR CHILD
     *
     * Parent phải đăng nhập trước.
     *
     * POST /auth/child-session
     *
     * {
     *     "childUserId": 6
     * }
     * ============================================================
     */
    post("/auth/child-session") {

        val parentUserId =
            call.requireUserId(
                authService
            )

        if (parentUserId == null) {

            call.respond(
                HttpStatusCode.Unauthorized,
                CreateChildSessionResponse(
                    success = false,
                    message = "Unauthorized"
                )
            )

            return@post
        }

        val request =
            call.receive<CreateChildSessionRequest>()

        /*
         * Không cho Parent tự ý tạo session cho
         * một Child không thuộc quyền quản lý.
         */
        val isChild =
            parentChildService.isChildOfParent(
                parentUserId = parentUserId,
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

        /*
         * Tạo session với userId = childUserId.
         */
        val result =
            authService.createSession(
                userId = request.childUserId
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
                userId = result.userId
            )
        )
    }


    get("/auth/me") {

        val userId =
            call.requireUserId(
                authService
            )

        if (userId == null) {

            call.respond(
                HttpStatusCode.Unauthorized
            )

            return@get
        }

        call.respond(
            mapOf(
                "success" to true,
                "userId" to userId
            )
        )
    }


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
