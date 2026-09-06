package com.example.ktorservice.routes

import com.example.ktorservice.model.AssignedAssignmentResponse
import com.example.ktorservice.model.AssignmentActionResponse
import com.example.ktorservice.model.AssignmentDetailResponse
import com.example.ktorservice.model.AssignmentListResponse
import com.example.ktorservice.model.AssignmentQuestion
import com.example.ktorservice.model.AssignmentStorageData
import com.example.ktorservice.model.AssignmentStudentData
import com.example.ktorservice.model.AssignmentSubmitRequest
import com.example.ktorservice.model.UserAssignmentResponse
import com.example.ktorservice.security.requireUserId
import com.example.ktorservice.service.AIService
import com.example.ktorservice.service.AssignmentService
import com.example.ktorservice.service.AuthService
import com.example.ktorservice.service.ParentChildService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*

fun Route.assignmentRoutes(
    authService: AuthService,
    assignmentService: AssignmentService,
    parentChildService: ParentChildService
) {

    // ============================================================
    // GET /assignments/next
    //
    // ADMIN:
    //     Có thể lấy bài cho bất kỳ CHILD nào.
    //
    // PARENT:
    //     Chỉ có thể lấy bài cho CHILD thuộc parent đó.
    //
    // CHILD:
    //     Không được gọi endpoint này.
    //
    // Assignment được lưu vào user_assignments với:
    //     userId = childUserId
    // ============================================================

    get("/assignments/next") {

        try {

            // ========================================================
            // 1. AUTHENTICATION
            // ========================================================

            val parentUserId =
                call.requireUserId(authService)

            if (parentUserId == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    UserAssignmentResponse(
                        success = false,
                        message = "Invalid or expired token"
                    )
                )

                return@get
            }

            // ========================================================
            // 2. ROLE
            // ========================================================

            val role =
                authService
                    .getUserRole(parentUserId)
                    ?.uppercase()

            if (
                role != ParentChildService.ROLE_ADMIN &&
                role != ParentChildService.ROLE_PARENT
            ) {

                call.respond(
                    HttpStatusCode.Forbidden,
                    UserAssignmentResponse(
                        success = false,
                        message =
                            "Only ADMIN or PARENT can request assignment for a child"
                    )
                )

                return@get
            }

            // ========================================================
            // 3. CHILD USER ID
            // ========================================================

            val childUserId =
                call.request
                    .queryParameters["childUserId"]
                    ?.toIntOrNull()

            if (childUserId == null) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    UserAssignmentResponse(
                        success = false,
                        message = "childUserId is required"
                    )
                )

                return@get
            }

            // ========================================================
            // 4. CHECK PARENT -> CHILD
            //
            // ADMIN:
            //     bypass relation check.
            //
            // PARENT:
            //     must own the child.
            // ========================================================

            if (role == ParentChildService.ROLE_ADMIN) {

                println(
                    "========== ADMIN ASSIGNMENT ACCESS =========="
                )

                println(
                    "ADMIN USER ID = $parentUserId"
                )

                println(
                    "CHILD USER ID = $childUserId"
                )

            } else {

                val isChild =
                    parentChildService.isChildOfParent(
                        parentUserId = parentUserId,
                        childUserId = childUserId
                    )

                if (!isChild) {

                    println(
                        "========== CHILD ACCESS DENIED =========="
                    )

                    println(
                        "PARENT USER ID = $parentUserId"
                    )

                    println(
                        "CHILD USER ID = $childUserId"
                    )

                    call.respond(
                        HttpStatusCode.Forbidden,
                        UserAssignmentResponse(
                            success = false,
                            message =
                                "Child account does not belong to this parent"
                        )
                    )

                    return@get
                }
            }

            // ========================================================
            // 5. PARAMETERS
            // ========================================================

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

            val difficulty =
                call.request
                    .queryParameters["difficulty"]
                    ?.uppercase()
                    ?.let {
                        runCatching {
                            AIService.Difficulty.valueOf(it)
                        }.getOrNull()
                    }

            // ========================================================
            // 6. VALIDATE DIFFICULTY
            // ========================================================

            if (difficulty == null) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    UserAssignmentResponse(
                        success = false,
                        message =
                            "Invalid difficulty. Use EASY, MEDIUM or HARD"
                    )
                )

                return@get
            }

            // ========================================================
            // 7. VALIDATE GRADE
            // ========================================================

            if (grade == null || grade !in 1..12) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    UserAssignmentResponse(
                        success = false,
                        message = "Invalid grade"
                    )
                )

                return@get
            }

            // ========================================================
            // 8. VALIDATE SUBJECT
            // ========================================================

            if (subject.isNullOrBlank()) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    UserAssignmentResponse(
                        success = false,
                        message = "Subject is required"
                    )
                )

                return@get
            }

            // ========================================================
            // 9. LOG
            // ========================================================

            println(
                "========== GET NEXT ASSIGNMENT =========="
            )

            println(
                "REQUEST USER ID = $parentUserId"
            )

            println(
                "ROLE = $role"
            )

            println(
                "CHILD USER ID = $childUserId"
            )

            println(
                "GRADE = $grade"
            )

            println(
                "SUBJECT = $subject"
            )

            println(
                "TOPIC = $topic"
            )

            println(
                "DIFFICULTY = $difficulty"
            )

            // ========================================================
            // 10. GET ASSIGNMENT FOR CHILD
            //
            // QUAN TRỌNG:
            //
            // userId = childUserId
            //
            // Không được dùng parentUserId ở đây.
            // ========================================================

            val result =
                assignmentService.getNextAssignment(
                    userId = childUserId,
                    grade = grade,
                    subject = subject,
                    topic = topic,
                    difficulty = difficulty
                )

            // ========================================================
            // 11. LOG RESULT
            // ========================================================

            println(
                "========== NEXT ASSIGNMENT RESULT =========="
            )

            println(
                "USER ASSIGNMENT ID = ${result.id}"
            )

            println(
                "ASSIGNMENT ID = ${result.assignmentId}"
            )

            println(
                "ASSIGNED USER ID = ${result.userId}"
            )

            println(
                "TITLE = ${result.assignment.title}"
            )

            // ========================================================
            // 12. RESPONSE
            // ========================================================

            call.respond(
                HttpStatusCode.OK,
                UserAssignmentResponse(

                    success = true,

                    id =
                        result.id,

                    assignmentId =
                        result.assignmentId,

                    userId =
                        result.userId,

                    status =
                        result.status,

                    answer =
                        result.answer,

                    score =
                        result.score,

                    feedback =
                        result.feedback,

                    startedAt =
                        result.startedAt,

                    completedAt =
                        result.completedAt,

                    assignment =
                        AssignmentStudentData(

                            id =
                                result.assignment.id,

                            grade =
                                result.assignment.grade,

                            subject =
                                result.assignment.subject,

                            topic =
                                result.assignment.topic,

                            title =
                                result.assignment.title,

                            difficulty =
                                result.assignment.difficulty,

                            questions =
                                result.assignment.questionMetadata.map {
                                        metadata ->

                                    AssignmentQuestion(

                                        id =
                                            metadata.id,

                                        question =
                                            metadata.question,

                                        points =
                                            metadata.points,

                                        answerType =
                                            metadata.answerType,

                                        gradingMethod =
                                            metadata.gradingMethod
                                    )
                                },

                            content =
                                result.assignment.content,

                            totalScore =
                                result.assignment.totalScore
                        )
                )
            )

        } catch (e: Exception) {

            println(
                "========== GET NEXT ASSIGNMENT ERROR =========="
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                UserAssignmentResponse(
                    success = false,
                    message =
                        e.message
                            ?: "Unknown error"
                )
            )
        }
    }


    // ============================================================
    // GET /assignments/{id}
    //
    // ADMIN:
    //     Có thể xem bất kỳ assignment nào trong storage.
    //
    // PARENT / CHILD:
    //     Chỉ được xem assignment đã được assign cho chính mình.
    //
    // ============================================================

    get("/assignments/{id}") {

        try {

            val userId =
                call.requireUserId(authService)

            if (userId == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    AssignmentDetailResponse(
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
                    AssignmentDetailResponse(
                        success = false,
                        message = "Invalid assignment id"
                    )
                )

                return@get
            }

            // ========================================================
            // ROLE
            // ========================================================

            val role =
                authService
                    .getUserRole(userId)
                    ?.uppercase()

            if (role == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    AssignmentDetailResponse(
                        success = false,
                        message = "User not found"
                    )
                )

                return@get
            }

            // ========================================================
            // ADMIN:
            //     bypass user_assignment check.
            //
            // PARENT / CHILD:
            //     assignment must belong to current user.
            // ========================================================

            if (role != ParentChildService.ROLE_ADMIN) {

                val hasAssignment =
                    assignmentService.hasUserAssignment(
                        userId = userId,
                        assignmentId = id
                    )

                if (!hasAssignment) {

                    println(
                        "========== ASSIGNMENT ACCESS DENIED =========="
                    )

                    println(
                        "USER ID = $userId"
                    )

                    println(
                        "ROLE = $role"
                    )

                    println(
                        "ASSIGNMENT ID = $id"
                    )

                    call.respond(
                        HttpStatusCode.Forbidden,
                        AssignmentDetailResponse(
                            success = false,
                            message =
                                "You do not have access to this assignment"
                        )
                    )

                    return@get
                }
            }

            println(
                "========== GET ASSIGNMENT FROM STORAGE =========="
            )

            println(
                "USER ID = $userId"
            )

            println(
                "ROLE = $role"
            )

            println(
                "ASSIGNMENT ID = $id"
            )

            // ========================================================
            // GET ASSIGNMENT
            // ========================================================

            val result =
                assignmentService.getById(id)

            if (result == null) {

                println(
                    "ASSIGNMENT NOT FOUND"
                )

                call.respond(
                    HttpStatusCode.NotFound,
                    AssignmentDetailResponse(
                        success = false,
                        message = "Assignment not found"
                    )
                )

                return@get
            }

            println(
                "ASSIGNMENT FOUND"
            )

            println(
                "ASSIGNMENT ID = ${result.id}"
            )

            println(
                "TITLE = ${result.title}"
            )

            println(
                "GRADE = ${result.grade}"
            )

            println(
                "SUBJECT = ${result.subject}"
            )

            // ========================================================
            // RESPONSE
            //
            // Không trả answerKey / gradingGuide.
            // ========================================================

            call.respond(
                HttpStatusCode.OK,
                AssignmentDetailResponse(

                    success = true,

                    assignment =
                        AssignmentStudentData(

                            id =
                                result.id,

                            grade =
                                result.grade,

                            subject =
                                result.subject,

                            topic =
                                result.topic,

                            title =
                                result.title,

                            difficulty =
                                result.difficulty,

                            questions =
                                result.questionMetadata.map {
                                        metadata ->

                                    AssignmentQuestion(

                                        id =
                                            metadata.id,

                                        question =
                                            metadata.question,

                                        points =
                                            metadata.points,

                                        answerType =
                                            metadata.answerType,

                                        gradingMethod =
                                            metadata.gradingMethod
                                    )
                                },

                            content =
                                result.content,

                            totalScore =
                                result.totalScore
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
                AssignmentDetailResponse(
                    success = false,
                    message =
                        e.message
                            ?: "Unknown error"
                )
            )
        }
    }


    // ============================================================
    // GET /assignments
    //
    // ĐÂY LÀ STORAGE ENDPOINT.
    //
    // Nó trả:
    //     answerKey
    //     gradingGuide
    //
    // Vì vậy:
    //
    // ADMIN  -> được phép
    // PARENT -> 403
    // CHILD  -> 403
    //
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

            // ========================================================
            // ROLE
            // ========================================================

            val role =
                authService
                    .getUserRole(userId)
                    ?.uppercase()

            if (role != ParentChildService.ROLE_ADMIN) {

                call.respond(
                    HttpStatusCode.Forbidden,
                    AssignmentListResponse(
                        success = false,
                        message =
                            "Only ADMIN can access assignment storage"
                    )
                )

                return@get
            }

            // ========================================================
            // PARAMETERS
            // ========================================================

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

            val difficulty =
                call.request
                    .queryParameters["difficulty"]
                    ?.uppercase()
                    ?.let {
                        runCatching {
                            AIService.Difficulty.valueOf(it)
                        }.getOrNull()
                    }

            // ========================================================
            // DIFFICULTY IS REQUIRED
            // Giữ nguyên behavior cũ.
            // ========================================================

            if (difficulty == null) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    AssignmentListResponse(
                        success = false,
                        message =
                            "Invalid difficulty. Use EASY, MEDIUM or HARD"
                    )
                )

                return@get
            }

            // ========================================================
            // LOG
            // ========================================================

            println(
                "========== GET ASSIGNMENT STORAGE =========="
            )

            println(
                "USER ID = $userId"
            )

            println(
                "ROLE = $role"
            )

            println(
                "GRADE = $grade"
            )

            println(
                "SUBJECT = $subject"
            )

            println(
                "TOPIC = $topic"
            )

            println(
                "DIFFICULTY = $difficulty"
            )

            // ========================================================
            // GET STORAGE
            //
            // LƯU Ý:
            // AssignmentService hiện tại của bạn chưa nhận
            // difficulty ở getAllAssignments().
            //
            // Vì vậy chưa giả vờ filter difficulty ở đây.
            // ========================================================

            val assignments =
                assignmentService.getAllAssignments(
                    grade = grade,
                    subject = subject,
                    topic = topic
                )

            println(
                "ASSIGNMENT STORAGE COUNT = ${assignments.size}"
            )

            // ========================================================
            // RESPONSE
            //
            // ADMIN mới nhận được answerKey / gradingGuide.
            // ========================================================

            call.respond(
                HttpStatusCode.OK,
                AssignmentListResponse(
                    success = true,

                    assignments =
                        assignments.map { assignment ->

                            AssignmentStorageData(

                                id =
                                    assignment.id,

                                grade =
                                    assignment.grade,

                                subject =
                                    assignment.subject,

                                topic =
                                    assignment.topic,

                                title =
                                    assignment.title,

                                content =
                                    assignment.content,

                                answerKey =
                                    assignment.answerKey,

                                gradingGuide =
                                    assignment.gradingGuide,

                                totalScore =
                                    assignment.totalScore
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
    // GET /assignments/my
    //
    // ADMIN:
    //     Có thể gọi, thường sẽ trả danh sách rỗng nếu ADMIN
    //     chưa có user_assignments.
    //
    // PARENT / CHILD:
    //     Chỉ lấy assignment của chính user hiện tại.
    //
    // ============================================================

    get("/assignments/my") {

        val userId =
            call.requireUserId(authService)

        if (userId == null) {

            call.respond(
                HttpStatusCode.Unauthorized,
                AssignedAssignmentResponse(
                    success = false,
                    message = "Authentication required"
                )
            )

            return@get
        }

        try {

            val results =
                assignmentService.getUserAssignments(
                    userId = userId
                )

            val assignments =
                results.map { result ->

                    UserAssignmentResponse(

                        success = true,

                        id =
                            result.id,

                        assignmentId =
                            result.assignmentId,

                        userId =
                            result.userId,

                        status =
                            result.status,

                        answer =
                            result.answer,

                        score =
                            result.score,

                        feedback =
                            result.feedback,

                        startedAt =
                            result.startedAt,

                        completedAt =
                            result.completedAt,

                        assignment =
                            AssignmentStudentData(

                                id =
                                    result.assignment.id,

                                grade =
                                    result.assignment.grade,

                                subject =
                                    result.assignment.subject,

                                topic =
                                    result.assignment.topic,

                                title =
                                    result.assignment.title,

                                difficulty =
                                    result.assignment.difficulty,

                                questions =
                                    result.questionMetadata.map {
                                            metadata ->

                                        AssignmentQuestion(

                                            id =
                                                metadata.id,

                                            question =
                                                metadata.question,

                                            points =
                                                metadata.points,

                                            answerType =
                                                metadata.answerType,

                                            gradingMethod =
                                                metadata.gradingMethod
                                        )
                                    },

                                content =
                                    result.assignment.content,

                                totalScore =
                                    result.assignment.totalScore
                            )
                    )
                }

            call.respond(
                HttpStatusCode.OK,
                AssignedAssignmentResponse(
                    success = true,
                    assignments = assignments
                )
            )

        } catch (e: Exception) {

            println(
                "========== GET MY ASSIGNMENTS ERROR =========="
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                AssignedAssignmentResponse(
                    success = false,
                    message =
                        e.message
                            ?: "Failed to load assignments"
                )
            )
        }
    }


    // ============================================================
    // POST /assignments/{id}/start
    //
    // ID ở đây là userAssignmentId.
    //
    // AssignmentService đã giới hạn theo userId hiện tại.
    // ============================================================

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

            println(
                "USER ID = $userId"
            )

            println(
                "USER ASSIGNMENT ID = $id"
            )

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


    // ============================================================
    // POST /assignments/{id}/submit
    //
    // ID = userAssignmentId.
    //
    // User chỉ submit assignment thuộc chính mình.
    // ============================================================

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

            println(
                "USER ID = $userId"
            )

            println(
                "USER ASSIGNMENT ID = $id"
            )

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
                    message =
                        "Assignment submitted successfully"
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


    // ============================================================
    // DEBUG POSTGRES
    //
    // Tạm thời giữ nguyên để debug Tailscale/PostgreSQL.
    // ============================================================

    get("/debug/postgres") {

        try {

            val socket =
                java.net.Socket()

            socket.connect(
                java.net.InetSocketAddress(
                    "100.76.246.38",
                    5432
                ),
                5000
            )

            socket.close()

            call.respondText(
                "POSTGRES TCP OK"
            )

        } catch (e: Exception) {

            call.respondText(
                "POSTGRES TCP FAILED: ${e.javaClass.name}: ${e.message}",
                status = HttpStatusCode.InternalServerError
            )
        }
    }
}
