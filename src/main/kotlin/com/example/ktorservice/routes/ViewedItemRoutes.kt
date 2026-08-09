package com.example.ktorservice.routes

import com.example.ktorservice.model.ViewedIdsBatchRequest
import com.example.ktorservice.model.ViewedIdsBatchResponse
import com.example.ktorservice.repository.ViewedItemRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.viewedItemRoutes(
    repository: ViewedItemRepository
) {

    get("/viewed-ids") {

        val ids = repository.getAllIds()

        call.respond(ids)
    }

    get("/viewed-hashes") {

        val hashes = repository.getAllHashes()

        call.respond(hashes)
    }

    post("/viewed-ids") {

        try {

            val id = call.receive<Long>()

            repository.insertId(id)

            call.respond(
                HttpStatusCode.OK,
                "Added"
            )

        } catch (e: Exception) {

            call.respond(
                HttpStatusCode.InternalServerError,
                "Error: ${e.message}"
            )
        }
    }

    post("/viewed-hashes") {

        try {

            val hash = call.receive<String>()

            repository.insertHash(hash)

            call.respond(
                HttpStatusCode.OK,
                "Added"
            )

        } catch (e: Exception) {

            call.respond(
                HttpStatusCode.InternalServerError,
                "Error: ${e.message}"
            )
        }
    }


    post("/viewed-ids/batch") {

        println("========== POST /viewed-ids/batch ==========")

        try {

            val request = call.receive<ViewedIdsBatchRequest>()

            println("Received IDs: ${request.ids}")
            println("Start insertIds...")

            repository.insertIds(request.ids)

            println("insertIds completed successfully")

            call.respond(
                HttpStatusCode.OK,
                ViewedIdsBatchResponse(
                    success = true,
                    count = request.ids.size,
                    message = "IDs inserted successfully"
                )
            )

        } catch (e: Exception) {

            println("========== BATCH ERROR ==========")
            println("Exception: ${e::class.qualifiedName}")
            println("Message: ${e.message}")

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                ViewedIdsBatchResponse(
                    success = false,
                    count = 0,
                    message = e.message ?: "Unknown error"
                )
            )
        }
    }



}