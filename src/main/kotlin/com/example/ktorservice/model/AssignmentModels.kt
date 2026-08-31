package com.example.ktorservice.model

import kotlinx.serialization.Serializable

@Serializable
data class AssignmentGenerateResponse(
    val success: Boolean,
    val assignment: AssignmentData? = null,
    val message: String? = null
)

@Serializable
data class AssignmentData(
    val title: String,
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

    val startedAt: Long? = null,

    val completedAt: Long? = null,

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
    val answer: String
)

@Serializable
data class AssignmentActionResponse(
    val success: Boolean,
    val message: String? = null,
    val status: String? = null,
    val score: Double? = null,
    val feedback: String? = null
)