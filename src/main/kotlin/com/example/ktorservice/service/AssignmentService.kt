package com.example.ktorservice.service

import com.example.ktorservice.database.table.AssignmentsTable
import com.example.ktorservice.database.table.UserAssignmentsTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.ConcurrentHashMap

class AssignmentService(
    private val aiService: AIService
) {
    private val assignmentGenerationMutex = Mutex()
    // ============================================================
// GET NEXT ASSIGNMENT
//
// - Ưu tiên lấy bài đã có trong kho
// - Không lấy bài đã giao cho user này
// - Khi lấy được bài -> ghi user_assignments ngay
// - Nếu kho hết -> AI tạo bài mới
// - Bài AI tạo được lưu vào kho
// - Sau đó giao bài đó cho user
// ============================================================

    suspend fun getNextAssignment(
        userId: Int,
        grade: Int,
        subject: String,
        topic: String? = null
    ): UserAssignmentResult {

        require(grade in 1..12) {
            "Invalid grade"
        }

        require(subject.isNotBlank()) {
            "Subject is required"
        }

        println("========== GET NEXT ASSIGNMENT ==========")
        println("USER ID = $userId")
        println("GRADE = $grade")
        println("SUBJECT = $subject")
        println("TOPIC = $topic")

        // ========================================================
        // BƯỚC 1
        // Tìm bài đã có trong kho nhưng CHƯA giao cho user
        // ========================================================

        val existingAssignment =
            findNextAvailableAssignment(
                userId = userId,
                grade = grade,
                subject = subject,
                topic = topic
            )

        if (existingAssignment != null) {

            println(
                "ASSIGNMENT STORAGE HIT: " +
                        "id=${existingAssignment.id}"
            )

            return createUserAssignmentImmediately(
                userId = userId,
                assignment = existingAssignment
            )
        }

        // ========================================================
        // BƯỚC 2
        // Kho hết.
        //
        // Khóa phần generate để:
        //
        // User A -> AI generate
        // User B -> chờ
        // User C -> chờ
        //
        // Khi A tạo xong, B/C sẽ kiểm tra lại kho.
        // ========================================================

        return assignmentGenerationMutex.withLock {

            println(
                "NO AVAILABLE ASSIGNMENT IN STORAGE"
            )

            println(
                "WAITING FOR ASSIGNMENT GENERATION LOCK..."
            )

            // ----------------------------------------------------
            // QUAN TRỌNG:
            // Trong lúc chờ Mutex, tài khoản khác có thể vừa
            // tạo bài và lưu vào kho.
            //
            // Vì vậy phải kiểm tra kho LẠI.
            // ----------------------------------------------------

            val assignmentAfterLock =
                findNextAvailableAssignment(
                    userId = userId,
                    grade = grade,
                    subject = subject,
                    topic = topic
                )

            if (assignmentAfterLock != null) {

                println(
                    "ASSIGNMENT BECAME AVAILABLE WHILE WAITING: " +
                            "id=${assignmentAfterLock.id}"
                )

                return@withLock createUserAssignmentImmediately(
                    userId = userId,
                    assignment = assignmentAfterLock
                )
            }

            // ====================================================
            // BƯỚC 3
            // Thực sự không còn bài -> gọi AI
            // ====================================================

            println(
                "ASSIGNMENT STORAGE EMPTY"
            )

            println(
                "GENERATING NEW ASSIGNMENT WITH AI..."
            )

            val generated =
                aiService.generateAssignment(
                    grade = grade,
                    subject = subject,
                    topic = topic
                )
            val content =
                generated.questions
                    .joinToString("\n\n") { question ->
                        "Câu ${question.id} (${question.points} điểm):\n${question.question}"
                    }

            val answerKey =
                generated.answerKey
                    .joinToString("\n\n") { answer ->
                        "Câu ${answer.id}:\n${answer.answer}"
                    }

            val gradingGuide =
                generated.gradingGuide

            val assignment =
                withContext(Dispatchers.IO) {

                    transaction {

                        val statement =
                            AssignmentsTable.insert {

                                it[AssignmentsTable.grade] =
                                    grade

                                it[AssignmentsTable.subject] =
                                    subject

                                it[AssignmentsTable.topic] =
                                    topic

                                it[AssignmentsTable.title] =
                                    generated.title

                                it[AssignmentsTable.content] =
                                    content

                                it[AssignmentsTable.answerKey] =
                                    answerKey

                                it[AssignmentsTable.gradingGuide] =
                                    gradingGuide

                                it[AssignmentsTable.totalScore] =
                                    generated.totalScore

                                it[AssignmentsTable.createdAt] =
                                    System.currentTimeMillis()
                            }

                        val id =
                            statement[
                                AssignmentsTable.id
                            ]

                        println(
                            "NEW ASSIGNMENT SAVED: id=$id"
                        )

                        AssignmentResult(
                            id = id,
                            grade = grade,
                            subject = subject,
                            topic = topic,
                            title = generated.title,
                            content = content,
                            answerKey = answerKey,
                            gradingGuide = gradingGuide,
                            totalScore = generated.totalScore
                        )
                    }
                }

            // ====================================================
            // BƯỚC 5
            // Ghi nhận giao cho user NGAY
            // ====================================================

            println(
                "ASSIGNING NEW AI ASSIGNMENT TO USER"
            )

            println(
                "USER ID = $userId"
            )

            println(
                "ASSIGNMENT ID = ${assignment.id}"
            )

            createUserAssignmentImmediately(
                userId = userId,
                assignment = assignment
            )
        }
    }
    // ============================================================
// CREATE USER ASSIGNMENT IMMEDIATELY
// ============================================================

    private suspend fun createUserAssignmentImmediately(
        userId: Int,
        assignment: AssignmentResult
    ): UserAssignmentResult {

        return withContext(Dispatchers.IO) {

            transaction {

                // ------------------------------------------------
                // Kiểm tra lần cuối để chống giao trùng
                // ------------------------------------------------

                val existing =
                    UserAssignmentsTable
                        .selectAll()
                        .where {

                            (UserAssignmentsTable.userId eq userId) and
                                    (
                                            UserAssignmentsTable.assignmentId eq
                                                    assignment.id
                                            )
                        }
                        .firstOrNull()

                if (existing != null) {

                    println(
                        "USER ASSIGNMENT ALREADY EXISTS: " +
                                "user=$userId " +
                                "assignment=${assignment.id}"
                    )

                    return@transaction rowToUserAssignment(
                        existing,
                        assignment
                    )
                }

                // ------------------------------------------------
                // Tạo bản ghi mới
                // ------------------------------------------------

                val statement =
                    UserAssignmentsTable.insert {

                        it[UserAssignmentsTable.userId] =
                            userId

                        it[UserAssignmentsTable.assignmentId] =
                            assignment.id

                        it[UserAssignmentsTable.status] =
                            "NEW"
                    }

                val userAssignmentId =
                    statement[
                        UserAssignmentsTable.id
                    ]

                println(
                    "USER ASSIGNMENT CREATED: " +
                            "id=$userAssignmentId " +
                            "user=$userId " +
                            "assignment=${assignment.id}"
                )

                UserAssignmentResult(
                    id = userAssignmentId,
                    assignmentId = assignment.id,
                    userId = userId,
                    status = "NEW",
                    answer = null,
                    score = null,
                    feedback = null,
                    startedAt = null,
                    completedAt = null,
                    assignment = assignment
                )
            }
        }
    }
    private suspend fun findNextAvailableAssignment(
        userId: Int,
        grade: Int,
        subject: String,
        topic: String?
    ): AssignmentResult? {

        return withContext(Dispatchers.IO) {

            transaction {

                AssignmentsTable
                    .selectAll()
                    .where {

                        (AssignmentsTable.grade eq grade) and
                                (AssignmentsTable.subject eq subject) and
                                (
                                        if (topic == null) {
                                            AssignmentsTable.topic.isNull()
                                        } else {
                                            AssignmentsTable.topic eq topic
                                        }
                                        )
                    }
                    .orderBy(
                        AssignmentsTable.id to SortOrder.ASC
                    )
                    .firstOrNull { row ->

                        val assignmentId =
                            row[AssignmentsTable.id]

                        val alreadyAssigned =
                            UserAssignmentsTable
                                .selectAll()
                                .where {

                                    (UserAssignmentsTable.userId eq userId) and
                                            (
                                                    UserAssignmentsTable.assignmentId eq
                                                            assignmentId
                                                    )
                                }
                                .count() > 0

                        !alreadyAssigned
                    }
                    ?.let {
                        rowToResult(it)
                    }
            }
        }
    }
// ============================================================
    // GENERATION LOCK
    // ============================================================

    private val generationLocks =
        ConcurrentHashMap<String, Mutex>()

    private fun getGenerationLock(
        grade: Int,
        subject: String,
        topic: String?
    ): Mutex {

        val key =
            buildString {

                append(grade)
                append("|")
                append(subject.trim().lowercase())
                append("|")
                append(topic?.trim()?.lowercase() ?: "")
            }

        return generationLocks.computeIfAbsent(key) {
            Mutex()
        }
    }



    // ============================================================
    // GET USER ASSIGNMENT BY ID
    // ============================================================

    suspend fun getUserAssignment(
        userId: Int,
        userAssignmentId: Int
    ): UserAssignmentResult? {

        return withContext(Dispatchers.IO) {

            transaction {

                println("========== GET USER ASSIGNMENT ==========")
                println("USER ID = $userId")
                println("USER ASSIGNMENT ID = $userAssignmentId")

                val row =
                    UserAssignmentsTable
                        .selectAll()
                        .where {
                            (UserAssignmentsTable.id eq userAssignmentId) and
                                    (UserAssignmentsTable.userId eq userId)
                        }
                        .firstOrNull()

                if (row == null) {

                    println("USER ASSIGNMENT NOT FOUND")
                    println(
                        "Looking for UserAssignmentsTable.id=$userAssignmentId " +
                                "AND userId=$userId"
                    )

                    return@transaction null
                }

                val assignmentId =
                    row[UserAssignmentsTable.assignmentId]

                println("USER ASSIGNMENT FOUND")
                println("USER ASSIGNMENT ID = ${row[UserAssignmentsTable.id]}")
                println("USER ID = ${row[UserAssignmentsTable.userId]}")
                println("ASSIGNMENT ID = $assignmentId")
                println("STATUS = ${row[UserAssignmentsTable.status]}")

                val assignmentRow =
                    AssignmentsTable
                        .selectAll()
                        .where {
                            AssignmentsTable.id eq assignmentId
                        }
                        .firstOrNull()

                if (assignmentRow == null) {

                    println("ASSIGNMENT NOT FOUND")
                    println(
                        "Looking for AssignmentsTable.id=$assignmentId"
                    )

                    return@transaction null
                }

                val assignment =
                    rowToResult(assignmentRow)

                println("ASSIGNMENT FOUND")
                println("TITLE = ${assignment.title}")

                rowToUserAssignment(
                    row,
                    assignment
                )
            }
        }
    }

    // ============================================================
    // START ASSIGNMENT
    // ============================================================

    suspend fun startAssignment(
        userId: Int,
        userAssignmentId: Int
    ): Boolean {

        return withContext(Dispatchers.IO) {

            transaction {

                val row =
                    UserAssignmentsTable
                        .selectAll()
                        .where {
                            (UserAssignmentsTable.id eq userAssignmentId) and
                                    (
                                            UserAssignmentsTable.userId eq userId
                                            )
                        }
                        .firstOrNull()
                        ?: return@transaction false

                if (
                    row[UserAssignmentsTable.status] ==
                    "COMPLETED"
                ) {
                    return@transaction true
                }

                UserAssignmentsTable.update(
                    where = {
                        UserAssignmentsTable.id eq
                                userAssignmentId
                    }
                ) {

                    it[status] =
                        "IN_PROGRESS"

                    if (
                        row[UserAssignmentsTable.startedAt] == null
                    ) {
                        it[startedAt] =
                            System.currentTimeMillis()
                    }
                }

                true
            }
        }
    }

    // ============================================================
    // SUBMIT ASSIGNMENT
    // ============================================================

    suspend fun submitAssignment(
        userId: Int,
        userAssignmentId: Int,
        answer: String
    ): UserAssignmentResult? {

        if (answer.isBlank()) {
            throw IllegalArgumentException("Answer is required")
        }

        val userAssignment =
            getUserAssignment(
                userId = userId,
                userAssignmentId = userAssignmentId
            )
                ?: return null

        if (userAssignment.status == "COMPLETED") {
            return userAssignment
        }

        println("========== SUBMIT ASSIGNMENT ==========")
        println("USER ID = $userId")
        println("USER ASSIGNMENT ID = $userAssignmentId")
        println("ASSIGNMENT ID = ${userAssignment.assignmentId}")

        // --------------------------------------------------------
        // Gọi Gemini để chấm
        // --------------------------------------------------------

        println("GRADING ASSIGNMENT WITH AI...")
        println("========== ASSIGNMENT DATA FOR GRADING ==========")

        println("ASSIGNMENT ID = ${userAssignment.assignment.id}")
        println("TITLE = ${userAssignment.assignment.title}")

        println("----- CONTENT -----")
        println(userAssignment.assignment.content)

        println("----- ANSWER KEY -----")
        println(userAssignment.assignment.answerKey)

        println("----- GRADING GUIDE -----")
        println(userAssignment.assignment.gradingGuide)

        println("----- TOTAL SCORE -----")
        println(userAssignment.assignment.totalScore)

        println("----- STUDENT ANSWER -----")
        println(answer)

        println("=================================================")

        println("GRADING ASSIGNMENT WITH AI...")

        val grading =
            aiService.gradeAssignment(
                assignment = userAssignment.assignment,
                studentAnswer = answer
            )

        println(
            "AI GRADE = ${grading.score}"
        )

        // --------------------------------------------------------
        // Lưu kết quả
        // --------------------------------------------------------

        withContext(Dispatchers.IO) {

            transaction {

                UserAssignmentsTable.update(
                    where = {
                        (UserAssignmentsTable.id eq userAssignmentId) and
                                (UserAssignmentsTable.userId eq userId)
                    }
                ) {

                    it[status] =
                        "COMPLETED"

                    it[UserAssignmentsTable.answer] =
                        answer

                    it[UserAssignmentsTable.score] =
                        grading.score

                    it[UserAssignmentsTable.feedback] =
                        grading.feedback

                    it[completedAt] =
                        System.currentTimeMillis()
                }
            }
        }

        return getUserAssignment(
            userId = userId,
            userAssignmentId = userAssignmentId
        )
    }

    // ============================================================
// GET ALL ASSIGNMENTS FROM STORAGE
// ============================================================

    suspend fun getAllAssignments(
        grade: Int? = null,
        subject: String? = null,
        topic: String? = null
    ): List<AssignmentResult> {

        return withContext(Dispatchers.IO) {

            transaction {

                var query =
                    AssignmentsTable
                        .selectAll()

                if (grade != null) {
                    query =
                        query.andWhere {
                            AssignmentsTable.grade eq grade
                        }
                }

                if (!subject.isNullOrBlank()) {
                    query =
                        query.andWhere {
                            AssignmentsTable.subject eq subject
                        }
                }

                if (topic != null) {

                    query =
                        query.andWhere {

                            AssignmentsTable.topic eq topic
                        }
                }

                query
                    .orderBy(
                        AssignmentsTable.id to SortOrder.ASC
                    )
                    .map {
                        rowToResult(it)
                    }
            }
        }
    }
    // ============================================================
    // FIND EXISTING ASSIGNMENT
    // ============================================================
    // ============================================================
// GET NEXT ASSIGNMENT FOR USER
// ============================================================
    private suspend fun findExistingAssignment(
        grade: Int,
        subject: String,
        topic: String?
    ): AssignmentResult? {

        return withContext(Dispatchers.IO) {

            transaction {

                AssignmentsTable
                    .selectAll()
                    .where {

                        (AssignmentsTable.grade eq grade) and
                                (AssignmentsTable.subject eq subject) and
                                (
                                        if (topic == null) {
                                            AssignmentsTable.topic.isNull()
                                        } else {
                                            AssignmentsTable.topic eq topic
                                        }
                                        )
                    }
                    .orderBy(
                        AssignmentsTable.id to SortOrder.ASC
                    )
                    .limit(1)
                    .firstOrNull()
                    ?.let {
                        rowToResult(it)
                    }
            }
        }
    }

    // ============================================================
    // GET BY ID
    // ============================================================

    suspend fun getById(
        id: Int
    ): AssignmentResult? {

        return withContext(Dispatchers.IO) {

            transaction {

                AssignmentsTable
                    .selectAll()
                    .where {
                        AssignmentsTable.id eq id
                    }
                    .firstOrNull()
                    ?.let {
                        rowToResult(it)
                    }
            }
        }
    }

    // ============================================================
    // ASSIGNMENT ROW
    // ============================================================

    private fun rowToResult(
        row: ResultRow
    ): AssignmentResult {

        return AssignmentResult(

            id =
                row[AssignmentsTable.id],

            grade =
                row[AssignmentsTable.grade],

            subject =
                row[AssignmentsTable.subject],

            topic =
                row[AssignmentsTable.topic],

            title =
                row[AssignmentsTable.title],

            content =
                row[AssignmentsTable.content],

            answerKey =
                row[AssignmentsTable.answerKey],

            gradingGuide =
                row[AssignmentsTable.gradingGuide],

            totalScore =
                row[AssignmentsTable.totalScore]
        )
    }

    // ============================================================
    // USER ASSIGNMENT ROW
    // ============================================================

    private fun rowToUserAssignment(
        row: ResultRow,
        assignment: AssignmentResult
    ): UserAssignmentResult {

        return UserAssignmentResult(

            id =
                row[UserAssignmentsTable.id],

            assignmentId =
                row[UserAssignmentsTable.assignmentId],

            userId =
                row[UserAssignmentsTable.userId],

            status =
                row[UserAssignmentsTable.status],

            answer =
                row[UserAssignmentsTable.answer],

            score =
                row[UserAssignmentsTable.score],

            feedback =
                row[UserAssignmentsTable.feedback],

            startedAt =
                row[UserAssignmentsTable.startedAt],

            completedAt =
                row[UserAssignmentsTable.completedAt],

            assignment =
                assignment
        )
    }

    // ============================================================
    // ASSIGNMENT RESULT
    // ============================================================

    data class AssignmentResult(

        val id: Int,

        val grade: Int,

        val subject: String,

        val topic: String?,

        val title: String,

        val content: String,

        val answerKey: String,

        val gradingGuide: String,

        val totalScore: Double
    )

    // ============================================================
    // USER ASSIGNMENT RESULT
    // ============================================================

    data class UserAssignmentResult(

        val id: Int,

        val assignmentId: Int,

        val userId: Int,

        val status: String,

        val answer: String?,

        val score: Double?,

        val feedback: String?,

        val startedAt: Long?,

        val completedAt: Long?,

        val assignment: AssignmentResult
    )
}