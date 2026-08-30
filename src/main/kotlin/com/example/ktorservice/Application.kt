package com.example.ktorservice

import com.example.ktorservice.config.DatabaseFactory
import com.example.ktorservice.plugins.configureRouting
import com.example.ktorservice.plugins.configureSerialization
import io.ktor.server.application.*
import io.ktor.server.netty.*


fun Application.module() {

    if (environment.config.propertyOrNull("ktor.database.enabled")
            ?.getString() != "false"
    ) {
        DatabaseFactory.init()
    }

    configureSerialization()

    configureRouting()
}