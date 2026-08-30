package com.example.ktorservice

import com.example.ktorservice.config.DatabaseFactory
import com.example.ktorservice.plugins.configureRouting
import com.example.ktorservice.plugins.configureSerialization
import io.ktor.server.application.*
import io.ktor.server.netty.*


fun Application.module() {

    val isTest =
        environment.config
            .propertyOrNull("ktor.environment")
            ?.getString() == "test"

    if (!isTest) {
        DatabaseFactory.init()
    }

    configureSerialization()
    configureRouting()
}