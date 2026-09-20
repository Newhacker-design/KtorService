package com.example.ktorservice.routes

import com.example.ktorservice.service.LearningPathService
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.learningPathRoutes(
    learningPathService: LearningPathService
) {

    route("/learning-paths") {

        // Lấy toàn bộ learning path
        get {
            val paths = learningPathService.getAllPaths()

            call.respond(
                HttpStatusCode.OK,
                paths
            )
        }

        // Lấy một learning path
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()

            if (id == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("success" to false, "message" to "Invalid path id")
                )
                return@get
            }

            val path = learningPathService.getPathById(id)

            if (path == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("success" to false, "message" to "Learning path not found")
                )
                return@get
            }

            call.respond(
                HttpStatusCode.OK,
                path
            )
        }

        // Lấy các step của path
        get("/{id}/steps") {
            val pathId = call.parameters["id"]?.toIntOrNull()

            if (pathId == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("success" to false, "message" to "Invalid path id")
                )
                return@get
            }

            val steps = learningPathService.getSteps(pathId)

            call.respond(
                HttpStatusCode.OK,
                steps
            )
        }

        /*
         * Lấy step tiếp theo mà học sinh cần học.
         *
         * Ví dụ:
         * user chưa có progress:
         * -> step 1
         *
         * step 1 MASTERED:
         * -> step 2
         *
         * step 1..3 MASTERED:
         * -> step 4
         */
        get("/next") {

            val userId = call.request.queryParameters["userId"]?.toIntOrNull()
            val grade = call.request.queryParameters["grade"]?.toIntOrNull()
            val subject = call.request.queryParameters["subject"]

            if (userId == null || grade == null || subject.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "success" to false,
                        "message" to "userId, grade and subject are required"
                    )
                )
                return@get
            }

            val nextStep = learningPathService.getNextStep(
                userId = userId,
                grade = grade,
                subject = subject
            )

            if (nextStep == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf(
                        "success" to false,
                        "message" to "No learning path found"
                    )
                )
                return@get
            }

            call.respond(
                HttpStatusCode.OK,
                nextStep
            )
        }

        // Lấy progress của một học sinh cho một step
        get("/progress/{userId}/{stepId}") {

            val userId = call.parameters["userId"]?.toIntOrNull()
            val stepId = call.parameters["stepId"]?.toIntOrNull()

            if (userId == null || stepId == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "success" to false,
                        "message" to "Invalid userId or stepId"
                    )
                )
                return@get
            }

            val progress = learningPathService.getOrCreateProgress(
                userId = userId,
                stepId = stepId
            )

            call.respond(
                HttpStatusCode.OK,
                progress
            )
        }
    }
}