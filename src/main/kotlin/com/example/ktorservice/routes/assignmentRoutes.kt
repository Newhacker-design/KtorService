package com.example.ktorservice.routes

import com.example.ktorservice.model.AssignmentActionResponse
import com.example.ktorservice.model.AssignmentData
import com.example.ktorservice.model.AssignmentGenerateResponse
import com.example.ktorservice.model.AssignmentListResponse
import com.example.ktorservice.model.AssignmentStorageData
import com.example.ktorservice.model.AssignmentStudentData
import com.example.ktorservice.model.AssignmentSubmitRequest
import com.example.ktorservice.model.UserAssignmentResponse
import com.example.ktorservice.security.requireUserId
import com.example.ktorservice.service.AssignmentService
import com.example.ktorservice.service.AuthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*

fun Route.assignmentRoutes(
    authService: AuthService,
    assignmentService: AssignmentService
) {
    // ============================================================
// GET ASSIGNMENT FROM STORAGE
// ============================================================

    get("/assignments/{id}") {

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

            val id =
                call.parameters["id"]
                    ?.toIntOrNull()

            if (id == null) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    AssignmentGenerateResponse(
                        success = false,
                        message = "Invalid assignment id"
                    )
                )

                return@get
            }

            println("========== GET ASSIGNMENT FROM STORAGE ==========")
            println("USER ID = $userId")
            println("ASSIGNMENT ID = $id")

            val result =
                assignmentService.getById(id)

            if (result == null) {

                call.respond(
                    HttpStatusCode.NotFound,
                    AssignmentGenerateResponse(
                        success = false,
                        message = "Assignment not found"
                    )
                )

                return@get
            }

            println("ASSIGNMENT FOUND")
            println("TITLE = ${result.title}")
            println("GRADE = ${result.grade}")
            println("SUBJECT = ${result.subject}")

            call.respond(
                HttpStatusCode.OK,
                mapOf(
                    "success" to true,
                    "assignment" to AssignmentStudentData(
                        id = result.id,
                        grade = result.grade,
                        subject = result.subject,
                        topic = result.topic,
                        title = result.title,
                        content = result.content,
                        totalScore = result.totalScore
                    )
                )
            )

        } catch (e: Exception) {

            println(
                "========== GET ASSIGNMENT ERROR =========="
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf(
                    "success" to false,
                    "message" to (
                            e.message ?: "Unknown error"
                            )
                )
            )
        }
    }
