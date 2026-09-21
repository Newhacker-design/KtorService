package com.example.ktorservice

import com.example.ktorservice.config.DatabaseFactory
import com.example.ktorservice.plugins.configureRouting
import com.example.ktorservice.plugins.configureSerialization
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.websocket.*
import kotlin.time.Duration.Companion.seconds

fun Application.module() {

    if (environment.config.propertyOrNull("ktor.database.enabled")
            ?.getString() != "false"
    ) {
        DatabaseFactory.init()
    }

    configureSerialization()

    // PHẢI đặt TRƯỚC configureRouting()
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    configureRouting()
}