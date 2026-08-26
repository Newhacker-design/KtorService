package com.example.ktorservice.routes

import com.example.ktorservice.model.DeviceResponse
import com.example.ktorservice.model.RegisterDeviceRequest
import com.example.ktorservice.security.requireUserId
import com.example.ktorservice.service.AuthService
import com.example.ktorservice.service.DeviceService
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*

fun Route.deviceRoutes(
    authService: AuthService,
    deviceService: DeviceService
) {

    post("/devices/register") {

        val userId =
            call.requireUserId(
                authService
            )

        if (userId == null) {

            call.respond(
                HttpStatusCode.Unauthorized
            )

            return@post
        }

        val request =
            call.receive<RegisterDeviceRequest>()

        if (
            request.deviceId.isBlank()
        ) {

            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "message" to
                            "deviceId is required"
                )
            )

            return@post
        }

        val deviceId =
            deviceService.register(
                userId = userId,
                deviceId =
                    request.deviceId,
                deviceName =
                    request.deviceName,
                appVersion =
                    request.appVersion
            )

        call.respond(
            DeviceResponse(
                id = deviceId,
                deviceId =
                    request.deviceId,
                deviceName =
                    request.deviceName,
                appVersion =
                    request.appVersion,
                status =
                    "ACTIVE",
                lastSeen =
                    System.currentTimeMillis()
            )
        )
    }
}