package com.example.ktorservice.service

import com.example.ktorservice.database.table.LearningPathsTable
import com.example.ktorservice.database.table.LearningStepsTable
import com.example.ktorservice.database.table.StudentLearningProgressTable
import com.example.ktorservice.model.LearningPath
import com.example.ktorservice.model.LearningProgressStatus
import com.example.ktorservice.model.LearningStep
import com.example.ktorservice.model.StudentLearningProgress
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

class LearningPathService {
    data class RaceTopLevelStatus(val level: Int, val masteredSubjects: Int, val totalSubjects: Int) {
        val scoreCoefficient: Double get() = 1.0 + (level - 1) * 0.1
    }

    /** Synchronized level shown to the student, based on mastery across all seven subjects. */
    fun getRaceTopLevelStatus(userId: Int, grade: Int): RaceTopLevelStatus = transaction {
        val subjects = listOf("MATH", "LITERATURE", "ENGLISH", "PHYSICS", "CHEMISTRY", "BIOLOGY", "GIAO_DUC_GIOI_TINH")
        val paths = LearningPathsTable.selectAll().where { LearningPathsTable.grade eq grade }
            .associateBy { it[LearningPathsTable.subject].trim().uppercase(java.util.Locale.ROOT) }
        require(subjects.all(paths::containsKey)) { "Đua Top cần learning path cho đủ 7 môn, lớp $grade." }
        val steps = subjects.associateWith { subject ->
            LearningStepsTable.selectAll().where { LearningStepsTable.pathId eq paths.getValue(subject)[LearningPathsTable.id] }
                .orderBy(LearningStepsTable.stepOrder to SortOrder.ASC).map { it.toLearningStep() }
        }
        val commonOrders = steps.values.map { it.map(LearningStep::stepOrder).toSet() }
            .reduce { common, current -> common.intersect(current) }.sorted()
        require(commonOrders.isNotEmpty()) { "Learning path của 7 môn chưa có step đồng bộ." }
        val activeOrder = commonOrders.firstOrNull { order ->
            subjects.any { subject ->
                val step = steps.getValue(subject).first { it.stepOrder == order }
                getOrCreateProgress(userId, step.id).status != LearningProgressStatus.MASTERED
            }
        } ?: commonOrders.last()
        val mastered = subjects.count { subject ->
            val step = steps.getValue(subject).first { it.stepOrder == activeOrder }
            getOrCreateProgress(userId, step.id).status == LearningProgressStatus.MASTERED
        }
        RaceTopLevelStatus(activeOrder, mastered, subjects.size)
    }

    private fun normalizeSubject(subject: String): String =
        subject.trim().uppercase(java.util.Locale.ROOT)

    fun getPathById(id: Int): LearningPath? = transaction {

        LearningPathsTable
            .selectAll()
            .where {
                LearningPathsTable.id eq id
            }
            .singleOrNull()
            ?.toLearningPath()
    }
    /**
     * Lấy Learning Path theo grade + subject.
     */
    fun getPath(
        grade: Int,
        subject: String
    ): LearningPath? {
        val normalizedSubject = normalizeSubject(subject)
        return transaction {
            LearningPathsTable
                .selectAll()
                .where {
                    LearningPathsTable.grade eq grade
                }
                .firstOrNull { it[LearningPathsTable.subject].trim()
                    .uppercase(java.util.Locale.ROOT) == normalizedSubject }
                ?.toLearningPath()
        }
    }

    /**
     * Lấy tất cả Learning Path.
     */
    fun getAllPaths(): List<LearningPath> {
        return transaction {
            LearningPathsTable
                .selectAll()
                .orderBy(
                    LearningPathsTable.grade to SortOrder.ASC,
                    LearningPathsTable.subject to SortOrder.ASC
                )
                .map { it.toLearningPath() }
        }
    }

