package com.example.ktorservice.routes

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

        get {
            val groupKey = call.request.queryParameters["group"]

            if (groupKey == null) {
                val all = LeaderboardGroup.entries.associate { g ->
                    g.name to repo.getLeaderboard(g)
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

            val days = call.request.queryParameters["days"]
                ?.toIntOrNull() ?: 7

            val min = call.request.queryParameters["min"]
                ?.toIntOrNull() ?: 1

            call.respond(repo.getLeaderboard(group, days, min))
        }

        get("/my-group") {
            val userId = call.requireUserId(authService)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized)
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
            call.respond(repo.getLeaderboard(group))
        }
    }
}