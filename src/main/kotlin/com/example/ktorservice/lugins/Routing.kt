package com.example.ktorservice.plugins

import com.example.ktorservice.database.dao.ViewedItemDaoImpl
import com.example.ktorservice.repository.ViewedItemRepository
import com.example.ktorservice.routes.viewedItemRoutes
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureRouting() {

    val viewedItemRepository = ViewedItemRepository(
        ViewedItemDaoImpl()
    )

    routing {

        get("/") {
            call.respondText("Ktor Server is running")
        }

        viewedItemRoutes(viewedItemRepository)
    }
}