package com.example.ktorservice.routes

import com.example.ktorservice.security.requireUserId
import com.example.ktorservice.service.AssignmentService
import com.example.ktorservice.service.AuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.assignmentRoutes(
    authService: AuthService,
    assignmentService: AssignmentService
) {

    // ============================================================
    // GET /assignments/generate
    // ============================================================

    get("/assignments/generate") {

        val userId =
            call.requireUserId(
                authService
            )

        if (userId == null) {

            call.respond(
                HttpStatusCode.Unauthorized,
                mapOf(
                    "success" to false,
                    "message" to
                            "Invalid or expired token"
                )
            )

            return@get
        }

        val grade =
            call.request
                .queryParameters["grade"]
                ?.toIntOrNull()

        val subject =
            call.request
                .queryParameters["subject"]
                ?.trim()

        val topic =
            call.request
                .queryParameters["topic"]
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        if (grade == null || grade !in 1..12) {

            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "success" to false,
                    "message" to
                            "grade must be between 1 and 12"
                )
            )

            return@get
        }

        if (subject.isNullOrBlank()) {

            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "success" to false,
                    "message" to
                            "subject is required"
                )
            )

            return@get
        }

        try {

            val result =
                assignmentService
                    .getOrGenerateAssignment(
                        grade = grade,
                        subject = subject,
                        topic = topic
                    )

            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "success" to true,
                    "generated" to true,
                    "assignment" to result
                )
            )

        } catch (e: Exception) {

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "success" to false,
                    "message" to
                            (
                                    e.message
                                        ?: "Failed to generate assignment"
                                    )
                )
            )
        }
    }
}