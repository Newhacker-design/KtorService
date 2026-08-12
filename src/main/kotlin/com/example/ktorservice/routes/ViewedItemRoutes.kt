
package com.example.ktorservice.routes

import com.example.ktorservice.model.RecordingUploadResponse
import com.example.ktorservice.model.ViewedIdsBatchRequest
import com.example.ktorservice.model.ViewedIdsBatchResponse
import com.example.ktorservice.repository.ViewedItemRepository
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.io.File
import io.ktor.utils.io.jvm.javaio.copyTo

fun Route.viewedItemRoutes(
    repository: ViewedItemRepository
) {
    get("/recordings") {

        val recordingsDir =
            File("data", "recordings")

        if (!recordingsDir.exists()) {
            call.respond(
                emptyList<String>()
            )
            return@get
        }

        val files =
            recordingsDir
                .listFiles()
                ?.filter {
                    it.isFile &&
                            it.extension.equals(
                                "m4a",
                                ignoreCase = true
                            )
                }
                ?.sortedByDescending {
                    it.lastModified()
                }
                ?.map {
                    mapOf(
                        "fileName" to it.name,
                        "size" to it.length(),
                        "lastModified" to it.lastModified()
                    )
                }
                ?: emptyList()

        call.respond(files)
    }
    get("/viewed-ids") {

        val ids =
            repository.getAllIds()

        call.respond(ids)
    }

    get("/viewed-hashes") {

        val hashes =
            repository.getAllHashes()

        call.respond(hashes)
    }

    post("/viewed-ids") {

        try {

            val id =
                call.receive<Long>()

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

            val hash =
                call.receive<String>()

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

        println(
            "========== POST /viewed-ids/batch =========="
        )

        try {

            val request =
                call.receive<ViewedIdsBatchRequest>()

            println(
                "Received IDs: ${request.ids}"
            )

            repository.insertIds(
                request.ids
            )

            println(
                "insertIds completed successfully"
            )

            call.respond(
                HttpStatusCode.OK,
                ViewedIdsBatchResponse(
                    success = true,
                    count = request.ids.size,
                    message = "IDs inserted successfully"
                )
            )

        } catch (e: Exception) {

            println(
                "========== BATCH ERROR =========="
            )

            println(
                "Exception: ${e::class.qualifiedName}"
            )

            println(
                "Message: ${e.message}"
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                ViewedIdsBatchResponse(
                    success = false,
                    count = 0,
                    message =
                        e.message
                            ?: "Unknown error"
                )
            )
        }
    }

    /**
     * Upload file ghi âm.
     *
     * POST /recordings/upload
     */
    post("/recordings/upload") {

        try {

            val multipart =
                call.receiveMultipart()

            var savedFile: File? = null

            multipart.forEachPart { part ->

                if (part is PartData.FileItem) {

                    val originalName =
                        part.originalFileName
                            ?: "recording_${System.currentTimeMillis()}.m4a"

                    val recordingsDir =
                        File("data", "recordings")

                    if (!recordingsDir.exists()) {
                        recordingsDir.mkdirs()
                    }

                    val file =
                        File(
                            recordingsDir,
                            originalName
                        )

                    val input = part.provider()

                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }

                    savedFile = file

                    println(
                        "Recording saved: ${file.absolutePath}"
                    )

                    println(
                        "File size: ${file.length()} bytes"
                    )
                }

                part.dispose()
            }

            val file =
                savedFile
                    ?: run {

                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "success" to false,
                                "message" to "No file uploaded"
                            )
                        )

                        return@post
                    }

            call.respond(
                HttpStatusCode.OK,
                RecordingUploadResponse(
                    success = true,
                    fileName = savedFile!!.name,
                    message = "Recording uploaded successfully"
                )
            )

        } catch (e: Exception) {

            println(
                "UPLOAD RECORDING ERROR: ${e.message}"
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                RecordingUploadResponse(
                    success = false,
                    fileName = "",
                    message = e.message ?: "Upload failed"
                )
            )
        }
    }
}