// ============================================================
// GET /assignments
// GET ASSIGNMENTS FROM STORAGE
// ============================================================

    get("/assignments") {

        try {

            val userId =
                call.requireUserId(authService)

            if (userId == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    AssignmentListResponse(
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

            println(
                "========== GET ASSIGNMENT STORAGE =========="
            )

            println("USER ID = $userId")
            println("GRADE = $grade")
            println("SUBJECT = $subject")
            println("TOPIC = $topic")

            val assignments =
                assignmentService.getAllAssignments(
                    grade = grade,
                    subject = subject,
                    topic = topic
                )

            println(
                "ASSIGNMENT STORAGE COUNT = ${assignments.size}"
            )

            call.respond(
                HttpStatusCode.OK,
                AssignmentListResponse(
                    success = true,
                    assignments =
                        assignments.map { assignment ->

                            AssignmentStorageData(
                                id = assignment.id,
                                grade = assignment.grade,
                                subject = assignment.subject,
                                topic = assignment.topic,
                                title = assignment.title,
                                content = assignment.content,
                                answerKey = assignment.answerKey,
                                gradingGuide = assignment.gradingGuide,
                                totalScore = assignment.totalScore
                            )
                        }
                )
            )

        } catch (e: Exception) {

            println(
                "========== GET ASSIGNMENT STORAGE ERROR =========="
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                AssignmentListResponse(
                    success = false,
                    message =
                        e.message
                            ?: "Unknown error"
                )
            )
        }
    }
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
    get("/assignments/{id}") {

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

            val id =
                call.parameters["id"]
                    ?.toIntOrNull()

            if (id == null) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    AssignmentGenerateResponse(
                        success = false,
                        message = "Invalid assignment id"
                    )
                )

                return@get
            }

            println(
                "========== GET ASSIGNMENT BY ID =========="
            )

            println("USER ID = $userId")
            println("ASSIGNMENT ID = $id")

            val result =
                assignmentService.getById(id)

            if (result == null) {

                call.respond(
                    HttpStatusCode.NotFound,
                    AssignmentGenerateResponse(
                        success = false,
                        message = "Assignment not found"
                    )
                )

                return@get
            }

            call.respond(
                HttpStatusCode.OK,
                AssignmentGenerateResponse(
                    success = true,
                    assignment = AssignmentData(
                        title = result.title,
                        content = result.content,
                        answerKey = result.answerKey,
                        gradingGuide = result.gradingGuide,
                        totalScore = result.totalScore
                    )
                )
            )

        } catch (e: Exception) {

            println(
                "========== GET ASSIGNMENT BY ID ERROR =========="
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
                    assignment = AssignmentStudentData(
                        id = result.assignment.id,
                        grade = result.assignment.grade,
                        subject = result.assignment.subject,
                        topic = result.assignment.topic,
                        title = result.assignment.title,
                        content = result.assignment.content,
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
    post("/assignments/{id}/start") {

        try {

            val userId =
                call.requireUserId(authService)

            if (userId == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    AssignmentActionResponse(
                        success = false,
                        message = "Invalid or expired token"
                    )
                )

                return@post
            }

            val id =
                call.parameters["id"]
                    ?.toIntOrNull()

            if (id == null) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    AssignmentActionResponse(
                        success = false,
                        message = "Invalid assignment id"
                    )
                )

                return@post
            }

            println(
                "========== START ASSIGNMENT =========="
            )

            println("USER ID = $userId")
            println("USER ASSIGNMENT ID = $id")

            val result =
                assignmentService.startAssignment(
                    userId = userId,
                    userAssignmentId = id
                )

            if (!result) {

                call.respond(
                    HttpStatusCode.NotFound,
                    AssignmentActionResponse(
                        success = false,
                        message = "Assignment not found"
                    )
                )

                return@post
            }

            call.respond(
                HttpStatusCode.OK,
                AssignmentActionResponse(
                    success = true,
                    status = "IN_PROGRESS",
                    message = "Assignment started"
                )
            )

        } catch (e: Exception) {

            println(
                "========== START ASSIGNMENT ERROR =========="
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                AssignmentActionResponse(
                    success = false,
                    message =
                        e.message
                            ?: "Unknown error"
                )
            )
        }
    }
    post("/assignments/{id}/submit") {

        try {

            val userId =
                call.requireUserId(authService)

            if (userId == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    AssignmentActionResponse(
                        success = false,
                        message = "Invalid or expired token"
                    )
                )

                return@post
            }

            val id =
                call.parameters["id"]
                    ?.toIntOrNull()

            if (id == null) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    AssignmentActionResponse(
                        success = false,
                        message = "Invalid assignment id"
                    )
                )

                return@post
            }

            val request =
                call.receive<AssignmentSubmitRequest>()

            if (request.answer.isBlank()) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    AssignmentActionResponse(
                        success = false,
                        message = "Answer is required"
                    )
                )

                return@post
            }

            println(
                "========== SUBMIT ASSIGNMENT =========="
            )

            println("USER ID = $userId")
            println("USER ASSIGNMENT ID = $id")

            val result =
                assignmentService.submitAssignment(
                    userId = userId,
                    userAssignmentId = id,
                    answer = request.answer
                )

            if (result == null) {

                call.respond(
                    HttpStatusCode.NotFound,
                    AssignmentActionResponse(
                        success = false,
                        message = "Assignment not found"
                    )
                )

                return@post
            }

            call.respond(
                HttpStatusCode.OK,
                AssignmentActionResponse(
                    success = true,
                    status = result.status,
                    score = result.score,
                    feedback = result.feedback,
                    message = "Assignment submitted successfully"
                )
            )

        } catch (e: Exception) {

            println(
                "========== SUBMIT ASSIGNMENT ERROR =========="
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                AssignmentActionResponse(
                    success = false,
                    message =
                        e.message
                            ?: "Unknown error"
                )
            )
        }
    }
    get("/debug/postgres") {
        try {
            val socket = java.net.Socket()
            socket.connect(
                java.net.InetSocketAddress("100.76.246.38", 5432),
                5000
            )
            socket.close()

            call.respondText("POSTGRES TCP OK")
        } catch (e: Exception) {
            call.respondText(
                "POSTGRES TCP FAILED: ${e.javaClass.name}: ${e.message}",
                status = HttpStatusCode.InternalServerError
            )
        }
    }
}