    /**
     * Tạo Learning Path mới.
     *
     * LearningPathsTable đang dùng:
     * integer("id").autoIncrement()
     *
     * nên dùng insert { ... } rồi lấy id từ ResultRow.
     */
    fun createPath(
        grade: Int,
        subject: String,
        name: String,
        description: String? = null
    ): LearningPath {
        return transaction {

            val now = System.currentTimeMillis()

            val insertedRow = LearningPathsTable
                .insert {
                    it[LearningPathsTable.grade] = grade
                    it[LearningPathsTable.subject] = normalizeSubject(subject)
                    it[LearningPathsTable.name] = name.trim()
                    it[LearningPathsTable.description] =
                        description?.trim()?.takeIf { value ->
                            value.isNotBlank()
                        }
                    it[LearningPathsTable.createdAt] = now
                }

            insertedRow.resultedValues
                ?.singleOrNull()
                ?.toLearningPath()
                ?: throw IllegalStateException(
                    "Failed to create learning path."
                )
        }
    }

    /**
     * Lấy các step của một Learning Path.
     */
    fun getSteps(
        pathId: Int
    ): List<LearningStep> {
        return transaction {
            LearningStepsTable
                .selectAll()
                .where {
                    LearningStepsTable.pathId eq pathId
                }
                .orderBy(
                    LearningStepsTable.stepOrder to SortOrder.ASC
                )
                .map { it.toLearningStep() }
        }
    }

    /**
     * Lấy một step cụ thể.
     */
    fun getStep(
        stepId: Int
    ): LearningStep? {
        return transaction {
            LearningStepsTable
                .selectAll()
                .where {
                    LearningStepsTable.id eq stepId
                }
                .limit(1)
                .map { it.toLearningStep() }
                .singleOrNull()
        }
    }

    /**
     * Lấy progress của học sinh cho một step.
     *
     * Nếu chưa có progress thì tạo NOT_STARTED.
     */
    fun getOrCreateProgress(
        userId: Int,
        stepId: Int
    ): StudentLearningProgress {
        return transaction {

            val existing = StudentLearningProgressTable
                .selectAll()
                .where {
                    (StudentLearningProgressTable.userId eq userId) and
                            (StudentLearningProgressTable.stepId eq stepId)
                }
                .limit(1)
                .singleOrNull()

            if (existing != null) {
                return@transaction existing.toStudentLearningProgress()
            }

            StudentLearningProgressTable.insert {
                it[StudentLearningProgressTable.userId] = userId
                it[StudentLearningProgressTable.stepId] = stepId
                it[masteryScore] = 0.0
                it[attemptCount] = 0
                it[lastScore] = null
                it[status] = LearningProgressStatus.NOT_STARTED.name
                it[lastAttemptAt] = null
            }

            StudentLearningProgressTable
                .selectAll()
                .where {
                    (StudentLearningProgressTable.userId eq userId) and
                            (StudentLearningProgressTable.stepId eq stepId)
                }
                .single()
                .toStudentLearningProgress()
        }
    }

    /**
     * Tìm learning step tiếp theo cho học sinh.
     *
     * Logic:
     *
     * 1. Nếu chưa có Learning Path -> null.
     * 2. Duyệt các step theo stepOrder.
     * 3. Step đầu tiên chưa MASTERED là step hiện tại.
     * 4. Nếu tất cả đã MASTERED -> hiện tại quay lại step cuối
     *    để review.
     */
    fun getNextStep(
        userId: Int,
        grade: Int,
        subject: String
    ): Pair<LearningStep, StudentLearningProgress>? {

        val normalizedSubject = normalizeSubject(subject)
        return transaction {

            val path = LearningPathsTable
                .selectAll()
                .where { LearningPathsTable.grade eq grade }
                .map { it.toLearningPath() }
                .firstOrNull { it.subject.trim()
                    .uppercase(java.util.Locale.ROOT) == normalizedSubject }
                ?: return@transaction null

            val steps = LearningStepsTable
                .selectAll()
                .where {
                    LearningStepsTable.pathId eq path.id
                }
                .orderBy(
                    LearningStepsTable.stepOrder to SortOrder.ASC
                )
                .map { it.toLearningStep() }

            if (steps.isEmpty()) {
                return@transaction null
            }

            for (step in steps) {

                val progress = getOrCreateProgress(
                    userId = userId,
                    stepId = step.id
                )

                if (progress.status != LearningProgressStatus.MASTERED) {
                    return@transaction step to progress
                }
            }

            /*
             * Tất cả step đã MASTERED.
             *
             * Hiện tại chưa tạo cơ chế review định kỳ,
             * nên tạm thời trả về step cuối.
             */
            val lastStep = steps.last()

            val progress = getOrCreateProgress(
                userId = userId,
                stepId = lastStep.id
            )

            return@transaction lastStep to progress
        }
    }

