package com.example.ktorservice.routes

import com.example.ktorservice.model.DeviceResponse
import com.example.ktorservice.model.RegisterDeviceRequest
import com.example.ktorservice.security.requireUserId
import com.example.ktorservice.service.AuthService
import com.example.ktorservice.service.DeviceService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.deviceRoutes(
    authService: AuthService,
    deviceService: DeviceService
) {

    // ============================================================
    // POST /devices/register
    // ============================================================

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


        // ========================================================
        // Nhận request
        // ========================================================

        val request =
            call.receive<RegisterDeviceRequest>()


        // ========================================================
        // Validate deviceId
        // ========================================================

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


        // ========================================================
        // Register / Update Device
        // ========================================================

        val deviceId: Int

        try {

            deviceId =
                deviceService.registerDevice(
                    userId = userId,
                    deviceId =
                        request.deviceId,
                    deviceName =
                        request.deviceName,
                    appVersion =
                        request.appVersion
                )

        } catch (e: IllegalStateException) {

            val message =
                e.message
                    ?: "Unable to register device"


            // ----------------------------------------------------
            // Device đã thuộc tài khoản khác
            // hoặc Parent/Child đã có device
            // ----------------------------------------------------

            if (
                message ==
                "Device is already registered to another user"
            ) {

                call.respond(
                    HttpStatusCode.Conflict,
                    mapOf(
                        "message" to message
                    )
                )

                return@post
            }


            if (
                message.contains(
                    "Maximum",
                    ignoreCase = true
                )
            ) {

                call.respond(
                    HttpStatusCode.Conflict,
                    mapOf(
                        "message" to message
                    )
                )

                return@post
            }


            // ----------------------------------------------------
            // Các lỗi trạng thái khác
            // ----------------------------------------------------

            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "message" to message
                )
            )

            return@post
        }


        // ========================================================
        // Thành công
        // ========================================================

        call.respond(
            DeviceResponse(
                id =
                    deviceId,

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
