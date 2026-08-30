package com.example.ktorservice.service

import com.example.ktorservice.database.table.AssignmentsTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

class AssignmentService(
    private val aiService: AIService
) {

    /*
     * ============================================================
     * CHỐNG NHIỀU REQUEST CÙNG TẠO BÀI
     * ============================================================
     *
     * Ví dụ 100 user cùng yêu cầu:
     *
     * grade = 6
     * subject = Ngữ văn
     * topic = null
     *
     * Chỉ cho 1 request đi gọi Gemini.
     *
     * Các request còn lại chờ Mutex rồi lấy bài từ DB.
     *
     * Lưu ý:
     * Mutex này hoạt động trong 1 instance Ktor.
     * Với Render chạy nhiều instance sau này, ta sẽ bổ sung
     * cơ chế khóa bằng DB/Redis.
     */

    private val generationMutex =
        Mutex()

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

        val normalizedSubject =
            subject.trim()

        val normalizedTopic =
            topic
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        /*
         * --------------------------------------------------------
         * 1. Kiểm tra DB trước
         * --------------------------------------------------------
         */

        val existing =
            findExistingAssignment(
                grade = grade,
                subject = normalizedSubject,
                topic = normalizedTopic
            )

        if (existing != null) {

            println(
                "ASSIGNMENT CACHE HIT: " +
                        "grade=$grade " +
                        "subject=$normalizedSubject " +
                        "topic=$normalizedTopic " +
                        "id=${existing.id}"
            )

            return existing
        }

        /*
         * --------------------------------------------------------
         * 2. CACHE MISS
         *
         * Khóa quá trình tạo bài.
         * --------------------------------------------------------
         */

        return generationMutex.withLock {

            /*
             * Rất quan trọng:
             *
             * Trong lúc request hiện tại chờ Mutex,
             * request khác có thể đã tạo bài rồi.
             *
             * Vì vậy phải kiểm tra DB LẦN 2.
             */

            val existingAfterLock =
                findExistingAssignment(
                    grade = grade,
                    subject = normalizedSubject,
                    topic = normalizedTopic
                )

            if (existingAfterLock != null) {

                println(
                    "ASSIGNMENT CACHE HIT AFTER LOCK: " +
                            "grade=$grade " +
                            "subject=$normalizedSubject " +
                            "topic=$normalizedTopic " +
                            "id=${existingAfterLock.id}"
                )

                return@withLock existingAfterLock
            }

            /*
             * ----------------------------------------------------
             * 3. Không có bài → gọi Gemini
             * ----------------------------------------------------
             */

            println(
                "ASSIGNMENT CACHE MISS: " +
                        "grade=$grade " +
                        "subject=$normalizedSubject " +
                        "topic=$normalizedTopic"
            )

            println(
                "GENERATING NEW ASSIGNMENT WITH AI..."
            )

            val generated =
                aiService.generateAssignment(
                    grade = grade,
                    subject = normalizedSubject,
                    topic = normalizedTopic
                )

            /*
             * ----------------------------------------------------
             * 4. Lưu DB
             * ----------------------------------------------------
             */

            val id =
                withContext(Dispatchers.IO) {

                    transaction {

                        val statement =
                            AssignmentsTable.insert {

                                it[AssignmentsTable.grade] =
                                    grade

                                it[AssignmentsTable.subject] =
                                    normalizedSubject

                                it[AssignmentsTable.topic] =
                                    normalizedTopic

                                it[AssignmentsTable.title] =
                                    generated.title.trim()

                                it[AssignmentsTable.content] =
                                    generated.content.trim()

                                it[AssignmentsTable.answerKey] =
                                    generated.answerKey.trim()

                                it[AssignmentsTable.gradingGuide] =
                                    generated.gradingGuide.trim()

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

            AssignmentResult(
                id = id,
                grade = grade,
                subject = normalizedSubject,
                topic = normalizedTopic,
                title = generated.title.trim(),
                content = generated.content.trim(),
                answerKey = generated.answerKey.trim(),
                gradingGuide = generated.gradingGuide.trim(),
                totalScore = generated.totalScore
            )
        }
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