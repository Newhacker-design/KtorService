package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class LearningPath(
    val id: Int,
    val grade: Int,
    val subject: String,
    val name: String,
    val description: String? = null
)

@Serializable
data class LearningStep(
    val id: Int,
    val pathId: Int,
    val stepOrder: Int,
    val title: String,
    val skill: String,
    val description: String? = null,
    val prerequisiteStepId: Int? = null
)

@Serializable
enum class LearningProgressStatus {
    NOT_STARTED,
    LEARNING,
    PRACTICING,
    MASTERED,
    NEEDS_REVIEW
}

@Serializable
data class StudentLearningProgress(
    val userId: Int,
    val stepId: Int,
    val masteryScore: Double,
    val attemptCount: Int,
    val lastScore: Double?,
    val status: LearningProgressStatus,
    val lastAttemptAt: Long?
)

@Serializable
data class NextLearningStep(
    val step: LearningStep,
    val progress: StudentLearningProgress
)