package com.example.ktorservice.plugins

import com.example.ktorservice.database.dao.ViewedItemDaoImpl
import com.example.ktorservice.repository.LocationRepository
import com.example.ktorservice.repository.ViewedItemRepository
import com.example.ktorservice.routes.assignmentRoutes
import com.example.ktorservice.routes.authRoutes
import com.example.ktorservice.routes.deviceRoutes
import com.example.ktorservice.routes.licenseRoutes
import com.example.ktorservice.routes.parentRoutes
import com.example.ktorservice.routes.viewedItemRoutes
import com.example.ktorservice.service.AIService
import com.example.ktorservice.service.AssignmentService
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import com.example.ktorservice.service.AuthService
import com.example.ktorservice.service.DeviceService
import com.example.ktorservice.service.LicenseService
import com.example.ktorservice.service.ParentChildService

fun Application.configureRouting() {

    val viewedItemRepository =
        ViewedItemRepository(
            ViewedItemDaoImpl()
        )

    val authService =
        AuthService()

    val deviceService =
        DeviceService()

    val licenseService =
        LicenseService()

    val aiService =
        AIService()

    val assignmentService =
        AssignmentService(
            aiService
        )
    val parentChildService =
        ParentChildService()
    routing {

        get("/") {

            call.respondText(
                "Ktor Server is running"
            )
        }

        viewedItemRoutes(
            viewedItemRepository,
            locationRepository =
                LocationRepository()
        )

        authRoutes(
            authService
        )

        deviceRoutes(
            authService,
            deviceService
        )

        licenseRoutes(
            authService,
            licenseService
        )

        assignmentRoutes(
            authService,
            assignmentService
        )
        parentRoutes(
            authService = authService,
            parentChildService = parentChildService
        )
    }
}