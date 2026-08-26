package com.example.ktorservice.plugins

import com.example.ktorservice.database.dao.ViewedItemDaoImpl
import com.example.ktorservice.repository.LocationRepository
import com.example.ktorservice.repository.ViewedItemRepository
import com.example.ktorservice.routes.authRoutes
import com.example.ktorservice.routes.deviceRoutes
import com.example.ktorservice.routes.viewedItemRoutes
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import com.example.ktorservice.service.AuthService
import com.example.ktorservice.service.DeviceService

fun Application.configureRouting() {

    val viewedItemRepository =
        ViewedItemRepository(
            ViewedItemDaoImpl()
        )
    val authService = AuthService()
    val deviceService =
        DeviceService()
    routing {

        get("/") {
            call.respondText("Ktor Server is running")
        }

        viewedItemRoutes(
            viewedItemRepository,
            locationRepository = LocationRepository()
        )
        authRoutes(
            authService
        )
        deviceRoutes(
            authService,
            deviceService
        )
    }
}