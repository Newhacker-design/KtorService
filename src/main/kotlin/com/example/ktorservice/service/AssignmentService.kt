package com.example.ktorservice.service

import com.example.ktorservice.database.table.AssignmentsTable
import com.example.ktorservice.database.table.UserAssignmentsTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class AssignmentService(
    private val aiService: AIService
) {

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
                        ?: return@transaction null

                val assignment =
                    AssignmentsTable
                        .selectAll()
                        .where {
                            AssignmentsTable.id eq
                                    row[UserAssignmentsTable.assignmentId]
                        }
                        .firstOrNull()
                        ?.let {
                            rowToResult(it)
                        }
                        ?: return@transaction null

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