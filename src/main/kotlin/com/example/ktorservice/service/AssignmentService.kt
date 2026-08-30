package com.example.ktorservice.service

import com.example.ktorservice.database.table.AssignmentsTable
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

        require(
            grade in 1..12
        ) {
            "Invalid grade"
        }

        if (subject.isBlank()) {
            throw IllegalArgumentException(
                "Subject is required"
            )
        }

        // --------------------------------------------------------
        // 1. Tìm bài đã có trong DB
        // --------------------------------------------------------

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
                        "topic=$topic"
            )

            return existing
        }

        // --------------------------------------------------------
        // 2. Chưa có → gọi AI
        // --------------------------------------------------------

        println(
            "ASSIGNMENT CACHE MISS: " +
                    "grade=$grade " +
                    "subject=$subject " +
                    "topic=$topic"
        )

        val generated =
            aiService.generateAssignment(
                grade = grade,
                subject = subject,
                topic = topic
            )

        // --------------------------------------------------------
        // 3. Lưu DB
        // --------------------------------------------------------

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
    // FIND EXISTING
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
    // ROW → RESULT
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
    // RESULT
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
}