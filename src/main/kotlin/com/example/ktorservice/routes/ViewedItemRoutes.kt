
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
import io.ktor.http.ContentType
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import com.example.ktorservice.model.RecordingFile
import com.example.ktorservice.model.RecordingDeleteResponse
import io.ktor.server.routing.delete
fun Route.viewedItemRoutes(
    repository: ViewedItemRepository
) {
    get("/recordings/list") {

        val recordingsDir =
            File("data", "recordings")

        if (!recordingsDir.exists()) {

            call.respond(
                emptyList<RecordingFile>()
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

                    RecordingFile(
                        fileName = it.name,
                        size = it.length(),
                        lastModified = it.lastModified()
                    )
                }
                ?: emptyList()

        call.respond(files)
    }
    get("/recordings") {

        val recordingsDir =
            File("data", "recordings")

        if (!recordingsDir.exists()) {
            call.respondText(
                """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Recordings</title>
            </head>
            <body>
                <h1>Recordings</h1>
                <p>Chưa có file ghi âm.</p>
            </body>
            </html>
            """.trimIndent(),
                ContentType.Text.Html
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
                ?: emptyList()

        val html = buildString {

            append(
                """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport"
                      content="width=device-width, initial-scale=1.0">

                <title>Audio Recordings</title>

                <style>
                    body {
                        font-family: Arial, sans-serif;
                        margin: 30px;
                        background: #f5f5f5;
                    }

                    h1 {
                        margin-bottom: 20px;
                    }

                    .recording {
                        background: white;
                        padding: 15px;
                        margin-bottom: 15px;
                        border-radius: 10px;
                        box-shadow: 0 2px 6px rgba(0,0,0,0.1);
                    }

                    .name {
                        font-weight: bold;
                        margin-bottom: 8px;
                        word-break: break-all;
                    }

                    .info {
                        color: #666;
                        font-size: 14px;
                        margin-bottom: 10px;
                    }

                    audio {
                        width: 100%;
                    }

                    .download {
                        display: inline-block;
                        margin-top: 10px;
                        text-decoration: none;
                    }
                </style>
            </head>

            <body>

            <h1>Audio Recordings</h1>

            <p>
                Tổng số file: ${files.size}
            </p>
            """.trimIndent()
            )

            if (files.isEmpty()) {

                append(
                    """
                <p>Chưa có file ghi âm.</p>
                """.trimIndent()
                )

            } else {

                files.forEach { file ->

                    val encodedName =
                        java.net.URLEncoder
                            .encode(
                                file.name,
                                Charsets.UTF_8
                            )
                            .replace("+", "%20")

                    val sizeMb =
                        "%.2f".format(
                            file.length() / 1024.0 / 1024.0
                        )

                    append(
                        """
                    <div class="recording">

                        <div class="name">
                            ${file.name}
                        </div>

                        <div class="info">
                            ${sizeMb} MB
                        </div>

                        <audio controls preload="none">
                            <source
                                src="/recordings/$encodedName"
                                type="audio/mp4">
                            Trình duyệt không hỗ trợ audio.
                        </audio>

                        <br>

                        <a
                            class="download"
                            href="/recordings/$encodedName"
                            download>
                            Tải xuống
                        </a>

                    </div>
                    """.trimIndent()
                    )
                }
            }

            append(
                """
            </body>
            </html>
            """.trimIndent()
            )
        }

        call.respondText(
            html,
            ContentType.Text.Html
        )
    }
    get("/recordings/{fileName}") {

        val fileName =
            call.parameters["fileName"]

        if (fileName.isNullOrBlank()) {

            call.respond(
                HttpStatusCode.BadRequest,
                "File name is required"
            )

            return@get
        }

        val recordingsDir =
            File("data", "recordings")

        val file =
            File(
                recordingsDir,
                fileName
            )

        // Không cho truy cập ra ngoài thư mục recordings
        if (
            !file.canonicalPath.startsWith(
                recordingsDir.canonicalPath
            )
        ) {

            call.respond(
                HttpStatusCode.Forbidden,
                "Access denied"
            )

            return@get
        }

        if (!file.exists() || !file.isFile) {

            call.respond(
                HttpStatusCode.NotFound,
                "File not found"
            )

            return@get
        }

        call.respondFile(file)
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
    delete("/recordings/{fileName}") {

        try {

            val fileName =
                call.parameters["fileName"]

            if (fileName.isNullOrBlank()) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    RecordingDeleteResponse(
                        success = false,
                        fileName = "",
                        message = "File name is required"
                    )
                )

                return@delete
            }

            val recordingsDir =
                File("data", "recordings")

            if (!recordingsDir.exists()) {

                call.respond(
                    HttpStatusCode.NotFound,
                    RecordingDeleteResponse(
                        success = false,
                        fileName = fileName,
                        message = "Recordings directory not found"
                    )
                )

                return@delete
            }

            val file =
                File(
                    recordingsDir,
                    fileName
                )

            println(
                "DELETE recording:"
            )

            println(
                "Directory = ${recordingsDir.absolutePath}"
            )

            println(
                "File = ${file.absolutePath}"
            )

            println(
                "Exists = ${file.exists()}"
            )

            println(
                "IsFile = ${file.isFile}"
            )

            if (!file.exists() || !file.isFile) {

                call.respond(
                    HttpStatusCode.NotFound,
                    RecordingDeleteResponse(
                        success = false,
                        fileName = fileName,
                        message = "File not found"
                    )
                )

                return@delete
            }

            // Chống path traversal
            val directoryPath =
                recordingsDir
                    .canonicalFile
                    .toPath()

            val filePath =
                file
                    .canonicalFile
                    .toPath()

            if (!filePath.startsWith(directoryPath)) {

                call.respond(
                    HttpStatusCode.Forbidden,
                    RecordingDeleteResponse(
                        success = false,
                        fileName = fileName,
                        message = "Access denied"
                    )
                )

                return@delete
            }

            val deleted =
                file.delete()

            println(
                "Delete result = $deleted"
            )

            if (!deleted) {

                call.respond(
                    HttpStatusCode.InternalServerError,
                    RecordingDeleteResponse(
                        success = false,
                        fileName = fileName,
                        message = "Cannot delete file"
                    )
                )

                return@delete
            }

            call.respond(
                HttpStatusCode.OK,
                RecordingDeleteResponse(
                    success = true,
                    fileName = fileName,
                    message = "Recording deleted"
                )
            )

        } catch (e: Exception) {

            println(
                "========== DELETE RECORDING ERROR =========="
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                RecordingDeleteResponse(
                    success = false,
                    fileName =
                        call.parameters["fileName"]
                            ?: "",
                    message =
                        e.message
                            ?: "Delete failed"
                )
            )
        }
    }
}

