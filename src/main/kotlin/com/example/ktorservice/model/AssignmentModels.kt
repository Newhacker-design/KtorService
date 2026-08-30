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