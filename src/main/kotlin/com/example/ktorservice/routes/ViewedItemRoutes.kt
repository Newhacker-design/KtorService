
package com.example.ktorservice.routes

import com.example.ktorservice.database.VideosTable
import com.example.ktorservice.database.table.ParentChildrenTable
import com.example.ktorservice.model.CallEventRequest
import com.example.ktorservice.model.CallEventResponse
import com.example.ktorservice.model.ControlRequest
import com.example.ktorservice.model.ControlResponse
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
import com.example.ktorservice.model.VideoDeleteResponse
import com.example.ktorservice.model.VideoInfo
import com.example.ktorservice.model.VideoUploadResponse
import com.example.ktorservice.repository.LocationRepository
import com.example.ktorservice.security.requireUserId
import com.example.ktorservice.service.AuthService
import com.example.ktorservice.service.ParentChildService
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.server.http.content.staticFiles
import io.ktor.server.http.content.default
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import com.example.ktorservice.database.table.DeviceControlsTable
import com.example.ktorservice.service.ControlWebSocketHub
import org.jetbrains.exposed.sql.update
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText


private const val MAX_LOCATIONS = 50
private const val MAX_VIDEOS = 10


// ============================================================
// GLOBAL CLEANUP LOCK
// ============================================================

private val videoCleanupLock =
    Any()

private const val MAX_VIDEO_SIZE = 100L * 1024L * 1024L //TỐI ĐA 100MB/1VIDEO

private const val MAX_DAILY_UPLOADS = 3// 1 NGÀY CHỈ ĐƯỢC UPLOAD 3 LẦN
private const val MAX_CONCURRENT_UPLOADS = 5//TỐI ĐA 5 NGƯỜI CÙNG UPLOAD 1 LÚC

private val uploadSemaphore = Semaphore(MAX_CONCURRENT_UPLOADS)

// 120 giây giữa hai lần upload thành công.
private const val MIN_UPLOAD_INTERVAL_MS = 120_000L
/** * Lưu thời điểm upload thành công gần nhất * của từng parent.
 * * * parentUserId -> timestamp */
private val lastVideoUploadTime = ConcurrentHashMap<Int, Long>()
/** * Lock riêng cho từng parent.
 * * * Mục đích:
 * * Nếu cùng một parent gửi nhiều request
 * * upload đồng thời, các request sẽ phải * lần lượt kiểm tra quota. */
