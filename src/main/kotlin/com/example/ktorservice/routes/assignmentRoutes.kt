package com.example.ktorservice.routes

import com.example.ktorservice.model.AssignmentData
import com.example.ktorservice.model.AssignmentGenerateResponse
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

        try {

            val userId =
                call.requireUserId(authService)

            if (userId == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    AssignmentGenerateResponse(
                        success = false,
                        message = "Invalid or expired token"
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

            val topic =
                call.request
                    .queryParameters["topic"]

            if (grade == null || grade !in 1..12) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    AssignmentGenerateResponse(
                        success = false,
                        message = "Invalid grade"
                    )
                )

                return@get
            }

            if (subject.isNullOrBlank()) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    AssignmentGenerateResponse(
                        success = false,
                        message = "Subject is required"
                    )
                )

                return@get
            }

            println("========== GENERATE ASSIGNMENT ==========")
            println("USER ID = $userId")
            println("GRADE = $grade")
            println("SUBJECT = $subject")
            println("TOPIC = $topic")

            // ====================================================
            // GENERATE THROUGH ASSIGNMENT SERVICE
            // ====================================================

            val result =
                assignmentService.getOrGenerateAssignment(
                    grade = grade,
                    subject = subject,
                    topic = topic
                )

            val assignment =
                AssignmentData(
                    title = result.title,
                    content = result.content,
                    answerKey = result.answerKey,
                    gradingGuide = result.gradingGuide,
                    totalScore = result.totalScore
                )

            call.respond(
                HttpStatusCode.OK,
                AssignmentGenerateResponse(
                    success = true,
                    assignment = assignment
                )
            )

        } catch (e: Exception) {

            println(
                "========== GENERATE ASSIGNMENT ERROR =========="
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                AssignmentGenerateResponse(
                    success = false,
                    message =
                        e.message
                            ?: "Unknown error"
                )
            )
        }
    }
}