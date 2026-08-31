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
    // GET OR GENERATE
    // ============================================================

    suspend fun getOrGenerateAssignment(
        grade: Int,
        subject: String,
        topic: String? = null
    ): AssignmentResult {

        require(grade in 1..12) {
            "Invalid grade"
        }

        require(subject.isNotBlank()) {
            "Subject is required"
        }

        val existing =
            findExistingAssignment(
                grade = grade,
                subject = subject,
                topic = topic
            )

        if (existing != null) {

            println(
                "ASSIGNMENT CACHE HIT: " +
                        "grade=$grade " +
                        "subject=$subject " +
                        "topic=$topic " +
                        "id=${existing.id}"
            )

            return existing
        }

        println(
            "ASSIGNMENT CACHE MISS: " +
                    "grade=$grade " +
                    "subject=$subject " +
                    "topic=$topic"
        )

        println("GENERATING NEW ASSIGNMENT WITH AI...")

        val generated =
            aiService.generateAssignment(
                grade = grade,
                subject = subject,
                topic = topic
            )

        val id =
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
                                generated.content

                            it[AssignmentsTable.answerKey] =
                                generated.answerKey

                            it[AssignmentsTable.gradingGuide] =
                                generated.gradingGuide

                            it[AssignmentsTable.totalScore] =
                                generated.totalScore

                            it[AssignmentsTable.createdAt] =
                                System.currentTimeMillis()
                        }

                    statement[
                        AssignmentsTable.id
                    ]
                }
            }

        println(
            "ASSIGNMENT SAVED: id=$id"
        )

        return AssignmentResult(
            id = id,
            grade = grade,
            subject = subject,
            topic = topic,
            title = generated.title,
            content = generated.content,
            answerKey = generated.answerKey,
            gradingGuide = generated.gradingGuide,
            totalScore = generated.totalScore
        )
    }

    // ============================================================
    // GET OR CREATE USER ASSIGNMENT
    // ============================================================

    suspend fun getOrCreateUserAssignment(
        userId: Int,
        grade: Int,
        subject: String,
        topic: String? = null
    ): UserAssignmentResult {

        val assignment =
            getOrGenerateAssignment(
                grade = grade,
                subject = subject,
                topic = topic
            )

        return withContext(Dispatchers.IO) {

            transaction {

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

                    return@transaction rowToUserAssignment(
                        existing,
                        assignment
                    )
                }

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

    // ============================================================
    // GET TODAY / NEXT ASSIGNMENT
    // ============================================================

    suspend fun getTodayAssignment(
        userId: Int,
        grade: Int,
        subject: String
    ): UserAssignmentResult {

        return getOrCreateUserAssignment(
            userId = userId,
            grade = grade,
            subject = subject
        )
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
    // GET NEXT ASSIGNMENT FOR PARENT
    //
    // Lấy bài chưa từng giao cho user.
    // Khi lấy được bài -> ghi ngay vào user_assignments.
    // ============================================================

    suspend fun getNextAssignmentForParent(
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

        // ========================================================
        // 1. TÌM BÀI ĐÃ CÓ TRONG KHO
        // ========================================================

        val existingAssignment =
            withContext(Dispatchers.IO) {

                transaction {

                    val assignedIds =
                        UserAssignmentsTable
                            .select(
                                UserAssignmentsTable.assignmentId
                            )
                            .where {
                                UserAssignmentsTable.userId eq userId
                            }
                            .map {
                                it[
                                    UserAssignmentsTable.assignmentId
                                ]
                            }

                    var query =
                        AssignmentsTable
                            .selectAll()
                            .where {

                                (AssignmentsTable.grade eq grade) and
                                        (
                                                AssignmentsTable.subject eq
                                                        subject
                                                )
                            }

                    if (topic != null) {

                        query =
                            query.andWhere {
                                AssignmentsTable.topic eq topic
                            }
                    }

                    if (assignedIds.isNotEmpty()) {

                        query =
                            query.andWhere {

                                AssignmentsTable.id
                                    .notInList(assignedIds)
                            }
                    }

                    query
                        .orderBy(
                            AssignmentsTable.id to
                                    SortOrder.ASC
                        )
                        .limit(1)
                        .firstOrNull()
                        ?.let {
                            rowToResult(it)
                        }
                }
            }

        // ========================================================
        // 2. NẾU CÒN BÀI TRONG KHO
        // ========================================================

        if (existingAssignment != null) {

            println(
                "========== NEXT ASSIGNMENT FROM STORAGE =========="
            )

            println("USER ID = $userId")
            println("ASSIGNMENT ID = ${existingAssignment.id}")
            println("TITLE = ${existingAssignment.title}")

            val userAssignment =
                withContext(Dispatchers.IO) {

                    transaction {

                        // Kiểm tra lại để tránh race condition
                        val existing =
                            UserAssignmentsTable
                                .selectAll()
                                .where {

                                    (
                                            UserAssignmentsTable.userId eq
                                                    userId
                                            ) and
                                            (
                                                    UserAssignmentsTable.assignmentId eq
                                                            existingAssignment.id
                                                    )
                                }
                                .firstOrNull()

                        if (existing != null) {

                            return@transaction rowToUserAssignment(
                                existing,
                                existingAssignment
                            )
                        }

                        val statement =
                            UserAssignmentsTable.insert {

                                it[UserAssignmentsTable.userId] =
                                    userId

                                it[UserAssignmentsTable.assignmentId] =
                                    existingAssignment.id

                                it[UserAssignmentsTable.status] =
                                    "NEW"
                            }

                        val userAssignmentId =
                            statement[
                                UserAssignmentsTable.id
                            ]

                        println(
                            "ASSIGNMENT MARKED AS DELIVERED"
                        )

                        println(
                            "USER ID = $userId"
                        )

                        println(
                            "ASSIGNMENT ID = ${existingAssignment.id}"
                        )

                        println(
                            "USER ASSIGNMENT ID = $userAssignmentId"
                        )

                        UserAssignmentResult(
                            id = userAssignmentId,
                            assignmentId = existingAssignment.id,
                            userId = userId,
                            status = "NEW",
                            answer = null,
                            score = null,
                            feedback = null,
                            startedAt = null,
                            completedAt = null,
                            assignment = existingAssignment
                        )
                    }
                }

            return userAssignment
        }

        // ========================================================
        // 3. KHO HẾT BÀI
        // ========================================================

        println(
            "========== ASSIGNMENT STORAGE EMPTY FOR USER =========="
        )

        println("USER ID = $userId")
        println("GRADE = $grade")
        println("SUBJECT = $subject")
        println("TOPIC = $topic")

        // ========================================================
        // 4. KHÓA VIỆC GENERATE AI
        // ========================================================

        val lock =
            getGenerationLock(
                grade = grade,
                subject = subject,
                topic = topic
            )

        return lock.withLock {

            // ====================================================
            // 5. DOUBLE CHECK SAU KHI LẤY LOCK
            //
            // Một request khác có thể vừa tạo bài trong lúc
            // request này đang chờ lock.
            // ====================================================

            val assignmentAfterLock =
                withContext(Dispatchers.IO) {

                    transaction {

                        val assignedIds =
                            UserAssignmentsTable
                                .select(
                                    UserAssignmentsTable.assignmentId
                                )
                                .where {
                                    UserAssignmentsTable.userId eq
                                            userId
                                }
                                .map {
                                    it[
                                        UserAssignmentsTable.assignmentId
                                    ]
                                }

                        var query =
                            AssignmentsTable
                                .selectAll()
                                .where {

                                    (AssignmentsTable.grade eq grade) and
                                            (
                                                    AssignmentsTable.subject eq
                                                            subject
                                                    )
                                }

                        if (topic != null) {

                            query =
                                query.andWhere {
                                    AssignmentsTable.topic eq topic
                                }
                        }

                        if (assignedIds.isNotEmpty()) {

                            query =
                                query.andWhere {

                                    AssignmentsTable.id
                                        .notInList(assignedIds)
                                }
                        }

                        query
                            .orderBy(
                                AssignmentsTable.id to
                                        SortOrder.ASC
                            )
                            .limit(1)
                            .firstOrNull()
                            ?.let {
                                rowToResult(it)
                            }
                    }
                }

            // ====================================================
            // 6. REQUEST KHÁC ĐÃ TẠO BÀI
            // ====================================================

            if (assignmentAfterLock != null) {

                println(
                    "ASSIGNMENT CREATED BY ANOTHER REQUEST"
                )

                val assignment =
                    assignmentAfterLock

                return@withLock withContext(
                    Dispatchers.IO
                ) {

                    transaction {

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
                            "ASSIGNMENT MARKED AS DELIVERED"
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

            // ====================================================
            // 7. THỰC SỰ HẾT KHO → GỌI AI
            // ====================================================

            println(
                "========== GENERATING NEW ASSIGNMENT =========="
            )

            println("GRADE = $grade")
            println("SUBJECT = $subject")
            println("TOPIC = $topic")

            val generated =
                aiService.generateAssignment(
                    grade = grade,
                    subject = subject,
                    topic = topic
                )

            // ====================================================
            // 8. LƯU BÀI MỚI VÀO KHO CHUNG
            // ====================================================

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
                                    generated.content

                                it[AssignmentsTable.answerKey] =
                                    generated.answerKey

                                it[AssignmentsTable.gradingGuide] =
                                    generated.gradingGuide

                                it[AssignmentsTable.totalScore] =
                                    generated.totalScore

                                it[AssignmentsTable.createdAt] =
                                    System.currentTimeMillis()
                            }

                        val assignmentId =
                            statement[
                                AssignmentsTable.id
                            ]

                        println(
                            "NEW ASSIGNMENT SAVED"
                        )

                        println(
                            "ASSIGNMENT ID = $assignmentId"
                        )

                        AssignmentResult(
                            id = assignmentId,
                            grade = grade,
                            subject = subject,
                            topic = topic,
                            title = generated.title,
                            content = generated.content,
                            answerKey = generated.answerKey,
                            gradingGuide = generated.gradingGuide,
                            totalScore = generated.totalScore
                        )
                    }
                }

            // ====================================================
            // 9. ĐÁNH DẤU NGAY LÀ ĐÃ GIAO CHO USER
            // ====================================================

            return@withLock withContext(
                Dispatchers.IO
            ) {

                transaction {

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
                        "NEW ASSIGNMENT DELIVERED TO USER"
                    )

                    println(
                        "USER ID = $userId"
                    )

                    println(
                        "ASSIGNMENT ID = ${assignment.id}"
                    )

                    println(
                        "USER ASSIGNMENT ID = $userAssignmentId"
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
    }
    // ============================================================
// GET NEXT ASSIGNMENT FOR USER
// ============================================================

    suspend fun getNextAssignment(
        userId: Int,
        grade: Int,
        subject: String
    ): UserAssignmentResult? {

        require(grade in 1..12) {
            "Invalid grade"
        }

        require(subject.isNotBlank()) {
            "Subject is required"
        }

        return withContext(Dispatchers.IO) {

            transaction {

                println(
                    "========== GET NEXT ASSIGNMENT =========="
                )

                println("USER ID = $userId")
                println("GRADE = $grade")
                println("SUBJECT = $subject")

                // ----------------------------------------------------
                // Tìm bài đầu tiên mà user này chưa từng nhận
                // ----------------------------------------------------

                val assignmentRow =
                    AssignmentsTable
                        .selectAll()
                        .where {
                            AssignmentsTable.grade eq grade
                        }
                        .andWhere {
                            AssignmentsTable.subject eq subject
                        }
                        .orderBy(
                            AssignmentsTable.id to SortOrder.ASC
                        )
                        .firstOrNull { assignment ->

                            val assignmentId =
                                assignment[
                                    AssignmentsTable.id
                                ]

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

                if (assignmentRow == null) {

                    println(
                        "NO NEW ASSIGNMENT AVAILABLE"
                    )

                    return@transaction null
                }

                val assignment =
                    rowToResult(assignmentRow)

                println(
                    "NEXT ASSIGNMENT FOUND"
                )

                println(
                    "ASSIGNMENT ID = ${assignment.id}"
                )

                println(
                    "TITLE = ${assignment.title}"
                )

                // ----------------------------------------------------
                // Đánh dấu đã giao NGAY LẬP TỨC
                // ----------------------------------------------------

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
                    "ASSIGNMENT MARKED AS ASSIGNED"
                )

                println(
                    "USER ASSIGNMENT ID = $userAssignmentId"
                )

                // ----------------------------------------------------
                // Trả UserAssignment
                // ----------------------------------------------------

                UserAssignmentResult(

                    id =
                        userAssignmentId,

                    assignmentId =
                        assignment.id,

                    userId =
                        userId,

                    status =
                        "NEW",

                    answer =
                        null,

                    score =
                        null,

                    feedback =
                        null,

                    startedAt =
                        null,

                    completedAt =
                        null,

                    assignment =
                        assignment
                )
            }
        }
    }
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