package com.example.ktorservice.routes

import com.example.ktorservice.model.VerifyGradeRequest
import com.example.ktorservice.model.VerifyGradeResponse
import com.example.ktorservice.repository.StudentGradeRepository
import com.example.ktorservice.security.requireUserId
import com.example.ktorservice.service.AuthService
import io.ktor.http.*
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.studentGradeRoutes(
    authService: AuthService,
    repo: StudentGradeRepository
) {
    route("/student/grade") {

        /**
         * GET /student/grade
         * Trả về lớp hiện tại của user.
         * Nếu chưa nhập → grade = null.
         */
        get {
            val userId = call.requireUserId(authService)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }

            val birthYear = repo.getBirthYear(userId)
            val grade = if (birthYear != null) repo.computeGrade(birthYear) else null

            call.respond(HttpStatusCode.OK, VerifyGradeResponse(
                success = true,
                grade = grade,
                birthYear = birthYear
            ))
        }

        /**
         * POST /student/grade
         * Body: { "birthYear": 2013 }
         *
         * Server tính lớp, lưu vào users.grade.
         * Cho phép ghi đè (user có thể sửa năm sinh).
         */
        post {
            val userId = call.requireUserId(authService)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized, VerifyGradeResponse(
                    success = false,
                    message = "Invalid or expired token"
                ))
                return@post
            }

            val request = call.receive<VerifyGradeRequest>()

            if (!repo.validateBirthYear(request.birthYear)) {
                call.respond(HttpStatusCode.BadRequest, VerifyGradeResponse(
                    success = false,
                    message = "Năm sinh không hợp lệ."
                ))
                return@post
            }

            // Lưu năm sinh (không lưu lớp)
            repo.setBirthYear(userId, request.birthYear)

            // Tính lớp để trả về cho client hiển thị ngay
            val grade = repo.computeGrade(request.birthYear)

            call.respond(HttpStatusCode.OK, VerifyGradeResponse(
                success = true,
                grade = grade,
                birthYear = request.birthYear
            ))
        }
    }
}