    /** Returns the student's current synchronized race-top step for a subject. */
    fun getNextRaceTopStep(
        userId: Int,
        grade: Int,
        subject: String
    ): Pair<LearningStep, StudentLearningProgress>? = transaction {
        val raceSubjects = listOf(
            "MATH", "LITERATURE", "ENGLISH", "PHYSICS", "CHEMISTRY", "BIOLOGY", "GIAO_DUC_GIOI_TINH"
        )
        val paths = LearningPathsTable.selectAll().where { LearningPathsTable.grade eq grade }
            .orderBy(LearningPathsTable.id to SortOrder.ASC)
            .associateBy { it[LearningPathsTable.subject].trim().uppercase(java.util.Locale.ROOT) }
        val missingPaths = raceSubjects.filterNot(paths::containsKey)
        require(missingPaths.isEmpty()) {
            "Đua Top lớp $grade cần learning path cho đủ 7 môn. Thiếu: ${missingPaths.joinToString()}."
        }
        val stepsBySubject = raceSubjects.associateWith { normalized ->
            val pathId = paths.getValue(normalized)[LearningPathsTable.id]
            LearningStepsTable.selectAll().where { LearningStepsTable.pathId eq pathId }
                .orderBy(LearningStepsTable.stepOrder to SortOrder.ASC)
                .map { it.toLearningStep() }
        }
        val availableOrders = stepsBySubject.values.map { list ->
            list.map { it.stepOrder }.toSet()
        }.reduce { common, orders -> common.intersect(orders) }.sorted()
        require(availableOrders.isNotEmpty()) {
            "Learning path của 7 môn lớp $grade chưa có step đồng bộ."
        }
        require(availableOrders == (1..availableOrders.last()).toList()) {
            "Step của 7 learning path lớp $grade phải liên tục từ step 1 để không bỏ qua nội dung."
        }
        val targetSubject = subject.trim().uppercase(java.util.Locale.ROOT)
        require(targetSubject in raceSubjects) { "Môn $subject không thuộc bộ Đua Top." }

        var activeOrder: Int? = null
        for (order in availableOrders) {
            val allMastered = raceSubjects.all { currentSubject ->
                val step = stepsBySubject.getValue(currentSubject).first { it.stepOrder == order }
                getOrCreateProgress(userId, step.id).status == LearningProgressStatus.MASTERED
            }
            if (!allMastered) {
                activeOrder = order
                break
            }
        }
        val chosenOrder = activeOrder ?: availableOrders.last()
        val chosenStep = stepsBySubject.getValue(targetSubject).first { it.stepOrder == chosenOrder }
        chosenStep to getOrCreateProgress(userId, chosenStep.id)
    }

