package com.example.ktorservice.routes

import com.example.ktorservice.WeekUtils
import com.example.ktorservice.model.LeaderboardGroup
import com.example.ktorservice.repository.LeaderboardRepository
import com.example.ktorservice.security.requireUserId
import com.example.ktorservice.service.AuthService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.leaderboardRoutes(
    authService: AuthService,
    repo: LeaderboardRepository
) {
    route("/leaderboard") {

        /**
         * GET /leaderboard?group=GRADE_1_2&week=0&min=1
         *
         * Nếu không có group → trả về cả 3 khối.
         */
        get {
            val groupKey = call.request.queryParameters["group"]

            val weekOffset = call.request.queryParameters["week"]
                ?.toIntOrNull() ?: 0

            if (groupKey == null) {
                val all = LeaderboardGroup.entries.associate { g ->
                    g.name to repo.getLeaderboard(g, weekOffset)
                }
                call.respond(all)
                return@get
            }

            val group = LeaderboardGroup.fromKey(groupKey)
            if (group == null) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid group: $groupKey")
                )
                return@get
            }

            val min = call.request.queryParameters["min"]
                ?.toIntOrNull() ?: 1

            val resp = repo.getLeaderboard(group, weekOffset, min)

            call.respond(
                resp.copy(
                    weekLabel = WeekUtils.labelCurrentWeek()
                )
            )
        }

        /**
         * GET /leaderboard/my-group
         *
         * Tự nhận diện khối của user đang đăng nhập.
         * Yêu cầu Authorization: Bearer <token>.
         */
        get("/my-group") {
            val userId = call.requireUserId(authService)
            if (userId == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "Invalid or expired token")
                )
                return@get
            }

            val grade = repo.getUserGrade(userId)
            if (grade == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "User has not set birth year")
                )
                return@get
            }

            val group = LeaderboardGroup.fromGrade(grade)
            val resp = repo.getLeaderboard(group)

            call.respond(
                resp.copy(
                    weekLabel = WeekUtils.labelCurrentWeek()
                )
            )
        }
    }
}