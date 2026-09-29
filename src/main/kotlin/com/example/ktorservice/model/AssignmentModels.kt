package com.example.ktorservice.model

import com.example.ktorservice.service.AIService
import kotlinx.serialization.Serializable

@Serializable
enum class AssignmentMode {
    PRACTICE,
    RACE_TOP
}
@Serializable
data class QuestionMetadata(
    val id: Int,
    val question: String,
    val learningObjective: String,
    val points: Double,
    val answerType: AIService.AnswerType,
    val gradingMethod: AIService.GradingMethod,
    val sourceType: AIService.QuestionSourceType =
        AIService.QuestionSourceType.SELF_CONTAINED,
    val gradingSpec: AIService.GradingSpec? = null,
    val options: List<String> = emptyList(),
    val statements: List<String> = emptyList()
)
@Serializable
data class AssignmentGenerateResponse(
    val success: Boolean,
    val assignment: AssignmentData? = null,
    val message: String? = null
)

@Serializable
data class AssignmentData(
    val title: String,

    val questions: List<AssignmentQuestion> = emptyList(),

    val content: String,

    val answerKey: String,

    val gradingGuide: String,

    val totalScore: Double
)

@Serializable
data class AssignmentStudentData(
    val id: Int,
    val grade: Int,
    val subject: String,
    val topic: String? = null,

    val title: String,

    val difficulty: AIService.Difficulty,

    val questions: List<AssignmentQuestion> = emptyList(),

    val content: String,

    val totalScore: Double
)


@Serializable
data class AssignmentDetailResponse(
    val success: Boolean,
    val assignment: AssignmentStudentData? = null,
    val message: String? = null
)
@Serializable
data class UserAssignmentResponse(
    val success: Boolean,
    val id: Int? = null,
    val assignmentId: Int? = null,
    val userId: Int? = null,
    val status: String? = null,
    val answer: String? = null,
    val score: Double? = null,
    val feedback: String? = null,

    // JSON chứa kết quả chấm từng câu
    val gradingDetails: String? = null,

    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val mode: AssignmentMode = AssignmentMode.PRACTICE,
    val assignment: AssignmentStudentData? = null,
    val message: String? = null
)

@Serializable
data class AssignmentStorageData(
    val id: Int,
    val grade: Int,
    val subject: String,
    val topic: String? = null,
    val title: String,
    val content: String,
    val answerKey: String,
    val gradingGuide: String,
    val totalScore: Double
)

@Serializable
data class AssignmentListResponse(
    val success: Boolean,
    val assignments: List<AssignmentStorageData> = emptyList(),
    val message: String? = null
)
@Serializable
data class AssignmentSubmitRequest(
    val answer: String,
    val localScore: Double? = null,
    val localFeedback: String? = null,
    val localGradingDetails: String? = null
)

@Serializable
data class AssignmentQuestion(
    val id: Int,
    val question: String,
    val learningObjective: String = "",
    val points: Double,
    val answerType: AIService.AnswerType,
    val gradingMethod: AIService.GradingMethod,
    val sourceType: AIService.QuestionSourceType =
        AIService.QuestionSourceType.SELF_CONTAINED,
    val gradingSpec: AIService.GradingSpec? = null,
    val options: List<String> = emptyList(),
    val statements: List<String> = emptyList()
)

@Serializable
data class AssignmentAnswerKey(
    val id: Int,
    val answer: String
)

@Serializable
data class AssignmentActionResponse(
    val success: Boolean,
    val message: String? = null,
    val status: String? = null,
    val score: Double? = null,
    val feedback: String? = null,

    // JSON chứa kết quả chấm từng câu
    val gradingDetails: String? = null
)

@Serializable
data class CreateChildSessionResponse(
    val success: Boolean,
    val token: String? = null,
    val userId: Int? = null,
    val role: String? = null,
    val message: String? = null
)

@Serializable data class AssignedAssignmentResponse( val success: Boolean,
                                                     val assignments: List<UserAssignmentResponse> = emptyList(),
                                                     val message: String? = null )
@Serializable
data class TopStudentResponse(
    val userId: Int,
    val name: String,
    val totalScore: Double
)

@Serializable
data class TopStudentsResponse(
    val success: Boolean,
    val students: List<TopStudentResponse> = emptyList(),
    val message: String? = null
)

@Serializable
data class RaceTopSubjectResult(
    val subject: String,
    val userAssignmentId: Int,
    val status: String,
    val score: Double? = null,
    val possibleScore: Double,
    val scoreCoefficient: Double = 1.0,
    val weightedScore: Double? = null,
    val weightedPossibleScore: Double? = null
)

@Serializable
data class RaceTopStartResponse(
    val success: Boolean,
    val sessionId: Int? = null,
    val assignments: List<RaceTopSubjectResult> = emptyList(),
    val complete: Boolean = false,
    val weakSubjects: List<String> = emptyList(),
    val averageSubjects: List<String> = emptyList(),
    val strongSubjects: List<String> = emptyList(),
    val previousWeakSubjects: List<String> = emptyList(),
    val previousAverageSubjects: List<String> = emptyList(),
    val previousStrongSubjects: List<String> = emptyList(),
    val level: Int = 1,
    val masteredSubjects: Int = 0,
    val totalSubjects: Int = 7,
    val scoreCoefficient: Double = 1.0,
    val message: String? = null
)

@Serializable
data class AssignmentCompletionStatusResponse(
    val success: Boolean,
    val shouldUnlock: Boolean = false,
    val unfinishedCount: Long = 0,
    val completedTodayCount: Long = 0,
    val message: String? = null
)

@Serializable
data class RaceTopPoolGenerateRequest(
    val grade: Int,
    val learningStepId: Int
)

@Serializable
data class RaceTopPoolGenerateResponse(
    val success: Boolean,
    val assignmentId: Int? = null,
    val grade: Int? = null,
    val subject: String? = null,
    val learningStepId: Int? = null,
    val stepOrder: Int? = null,
    val title: String? = null,
    val message: String? = null
)

