package com.example.ktorservice.routes

import com.example.ktorservice.model.AvatarActionResponse
import com.example.ktorservice.model.AvatarUploadRequest
import com.example.ktorservice.repository.AvatarRepository
import com.example.ktorservice.security.requireUserId
import com.example.ktorservice.service.AuthService
import io.ktor.http.*
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.Base64

fun Route.avatarRoutes(
    authService: AuthService,
    repo: AvatarRepository
) {
    route("/users") {

        post("/me/avatar") {
            val userId = call.requireUserId(authService)
            if (userId == null) {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    AvatarActionResponse(false, "Unauthorized")
                )
                return@post
            }

            val req = call.receive<AvatarUploadRequest>()

            val bytes = try {
                Base64.getDecoder().decode(req.imageBase64)
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    AvatarActionResponse(false, "Invalid base64")
                )
                return@post
            }

            if (bytes.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    AvatarActionResponse(false, "Empty image")
                )
                return@post
            }

            if (bytes.size > 500 * 1024) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    AvatarActionResponse(false, "Avatar too large")
                )
                return@post
            }

            repo.save(userId, bytes, req.contentType)

            println("AVATAR UPLOADED: userId=$userId, size=${bytes.size}")

            call.respond(AvatarActionResponse(true))
        }

        delete("/me/avatar") {
            val userId = call.requireUserId(authService)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@delete
            }

            repo.delete(userId)
            println("AVATAR DELETED: userId=$userId")
            call.respond(AvatarActionResponse(true))
        }

        get("/{id}/avatar") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }

            val avatar = repo.get(id)
            if (avatar == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }

            val (data, contentType) = avatar
            call.respondBytes(data, ContentType.parse(contentType))
        }
    }
}