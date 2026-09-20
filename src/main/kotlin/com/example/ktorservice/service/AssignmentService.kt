package com.example.ktorservice.service

import com.example.ktorservice.database.UsersTable
import com.example.ktorservice.database.table.AssignmentsTable
import com.example.ktorservice.database.table.UserAssignmentsTable
import com.example.ktorservice.model.QuestionMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction


class AssignmentService(
    private val aiService: AIService
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ============================================================
    // AI CONCURRENCY LIMIT
    // ============================================================

    private val aiSemaphore = Semaphore(2)


    // ============================================================
    // GET TOP 5 CHILDREN
    // ============================================================

    data class TopStudentResult(
        val userId: Int,
        val name: String,
        val totalScore: Double
    )

    suspend fun getTop5Children(): List<TopStudentResult> {

        return withContext(Dispatchers.IO) {

            transaction {

                val rows =
                    UserAssignmentsTable
                        .innerJoin(
                            UsersTable,
                            { UserAssignmentsTable.userId },
                            { UsersTable.id }
                        )
                        .select(
                            UserAssignmentsTable.userId,
                            UserAssignmentsTable.score
                        )
                        .where {
                            (UserAssignmentsTable.status eq "COMPLETED") and
                                    (UsersTable.role eq "CHILD")
                        }

                val scoreMap =
                    mutableMapOf<Int, Double>()

                rows.forEach { row ->

                    val userId =
                        row[UserAssignmentsTable.userId]

                    val score =
                        row[UserAssignmentsTable.score]
                            ?: 0.0

                    scoreMap[userId] =
                        (scoreMap[userId] ?: 0.0) + score
                }

                scoreMap.entries
                    .sortedByDescending { entry ->
                        entry.value
                    }
                    .take(5)
                    .mapNotNull { entry ->

                        val userId =
                            entry.key

                        val user =
                            UsersTable
                                .selectAll()
                                .where {
                                    UsersTable.id eq userId
                                }
                                .firstOrNull()

                        if (user == null) {
                            null
                        } else {
                            TopStudentResult(
                                userId = userId,
                                name = user[UsersTable.username],
                                totalScore = entry.value
                            )
                        }
                    }
            }
        }
    }


    // ============================================================
    // GET NEXT ASSIGNMENT
    // ============================================================

    suspend fun getNextAssignment(
        userId: Int,
        grade: Int,
        subject: String,
        topic: String?,
        difficulty: AIService.Difficulty
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
        println("DIFFICULTY = $difficulty")

        // ========================================================
        // BƯỚC 1
        // Tìm bài đã có trong kho nhưng CHƯA giao cho user
        // ========================================================

        val existingAssignment =
            findNextAvailableAssignment(
                userId = userId,
                grade = grade,
                subject = subject,
                topic = topic,
                difficulty = difficulty
            )

        if (existingAssignment != null) {

            println(
                "ASSIGNMENT STORAGE HIT: id=${existingAssignment.id}"
            )

            return createUserAssignmentImmediately(
                userId = userId,
                assignment = existingAssignment
            )
        }

        println(
            "NO AVAILABLE ASSIGNMENT IN STORAGE"
        )

        println(
            "ASSIGNMENT STORAGE EMPTY"
        )

        println(
            "WAITING FOR AI SEMAPHORE..."
        )

        // ========================================================
        // BƯỚC 2
        // Generate + Validate + AI Quality Review
        // ========================================================

        var generated: AIService.GeneratedAssignment? = null

        var lastErrors =
            emptyList<String>()

        for (attempt in 1..3) {

            println(
                "=================================================="
            )

            println(
                "ASSIGNMENT GENERATION ATTEMPT $attempt/3"
            )

            println(
                "=================================================="
            )

            try {

                // ------------------------------------------------
                // GENERATE
                // ------------------------------------------------

                val candidate =
                    aiSemaphore.withPermit {

                        println(
                            "AI GENERATE SLOT ACQUIRED"
                        )

                        try {

                            aiService.generateAssignment(
                                grade = grade,
                                subject = subject,
                                topic = topic,
                                difficulty = difficulty,

                                // AIService v2 nhận String?
                                qualityFeedback =
                                    lastErrors
                                        .joinToString("\n")
                                        .ifBlank { null }
                            )

                        } finally {

                            println(
                                "AI GENERATE SLOT RELEASED"
                            )
                        }
                    }

                println(
                    "AI GENERATED ASSIGNMENT"
                )

                println(
                    "TITLE = ${candidate.title}"
                )

                // ------------------------------------------------
                // STRUCTURAL VALIDATION
                // ------------------------------------------------

                val validation =
                    AssignmentValidator.validate(
                        title = candidate.title,
                        questions = candidate.questions,
                        answerKey = candidate.answerKey,
                        gradingGuide = candidate.gradingGuide,
                        totalScore = candidate.totalScore,
                        learningMaterial = candidate.learningMaterial
                    )

                if (!validation.valid) {

                    println(
                        "❌ ASSIGNMENT VALIDATOR FAILED"
                    )

                    validation.errors.forEach {
                        println("❌ $it")
                    }

                    lastErrors =
                        validation.errors

                    if (attempt < 3) {

                        println(
                            "REGENERATING BECAUSE VALIDATOR FAILED..."
                        )

                        continue
                    }

                    throw IllegalStateException(
                        "AI generated assignment failed validation after 3 attempts:\n" +
                                validation.errors.joinToString("\n")
                    )
                }

                println(
                    "✅ ASSIGNMENT VALIDATOR PASSED"
                )

                // ------------------------------------------------
                // AI QUALITY REVIEW
                // ------------------------------------------------

                println(
                    "WAITING FOR AI QUALITY REVIEW SLOT..."
                )

                val qualityReview =
                    aiSemaphore.withPermit {

                        println(
                            "AI QUALITY REVIEW SLOT ACQUIRED"
                        )

                        try {

                            aiService.reviewGeneratedAssignment(
                                assignment = candidate,
                                grade = grade,
                                subject = subject,
                                topic = topic,
                                difficulty = difficulty
                            )

                        } finally {

                            println(
                                "AI QUALITY REVIEW SLOT RELEASED"
                            )
                        }
                    }

                if (!qualityReview.pass) {

                    println(
                        "❌ AI QUALITY REVIEW FAILED"
                    )

                    qualityReview.issues.forEach {
                        println("❌ $it")
                    }

                    lastErrors =
                        if (qualityReview.issues.isNotEmpty()) {

                            qualityReview.issues

                        } else {

                            listOf(
                                qualityReview.summary.ifBlank {
                                    "Assignment failed AI quality review"
                                }
                            )
                        }

                    if (attempt < 3) {

                        println(
                            "REGENERATING BECAUSE AI QUALITY REVIEW FAILED..."
                        )

                        continue
                    }

                    throw IllegalStateException(
                        "AI generated assignment failed quality review after 3 attempts:\n" +
                                lastErrors.joinToString("\n")
                    )
                }

                println(
                    "✅ AI QUALITY REVIEW PASSED"
                )

                generated =
                    candidate

                break

            } catch (e: IllegalStateException) {

                println(
                    "❌ ASSIGNMENT GENERATION ERROR: ${e.message}"
                )

                if (attempt >= 3) {
                    throw e
                }

                lastErrors =
                    listOf(
                        e.message
                            ?: "Unknown assignment generation error"
                    )
            }
        }

        // ========================================================
        // BƯỚC 3
        // Lấy assignment cuối cùng đã pass
        // ========================================================

        val finalGenerated =
            generated
                ?: throw IllegalStateException(
                    "Unable to generate a valid assignment"
                )

        println(
            "✅ GENERATED ASSIGNMENT PASSED VALIDATION"
        )

        // ========================================================
        // Tạo content
        // ========================================================

        val content =
            buildAssignmentContent(
                assignment = finalGenerated
            )

        // ========================================================
        // Tạo QuestionMetadata
        // ========================================================

        val questionMetadata =
            json.encodeToString(
                finalGenerated.questions.map { question ->

                    QuestionMetadata(
                        id = question.id,
                        question = question.question,
                        learningObjective = question.learningObjective,
                        points = question.points,
                        answerType = question.answerType,
                        gradingMethod = question.gradingMethod,
                        sourceType = question.sourceType
                    )
                }
            )

        // ========================================================
        // Tạo answer key
        // ========================================================

        val answerKey =
            finalGenerated.answerKey
                .joinToString("\n\n") { answer ->

                    "Câu ${answer.id}:\n${answer.answer}"
                }

        val gradingGuide =
            finalGenerated.gradingGuide

        // ========================================================
        // BƯỚC 4
        // Lưu assignment vào PostgreSQL
        // ========================================================

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

                            it[AssignmentsTable.difficulty] =
                                difficulty.name

                            it[AssignmentsTable.title] =
                                finalGenerated.title

                            it[AssignmentsTable.content] =
                                content

                            it[AssignmentsTable.answerKey] =
                                answerKey

                            it[AssignmentsTable.gradingGuide] =
                                gradingGuide

                            it[AssignmentsTable.totalScore] =
                                finalGenerated.totalScore

                            it[AssignmentsTable.questionMetadata] =
                                questionMetadata

                            it[AssignmentsTable.createdAt] =
                                System.currentTimeMillis()
                        }

                    val id =
                        statement[AssignmentsTable.id]

                    println(
                        "NEW ASSIGNMENT SAVED: id=$id"
                    )

                    AssignmentResult(
                        id = id,
                        grade = grade,
                        subject = subject,
                        topic = topic,
                        title = finalGenerated.title,
                        content = content,
                        answerKey = answerKey,
                        gradingGuide = gradingGuide,
                        totalScore = finalGenerated.totalScore,
                        difficulty = difficulty,
                        questionMetadata =
                            finalGenerated.questions.map { question ->

                                QuestionMetadata(
                                    id = question.id,
                                    question = question.question,
                                    learningObjective = question.learningObjective,
                                    points = question.points,
                                    answerType = question.answerType,
                                    gradingMethod = question.gradingMethod,
                                    sourceType = question.sourceType
                                )
                            }
                    )
                }
            }

        // ========================================================
        // BƯỚC 5
        // Giao assignment cho user
        // ========================================================

        println(
            "ASSIGNING NEW AI ASSIGNMENT TO USER"
        )

        println(
            "USER ID = $userId"
        )

        println(
            "ASSIGNMENT ID = ${assignment.id}"
        )

        return createUserAssignmentImmediately(
            userId = userId,
            assignment = assignment
        )
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
                    statement[UserAssignmentsTable.id]

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
                    assignment = assignment,
                    questionMetadata =
                        assignment.questionMetadata
                )
            }
        }
    }


    // ============================================================
    // FIND NEXT AVAILABLE ASSIGNMENT
    // ============================================================

    private suspend fun findNextAvailableAssignment(
        userId: Int,
        grade: Int,
        subject: String,
        topic: String?,
        difficulty: AIService.Difficulty
    ): AssignmentResult? {

        return withContext(Dispatchers.IO) {

            transaction {

                AssignmentsTable
                    .selectAll()
                    .where {

                        (AssignmentsTable.grade eq grade) and
                                (AssignmentsTable.subject eq subject) and
                                (AssignmentsTable.difficulty eq difficulty.name) and
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
    // GET USER ASSIGNMENT BY ID
    // ============================================================

    suspend fun getUserAssignment(
        userId: Int,
        userAssignmentId: Int
    ): UserAssignmentResult? {

        return withContext(Dispatchers.IO) {

            transaction {

                println(
                    "========== GET USER ASSIGNMENT =========="
                )

                println(
                    "USER ID = $userId"
                )

                println(
                    "USER ASSIGNMENT ID = $userAssignmentId"
                )

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

                if (row == null) {

                    println(
                        "USER ASSIGNMENT NOT FOUND"
                    )

                    println(
                        "Looking for " +
                                "UserAssignmentsTable.id=$userAssignmentId " +
                                "AND userId=$userId"
                    )

                    return@transaction null
                }

                val assignmentId =
                    row[UserAssignmentsTable.assignmentId]

                println(
                    "USER ASSIGNMENT FOUND"
                )

                println(
                    "USER ASSIGNMENT ID = " +
                            row[UserAssignmentsTable.id]
                )

                println(
                    "USER ID = " +
                            row[UserAssignmentsTable.userId]
                )

                println(
                    "ASSIGNMENT ID = $assignmentId"
                )

                println(
                    "STATUS = " +
                            row[UserAssignmentsTable.status]
                )

                val assignmentRow =
                    AssignmentsTable
                        .selectAll()
                        .where {

                            AssignmentsTable.id eq assignmentId
                        }
                        .firstOrNull()

                if (assignmentRow == null) {

                    println(
                        "ASSIGNMENT NOT FOUND"
                    )

                    println(
                        "Looking for " +
                                "AssignmentsTable.id=$assignmentId"
                    )

                    return@transaction null
                }

                val assignment =
                    rowToResult(assignmentRow)

                println(
                    "ASSIGNMENT FOUND"
                )

                println(
                    "TITLE = ${assignment.title}"
                )

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
            throw IllegalArgumentException(
                "Answer is required"
            )
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

        println(
            "========== SUBMIT ASSIGNMENT =========="
        )

        println(
            "USER ID = $userId"
        )

        println(
            "USER ASSIGNMENT ID = $userAssignmentId"
        )

        println(
            "ASSIGNMENT ID = ${userAssignment.assignmentId}"
        )

        // ========================================================
        // Chuẩn bị GeneratedAssignment cho AIService v2
        // ========================================================

        val assignmentForGrading =
            buildGeneratedAssignmentForGrading(
                assignment = userAssignment.assignment
            )

        println(
            "========== ASSIGNMENT DATA FOR GRADING =========="
        )

        println(
            "ASSIGNMENT ID = ${userAssignment.assignment.id}"
        )

        println(
            "TITLE = ${userAssignment.assignment.title}"
        )

        println(
            "----- CONTENT -----"
        )

        println(
            userAssignment.assignment.content
        )

        println(
            "----- ANSWER KEY -----"
        )

        println(
            userAssignment.assignment.answerKey
        )

        println(
            "----- GRADING GUIDE -----"
        )

        println(
            userAssignment.assignment.gradingGuide
        )

        println(
            "----- TOTAL SCORE -----"
        )

        println(
            userAssignment.assignment.totalScore
        )

        println(
            "----- STUDENT ANSWER -----"
        )

        println(
            answer
        )

        println(
            "================================================="
        )

        // ========================================================
        // Gọi Gemini để chấm
        // ========================================================

        println(
            "GRADING ASSIGNMENT WITH AI..."
        )

        println(
            "WAITING FOR AI GRADING SLOT..."
        )

        val grading =
            aiSemaphore.withPermit {

                println(
                    "AI GRADING SLOT ACQUIRED"
                )

                try {

                    aiService.gradeAssignment(
                        assignment = assignmentForGrading,
                        studentAnswer = answer
                    )

                } finally {

                    println(
                        "AI GRADING SLOT RELEASED"
                    )
                }
            }

        println(
            "AI GRADE = ${grading.score}"
        )

        // ========================================================
        // Lưu kết quả
        // ========================================================

        withContext(Dispatchers.IO) {

            transaction {

                UserAssignmentsTable.update(

                    where = {

                        (UserAssignmentsTable.id eq userAssignmentId) and
                                (
                                        UserAssignmentsTable.userId eq userId
                                        )
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
    // BUILD GENERATED ASSIGNMENT FOR GRADING
    // ============================================================
    //
    // AssignmentResult là model lưu trong database.
    //
    // AIService v2 gradeAssignment() nhận:
    //
    //     AIService.GeneratedAssignment
    //
    // Vì vậy cần chuyển dữ liệu PostgreSQL về model AI.
    //
    // Không thay đổi database schema.
    // Không thay đổi public API của AssignmentService.
    // ============================================================

    private fun buildGeneratedAssignmentForGrading(
        assignment: AssignmentResult
    ): AIService.GeneratedAssignment {

        val questions =
            assignment.questionMetadata.map { metadata ->

                AIService.GeneratedQuestion(
                    id = metadata.id,
                    question = metadata.question,
                    learningObjective = metadata.learningObjective,
                    points = metadata.points,
                    answerType = metadata.answerType,
                    gradingMethod = metadata.gradingMethod,
                    sourceType = metadata.sourceType
                )
            }

        val answerKey =
            parseStoredAnswerKey(
                answerKey = assignment.answerKey
            )

        val learningMaterial =
            extractLearningMaterial(
                content = assignment.content
            )

        return AIService.GeneratedAssignment(
            title = assignment.title,
            learningMaterial = learningMaterial,
            questions = questions,
            answerKey = answerKey,
            gradingGuide = assignment.gradingGuide,
            totalScore = assignment.totalScore
        )
    }
    private fun extractLearningMaterial(
        content: String
    ): String? {

        val startMarker =
            "=== NỘI DUNG BÀI HỌC / BÀI ĐỌC ==="

        val endMarker =
            "=== CÂU HỎI ==="

        val start =
            content.indexOf(startMarker)

        if (start < 0) {
            return null
        }

        val materialStart =
            start + startMarker.length

        val end =
            content.indexOf(
                endMarker,
                materialStart
            )

        val material =
            if (end >= 0) {

                content.substring(
                    materialStart,
                    end
                )

            } else {

                content.substring(
                    materialStart
                )
            }

        return material
            .trim()
            .takeIf {
                it.isNotBlank()
            }
    }
    // ============================================================
    // PARSE STORED ANSWER KEY
    // ============================================================
    //
    // Database đang lưu:
    //
    // Câu 1:
    // ...
    //
    // Câu 2:
    // ...
    //
    // Câu 3:
    // ...
    //
    // Chuyển lại thành:
    //
    // List<AIService.GeneratedAnswer>
    // ============================================================

    private fun parseStoredAnswerKey(
        answerKey: String
    ): List<AIService.GeneratedAnswer> {

        if (answerKey.isBlank()) {
            return emptyList()
        }

        val result =
            mutableListOf<AIService.GeneratedAnswer>()

        val sections =
            Regex(
                pattern = "(?m)(?=^Câu\\s+\\d+\\s*:)"
            )
                .split(answerKey)
                .map {
                    it.trim()
                }
                .filter {
                    it.isNotBlank()
                }

        sections.forEach { section ->

            val match =
                Regex(
                    pattern = "^Câu\\s+(\\d+)\\s*:\\s*(.*)$",
                    options = setOf(
                        RegexOption.MULTILINE,
                        RegexOption.DOT_MATCHES_ALL
                    )
                ).find(section)

            if (match != null) {

                val id =
                    match.groupValues[1].toIntOrNull()

                val answer =
                    match.groupValues[2].trim()

                if (
                    id != null &&
                    answer.isNotBlank()
                ) {

                    result +=
                        AIService.GeneratedAnswer(
                            id = id,
                            answer = answer
                        )
                }
            }
        }

        // --------------------------------------------------------
        // Fallback
        //
        // Nếu format cũ không parse được, vẫn tạo answer key
        // theo từng dòng/phần để tránh làm crash toàn bộ.
        // --------------------------------------------------------

        if (result.isEmpty()) {

            println(
                "⚠️ STORED ANSWER KEY COULD NOT BE PARSED"
            )

            println(
                "Answer key will be passed as a single answer entry."
            )

            result +=
                AIService.GeneratedAnswer(
                    id = 1,
                    answer = answerKey.trim()
                )
        }

        return result
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

        val questionMetadata =
            row[AssignmentsTable.questionMetadata]
                ?.let { jsonString ->

                    try {

                        json.decodeFromString<List<QuestionMetadata>>(
                            jsonString
                        )

                    } catch (e: Exception) {

                        println(
                            "FAILED TO DECODE QUESTION METADATA: " +
                                    e.message
                        )

                        emptyList()
                    }
                }
                ?: emptyList()

        val difficulty =
            row[AssignmentsTable.difficulty]
                .let { value ->

                    runCatching {

                        AIService.Difficulty.valueOf(
                            value.uppercase()
                        )

                    }.getOrElse {

                        println(
                            "INVALID ASSIGNMENT DIFFICULTY: $value"
                        )

                        AIService.Difficulty.MEDIUM
                    }
                }

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
                row[AssignmentsTable.totalScore],

            difficulty =
                difficulty,

            questionMetadata =
                questionMetadata
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
                assignment,

            questionMetadata =
                assignment.questionMetadata
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

        val totalScore: Double,

        val difficulty: AIService.Difficulty,

        val questionMetadata: List<QuestionMetadata> =
            emptyList()
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

        val assignment: AssignmentResult,

        val questionMetadata: List<QuestionMetadata>
    )


    // ============================================================
    // GET ALL USER ASSIGNMENTS
    // ============================================================

    suspend fun getUserAssignments(
        userId: Int
    ): List<UserAssignmentResult> {

        return withContext(Dispatchers.IO) {

            transaction {

                UserAssignmentsTable
                    .selectAll()
                    .where {

                        UserAssignmentsTable.userId eq userId
                    }
                    .orderBy(
                        UserAssignmentsTable.id to SortOrder.DESC
                    )
                    .mapNotNull { row ->

                        val assignmentId =
                            row[UserAssignmentsTable.assignmentId]

                        val assignmentRow =
                            AssignmentsTable
                                .selectAll()
                                .where {

                                    AssignmentsTable.id eq assignmentId
                                }
                                .firstOrNull()

                        if (assignmentRow == null) {

                            println(
                                "ASSIGNMENT NOT FOUND: " +
                                        "assignmentId=$assignmentId"
                            )

                            return@mapNotNull null
                        }

                        val assignment =
                            rowToResult(assignmentRow)

                        rowToUserAssignment(
                            row = row,
                            assignment = assignment
                        )
                    }
            }
        }
    }


    // ============================================================
    // HAS USER ASSIGNMENT
    // ============================================================

    fun hasUserAssignment(
        userId: Int,
        assignmentId: Int
    ): Boolean {

        return transaction {

            UserAssignmentsTable
                .selectAll()
                .where {

                    (UserAssignmentsTable.userId eq userId) and
                            (
                                    UserAssignmentsTable.assignmentId eq
                                            assignmentId
                                    )
                }
                .limit(1)
                .count() > 0
        }
    }
    private fun buildAssignmentContent(
        assignment: AIService.GeneratedAssignment
    ): String {

        val builder = StringBuilder()

        val material =
            assignment.learningMaterial
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        if (material != null) {

            builder.appendLine(
                "=== NỘI DUNG BÀI HỌC / BÀI ĐỌC ==="
            )

            builder.appendLine()

            builder.appendLine(material)

            builder.appendLine()

            builder.appendLine(
                "=== CÂU HỎI ==="
            )

            builder.appendLine()
        }

        assignment.questions.forEach { question ->

            builder.appendLine(
                "Câu ${question.id} (${question.points} điểm):"
            )

            builder.appendLine(
                question.question
            )

            builder.appendLine()
        }

        return builder
            .toString()
            .trim()
    }
}