    /**
     * Cập nhật progress sau khi học sinh hoàn thành assignment.
     *
     * score: 0..100
     */
    fun updateProgress(
        userId: Int,
        stepId: Int,
        score: Double,
        preserveMastery: Boolean = false
    ): StudentLearningProgress {

        return transaction {

            val current = getOrCreateProgress(
                userId = userId,
                stepId = stepId
            )

            val safeScore = score.coerceIn(0.0, 100.0)

            val newAttemptCount =
                current.attemptCount + 1

            /*
             * Lần đầu:
             * mastery = điểm hiện tại.
             *
             * Các lần sau:
             * 70% mastery cũ
             * 30% điểm mới.
             */
            val calculatedMastery =
                if (current.attemptCount == 0) {
                    safeScore
                } else {
                    current.masteryScore * 0.7 +
                            safeScore * 0.3
                }

            val newMastery = if (preserveMastery && current.status == LearningProgressStatus.MASTERED) {
                maxOf(current.masteryScore, calculatedMastery)
            } else calculatedMastery

            val newStatus = if (preserveMastery && current.status == LearningProgressStatus.MASTERED) {
                LearningProgressStatus.MASTERED
            } else calculateStatus(masteryScore = newMastery, attemptCount = newAttemptCount)

            val now = System.currentTimeMillis()

            StudentLearningProgressTable.update(
                where = {
                    (StudentLearningProgressTable.userId eq userId) and
                            (StudentLearningProgressTable.stepId eq stepId)
                }
            ) {
                it[masteryScore] = newMastery
                it[attemptCount] = newAttemptCount
                it[lastScore] = safeScore
                it[status] = newStatus.name
                it[lastAttemptAt] = now
            }

            StudentLearningProgressTable
                .selectAll()
                .where {
                    (StudentLearningProgressTable.userId eq userId) and
                            (StudentLearningProgressTable.stepId eq stepId)
                }
                .single()
                .toStudentLearningProgress()
        }
    }

    /**
     * Xác định trạng thái học tập.
     */
    private fun calculateStatus(
        masteryScore: Double,
        attemptCount: Int
    ): LearningProgressStatus {

        return when {
            masteryScore < 50.0 ->
                LearningProgressStatus.NEEDS_REVIEW

            masteryScore < 70.0 ->
                LearningProgressStatus.LEARNING

            masteryScore < 85.0 ->
                LearningProgressStatus.PRACTICING

            attemptCount >= 2 ->
                LearningProgressStatus.MASTERED

            else ->
                LearningProgressStatus.PRACTICING
        }
    }

    /**
     * Mapping database row -> LearningPath.
     */
    private fun ResultRow.toLearningPath(): LearningPath {
        return LearningPath(
            id = this[LearningPathsTable.id],
            grade = this[LearningPathsTable.grade],
            subject = this[LearningPathsTable.subject],
            name = this[LearningPathsTable.name],
            description = this[LearningPathsTable.description]
        )
    }

    /**
     * Mapping database row -> LearningStep.
     */
    private fun ResultRow.toLearningStep(): LearningStep {
        return LearningStep(
            id = this[LearningStepsTable.id],
            pathId = this[LearningStepsTable.pathId],
            stepOrder = this[LearningStepsTable.stepOrder],
            title = this[LearningStepsTable.title],
            skill = this[LearningStepsTable.skill],
            description = this[LearningStepsTable.description],
            prerequisiteStepId =
                this[LearningStepsTable.prerequisiteStepId]
        )
    }

    /**
     * Mapping database row -> StudentLearningProgress.
     */
    private fun ResultRow.toStudentLearningProgress():
            StudentLearningProgress {

        val statusText =
            this[StudentLearningProgressTable.status]

        val status =
            try {
                LearningProgressStatus.valueOf(statusText)
            } catch (_: Exception) {
                LearningProgressStatus.NOT_STARTED
            }

        return StudentLearningProgress(
            userId =
                this[StudentLearningProgressTable.userId],

            stepId =
                this[StudentLearningProgressTable.stepId],

            masteryScore =
                this[StudentLearningProgressTable.masteryScore],

            attemptCount =
                this[StudentLearningProgressTable.attemptCount],

            lastScore =
                this[StudentLearningProgressTable.lastScore],

            status = status,

            lastAttemptAt =
                this[StudentLearningProgressTable.lastAttemptAt]
        )
    }
}
