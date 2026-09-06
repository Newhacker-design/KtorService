package com.example.ktorservice.routes

import com.example.ktorservice.model.CreateLicenseRequest
import com.example.ktorservice.model.LicenseResponse
import com.example.ktorservice.security.requireUserId
import com.example.ktorservice.service.AuthService
import com.example.ktorservice.service.LicenseService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.licenseRoutes(
    authService: AuthService,
    licenseService: LicenseService
) {

    // ============================================================
    // GET /licenses/check
    // ============================================================

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


    // ============================================================
    // POST /licenses/create
    //
    // CHỈ ADMIN được phép tạo license.
    // ============================================================

    post("/licenses/create") {

        println("========== CREATE LICENSE ==========")
        println("CREATE LICENSE ROUTE HIT")

        // ------------------------------------------------------------
        // Authenticate
        // ------------------------------------------------------------

        val adminUserId =
            call.requireUserId(authService)

        if (adminUserId == null) {

            call.respond(
                HttpStatusCode.Unauthorized,
                LicenseResponse(
                    active = false,
                    message = "Invalid or expired token"
                )
            )

            return@post
        }

        // ------------------------------------------------------------
        // Check ADMIN role
        // ------------------------------------------------------------

        val role =
            authService.getUserRole(adminUserId)
                ?.uppercase()

        if (role != LicenseService.ROLE_ADMIN) {

            call.respond(
                HttpStatusCode.Forbidden,
                LicenseResponse(
                    active = false,
                    message = "Only ADMIN can create licenses"
                )
            )

            return@post
        }

        // ------------------------------------------------------------
        // Receive request
        // ------------------------------------------------------------

        try {

            val rawBody =
                call.receiveText()

            println("RAW BODY = $rawBody")

            val request =
                kotlinx.serialization.json.Json
                    .decodeFromString<CreateLicenseRequest>(
                        rawBody
                    )

            println("REQUEST = $request")

            // --------------------------------------------------------
            // Validate userId
            // --------------------------------------------------------

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

            // --------------------------------------------------------
            // Validate deviceId
            // --------------------------------------------------------

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

            // --------------------------------------------------------
            // Validate duration
            // --------------------------------------------------------

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
                    message =
                        result.message
                            ?: "License created successfully"
                )
            )

        } catch (e: Exception) {

            println(
                "========== CREATE LICENSE ERROR =========="
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                LicenseResponse(
                    active = false,
                    message =
                        e.message
                            ?: "Unknown error"
                )
            )
        }
    }
}
