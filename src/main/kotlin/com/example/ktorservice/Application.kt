package com.example.ktorservice

import com.example.ktorservice.config.DatabaseFactory
import com.example.ktorservice.plugins.configureRouting
import com.example.ktorservice.plugins.configureSerialization
import io.ktor.server.application.*
import io.ktor.server.netty.*


fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {

    DatabaseFactory.init()

    configureSerialization()

    configureRouting()

}