private val videoUploadLocks = ConcurrentHashMap<Int, Any>()
fun Route.viewedItemRoutes(
    repository: ViewedItemRepository,
    locationRepository: LocationRepository,
    authService: AuthService,
    parentChildService: ParentChildService
) {
    val callEvents = mutableListOf<CallEventRequest>()




    post("/control") {

        try {

            // ============================================================
            // PARENT ID
            // ============================================================

            val parentUserId =
                call.requireUserId(authService)

            if (parentUserId == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    "Invalid or expired token"
                )

                return@post
            }

            // ============================================================
            // READ BODY
            // ============================================================

            val body =
                call.receiveText()

            val request =
                Json.decodeFromString<ControlRequest>(
                    body
                )

            // ============================================================
            // VALIDATE COMMAND
            // ============================================================

            val command =
                request.command
                    .trim()
                    .uppercase()

            if (
                command != "ON" &&
                command != "OFF" &&
                command != "LOGOUT"
            ) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    "command must be ON, OFF or LOGOUT"
                )

                return@post
            }

            // ============================================================
            // CHECK CHILD OWNERSHIP
            // ============================================================

            if (
                !parentChildService.isChildOfParent(
                    parentUserId = parentUserId,
                    childUserId = request.childUserId
                )
            ) {

                call.respond(
                    HttpStatusCode.Forbidden,
                    "Child does not belong to parent"
                )

                return@post
            }

            // ============================================================
            // SAVE TO POSTGRESQL
            // ============================================================

            val now =
                System.currentTimeMillis()

            val newVersion =
                transaction {

                    val existing =
                        DeviceControlsTable
                            .selectAll()
                            .where {
                                DeviceControlsTable.childUserId eq
                                        request.childUserId
                            }
                            .singleOrNull()

                    if (existing == null) {

                        DeviceControlsTable.insert {
                            it[childUserId] =
                                request.childUserId

                            it[DeviceControlsTable.command] =
                                command

                            it[text] =
                                request.text

                            it[videoUrl] =
                                request.videoUrl

                            it[version] =
                                1L

                            it[updatedAt] =
                                now
                        }

                        1L

                    } else {

                        val nextVersion =
                            existing[
                                DeviceControlsTable.version
                            ] + 1L

                        DeviceControlsTable.update(
                            where = {
                                DeviceControlsTable.childUserId eq
                                        request.childUserId
                            }
                        ) {

                            it[DeviceControlsTable.command] =
                                command

                            it[text] =
                                request.text

                            it[videoUrl] =
                                request.videoUrl

                            it[version] =
                                nextVersion

                            it[updatedAt] =
                                now
                        }

                        nextVersion
                    }
                }

            val control =
                ControlResponse(
                    command = command,
                    text = request.text,
                    videoUrl = request.videoUrl,
                    version = newVersion
                )

            // ============================================================
            // PUSH CHANGE TO RECEIVER
            // ============================================================

            ControlWebSocketHub.notifyChanged(
                childUserId = request.childUserId,
                version = newVersion
            )

            println(
                "CONTROL SAVED: " +
                        "parent=$parentUserId " +
                        "child=${request.childUserId} " +
                        "command=$command " +
                        "version=$newVersion"
            )

            call.respond(
                HttpStatusCode.OK,
                control
            )

        } catch (e: Exception) {

            println(
                "========== CONTROL ERROR =========="
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                "Control error: ${e.message}"
            )
        }
    }


    get("/control/state") {

        try {

            val childUserId =
                call.requireUserId(authService)

            if (childUserId == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    "Invalid or expired token"
                )

                return@get
            }

            val control =
                transaction {

                    DeviceControlsTable
                        .selectAll()
                        .where {
                            DeviceControlsTable.childUserId eq
                                    childUserId
                        }
                        .singleOrNull()
                        ?.let { row ->

                            ControlResponse(
                                command =
                                    row[
                                        DeviceControlsTable.command
                                    ],

                                text =
                                    row[
                                        DeviceControlsTable.text
                                    ],

                                videoUrl =
                                    row[
                                        DeviceControlsTable.videoUrl
                                    ],

                                version =
                                    row[
                                        DeviceControlsTable.version
                                    ]
                            )
                        }
                }
                    ?: ControlResponse(
                        command = "",
                        text = "",
                        videoUrl = null,
                        version = 0L
                    )

            call.respond(
                HttpStatusCode.OK,
                control
            )

        } catch (e: Exception) {

            println(
                "========== GET CONTROL STATE ERROR =========="
            )

            e.printStackTrace()

            call.respond(
                HttpStatusCode.InternalServerError,
                "Control state error: ${e.message}"
            )
        }
    }


    webSocket("/control/ws") {

        val childUserId =
            call.requireUserId(authService)

        if (childUserId == null) {

            close(
                reason = io.ktor.websocket.CloseReason(
                    io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY,
                    "Invalid or expired token"
                )
            )

            return@webSocket
        }

        println(
            "CONTROL WS CONNECTED: child=$childUserId"
        )

        ControlWebSocketHub.register(
            childUserId = childUserId,
            session = this
        )

        try {

            // ============================================================
            // CONNECTION ALIVE
            // ============================================================

            for (frame in incoming) {

                when (frame) {

                    is Frame.Text -> {

                        // Client hiện tại không cần gửi command.
                        // Chỉ giữ connection sống.
                        println(
                            "CONTROL WS TEXT: " +
                                    "child=$childUserId " +
                                    frame.readText()
                        )
                    }

                    is Frame.Close -> {
                        break
                    }

                    else -> {
                        // Ignore binary/ping/pong.
                    }
                }
            }

        } catch (e: Exception) {

            println(
                "CONTROL WS ERROR: " +
                        "child=$childUserId " +
                        "${e.message}"
            )

        } finally {

            ControlWebSocketHub.unregister(
                childUserId = childUserId,
                session = this
            )

            println(
                "CONTROL WS DISCONNECTED: " +
                        "child=$childUserId"
            )
        }
    }


    post("/videos/upload") {

        println("========== UPLOAD START ==========")

        var savedFile: File? = null
        var childUserId: Int? = null
        var tempFile: File? = null

        // ============================================================
        // SEMAPHORE STATE
        //
        // Chỉ release nếu request thực sự acquire được slot.
        // ============================================================

        var uploadPermitAcquired = false

        try {

            // ============================================================
            // PARENT ID LẤY TỪ TOKEN
            // ============================================================

            val parentUserId =
                call.requireUserId(authService)

            if (parentUserId == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    "Invalid or expired token"
                )

                return@post
            }

            println(
                "PARENT USER ID = $parentUserId"
            )

            // ============================================================
            // EARLY DAILY LIMIT
            //
            // Chặn sớm trước khi nhận file.
            // Đây chỉ là lớp bảo vệ đầu tiên.
            //
            // Lớp kiểm tra chính vẫn nằm bên trong lock.
            // ============================================================

            fun getVietnamDayTimestamps(): Pair<Long, Long> {

                val vietnamZone =
                    ZoneId.of("Asia/Ho_Chi_Minh")

                val now =
                    ZonedDateTime.now(vietnamZone)

                val startOfDay =
                    now
                        .toLocalDate()
                        .atStartOfDay(vietnamZone)

                val startOfNextDay =
                    startOfDay.plusDays(1)

                return Pair(
                    startOfDay
                        .toInstant()
                        .toEpochMilli(),

                    startOfNextDay
                        .toInstant()
                        .toEpochMilli()
                )
            }

            fun getDailyUploadCount(): Int {

                val (
                    startTimestamp,
                    nextDayTimestamp
                ) =
                    getVietnamDayTimestamps()

                return transaction {

                    // ====================================================
                    // LẤY TẤT CẢ CHILD CỦA PARENT
                    // ====================================================

                    val childIds =
                        ParentChildrenTable
                            .selectAll()
                            .where {
                                ParentChildrenTable.parentUserId eq
                                        parentUserId
                            }
                            .map {
                                it[
                                    ParentChildrenTable.childUserId
                                ]
                            }

                    if (childIds.isEmpty()) {

                        0

                    } else {

                        // =================================================
                        // KHÔNG DÙNG inList
                        //
                        // Tạo:
                        //
                        // childUserId = 6
                        // OR
                        // childUserId = 7
                        // OR
                        // childUserId = 9
                        // =================================================

                        val childCondition =
                            childIds
                                .map { id ->
                                    VideosTable.childUserId eq id
                                }
                                .reduce { condition1, condition2 ->
                                    condition1 or condition2
                                }

                        VideosTable
                            .selectAll()
                            .where {
                                childCondition and
                                        (
                                                VideosTable.createdAt greaterEq
                                                        startTimestamp
                                                ) and
                                        (
                                                VideosTable.createdAt less
                                                        nextDayTimestamp
                                                )
                            }
                            .count()
                            .toInt()
                    }
                }
            }

            // ============================================================
            // EARLY COOLDOWN CHECK
            // ============================================================

            val currentTime =
                System.currentTimeMillis()

            val lastUploadTime =
                lastVideoUploadTime[parentUserId]

            if (
                lastUploadTime != null &&
                currentTime - lastUploadTime <
                MIN_UPLOAD_INTERVAL_MS
            ) {

                val remainingSeconds =
                    (
                            MIN_UPLOAD_INTERVAL_MS -
                                    (
                                            currentTime -
                                                    lastUploadTime
                                            )
                            ) / 1000L

                println(
                    "UPLOAD DENIED EARLY: " +
                            "cooldown=${remainingSeconds}s"
                )

                call.respond(
                    HttpStatusCode.TooManyRequests,
                    "Please wait ${remainingSeconds.coerceAtLeast(1)} seconds before uploading another video."
                )

                return@post
            }

            // ============================================================
            // EARLY DAILY LIMIT CHECK
            // ============================================================

            val earlyDailyUploadCount =
                getDailyUploadCount()

            println(
                "EARLY DAILY UPLOAD COUNT = " +
                        "$earlyDailyUploadCount / " +
                        "$MAX_DAILY_UPLOADS"
            )

            if (
                earlyDailyUploadCount >=
                MAX_DAILY_UPLOADS
            ) {

                println(
                    "UPLOAD DENIED EARLY: " +
                            "daily limit reached"
                )

                call.respond(
                    HttpStatusCode.TooManyRequests,
                    "Daily video upload limit reached. Maximum is 3 videos per day."
                )

                return@post
            }

// ============================================================
// ACQUIRE GLOBAL UPLOAD SLOT
//
// Tối đa 5 request được đi vào quá trình nhận file cùng lúc.
//
// Ví dụ:
//   Upload A -> slot 1
//   Upload B -> slot 2
//   Upload C -> slot 3
//   Upload D -> slot 4
//   Upload E -> slot 5
//   Upload F -> WAIT
//
// Khi một upload hoàn thành:
//   Upload F -> được cấp slot
// ============================================================

            println(
                "Waiting for upload slot... " +
                        "active limit = $MAX_CONCURRENT_UPLOADS"
            )

            uploadSemaphore.acquire()

            uploadPermitAcquired = true

            println(
                "Upload slot acquired"
            )

// ============================================================
// READ MULTIPART
//
// GIỮ NGUYÊN LIMIT 500 MB BẠN ĐÃ CẤU HÌNH
// ============================================================

            val multipart =
                call.receiveMultipart(
                    formFieldLimit =
                        500L * 1024 * 1024
                )


            var originalFileName: String? = null

            multipart.forEachPart { part ->

                try {

                    when (part) {

                        // ====================================================
                        // CHILD USER ID
                        // ====================================================

                        is PartData.FormItem -> {

                            if (
                                part.name ==
                                "childUserId"
                            ) {

                                childUserId =
                                    part.value
                                        .trim()
                                        .toIntOrNull()

                                println(
                                    "CHILD USER ID = $childUserId"
                                )
                            }
                        }

                        // ====================================================
                        // VIDEO FILE
                        // ====================================================

                        is PartData.FileItem -> {

                            originalFileName =
                                part.originalFileName
                                    ?.substringAfterLast("/")
                                    ?.substringAfterLast("\\")
                                    ?: "video_${System.currentTimeMillis()}.mp4"

                            val tempDir =
                                File("/app/videos/temp")

                            tempDir.mkdirs()

                            val tempName =
                                "upload_${System.currentTimeMillis()}_${System.nanoTime()}.tmp"

                            val temp =
                                File(
                                    tempDir,
                                    tempName
                                )

                            println(
                                "Receiving temporary file: " +
                                        temp.absolutePath
                            )

                            part.provider()
                                .toInputStream()
                                .use { input ->

                                    temp.outputStream()
                                        .use { output ->

                                            val buffer =
                                                ByteArray(64 * 1024)

                                            var total =
                                                0L

                                            while (true) {

                                                val count =
                                                    input.read(buffer)

                                                if (count <= 0) {
                                                    break
                                                }

                                                total += count

                                                // =================================================
                                                // 100 MB LIMIT
                                                // =================================================

                                                if (
                                                    total >
                                                    MAX_VIDEO_SIZE
                                                ) {

                                                    println(
                                                        "UPLOAD REJECTED: " +
                                                                "video exceeds 100 MB"
                                                    )

                                                    throw VideoTooLargeException(
                                                        "Video exceeds the maximum size of 100 MB"
                                                    )
                                                }

                                                output.write(
                                                    buffer,
                                                    0,
                                                    count
                                                )

                                                // =================================================
                                                // LOG MỖI ~10 MB
                                                // =================================================

                                                if (
                                                    total %
                                                    (10L * 1024 * 1024)
                                                    < count
                                                ) {

                                                    println(
                                                        "Received " +
                                                                "${total / 1024 / 1024} MB"
                                                    )
                                                }
                                            }
                                        }
                                }

                            tempFile =
                                temp

                            println(
                                "Temporary upload complete: " +
                                        "${temp.absolutePath}, " +
                                        "size=${temp.length()}"
                            )
                        }

                        else -> {}
                    }

                } catch (e: Exception) {

                    println(
                        "========== PART ERROR =========="
                    )

                    e.printStackTrace()

                    throw e

                } finally {

                    part.dispose()
                }
            }

            // ============================================================
            // VALIDATE CHILD USER ID
            // ============================================================

            val targetChildUserId =
                childUserId
                    ?: run {

                        call.respond(
                            HttpStatusCode.BadRequest,
                            "childUserId is required"
                        )

                        return@post
                    }

            // ============================================================
            // CHECK PARENT -> CHILD
            // ============================================================

            val isChild =
                parentChildService.isChildOfParent(
                    parentUserId =
                        parentUserId,

                    childUserId =
                        targetChildUserId
                )

            if (!isChild) {

                println(
                    "UPLOAD DENIED: " +
                            "parentUserId=$parentUserId " +
                            "does not own " +
                            "childUserId=$targetChildUserId"
                )

                call.respond(
                    HttpStatusCode.Forbidden,
                    "Child does not belong to parent"
                )

                return@post
            }

            println(
                "PARENT -> CHILD RELATION OK: " +
                        "$parentUserId -> $targetChildUserId"
            )

            // ============================================================
            // VALIDATE FILE
            // ============================================================

            val temp =
                tempFile
                    ?: run {

                        call.respond(
                            HttpStatusCode.BadRequest,
                            "No video file uploaded"
                        )

                        return@post
                    }

            val fileName =
                originalFileName
                    ?: "video_${System.currentTimeMillis()}.mp4"

            // ============================================================
            // FINAL FILE SIZE CHECK
            // ============================================================

            val uploadedFileSize =
                temp.length()

            println(
                "UPLOADED FILE SIZE = " +
                        "$uploadedFileSize bytes"
            )

            if (
                uploadedFileSize >
                MAX_VIDEO_SIZE
            ) {

                println(
                    "UPLOAD DENIED: " +
                            "file size exceeds 100 MB"
                )

                call.respond(
                    HttpStatusCode(
                        413,
                        "Content Too Large"
                    ),
                    "Video size must not exceed 100 MB"
                )

                return@post
            }

            // ============================================================
            // GET LOCK
            // ============================================================

            val uploadLock =
                videoUploadLocks.computeIfAbsent(
                    parentUserId
                ) {
                    Any()
                }

            // ============================================================
            // RESULT CỦA LOCK
            //
            // TUYỆT ĐỐI KHÔNG call.respond() TRONG synchronized.
            // ============================================================

            var rejectedStatus:
                    HttpStatusCode? = null

            var rejectedMessage:
                    String? = null

            var uploadSucceeded =
                false

            // ============================================================
            // CRITICAL SECTION
            // ============================================================

            synchronized(uploadLock) {

                // ========================================================
                // CHECK COOLDOWN LẠI
                //
                // Vì nhiều request có thể đã cùng vượt qua
                // early check ở phía trên.
                // ========================================================

                val nowInsideLock =
                    System.currentTimeMillis()

                val lastUploadInsideLock =
                    lastVideoUploadTime[parentUserId]

                if (
                    lastUploadInsideLock != null &&
                    nowInsideLock -
                    lastUploadInsideLock <
                    MIN_UPLOAD_INTERVAL_MS
                ) {

                    val remainingSeconds =
                        (
                                MIN_UPLOAD_INTERVAL_MS -
                                        (
                                                nowInsideLock -
                                                        lastUploadInsideLock
                                                )
                                ) / 1000L

                    println(
                        "UPLOAD DENIED INSIDE LOCK: " +
                                "cooldown active"
                    )

                    rejectedStatus =
                        HttpStatusCode.TooManyRequests

                    rejectedMessage =
                        "Please wait ${remainingSeconds.coerceAtLeast(1)} seconds before uploading another video."

                } else {

                    // ====================================================
                    // CHECK DAILY LIMIT LẠI
                    //
                    // Đây là check bảo vệ concurrent upload.
                    // ====================================================

                    val dailyUploadCount =
                        getDailyUploadCount()

                    println(
                        "DAILY UPLOAD COUNT INSIDE LOCK = " +
                                "$dailyUploadCount / " +
                                "$MAX_DAILY_UPLOADS"
                    )

                    if (
                        dailyUploadCount >=
                        MAX_DAILY_UPLOADS
                    ) {

                        println(
                            "UPLOAD DENIED INSIDE LOCK: " +
                                    "daily limit reached"
                        )

                        rejectedStatus =
                            HttpStatusCode.TooManyRequests

                        rejectedMessage =
                            "Daily video upload limit reached. Maximum is 3 videos per day."

                    } else {

                        // =================================================
                        // FINAL VIDEO DIRECTORY
                        // =================================================

                        val videoDir =
                            File("/app/videos")

                        videoDir.mkdirs()

                        val file =
                            File(
                                videoDir,
                                fileName
                            )

                        // =================================================
                        // CHECK DUPLICATE FILE NAME
                        // =================================================

                        val alreadyExists =
                            transaction {

                                VideosTable
                                    .selectAll()
                                    .where {
                                        VideosTable.fileName eq
                                                fileName
                                    }
                                    .any()
                            }

                        if (
                            alreadyExists ||
                            file.exists()
                        ) {

                            println(
                                "VIDEO ALREADY EXISTS: " +
                                        fileName
                            )

                            rejectedStatus =
                                HttpStatusCode.Conflict

                            rejectedMessage =
                                "Video file already exists: $fileName"

                        } else {

                            // =============================================
                            // MOVE TEMP -> FINAL
                            // =============================================

                            if (
                                !temp.renameTo(file)
                            ) {

                                throw IllegalStateException(
                                    "Cannot move temporary video to final location"
                                )
                            }

                            savedFile =
                                file

                            println(
                                "VIDEO SAVED: " +
                                        file.absolutePath
                            )

                            println(
                                "VIDEO SIZE: " +
                                        "${file.length()} bytes"
                            )

                            // =============================================
                            // SAVE DATABASE
                            // =============================================

                            val createdAt =
                                System.currentTimeMillis()

                            transaction {

                                VideosTable.insert {

                                    it[
                                        VideosTable.childUserId
                                    ] =
                                        targetChildUserId

                                    it[
                                        VideosTable.fileName
                                    ] =
                                        file.name

                                    it[
                                        VideosTable.fileSize
                                    ] =
                                        file.length()

                                    it[
                                        VideosTable.createdAt
                                    ] =
                                        createdAt
                                }
                            }

                            println(
                                "VIDEO DATABASE RECORD CREATED"
                            )

                            println(
                                "parentUserId = " +
                                        parentUserId
                            )

                            println(
                                "childUserId = " +
                                        targetChildUserId
                            )

                            println(
                                "fileName = " +
                                        file.name
                            )

                            // =============================================
                            // START COOLDOWN
                            //
                            // Chỉ ghi cooldown sau khi:
                            // - file đã lưu
                            // - DB đã insert thành công
                            // =============================================

                            lastVideoUploadTime[
                                parentUserId
                            ] =
                                System.currentTimeMillis()

                            println(
                                "UPLOAD COOLDOWN STARTED: " +
                                        "30 seconds"
                            )

                            // =============================================
                            // CLEANUP
                            //
                            // GLOBAL SERVER = 10 VIDEO
                            // =============================================

                            cleanupOldVideos()

                            uploadSucceeded =
                                true
                        }
                    }
                }
            }

            // ============================================================
            // RESPOND SAU KHI THOÁT SYNCHRONIZED
            //
            // Đây là điểm sửa lỗi:
            //
            // call.respond() là suspend function.
            // Không được gọi bên trong synchronized.
            // ============================================================

            if (
                rejectedStatus != null
            ) {

                call.respond(
                    rejectedStatus!!,
                    rejectedMessage
                        ?: "Upload rejected"
                )

                return@post
            }

            if (!uploadSucceeded) {

                throw IllegalStateException(
                    "Upload was not completed"
                )
            }

            // ============================================================
            // SUCCESS RESPONSE
            // ============================================================

            val finalFile =
                savedFile
                    ?: throw IllegalStateException(
                        "Saved file is missing"
                    )

            println(
                "========== UPLOAD COMPLETE =========="
            )

            call.respond(
                HttpStatusCode.OK,
                VideoUploadResponse(
                    success = true,

                    fileName =
                        finalFile.name,

                    size =
                        finalFile.length(),

                    videoUrl =
                        "https://ktorservice.onrender.com/videos/${finalFile.name}"
                )
            )

        } catch (
            e: VideoTooLargeException
    ) {

        println(
            "========== VIDEO TOO LARGE =========="
        )

        try {
            tempFile?.delete()
        } catch (_: Exception) {
        }

        try {
            savedFile?.delete()
        } catch (_: Exception) {
        }

        if (
            !call.response.isCommitted
        ) {

            call.respond(
                HttpStatusCode(
                    413,
                    "Content Too Large"
                ),
                "Video size must not exceed 100 MB"
            )
        }

    } catch (e: Exception) {

        println(
            "========== UPLOAD ERROR =========="
        )

        println(
            "Exception: " +
                    "${e::class.qualifiedName}"
        )

        println(
            "Message: " +
                    e.message
        )

        e.printStackTrace()

        // ============================================================
        // CLEANUP FAILED UPLOAD
        // ============================================================

        try {
            tempFile?.delete()
        } catch (_: Exception) {
        }

        try {
            savedFile?.delete()
        } catch (_: Exception) {
        }

        if (
            !call.response.isCommitted
        ) {

            call.respond(
                HttpStatusCode.InternalServerError,
                "Upload error: ${e.message}"
            )
        }

    } finally {

        // ============================================================
        // RELEASE GLOBAL UPLOAD SLOT
        //
        // Chỉ release nếu request đã acquire slot.
        //
        // Các request bị reject ở early check sẽ không release.
        // ============================================================

        if (uploadPermitAcquired) {

            uploadSemaphore.release()

            println(
                "Upload slot released"
            )
        }
    }
}



    get("/videos") {

        println(
            "========== GET /videos =========="
        )

        try {

            // ============================================================
            // CHILD ID LẤY TỪ TOKEN
            // ============================================================

            val childUserId =
                call.requireUserId(authService)

            if (childUserId == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    "Invalid or expired token"
                )

                return@get
            }

            println(
                "childUserId = $childUserId"
            )

            // ============================================================
            // LẤY VIDEO CỦA CHILD
            //
            // CHỈ TRẢ VIDEO KHI:
            //
            // 1. DB record tồn tại
            // 2. Physical file tồn tại
            // 3. Physical file là file thực sự
            // ============================================================

            val videos =
                transaction {

                    VideosTable
                        .selectAll()
                        .where {
                            VideosTable.childUserId eq childUserId
                        }
                        .orderBy(
                            VideosTable.createdAt to
                                    SortOrder.DESC
                        )
                        .mapNotNull { row ->

                            val fileName =
                                row[VideosTable.fileName]

                            val videoDir =
                                File("/app/videos")

                            val file =
                                File(
                                    videoDir,
                                    fileName
                                )

                            // ====================================================
                            // KIỂM TRA FILE THỰC TẾ
                            // ====================================================

                            if (
                                !file.exists() ||
                                !file.isFile
                            ) {

                                println(
                                    "SKIP MISSING VIDEO:"
                                )

                                println(
                                    "childUserId = $childUserId"
                                )

                                println(
                                    "fileName = $fileName"
                                )

                                println(
                                    "path = ${file.absolutePath}"
                                )

                                return@mapNotNull null
                            }

                            // ====================================================
                            // CANONICAL PATH CHECK
                            // ====================================================

                            val videoDirPath =
                                videoDir
                                    .canonicalFile
                                    .toPath()

                            val filePath =
                                file
                                    .canonicalFile
                                    .toPath()

                            if (
                                !filePath.startsWith(videoDirPath)
                            ) {

                                println(
                                    "SKIP INVALID VIDEO PATH:"
                                )

                                println(
                                    "fileName = $fileName"
                                )

                                return@mapNotNull null
                            }

                            // ====================================================
                            // VIDEO HỢP LỆ
                            // ====================================================

                            VideoInfo(
                                name = fileName,

                                size =
                                    file.length(),

                                url =
                                    "https://ktorservice.onrender.com/videos/$fileName"
                            )
                        }
                }

            println(
                "Videos for child $childUserId = ${videos.size}"
            )

            videos.forEach { video ->

                println(
                    "VIDEO -> ${video.name}"
                )
            }

            call.respond(
                HttpStatusCode.OK,
                videos
            )

        } catch (e: Exception) {

            println(
                "========== GET VIDEOS ERROR =========="
            )

            println(
                "Exception = ${e::class.qualifiedName}"
            )

            println(
                "Message = ${e.message}"
            )

            e.printStackTrace()

            if (!call.response.isCommitted) {

                call.respond(
                    HttpStatusCode.InternalServerError,
                    "Get videos error: ${e.message}"
                )
            }
        }
    }


    get("/videos/{fileName}") {

        println(
            "========== GET /videos/{fileName} =========="
        )

        try {

            // ============================================================
            // CHILD ID LẤY TỪ TOKEN
            // ============================================================

            val childUserId =
                call.requireUserId(authService)

            if (childUserId == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    "Invalid or expired token"
                )

                return@get
            }


            // ============================================================
            // FILE NAME
            // ============================================================

            val fileName =
                call.parameters["fileName"]

            if (fileName.isNullOrBlank()) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    "Missing file name"
                )

                return@get
            }


            // ============================================================
            // CHỐNG PATH TRAVERSAL
            // ============================================================

            val safeFileName =
                File(fileName).name

            if (safeFileName != fileName) {

                call.respond(
                    HttpStatusCode.Forbidden,
                    "Invalid file name"
                )

                return@get
            }


            println(
                "childUserId = $childUserId"
            )

            println(
                "Requested video = $safeFileName"
            )


            // ============================================================
            // KIỂM TRA VIDEO THUỘC CHILD
            // ============================================================

            val videoExists =
                transaction {

                    VideosTable
                        .selectAll()
                        .where {

                            (VideosTable.childUserId eq childUserId) and
                                    (VideosTable.fileName eq safeFileName)

                        }
                        .any()
                }


            if (!videoExists) {

                println(
                    "DOWNLOAD DENIED"
                )

                println(
                    "childUserId = $childUserId"
                )

                println(
                    "fileName = $safeFileName"
                )

                call.respond(
                    HttpStatusCode.NotFound,
                    "Video not found"
                )

                return@get
            }


            // ============================================================
            // FILE
            // ============================================================

            val videoDir =
                File("/app/videos")

            val file =
                File(
                    videoDir,
                    safeFileName
                )


            println(
                "Path = ${file.absolutePath}"
            )

            println(
                "Exists = ${file.exists()}"
            )

            println(
                "Size = ${
                    if (file.exists()) {
                        file.length()
                    } else {
                        0
                    }
                }"
            )


            // ============================================================
            // KIỂM TRA FILE THỰC TẾ
            // ============================================================

            if (!file.exists() || !file.isFile) {

                println(
                    "VIDEO FILE NOT FOUND"
                )

                call.respond(
                    HttpStatusCode.NotFound,
                    "Video file not found"
                )

                return@get
            }


            // ============================================================
            // CANONICAL PATH CHECK
            // ============================================================

            val videoDirPath =
                videoDir
                    .canonicalFile
                    .toPath()

            val filePath =
                file
                    .canonicalFile
                    .toPath()

            if (!filePath.startsWith(videoDirPath)) {

                println(
                    "PATH TRAVERSAL BLOCKED"
                )

                call.respond(
                    HttpStatusCode.Forbidden,
                    "Access denied"
                )

                return@get
            }


            // ============================================================
            // DOWNLOAD
            // ============================================================

            println(
                "VIDEO DOWNLOAD ALLOWED"
            )

            println(
                "childUserId = $childUserId"
            )

            println(
                "fileName = $safeFileName"
            )

            call.respondFile(file)

        } catch (e: Exception) {

            println(
                "========== VIDEO DOWNLOAD ERROR =========="
            )

            println(
                "Exception = ${e::class.qualifiedName}"
            )

            println(
                "Message = ${e.message}"
            )

            e.printStackTrace()

            if (!call.response.isCommitted) {

                call.respond(
                    HttpStatusCode.InternalServerError,
                    "Video download error: ${e.message}"
                )
            }
        }
    }

    delete("/videos/{fileName}") {

        println(
            "========== DELETE /videos/{fileName} =========="
        )

        try {

            // ============================================================
            // CHILD ID LẤY TỪ TOKEN
            // ============================================================

            val childUserId =
                call.requireUserId(authService)

            if (childUserId == null) {

                call.respond(
                    HttpStatusCode.Unauthorized,
                    VideoDeleteResponse(
                        success = false,
                        fileName = "",
                        message = "Invalid or expired token"
                    )
                )

                return@delete
            }

            // ============================================================
            // FILE NAME
            // ============================================================

            val fileName =
                call.parameters["fileName"]

            if (fileName.isNullOrBlank()) {

                call.respond(
                    HttpStatusCode.BadRequest,
                    VideoDeleteResponse(
                        success = false,
                        fileName = "",
                        message = "Missing file name"
                    )
                )

                return@delete
            }

            // ============================================================
            // CHỐNG PATH TRAVERSAL
            // ============================================================

            val safeName =
                File(fileName).name

            if (safeName != fileName) {

                call.respond(
                    HttpStatusCode.Forbidden,
                    VideoDeleteResponse(
                        success = false,
                        fileName = safeName,
                        message = "Invalid file name"
                    )
                )

                return@delete
            }

            println(
                "childUserId = $childUserId"
            )

            println(
                "Requested delete = $safeName"
            )

            // ============================================================
            // KIỂM TRA VIDEO THUỘC CHILD
            // ============================================================

            val videoExistsForChild =
                transaction {

                    VideosTable
                        .selectAll()
                        .where {

                            (VideosTable.childUserId eq childUserId) and
                                    (VideosTable.fileName eq safeName)

                        }
                        .any()
                }

            if (!videoExistsForChild) {

                println(
                    "DELETE DENIED"
                )

                println(
                    "childUserId = $childUserId"
                )

                println(
                    "fileName = $safeName"
                )

                call.respond(
                    HttpStatusCode.NotFound,
                    VideoDeleteResponse(
                        success = false,
                        fileName = safeName,
                        message = "Video not found"
                    )
                )

                return@delete
            }

            // ============================================================
            // FILE
            // ============================================================

            val videoDir =
                File("/app/videos")

            val file =
                File(
                    videoDir,
                    safeName
                )

            // ============================================================
            // CANONICAL PATH CHECK
            // ============================================================

            val videoDirPath =
                videoDir
                    .canonicalFile
                    .toPath()

            val filePath =
                file
                    .canonicalFile
                    .toPath()

            if (!filePath.startsWith(videoDirPath)) {

                println(
                    "PATH TRAVERSAL BLOCKED"
                )

                call.respond(
                    HttpStatusCode.Forbidden,
                    VideoDeleteResponse(
                        success = false,
                        fileName = safeName,
                        message = "Access denied"
                    )
                )

                return@delete
            }

            println(
                "DELETE TARGET = ${file.absolutePath}"
            )

            println(
                "Exists = ${file.exists()}"
            )

            // ============================================================
            // FILE ĐÃ BỊ XÓA TRƯỚC ĐÓ
            //
            // DB RECORD VẪN GIỮ LẠI.
            //
            // Không coi đây là lỗi nghiêm trọng.
            // ============================================================

            if (!file.exists() || !file.isFile) {

                println(
                    "Physical video already deleted: ${file.name}"
                )

                call.respond(
                    HttpStatusCode.OK,
                    VideoDeleteResponse(
                        success = true,
                        fileName = safeName,
                        message = "Video already deleted"
                    )
                )

                return@delete
            }

            // ============================================================
            // DELETE PHYSICAL FILE
            //
            // KHÔNG DELETE VideosTable
            //
            // DB record phải được giữ lại để:
            //
            // 1. Tính quota 3 video/ngày
            // 2. Giữ lịch sử upload
            // ============================================================

            if (file.delete()) {

                println(
                    "VIDEO DELETED SUCCESSFULLY"
                )

                println(
                    "childUserId = $childUserId"
                )

                println(
                    "fileName = ${file.name}"
                )

                call.respond(
                    HttpStatusCode.OK,
                    VideoDeleteResponse(
                        success = true,
                        fileName = file.name,
                        message = "Video deleted"
                    )
                )

            } else {

                println(
                    "VIDEO DELETE FAILED: ${file.absolutePath}"
                )

                call.respond(
                    HttpStatusCode.InternalServerError,
                    VideoDeleteResponse(
                        success = false,
                        fileName = file.name,
                        message = "Cannot delete video"
                    )
                )
            }

        } catch (e: Exception) {

            println(
                "========== DELETE VIDEO ERROR =========="
            )

            println(
                "Exception = ${e::class.qualifiedName}"
            )

            println(
                "Message = ${e.message}"
            )

            e.printStackTrace()

            if (!call.response.isCommitted) {

                call.respond(
                    HttpStatusCode.InternalServerError,
                    VideoDeleteResponse(
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
                    val formatter = SimpleDateFormat(
                        "dd/MM/yyyy HH:mm:ss",
                        Locale.getDefault()
                    )

                    formatter.timeZone = TimeZone.getTimeZone("Asia/Ho_Chi_Minh")
                    locations.forEach { location ->
                        val formattedTime = formatter.format(
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

private class VideoTooLargeException(
    message: String
) : Exception(message)


private fun cleanupOldVideos() {

    val videoDir =
        File("/app/videos")

    if (!videoDir.exists()) {
        return
    }

    println("========== VIDEO CLEANUP ==========")

    val videos =
        videoDir
            .listFiles()
            ?.filter { file ->
                file.isFile &&
                        file.extension.equals(
                            "mp4",
                            ignoreCase = true
                        )
            }
            ?.sortedBy { file ->
                file.lastModified()
            }
            ?: emptyList()

    println(
        "Total physical videos = ${videos.size}"
    )

    if (videos.size <= MAX_VIDEOS) {

        println(
            "Video count <= $MAX_VIDEOS, nothing to delete"
        )

        println("===================================")

        return
    }

    val deleteCount =
        videos.size - MAX_VIDEOS

    println(
        "Need to delete $deleteCount old video(s)"
    )

    videos
        .take(deleteCount)
        .forEach { file ->

            println(
                "Deleting old video: ${file.name}"
            )

            try {

                if (file.delete()) {

                    println(
                        "Deleted successfully: ${file.name}"
                    )

                    // ====================================================
                    // KHÔNG XÓA VideosTable
                    //
                    // VideosTable còn được giữ lại để:
                    //
                    // 1. Tính quota 3 video/ngày
                    // 2. Giữ lịch sử upload
                    //
                    // File vật lý đã bị cleanup nhưng record DB vẫn tồn tại.
                    // ====================================================

                } else {

                    println(
                        "FAILED to delete: ${file.name}"
                    )
                }

            } catch (e: Exception) {

                println(
                    "FAILED to delete: ${file.name}"
                )

                e.printStackTrace()
            }
        }

    println(
        "Remaining physical videos = " +
                videos
                    .drop(deleteCount)
                    .size
    )

    println("===================================")
}
