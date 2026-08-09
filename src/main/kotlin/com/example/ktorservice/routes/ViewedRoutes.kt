package com.example.ktorservice.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureViewedRoutes() {

    routing {

        get("/") {

            call.respondText("Ktor Server is running")

        }

    }

}