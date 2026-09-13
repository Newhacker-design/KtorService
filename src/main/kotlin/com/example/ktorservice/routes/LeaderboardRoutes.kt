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

            // Bổ sung nhãn tuần để client hiển thị
            call.respond(
                resp.copy(
                    weekLabel = WeekUtils.labelCurrentWeek()
                )
            )
        }
    }
}