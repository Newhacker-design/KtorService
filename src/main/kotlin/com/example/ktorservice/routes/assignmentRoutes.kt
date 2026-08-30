package com.example.ktorservice.routes

import com.example.ktorservice.model.AssignmentData
import com.example.ktorservice.model.AssignmentGenerateResponse
import com.example.ktorservice.model.UserAssignmentResponse
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
    get("/assignments/today") {

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

            println(
                "========== GET TODAY ASSIGNMENT =========="
            )

            println("USER ID = $userId")
            println("GRADE = $grade")
            println("SUBJECT = $subject")

            val result =
                assignmentService.getTodayAssignment(
                    userId = userId,
                    grade = grade,
                    subject = subject
                )

            call.respond(
                HttpStatusCode.OK,
                UserAssignmentResponse(
                    success = true,
                    id = result.id,
                    assignmentId = result.assignmentId,
                    userId = result.userId,
                    status = result.status,
                    answer = result.answer,
                    score = result.score,
                    feedback = result.feedback,
                    startedAt = result.startedAt,
                    completedAt = result.completedAt,
                    assignment = AssignmentData(
                        title = result.assignment.title,
                        content = result.assignment.content,
                        answerKey = result.assignment.answerKey,
                        gradingGuide = result.assignment.gradingGuide,
                        totalScore = result.assignment.totalScore
                    )
                )
            )

        } catch (e: Exception) {

            println(
                "========== GET TODAY ASSIGNMENT ERROR =========="
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "success" to false,
                    "message" to (
                            e.message
                                ?: "Unknown error"
                            )
                )
            )
        }
    }
}