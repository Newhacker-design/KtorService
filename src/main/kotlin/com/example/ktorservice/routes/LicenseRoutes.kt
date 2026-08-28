package com.example.ktorservice.routes

import com.example.ktorservice.model.CreateLicenseRequest
import com.example.ktorservice.model.LicenseResponse
import com.example.ktorservice.security.requireUserId
import com.example.ktorservice.service.AuthService
import com.example.ktorservice.service.LicenseService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.licenseRoutes(
    authService: AuthService,
    licenseService: LicenseService
) {

    get("/licenses/check") {

        val userId =
            call.requireUserId(authService)

        if (userId == null) {
            call.respond(
                HttpStatusCode.Unauthorized,
                LicenseResponse(
                    active = false,
                    message = "Invalid or expired token"
                )
            )
            return@get
        }

        val deviceId =
            call.request
                .queryParameters["deviceId"]
                ?.toIntOrNull()

        if (deviceId == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                LicenseResponse(
                    active = false,
                    message = "deviceId is required"
                )
            )
            return@get
        }

        val result =
            licenseService.getLicense(
                userId = userId,
                deviceId = deviceId
            )

        call.respond(
            LicenseResponse(
                active = result.active,
                licenseKey = result.licenseKey,
                type = result.type,
                expiresAt = result.expiresAt,
                message = result.message
            )
        )
    }


    post("/licenses/create") {

        println("========== CREATE LICENSE ==========")
        println("CREATE LICENSE ROUTE HIT")

        try {

            val rawBody = call.receiveText()

            println("RAW BODY = $rawBody")

            val request =
                kotlinx.serialization.json.Json.decodeFromString<CreateLicenseRequest>(
                    rawBody
                )

            println("REQUEST = $request")

            if (request.userId <= 0) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    LicenseResponse(
                        active = false,
                        message = "Invalid userId"
                    )
                )
                return@post
            }

            if (request.deviceId <= 0) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    LicenseResponse(
                        active = false,
                        message = "Invalid deviceId"
                    )
                )
                return@post
            }

            if (request.durationDays <= 0) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    LicenseResponse(
                        active = false,
                        message = "Invalid durationDays"
                    )
                )
                return@post
            }

            println("Calling LicenseService...")

            val result =
                licenseService.createLicense(
                    userId = request.userId,
                    deviceId = request.deviceId,
                    type = request.type,
                    durationDays = request.durationDays
                )

            println("LICENSE RESULT = $result")

            call.respond(
                HttpStatusCode.OK,
                LicenseResponse(
                    active = result.active,
                    licenseKey = result.licenseKey,
                    type = result.type,
                    expiresAt = result.expiresAt,
                    message = "License created successfully"
                )
            )

        } catch (e: Exception) {

            println("========== CREATE LICENSE ERROR ==========")
            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                LicenseResponse(
                    active = false,
                    message = e.message ?: "Unknown error"
                )
            )
        }
    }
}