package com.example.ktorservice.service

import com.example.ktorservice.database.UsersTable
import com.example.ktorservice.database.table.AssignmentsTable
import com.example.ktorservice.database.table.UserAssignmentsTable
import com.example.ktorservice.database.table.LearningPathsTable
import com.example.ktorservice.database.table.LearningStepsTable
import com.example.ktorservice.database.table.RaceTopSessionsTable
import com.example.ktorservice.database.table.RaceTopSessionAssignmentsTable
import com.example.ktorservice.model.AssignmentMode
import com.example.ktorservice.model.QuestionMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*

import org.jetbrains.exposed.sql.transactions.transaction

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

private const val MAX_ASSIGNMENT_GENERATION_ATTEMPTS = 5

class AssignmentService(
    private val aiService: AIService,
    private val learningPathService: LearningPathService
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ============================================================
    // AI CONCURRENCY LIMIT
    // ============================================================

    private val aiSemaphore = Semaphore(2)
    private val raceLocks = ConcurrentHashMap<Int, Mutex>()

    data class RaceTopSetResult(
        val sessionId: Int,
        val assignments: List<UserAssignmentResult>,
        val complete: Boolean,
        val weakSubjects: List<String>,
        val averageSubjects: List<String>,
        val strongSubjects: List<String>,
        val previousWeakSubjects: List<String>,
        val previousAverageSubjects: List<String>,
        val previousStrongSubjects: List<String>,
        val level: Int,
        val masteredSubjects: Int,
        val totalSubjects: Int,
        val scoreCoefficient: Double
    )

    data class AssignmentCompletionStatus(
        val unfinishedCount: Long,
        val completedTodayCount: Long
    ) {
        val shouldUnlock: Boolean
            get() = unfinishedCount == 0L && completedTodayCount > 0L
    }

    data class RaceTopPoolAssignmentResult(
        val assignmentId: Int,
        val grade: Int,
        val subject: String,
        val learningStepId: Int,
        val stepOrder: Int,
        val title: String
    )

    private data class RaceTopPoolStepContext(
        val stepOrder: Int,
        val title: String,
        val skill: String,
        val description: String,
        val subject: String
    )

    suspend fun generateRaceTopPoolAssignment(
        grade: Int,
        learningStepId: Int
    ): RaceTopPoolAssignmentResult {
        require(grade in 1..12) { "Invalid grade" }
        val step = withContext(Dispatchers.IO) {
            transaction {
                (LearningStepsTable innerJoin LearningPathsTable)
                    .selectAll()
                    .where {
                        (LearningStepsTable.id eq learningStepId) and
                            (LearningPathsTable.grade eq grade)
                    }
                    .singleOrNull()
                    ?.let { row ->
                        RaceTopPoolStepContext(
                            stepOrder = row[LearningStepsTable.stepOrder],
                            title = row[LearningStepsTable.title],
                            skill = row[LearningStepsTable.skill],
                            description = row[LearningStepsTable.description].orEmpty(),
                            subject = row[LearningPathsTable.subject]
                        )
                    }
            }
        } ?: error("Learning step $learningStepId does not belong to grade $grade")
        val subject = step.subject
        val stepOrder = step.stepOrder
        val stepTitle = step.title
        val stepSkill = step.skill
        val stepDescription = step.description
        val previous = withContext(Dispatchers.IO) {
            transaction {
                AssignmentsTable.selectAll().where {
                    (AssignmentsTable.grade eq grade) and
                        (AssignmentsTable.subject.lowerCase() eq subject.lowercase()) and
                        (AssignmentsTable.learningStepId eq learningStepId)
                }.orderBy(AssignmentsTable.id to SortOrder.DESC).limit(12)
                    .map { it[AssignmentsTable.content] }
            }
        }
        var lastErrors = emptyList<String>()
        var accepted: AIService.GeneratedAssignment? = null
        for (attempt in 1..MAX_ASSIGNMENT_GENERATION_ATTEMPTS) {
            val candidate = try {
                aiSemaphore.withPermit {
                    aiService.generateAssignment(
                        grade = grade,
                        subject = subject,
                        topic = null,
                        difficulty = AIService.Difficulty.MEDIUM,
                        previousAssignments = previous + listOfNotNull(accepted?.let(::buildAssignmentContent)),
                        qualityFeedback = lastErrors.joinToString("\n").ifBlank { null },
                        learningStepTitle = stepTitle,
                        learningStepSkill = stepSkill,
                        learningStepDescription = stepDescription
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val detail = error.message?.take(600) ?: error.javaClass.simpleName
                lastErrors = listOf("Lần $attempt: Gemini không trả được đề hợp lệ: $detail")
                println("[RaceTopPool] generation failed grade=$grade subject=$subject step=$stepOrder attempt=$attempt/$MAX_ASSIGNMENT_GENERATION_ATTEMPTS: $detail")
                if (attempt == MAX_ASSIGNMENT_GENERATION_ATTEMPTS) {
                    throw IllegalStateException(
                        "Không tạo được đề ${subject.uppercase()} level $stepOrder sau $attempt lần thử: $detail",
                        error
                    )
                }
                continue
            }
            val validation = AssignmentValidator.validate(
                title = candidate.title,
                questions = candidate.questions,
                answerKey = candidate.answerKey,
                gradingGuide = candidate.gradingGuide,
                totalScore = candidate.totalScore,
                learningMaterial = candidate.learningMaterial,
                grade = grade,
                subject = subject
            )
            if (!validation.valid) {
                lastErrors = validation.errors
                if (attempt == MAX_ASSIGNMENT_GENERATION_ATTEMPTS) error(validation.errors.joinToString("; "))
                continue
            }
            val draftContent = buildAssignmentContent(candidate)
            if (previous.any { prior -> assignmentSimilarity(draftContent, prior) >= 0.88 }) {
                lastErrors = listOf("Bài tạo bị trùng hoặc quá giống bài đã có trong kho cho step này")
                if (attempt == MAX_ASSIGNMENT_GENERATION_ATTEMPTS) error(lastErrors.single())
                continue
            }
            val review = aiSemaphore.withPermit {
                aiService.reviewGeneratedAssignment(
                    assignment = candidate,
                    grade = grade,
                    subject = subject,
                    topic = null,
                    difficulty = AIService.Difficulty.MEDIUM,
                    learningStepTitle = stepTitle,
                    learningStepSkill = stepSkill,
                    learningStepDescription = stepDescription
                )
            }
            if (!review.pass) {
                lastErrors = review.issues.ifEmpty { listOf(review.summary) }
                if (attempt == MAX_ASSIGNMENT_GENERATION_ATTEMPTS) error(lastErrors.joinToString("; "))
                continue
            }
            accepted = candidate
            break
        }
        val final = accepted ?: error("Could not generate a valid non-duplicate assignment")
        val content = buildAssignmentContent(final)
        val answerById = final.answerKey.associateBy { it.id }
        val metadata = json.encodeToString(final.questions.map { question ->
            QuestionMetadata(
                id = question.id,
                question = question.question,
                learningObjective = question.learningObjective,
                points = question.points,
                answerType = question.answerType,
                gradingMethod = question.gradingMethod,
                sourceType = question.sourceType,
                options = question.options,
                statements = question.statements,
                gradingSpec = question.gradingSpec.copy(
                    correctAnswer = answerById[question.id]?.answer
                        ?: error("Missing answer for question ${question.id}")
                )
            )
        })
        val answerKey = final.answerKey.joinToString("\n\n") { "Câu ${it.id}:\n${it.answer}" }
        val id = withContext(Dispatchers.IO) {
            transaction {
                AssignmentsTable.insert {
                    it[AssignmentsTable.grade] = grade
                    it[AssignmentsTable.subject] = subject
                    it[AssignmentsTable.topic] = null
                    it[AssignmentsTable.difficulty] = AIService.Difficulty.MEDIUM.name
                    it[AssignmentsTable.title] = final.title
                    it[AssignmentsTable.content] = content
                    it[AssignmentsTable.answerKey] = answerKey
                    it[AssignmentsTable.gradingGuide] = final.gradingGuide
                    it[AssignmentsTable.totalScore] = final.totalScore
                    it[AssignmentsTable.questionMetadata] = metadata
                    it[AssignmentsTable.learningStepId] = learningStepId
                    it[AssignmentsTable.raceTopPool] = true
                    it[AssignmentsTable.createdAt] = System.currentTimeMillis()
                }[AssignmentsTable.id]
            }
        }
        return RaceTopPoolAssignmentResult(id, grade, subject, learningStepId, stepOrder, final.title)
    }

    fun ensureRaceTopPoolStep(grade: Int, stepOrder: Int): List<LearningPathService.RaceTopStepChoice> =
        learningPathService.ensureRaceTopStep(grade, stepOrder)

    private fun assignmentSimilarity(first: String, second: String): Double {
        fun tokens(value: String): Set<String> = value
            .substringAfter("=== CÂU HỎI ===", value)
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 }
            .toSet()
        val a = tokens(first)
        val b = tokens(second)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size.toDouble()
    }

    suspend fun getAssignmentCompletionStatus(
        userId: Int,
        dayStartMillis: Long,
        nextDayStartMillis: Long
    ): AssignmentCompletionStatus = withContext(Dispatchers.IO) {
        transaction {
            val unfinished = UserAssignmentsTable.selectAll().where {
                (UserAssignmentsTable.userId eq userId) and
                    (UserAssignmentsTable.status.upperCase() neq "COMPLETED")
            }.count()
            val completedToday = UserAssignmentsTable.selectAll().where {
                (UserAssignmentsTable.userId eq userId) and
                    (UserAssignmentsTable.status.upperCase() eq "COMPLETED") and
                    (UserAssignmentsTable.completedAt greaterEq dayStartMillis) and
                    (UserAssignmentsTable.completedAt less nextDayStartMillis)
            }.count()
            AssignmentCompletionStatus(unfinished, completedToday)
        }
    }

    /** Creates/resumes one all-subject set. Stored assignments are reused by getNextAssignment. */
    suspend fun createRaceTopSet(userId: Int, grade: Int): RaceTopSetResult =
        raceLocks.computeIfAbsent(userId) { Mutex() }.withLock {
            require(grade in 1..12) { "Invalid grade" }
            val subjects = listOf("math", "literature", "english", "physics", "chemistry", "biology", "giao_duc_gioi_tinh")
            subjects.forEach { subject ->
                learningPathService.getNextRaceTopStep(userId, grade, subject)
                    ?: error("Đua Top cần learning path và step cho môn $subject, lớp $grade.")
            }
            val previousGroups = withContext(Dispatchers.IO) {
                transaction {
                    val latest = RaceTopSessionsTable.selectAll().where {
                        (RaceTopSessionsTable.userId eq userId) and (RaceTopSessionsTable.status eq "COMPLETED")
                    }.orderBy(RaceTopSessionsTable.id to SortOrder.DESC).firstOrNull()
                    latest?.let { row -> listOf(
                        row[RaceTopSessionsTable.weakSubjects].orEmpty().split('|').filter(String::isNotBlank),
                        row[RaceTopSessionsTable.averageSubjects].orEmpty().split('|').filter(String::isNotBlank),
                        row[RaceTopSessionsTable.strongSubjects].orEmpty().split('|').filter(String::isNotBlank)
                    ) }
                }
            }
            var sessionId = withContext(Dispatchers.IO) {
                transaction {
                    val active = RaceTopSessionsTable.selectAll().where {
                        (RaceTopSessionsTable.userId eq userId) and
                            (RaceTopSessionsTable.status eq "ACTIVE")
                    }.orderBy(RaceTopSessionsTable.id to SortOrder.DESC).firstOrNull()
                    if (active != null) {
                        // Return a completed round once so its score groups can be shown.
                        // The next button press will create the following round.
                        active[RaceTopSessionsTable.id]
                    } else null
                }
            }
            if (sessionId == null) {
                sessionId = withContext(Dispatchers.IO) {
                    transaction {
                        RaceTopSessionsTable.insert {
                            it[RaceTopSessionsTable.userId] = userId
                            it[RaceTopSessionsTable.grade] = grade
                            it[status] = "ACTIVE"
                            it[createdAt] = System.currentTimeMillis()
                        }[RaceTopSessionsTable.id]
                    }
                }
            }
            val existingSubjects = withContext(Dispatchers.IO) {
                transaction {
                    RaceTopSessionAssignmentsTable.selectAll().where {
                        RaceTopSessionAssignmentsTable.sessionId eq sessionId!!
                    }.map { it[RaceTopSessionAssignmentsTable.subject] }.toSet()
                }
            }
            for (subject in subjects.filterNot { it in existingSubjects }) {
                val assignment = getNextAssignment(
                    userId = userId, grade = grade, subject = subject, topic = null,
                    difficulty = AIService.Difficulty.MEDIUM, mode = AssignmentMode.RACE_TOP
                )
                withContext(Dispatchers.IO) {
                    transaction {
                        RaceTopSessionAssignmentsTable.insert {
                            it[RaceTopSessionAssignmentsTable.sessionId] = sessionId!!
                            it[userAssignmentId] = assignment.id
                            it[RaceTopSessionAssignmentsTable.subject] = subject
                        }
                    }
                }
            }
            val results = getUserAssignments(userId)
            val linkedIds = withContext(Dispatchers.IO) {
                transaction {
                    RaceTopSessionAssignmentsTable.selectAll().where {
                        RaceTopSessionAssignmentsTable.sessionId eq sessionId!!
                    }.map { it[RaceTopSessionAssignmentsTable.userAssignmentId] }.toSet()
                }
            }
            val assignments = results.filter { it.id in linkedIds }
            val complete = assignments.size == subjects.size && assignments.all { it.status == "COMPLETED" }
            if (complete) withContext(Dispatchers.IO) {
                transaction {
                    RaceTopSessionsTable.update({ RaceTopSessionsTable.id eq sessionId!! }) { it[status] = "COMPLETED" }
                }
            }
            val grouped = assignments.mapNotNull { item ->
                val total = item.assignment.totalScore
                val score = item.score
                if (item.status != "COMPLETED" || score == null || total <= 0.0) null
                else item.assignment.subject to (score / total * 100.0)
            }
            val levelStatus = learningPathService.getRaceTopLevelStatus(userId, grade)
            RaceTopSetResult(
                sessionId!!, assignments, complete,
                grouped.filter { it.second < 60.0 }.map { it.first },
                grouped.filter { it.second >= 60.0 && it.second < 80.0 }.map { it.first },
                grouped.filter { it.second >= 80.0 }.map { it.first },
                previousGroups?.getOrNull(0).orEmpty(),
                previousGroups?.getOrNull(1).orEmpty(),
                previousGroups?.getOrNull(2).orEmpty(),
                levelStatus.level,
                levelStatus.masteredSubjects,
                levelStatus.totalSubjects,
                levelStatus.scoreCoefficient
            )
        }



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
                            UserAssignmentsTable.score,
                            UserAssignmentsTable.scoreCoefficient
                        )
                        .where {
                            (UserAssignmentsTable.status eq "COMPLETED") and
                                    (UserAssignmentsTable.mode eq AssignmentMode.RACE_TOP.name) and
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
                    val coefficient = row[UserAssignmentsTable.scoreCoefficient]

                    scoreMap[userId] =
                        (scoreMap[userId] ?: 0.0) + score * coefficient
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
        difficulty: AIService.Difficulty,
        mode: AssignmentMode = AssignmentMode.PRACTICE
    ): UserAssignmentResult {

        require(grade in 1..12) {
            "Invalid grade"
        }

        require(subject.isNotBlank()) {
            "Subject is required"
        }
        val nextLearningStep = when (mode) {
            AssignmentMode.PRACTICE -> learningPathService.getNextStep(userId, grade, subject)
            AssignmentMode.RACE_TOP -> learningPathService.getNextRaceTopStep(userId, grade, subject)
        }
        val recentRacePercent =
            nextLearningStep?.second?.let { progress ->
                if (progress.attemptCount == 0) null else progress.lastScore
            } ?: if (mode == AssignmentMode.RACE_TOP) {
                getRecentRaceTopAverage(userId, grade, subject)
            } else {
                null
            }
        val effectiveDifficulty = when (mode) {
            AssignmentMode.PRACTICE -> difficulty
            AssignmentMode.RACE_TOP -> when {
                recentRacePercent == null -> AIService.Difficulty.MEDIUM
                recentRacePercent < 50.0 -> AIService.Difficulty.EASY
                recentRacePercent >= 85.0 -> AIService.Difficulty.HARD
                else -> AIService.Difficulty.MEDIUM
            }
        }
// ========================================================
// CHECK EXISTING ACTIVE ASSIGNMENT FOR CURRENT LEARNING STEP
// ========================================================

        println("========== LEARNING PATH RESULT ==========")

        if (nextLearningStep != null) {

            println(
                "LEARNING STEP ID = ${nextLearningStep.first.id}"
            )

            println(
                "STEP ORDER = ${nextLearningStep.first.stepOrder}"
            )

            println(
                "STEP TITLE = ${nextLearningStep.first.title}"
            )

            println(
                "STEP SKILL = ${nextLearningStep.first.skill}"
            )

            println(
                "STEP DESCRIPTION = ${nextLearningStep.first.description}"
            )

            println(
                "STEP STATUS = ${nextLearningStep.second.status}"
            )

            println(
                "MASTERY SCORE = ${nextLearningStep.second.masteryScore}"
            )

            println(
                "ATTEMPT COUNT = ${nextLearningStep.second.attemptCount}"
            )

        } else {

            println(
                "LEARNING PATH = NULL"
            )

            println(
                "NO LEARNING PATH FOR " +
                        "userId=$userId " +
                        "grade=$grade " +
                        "subject=$subject"
            )
        }

        println(
            "=========================================="
        )

        println("========== GET NEXT ASSIGNMENT ==========")
        println("USER ID = $userId")
        println("GRADE = $grade")
        println("SUBJECT = $subject")
        println("TOPIC = $topic")
        println("MODE = $mode")
        println("REQUESTED DIFFICULTY = $difficulty")
        println("EFFECTIVE DIFFICULTY = $effectiveDifficulty")

        val learningStepId = nextLearningStep?.first?.id

        if (learningStepId != null) {
            val activeAssignment =
                findActiveAssignmentForLearningStep(
                    userId = userId,
                    learningStepId = learningStepId,
                    mode = mode,
                    difficulty = effectiveDifficulty
                )

            if (
                activeAssignment != null &&
                (if (mode == AssignmentMode.RACE_TOP) {
                    activeAssignment.assignment.raceTopPool
                } else {
                    !LocalSubjectAssignmentGenerator.supports(subject) ||
                            isLocallyGradable(activeAssignment.assignment)
                })
            ) {
                println(
                    "REUSING ACTIVE ASSIGNMENT FOR LEARNING STEP: " +
                            "assignment=${activeAssignment.assignmentId} " +
                            "step=$learningStepId"
                )
                return activeAssignment
            }
        }

        if (mode == AssignmentMode.RACE_TOP && learningStepId != null) {
            val pooled = findRaceTopPoolAssignment(userId, grade, subject, learningStepId)
            if (pooled != null) {
                println("RACE TOP PREGENERATED POOL HIT: assignment=${pooled.id} step=$learningStepId")
                return createUserAssignmentImmediately(
                    userId = userId,
                    assignment = pooled,
                    learningStepId = learningStepId,
                    mode = mode
                )
            }

            // Race Top must use a pool item that has not already been assigned
            // to this student. If none remains, generate and persist a fresh AI
            // item for the current step instead of falling back to local banks
            // or recycling a completed assignment.
            val generatedPoolItem = generateRaceTopPoolAssignment(grade, learningStepId)
            val generatedAssignment = withContext(Dispatchers.IO) {
                transaction {
                    AssignmentsTable.selectAll()
                        .where { AssignmentsTable.id eq generatedPoolItem.assignmentId }
                        .single()
                        .let(::rowToResult)
                }
            }
            println(
                "RACE TOP POOL EMPTY OR EXHAUSTED; CREATED AI ASSIGNMENT: " +
                        "assignment=${generatedAssignment.id} step=$learningStepId"
            )
            return createUserAssignmentImmediately(
                userId = userId,
                assignment = generatedAssignment,
                learningStepId = learningStepId,
                mode = mode
            )
        }

        val existingAssignment =
            findNextAvailableAssignment(
                userId = userId,
                grade = grade,
                subject = subject,
                topic = topic,
                difficulty = effectiveDifficulty,
                learningStepId = learningStepId,
                mode = mode
            )

        if (existingAssignment != null) {

            println(
                "ASSIGNMENT STORAGE HIT: " +
                        "id=${existingAssignment.id}"
            )

            return createUserAssignmentImmediately(
                userId = userId,
                assignment = existingAssignment,
                learningStepId = learningStepId,
                mode = mode
            )
        }

        println("NO AVAILABLE ASSIGNMENT IN STORAGE")
        println("ASSIGNMENT STORAGE EMPTY")
        val useLocalGenerator = LocalSubjectAssignmentGenerator.supports(subject)
        if (useLocalGenerator) {
            println("Using built-in assignment bank; AI generation and review are disabled for $subject")
        } else {
            println("WAITING FOR AI SEMAPHORE...")
        }
        // ========================================================
        // BƯỚC 2
        // Generate + Validate + AI Quality Review
        // ========================================================

        var generated: AIService.GeneratedAssignment? = null

        var lastErrors =
            emptyList<String>()

        if (useLocalGenerator) {
            val candidate = LocalSubjectAssignmentGenerator.generate(
                grade = grade,
                subject = subject,
                difficulty = effectiveDifficulty
            )
            val validation = AssignmentValidator.validate(
                title = candidate.title,
                questions = candidate.questions,
                answerKey = candidate.answerKey,
                gradingGuide = candidate.gradingGuide,
                totalScore = candidate.totalScore,
                learningMaterial = candidate.learningMaterial,
                grade = grade,
                subject = subject
            )
            check(validation.valid) {
                "Local assignment bank failed validation: ${validation.errors.joinToString("; ")}"
            }
            generated = candidate
        } else {
        for (attempt in 1..MAX_ASSIGNMENT_GENERATION_ATTEMPTS) {

            println(
                "=================================================="
            )

            println(
                "ASSIGNMENT GENERATION ATTEMPT $attempt/$MAX_ASSIGNMENT_GENERATION_ATTEMPTS"
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
                                difficulty = effectiveDifficulty,

                                qualityFeedback =
                                    lastErrors
                                        .joinToString("\n")
                                        .ifBlank { null },

                                learningStepTitle =
                                    nextLearningStep?.first?.title,

                                learningStepSkill =
                                    nextLearningStep?.first?.skill,

                                learningStepDescription =
                                    nextLearningStep?.first?.description
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
                        learningMaterial = candidate.learningMaterial,
                        grade = grade,
                        subject = subject
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

                    if (attempt < MAX_ASSIGNMENT_GENERATION_ATTEMPTS) {

                        println(
                            "REGENERATING BECAUSE VALIDATOR FAILED..."
                        )

                        continue
                    }

                    throw IllegalStateException(
                        "AI generated assignment failed validation after $MAX_ASSIGNMENT_GENERATION_ATTEMPTS attempts:\n" +
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
                                difficulty = effectiveDifficulty,

                                learningStepTitle =
                                    nextLearningStep?.first?.title,

                                learningStepSkill =
                                    nextLearningStep?.first?.skill,

                                learningStepDescription =
                                    nextLearningStep?.first?.description
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

                    if (attempt < MAX_ASSIGNMENT_GENERATION_ATTEMPTS) {

                        println(
                            "REGENERATING BECAUSE AI QUALITY REVIEW FAILED..."
                        )

                        continue
                    }

                    throw IllegalStateException(
                        "AI generated assignment failed quality review after $MAX_ASSIGNMENT_GENERATION_ATTEMPTS attempts:\n" +
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

                if (attempt >= MAX_ASSIGNMENT_GENERATION_ATTEMPTS) {
                    throw e
                }

                lastErrors =
                    listOf(
                        e.message
                            ?: "Unknown assignment generation error"
                    )
            }
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

        val generatedAnswersById =
            finalGenerated.answerKey.associateBy { it.id }

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
                        sourceType = question.sourceType,
                        options = question.options,
                        statements = question.statements,
                        gradingSpec = question.gradingSpec.copy(
                            correctAnswer = generatedAnswersById[question.id]?.answer
                                ?: throw IllegalStateException(
                                    "Missing answer key for question ${question.id}"
                                )
                        )
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
                                effectiveDifficulty.name

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

                            it[AssignmentsTable.learningStepId] =
                                learningStepId

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
                        difficulty = effectiveDifficulty,
                        questionMetadata =
                            finalGenerated.questions.map { question ->

                                QuestionMetadata(
                                    id = question.id,
                                    question = question.question,
                                    learningObjective = question.learningObjective,
                                    points = question.points,
                                    answerType = question.answerType,
                                    gradingMethod = question.gradingMethod,
                                    sourceType = question.sourceType,
                                    options = question.options,
                                    statements = question.statements,
                                    gradingSpec = question.gradingSpec.copy(
                                        correctAnswer = generatedAnswersById[question.id]?.answer
                                            ?: throw IllegalStateException(
                                                "Missing answer key for question ${question.id}"
                                            )
                                    )
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
            assignment = assignment,
            learningStepId = nextLearningStep?.first?.id,
            mode = mode
        )
    }

    private suspend fun createUserAssignmentImmediately(
        userId: Int,
        assignment: AssignmentResult,
        learningStepId: Int? = null,
        mode: AssignmentMode = AssignmentMode.PRACTICE
    ): UserAssignmentResult {

        return withContext(Dispatchers.IO) {

            transaction {

                val existing =
                    UserAssignmentsTable
                        .selectAll()
                        .where {

                            (UserAssignmentsTable.userId eq userId) and
                                    (UserAssignmentsTable.mode eq mode.name) and
                                    (
                                            UserAssignmentsTable.assignmentId eq
                                                    assignment.id
                                            )
                        }
                        .firstOrNull()

                val canRepeatRacePoolAssignment =
                    existing != null &&
                        mode == AssignmentMode.RACE_TOP &&
                        assignment.raceTopPool &&
                        existing[UserAssignmentsTable.status].equals("COMPLETED", ignoreCase = true)

                if (existing != null && !canRepeatRacePoolAssignment) {

                    println(
                        "USER ASSIGNMENT ALREADY EXISTS: " +
                                "user=$userId " +
                                "assignment=${assignment.id}"
                    )

                    // Nếu assignment này đang được gắn với Learning Path,
                    // đảm bảo learningStepId được lưu đúng.
                    if (learningStepId != null) {

                        UserAssignmentsTable.update(
                            where = {
                                UserAssignmentsTable.id eq
                                        existing[UserAssignmentsTable.id]
                            }
                        ) {

                            it[UserAssignmentsTable.learningStepId] =
                                learningStepId
                        }

                        val updated =
                            UserAssignmentsTable
                                .selectAll()
                                .where {
                                    UserAssignmentsTable.id eq
                                            existing[UserAssignmentsTable.id]
                                }
                                .first()

                        return@transaction rowToUserAssignment(
                            updated,
                            assignment
                        )
                    }

                    return@transaction rowToUserAssignment(
                        existing,
                        assignment
                    )
                }
                val coefficient = if (mode == AssignmentMode.RACE_TOP && learningStepId != null) {
                    val stepOrder = LearningStepsTable.selectAll()
                        .where { LearningStepsTable.id eq learningStepId }
                        .single()[LearningStepsTable.stepOrder]
                    1.0 + (stepOrder - 1) * 0.1
                } else 1.0
                val statement =
                    UserAssignmentsTable.insert {

                        it[UserAssignmentsTable.userId] =
                            userId

                        it[UserAssignmentsTable.assignmentId] =
                            assignment.id

                        it[UserAssignmentsTable.status] =
                            "NEW"

                        it[UserAssignmentsTable.learningStepId] =
                            learningStepId

                        it[UserAssignmentsTable.mode] =
                            mode.name

                        it[UserAssignmentsTable.scoreCoefficient] = coefficient
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
                    gradingDetails = null,
                    startedAt = null,
                    completedAt = null,
                    assignment = assignment,
                    questionMetadata =
                        assignment.questionMetadata,
                    mode = mode,
                    scoreCoefficient = coefficient
                )
            }
        }
    }

    private suspend fun findActiveAssignmentForLearningStep(
        userId: Int,
        learningStepId: Int,
        mode: AssignmentMode,
        difficulty: AIService.Difficulty
    ): UserAssignmentResult? = withContext(Dispatchers.IO) {
        transaction {
            val userAssignment = UserAssignmentsTable
                .selectAll()
                .where {
                    (UserAssignmentsTable.userId eq userId) and
                            (UserAssignmentsTable.learningStepId eq learningStepId) and
                            (UserAssignmentsTable.mode eq mode.name)
                }
                .firstOrNull {
                    it[UserAssignmentsTable.status] != "COMPLETED"
                }
                ?: return@transaction null

            val assignment = AssignmentsTable
                .selectAll()
                .where {
                    AssignmentsTable.id eq
                            userAssignment[UserAssignmentsTable.assignmentId]
                }
                .firstOrNull()
                ?.let(::rowToResult)
                ?: return@transaction null

            if (assignment.difficulty != difficulty) {
                return@transaction null
            }
            rowToUserAssignment(userAssignment, assignment)
        }
    }

    private suspend fun getRecentRaceTopAverage(
        userId: Int,
        grade: Int,
        subject: String
    ): Double? = withContext(Dispatchers.IO) {
        transaction {
            val recentPercents = UserAssignmentsTable
                .innerJoin(
                    AssignmentsTable,
                    { UserAssignmentsTable.assignmentId },
                    { AssignmentsTable.id }
                )
                .select(
                    UserAssignmentsTable.score,
                    UserAssignmentsTable.completedAt,
                    AssignmentsTable.totalScore,
                    AssignmentsTable.subject
                )
                .where {
                    (UserAssignmentsTable.userId eq userId) and
                            (UserAssignmentsTable.mode eq AssignmentMode.RACE_TOP.name) and
                            (UserAssignmentsTable.status eq "COMPLETED") and
                            (AssignmentsTable.grade eq grade)
                }
                .orderBy(UserAssignmentsTable.completedAt to SortOrder.DESC)
                .mapNotNull { row ->
                    if (!row[AssignmentsTable.subject].equals(subject, ignoreCase = true)) {
                        return@mapNotNull null
                    }
                    val score = row[UserAssignmentsTable.score] ?: return@mapNotNull null
                    val total = row[AssignmentsTable.totalScore]
                    if (total <= 0.0) null else score / total * 100.0
                }
                .take(3)

            recentPercents.takeIf { it.isNotEmpty() }?.average()
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
        difficulty: AIService.Difficulty,
        learningStepId: Int? = null,
        mode: AssignmentMode
    ): AssignmentResult? {

        return withContext(Dispatchers.IO) {

            transaction {

                AssignmentsTable
                    .selectAll()
                    .where {

                        (AssignmentsTable.grade eq grade) and
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

                        val matchesSubject =
                            row[AssignmentsTable.subject].trim()
                                .equals(subject.trim(), ignoreCase = true)

                        val matchesLearningStep =
                            row[AssignmentsTable.learningStepId] == learningStepId

                        val alreadyAssigned =
                            UserAssignmentsTable
                                .selectAll()
                                .where {

                                    (UserAssignmentsTable.userId eq userId) and
                                            (UserAssignmentsTable.mode eq mode.name) and
                                            (
                                                    UserAssignmentsTable.assignmentId eq
                                                            assignmentId
                                                    )
                                }
                                .count() > 0

                        matchesSubject && matchesLearningStep && !alreadyAssigned &&
                                (!LocalSubjectAssignmentGenerator.supports(subject) ||
                                        isLocallyGradable(rowToResult(row)))
                    }
                    ?.let {
                        rowToResult(it)
                    }
            }
        }
    }

    private suspend fun findRaceTopPoolAssignment(
        userId: Int,
        grade: Int,
        subject: String,
        learningStepId: Int
    ): AssignmentResult? = withContext(Dispatchers.IO) {
        transaction {
            val pool = AssignmentsTable.selectAll().where {
                (AssignmentsTable.grade eq grade) and
                    (AssignmentsTable.subject.lowerCase() eq subject.trim().lowercase()) and
                    (AssignmentsTable.learningStepId eq learningStepId) and
                    (AssignmentsTable.raceTopPool eq true)
            }.orderBy(AssignmentsTable.id to SortOrder.ASC).toList()
            if (pool.isEmpty()) return@transaction null

            val attempts = UserAssignmentsTable.selectAll().where {
                (UserAssignmentsTable.userId eq userId) and
                    (UserAssignmentsTable.mode eq AssignmentMode.RACE_TOP.name) and
                    (UserAssignmentsTable.assignmentId inList pool.map { it[AssignmentsTable.id] })
            }.orderBy(UserAssignmentsTable.id to SortOrder.DESC).toList()
            val usedIds = attempts.map { it[UserAssignmentsTable.assignmentId] }.toSet()
            val nextUnused = pool.firstOrNull { it[AssignmentsTable.id] !in usedIds }
            nextUnused?.let(::rowToResult)
        }
    }

    private fun isLocallyGradable(assignment: AssignmentResult): Boolean {
        val supportedMethods = setOf(
            AIService.RuleGradingMethod.EXACT,
            AIService.RuleGradingMethod.NUMERIC,
            AIService.RuleGradingMethod.REQUIRED_CONCEPTS
        )
        return assignment.questionMetadata.isNotEmpty() &&
                assignment.questionMetadata.all { question ->
                    val spec = question.gradingSpec
                    spec != null && spec.method in supportedMethods &&
                            spec.correctAnswer.isNotBlank() &&
                            (spec.method != AIService.RuleGradingMethod.REQUIRED_CONCEPTS ||
                                    spec.requiredConcepts.isNotEmpty()) &&
                            (spec.mathAnswerSpec == null ||
                                    (spec.mathAnswerSpec.kind == AIService.MathAnswerKind.TRUE_FALSE_SET &&
                                            question.statements.size == 4 &&
                                            spec.correctAnswer.split(',', ';', '|').size == 4))
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
        answer: String,
        localScore: Double? = null,
        localFeedback: String? = null,
        localGradingDetails: String? = null
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

        val grading = if (localScore != null) {
            val feedback = localFeedback?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Local grading feedback is required")
            val detailsJson = localGradingDetails?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Local grading details are required")
            require(localScore.isFinite()) { "Local score must be finite" }
            require(localScore in 0.0..userAssignment.assignment.totalScore) {
                "Local score is outside the assignment score range"
            }
            require(userAssignment.assignment.questionMetadata.isNotEmpty()) {
                "This assignment has no question metadata"
            }
            require(userAssignment.assignment.questionMetadata.all {
                !it.gradingSpec?.correctAnswer.isNullOrBlank()
            }) {
                "This assignment does not contain a complete local grading specification"
            }

            val details = json.decodeFromString<List<AIService.QuestionGradingResult>>(
                detailsJson
            )
            val questionsById = userAssignment.assignment.questionMetadata.associateBy { it.id }
            val expectedIds = questionsById.keys
            require(details.size == expectedIds.size && details.map { it.id }.toSet() == expectedIds) {
                "Local grading details must contain exactly one result per question"
            }
            details.forEach { detail ->
                val question = questionsById[detail.id]
                    ?: throw IllegalArgumentException("Unknown question id ${detail.id}")
                require(detail.score.isFinite() && detail.score in 0.0..question.points) {
                    "Invalid local score for question ${detail.id}"
                }
            }
            val detailsTotal = details.sumOf { it.score }
            require(kotlin.math.abs(detailsTotal - localScore) <= 0.001) {
                "Local score must equal the sum of question scores"
            }

            AIService.GradingResult(
                score = localScore,
                feedback = feedback,
                questions = details
            )
        } else {
            require(!LocalSubjectAssignmentGenerator.supports(userAssignment.assignment.subject)) {
                "This subject uses local grading; the Receiver must submit its local grading result."
            }
            val assignmentForGrading = buildGeneratedAssignmentForGrading(
                assignment = userAssignment.assignment
            )
            aiSemaphore.withPermit {
                aiService.gradeAssignment(
                    assignment = assignmentForGrading,
                    studentAnswer = answer,
                    subject = userAssignment.assignment.subject
                )
            }
        }

        println("========== ASSIGNMENT SUBMISSION ==========")

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
            "SUBMISSION GRADE = ${grading.score}"
        )
        val gradingDetailsJson =
            json.encodeToString(
                grading.questions
            )

        println(
            "========== GRADING DETAILS =========="
        )

        println(
            gradingDetailsJson
        )

        println(
            "======================================"
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

                    it[UserAssignmentsTable.gradingDetails] =
                        gradingDetailsJson

                    it[completedAt] =
                        System.currentTimeMillis()
                }
            }
        }

        updateRaceTopRoundIfComplete(userAssignmentId)

// ========================================================
// UPDATE LEARNING PATH PROGRESS
// ========================================================

        val learningStepId =
            userAssignment.learningStepId

        if (learningStepId != null) {

            println(
                "========== UPDATE LEARNING PATH =========="
            )

            println(
                "USER ID = $userId"
            )

            println(
                "LEARNING STEP ID = $learningStepId"
            )

            println(
                "SCORE = ${grading.score}"
            )

            val scorePercent =
                if (userAssignment.assignment.totalScore > 0.0) {
                    (
                            grading.score /
                                    userAssignment.assignment.totalScore *
                                    100.0
                            ).coerceIn(0.0, 100.0)
                } else {
                    0.0
                }

            println("ASSIGNMENT SCORE = ${grading.score}/${userAssignment.assignment.totalScore}")
            println("LEARNING PATH SCORE PERCENT = $scorePercent")

            learningPathService.updateProgress(
                userId = userId,
                stepId = learningStepId,
                score = scorePercent,
                preserveMastery = userAssignment.mode == AssignmentMode.RACE_TOP
            )

            println(
                "LEARNING PATH PROGRESS UPDATED"
            )
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
                    sourceType = metadata.sourceType,
                    gradingSpec = metadata.gradingSpec ?: AIService.GradingSpec(),
                    options = metadata.options,
                    statements = metadata.statements
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
                questionMetadata,
            raceTopPool = row[AssignmentsTable.raceTopPool]
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

            learningStepId =
                row[UserAssignmentsTable.learningStepId],

            mode =
                runCatching {
                    AssignmentMode.valueOf(row[UserAssignmentsTable.mode])
                }.getOrDefault(AssignmentMode.PRACTICE),

            scoreCoefficient = row[UserAssignmentsTable.scoreCoefficient],

            status =
                row[UserAssignmentsTable.status],

            answer =
                row[UserAssignmentsTable.answer],

            score =
                row[UserAssignmentsTable.score],

            feedback =
                row[UserAssignmentsTable.feedback],

            gradingDetails =
                row[UserAssignmentsTable.gradingDetails],

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
            emptyList(),

        val raceTopPool: Boolean = false
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
        val gradingDetails: String?,
        val startedAt: Long?,
        val completedAt: Long?,
        val assignment: AssignmentResult,
        val questionMetadata: List<QuestionMetadata>,
        val learningStepId: Int? = null,
        val mode: AssignmentMode = AssignmentMode.PRACTICE,
        val scoreCoefficient: Double = 1.0
    )


    private fun updateRaceTopRoundIfComplete(userAssignmentId: Int) {
        transaction {
            val sessionIds = RaceTopSessionAssignmentsTable.selectAll().where {
                RaceTopSessionAssignmentsTable.userAssignmentId eq userAssignmentId
            }.map { it[RaceTopSessionAssignmentsTable.sessionId] }.distinct()
            sessionIds.forEach { sessionId ->
                val links = RaceTopSessionAssignmentsTable.selectAll().where {
                    RaceTopSessionAssignmentsTable.sessionId eq sessionId
                }.toList()
                if (links.size != 7) return@forEach
                val results = links.mapNotNull { link ->
                    val userRow = UserAssignmentsTable.selectAll().where {
                        UserAssignmentsTable.id eq link[RaceTopSessionAssignmentsTable.userAssignmentId]
                    }.firstOrNull() ?: return@mapNotNull null
                    if (userRow[UserAssignmentsTable.status] != "COMPLETED") return@mapNotNull null
                    val assignmentRow = AssignmentsTable.selectAll().where {
                        AssignmentsTable.id eq userRow[UserAssignmentsTable.assignmentId]
                    }.firstOrNull() ?: return@mapNotNull null
                    val score = userRow[UserAssignmentsTable.score] ?: return@mapNotNull null
                    val total = assignmentRow[AssignmentsTable.totalScore]
                    if (total <= 0.0) return@mapNotNull null
                    Triple(link[RaceTopSessionAssignmentsTable.subject], score / total * 100.0, total)
                }
                if (results.size == 7) {
                    RaceTopSessionsTable.update({ RaceTopSessionsTable.id eq sessionId }) {
                        it[RaceTopSessionsTable.status] = "COMPLETED"
                        it[RaceTopSessionsTable.weakSubjects] = results.filter { row -> row.second < 60.0 }.joinToString("|") { row -> row.first }
                        it[RaceTopSessionsTable.averageSubjects] = results.filter { row -> row.second >= 60.0 && row.second < 80.0 }.joinToString("|") { row -> row.first }
                        it[RaceTopSessionsTable.strongSubjects] = results.filter { row -> row.second >= 80.0 }.joinToString("|") { row -> row.first }
                    }
                }
            }
        }
    }

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

            question.options.forEachIndexed { index, option ->
                builder.appendLine("${('A'.code + index).toChar()}. $option")
            }
            question.statements.forEachIndexed { index, statement ->
                builder.appendLine("${('a'.code + index).toChar()}) $statement")
            }

            builder.appendLine()
        }

        return builder
            .toString()
            .trim()
    }
}
